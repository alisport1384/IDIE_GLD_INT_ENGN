package io.goldintelligence.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicProbabilityModelTest {

    private val model = HeuristicProbabilityModel()

    @Test
    fun `no factor scores yields no prediction`() {
        val result = model.predict(InputSnapshot.empty())
        assertNull(result)
    }

    @Test
    fun `dominant bullish factors push probability of up above 50 percent`() {
        val snapshot = InputSnapshot(
            observations = emptyMap(),
            factorScores = listOf(
                FactorScore("F01_REAL_RATE", 70.0),
                FactorScore("F07_GEOPOLITICAL_RISK", 50.0)
            )
        )
        val result = model.predict(snapshot)
        assertTrue(result != null && result.up != null && result.up!! > 0.5)
    }

    @Test
    fun `up and down probabilities always sum to one`() {
        val snapshot = InputSnapshot(
            observations = emptyMap(),
            factorScores = listOf(FactorScore("F02_USD", -35.0))
        )
        val result = model.predict(snapshot)
        assertEquals(1.0, (result!!.up ?: 0.0) + (result.down ?: 0.0), 1e-9)
    }
}

class LiquidityEngineTest {

    private val engine = RuleBasedLiquidityEngine()

    @Test
    fun `no funding stress feature is unknown`() {
        assertEquals(Liquidity.UNKNOWN, engine.assess(FeatureSet()))
    }

    @Test
    fun `low funding stress is normal`() {
        val features = FeatureSet(mapOf(FeatureKeys.DOLLAR_FUNDING_STRESS to 0.5))
        assertEquals(Liquidity.NORMAL, engine.assess(features))
    }

    @Test
    fun `high funding stress is stressed`() {
        val features = FeatureSet(mapOf(FeatureKeys.DOLLAR_FUNDING_STRESS to 3.0))
        assertEquals(Liquidity.STRESSED, engine.assess(features))
    }
}
