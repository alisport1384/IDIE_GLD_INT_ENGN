package io.goldintelligence.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class RegimeDetectorTest {

    private val engine = ThresholdRegimeEngine()

    private fun snapshotWith(features: Map<String, Double>) =
        InputSnapshot(observations = emptyMap(), features = FeatureSet(features))

    @Test
    fun `no features passes snapshot regime through unchanged`() {
        val snapshot = InputSnapshot(
            observations = emptyMap(),
            regime = Regime.RISK_ON,
            regimeStability = Stability.HIGH
        )
        val (regime, stability) = engine.detect(snapshot)
        assertEquals(Regime.RISK_ON, regime)
        assertEquals(Stability.HIGH, stability)
    }

    @Test
    fun `weak signals classify as NORMAL`() {
        val snapshot = snapshotWith(
            mapOf(
                FeatureKeys.GEOPOLITICAL_RISK_SCORE to 10.0,
                FeatureKeys.VIX_ZSCORE to 0.2
            )
        )
        val (regime, _) = engine.detect(snapshot)
        assertEquals(Regime.NORMAL, regime)
    }

    @Test
    fun `high geopolitical score triggers GEOPOLITICAL_CRISIS`() {
        val snapshot = snapshotWith(
            mapOf(FeatureKeys.GEOPOLITICAL_RISK_SCORE to 90.0)
        )
        val (regime, stability) = engine.detect(snapshot)
        assertEquals(Regime.GEOPOLITICAL_CRISIS, regime)
        assertEquals(Stability.HIGH, stability)
    }

    @Test
    fun `dovish fed with falling real yield and dxy triggers MONETARY_EASING`() {
        val snapshot = snapshotWith(
            mapOf(
                FeatureKeys.FED_EXPECTED_RATE_CHANGE to -0.25,
                FeatureKeys.REAL_YIELD_TREND to -0.10,
                FeatureKeys.DXY_TREND to -0.10
            )
        )
        val (regime, _) = engine.detect(snapshot)
        assertEquals(Regime.MONETARY_EASING, regime)
    }

    @Test
    fun `multiple simultaneous candidates lower stability`() {
        val snapshot = snapshotWith(
            mapOf(
                FeatureKeys.GEOPOLITICAL_RISK_SCORE to 90.0,
                FeatureKeys.DOLLAR_FUNDING_STRESS to 3.0
            )
        )
        val (_, stability) = engine.detect(snapshot)
        assertEquals(Stability.LOW, stability)
    }
}
