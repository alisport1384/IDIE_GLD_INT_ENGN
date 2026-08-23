package io.goldintelligence.engine

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldIntelligenceEngineTest {

    private val engine = GoldIntelligenceEngine()
    private val now = Instant.parse("2026-08-22T00:00:00Z")

    @Test
    fun `empty snapshot yields a safe unknown state, never a fabricated signal`() {
        val state = engine.evaluate(InputSnapshot.empty(), now)
        assertEquals(Direction.NEUTRAL, state.direction)
        assertNull(state.probability)
        assertEquals(Uncertainty.INSUFFICIENT_INFORMATION, state.uncertainty)
        assertEquals(SignalState.UNKNOWN, state.signalState)
        assertEquals("NONE", state.newsState)
        assertEquals(ShockState.NONE, state.shock)
        assertNull(state.goldBias)
    }

    @Test
    fun `invalid observation fails validation and reports the reason`() {
        val badObservation = Observation(value = 1.0, timestamp = now, source = "")
        val snapshot = InputSnapshot(observations = mapOf("DXY" to badObservation))
        val state = engine.evaluate(snapshot, now)
        assertEquals(SignalState.UNKNOWN, state.signalState)
        assertTrue(state.contradictions.contains("DATA_VALIDATION_FAILED"))
    }

    @Test
    fun `bearish factor set produces a bearish gold bias with attribution and dominant factor`() {
        val snapshot = InputSnapshot(
            observations = emptyMap(),
            factorScores = listOf(
                FactorScore("F01_REAL_RATE", -75.0),
                FactorScore("F02_USD", -60.0),
                FactorScore("F09_GOLD_FLOW", 20.0)
            )
        )
        val state = engine.evaluate(snapshot, now)
        assertNotNull(state.goldBias)
        assertTrue("expected negative bias, got ${state.goldBias}", state.goldBias!! < 0.0)
        assertEquals(Direction.BEARISH, state.direction)
        assertEquals("F01_REAL_RATE", state.dominantFactor)
        assertTrue(state.attribution.isNotEmpty())
        assertNotNull(state.invalidation)
    }

    @Test
    fun `two simultaneous sigma breaches flip shock state to ACTIVE and weaken the signal`() {
        val snapshot = InputSnapshot(
            observations = emptyMap(),
            features = FeatureSet(
                mapOf(
                    FeatureKeys.DXY_MOVE_SIGMA to 6.0,
                    FeatureKeys.GOLD_MOVE_SIGMA to 5.5
                )
            ),
            factorScores = listOf(FactorScore("F02_USD", -70.0))
        )
        val state = engine.evaluate(snapshot, now)
        assertEquals(ShockState.ACTIVE, state.shock)
        assertNotNull(state.shockDetail)
        assertTrue(state.shockDetail!!.active)
        assertEquals(SignalState.WEAK, state.signalState)
    }

    @Test
    fun `high-relevance news updates newsState via the news intelligence engine`() {
        val snapshot = InputSnapshot(
            observations = emptyMap(),
            news = listOf(
                NewsRecord(
                    eventType = "GEOPOLITICAL",
                    country = "IR-IL",
                    severity = 92.0,
                    novelty = 87.0,
                    credibility = 95.0,
                    goldRelevance = 91.0,
                    direction = Direction.BULLISH,
                    timestamp = now
                )
            )
        )
        val state = engine.evaluate(snapshot, now)
        assertEquals("HIGH_IMPACT", state.newsState)
    }

    @Test
    fun `scenario outlook always exposes Base Bull Bear Tail`() {
        val state = engine.evaluate(InputSnapshot.empty(), now)
        assertEquals(
            listOf("BASE", "BULL", "BEAR", "TAIL"),
            state.scenarioOutlook.map { it.name }
        )
    }
}
