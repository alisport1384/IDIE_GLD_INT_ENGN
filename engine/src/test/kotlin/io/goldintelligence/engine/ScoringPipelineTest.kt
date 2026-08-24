package io.goldintelligence.engine

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringPipelineTest {

    private val pipeline = ScoringPipeline()
    private val now = Instant.parse("2026-08-21T00:00:00Z")

    @Test
    fun `empty factor list yields null gold bias`() {
        val result = pipeline.run(emptyList(), FeatureSet(), Regime.NORMAL, now)
        assertEquals(null, result.goldBias)
        assertEquals(ConflictLevel.UNKNOWN, result.conflict)
    }

    @Test
    fun `aligned bearish monetary factors produce a negative gold bias with low conflict`() {
        val scores = listOf(
            FactorScore("F01_REAL_RATE", -80.0),
            FactorScore("F02_USD", -70.0),
            FactorScore("F03_FED", -60.0)
        )
        val result = pipeline.run(scores, FeatureSet(), Regime.NORMAL, now)
        assertNotNull(result.goldBias)
        assertTrue("expected negative bias, got ${result.goldBias}", result.goldBias!! < 0.0)
        assertEquals(ConflictLevel.LOW, result.conflict)
        assertTrue(result.attribution.isNotEmpty())
    }

    @Test
    fun `aligned bullish monetary factors produce a positive gold bias amplified by interaction`() {
        val scores = listOf(
            FactorScore("F01_REAL_RATE", 80.0),
            FactorScore("F02_USD", 70.0),
            FactorScore("F03_FED", 60.0)
        )
        val result = pipeline.run(scores, FeatureSet(), Regime.NORMAL, now)
        assertNotNull(result.goldBias)
        assertTrue("expected positive bias, got ${result.goldBias}", result.goldBias!! > 0.0)
        assertTrue(result.attribution.any { it.factorId == "INTERACTION_EFFECT" && it.contribution > 0.0 })
    }

    @Test
    fun `evenly opposed factors are flagged as high conflict`() {
        val scores = listOf(
            FactorScore("F01_REAL_RATE", -50.0),
            FactorScore("F02_USD", -50.0),
            FactorScore("F07_GEOPOLITICAL_RISK", 50.0),
            FactorScore("F09_GOLD_FLOW", 50.0)
        )
        val result = pipeline.run(scores, FeatureSet(), Regime.NORMAL, now)
        assertEquals(ConflictLevel.HIGH, result.conflict)
    }

    @Test
    fun `monetary easing regime shifts weight toward the larger monetary-group factor`() {
        val scores = listOf(
            FactorScore("F01_REAL_RATE", -60.0),
            FactorScore("F07_GEOPOLITICAL_RISK", -40.0)
        )
        val normalResult = pipeline.run(scores, FeatureSet(), Regime.NORMAL, now)
        val easingResult = pipeline.run(scores, FeatureSet(), Regime.MONETARY_EASING, now)
        assertTrue(easingResult.goldBias!! < normalResult.goldBias!!)
    }

    @Test
    fun `stale counter-signal fades relative to fresh evidence via time decay`() {
        val fresh = listOf(
            FactorScore("F01_REAL_RATE", -80.0, asOf = now),
            FactorScore("F09_GOLD_FLOW", 80.0, asOf = now)
        )
        val staleCounter = listOf(
            FactorScore("F01_REAL_RATE", -80.0, asOf = now),
            FactorScore("F09_GOLD_FLOW", 80.0, asOf = now.minusSeconds(3600L * 24 * 30))
        )
        val freshResult = pipeline.run(fresh, FeatureSet(), Regime.NORMAL, now)
        val staleResult = pipeline.run(staleCounter, FeatureSet(), Regime.NORMAL, now)
        assertTrue(staleResult.goldBias!! < freshResult.goldBias!!)
    }
}
