package io.goldintelligence.engine

import kotlin.math.abs

/**
 * Rule-based regime classification operating on already-computed features
 * (Layer 4 "Regime Engine"). Thresholds come from Specification.CalibrationDefaults
 * — provisional Initial Priors pending Walk-Forward recalibration.
 *
 * If no relevant features are present, the snapshot's own regime/stability
 * are passed through unchanged (fail-safe: never invents a regime from
 * missing data).
 */
class ThresholdRegimeEngine : RegimeEngine {
    override fun detect(snapshot: InputSnapshot): Pair<Regime, Stability> {
        val v = snapshot.features.values
        if (v.isEmpty()) return snapshot.regime to snapshot.regimeStability

        val d = CalibrationDefaults
        val geo = v[FeatureKeys.GEOPOLITICAL_RISK_SCORE]
        val vix = v[FeatureKeys.VIX_ZSCORE]
        val stress = v[FeatureKeys.FINANCIAL_STRESS_SCORE]
        val realYieldTrend = v[FeatureKeys.REAL_YIELD_TREND]
        val fedChange = v[FeatureKeys.FED_EXPECTED_RATE_CHANGE]
        val dxyTrend = v[FeatureKeys.DXY_TREND]
        val inflationSurprise = v[FeatureKeys.INFLATION_SURPRISE]
        val recessionProbability = v[FeatureKeys.RECESSION_PROBABILITY]
        val fundingStress = v[FeatureKeys.DOLLAR_FUNDING_STRESS]

        val candidates = mutableListOf<Pair<Regime, Double>>()

        if (fundingStress != null && fundingStress >= d.LIQUIDITY_STRESS_THRESHOLD) {
            candidates += Regime.LIQUIDITY_STRESS to fundingStress
        }
        if (geo != null && geo >= d.GEOPOLITICAL_CRISIS_THRESHOLD) {
            candidates += Regime.GEOPOLITICAL_CRISIS to geo
        }
        if (inflationSurprise != null && realYieldTrend != null &&
            inflationSurprise >= d.INFLATION_SURPRISE_THRESHOLD && realYieldTrend <= 0.0
        ) {
            candidates += Regime.INFLATION to inflationSurprise
        }
        if (fedChange != null && realYieldTrend != null && dxyTrend != null) {
            if (fedChange <= -d.FED_EXPECTATION_SHIFT_THRESHOLD && realYieldTrend < 0.0 && dxyTrend < 0.0) {
                candidates += Regime.MONETARY_EASING to abs(fedChange)
            } else if (fedChange >= d.FED_EXPECTATION_SHIFT_THRESHOLD && realYieldTrend > 0.0 && dxyTrend > 0.0) {
                candidates += Regime.MONETARY_TIGHTENING to abs(fedChange)
            }
        }
        if (recessionProbability != null && recessionProbability >= d.GROWTH_SLOWDOWN_THRESHOLD) {
            candidates += Regime.GROWTH_SLOWDOWN to recessionProbability
        }
        if (vix != null && stress != null) {
            if (vix >= d.RISK_OFF_VIX_ZSCORE && stress >= d.RISK_OFF_STRESS_THRESHOLD) {
                candidates += Regime.RISK_OFF to (vix + stress)
            } else if (vix <= -d.RISK_ON_VIX_ZSCORE) {
                candidates += Regime.RISK_ON to abs(vix)
            }
        }

        if (candidates.isEmpty()) {
            return Regime.NORMAL to Stability.MEDIUM
        }

        val winner = candidates.maxByOrNull { it.second }!!.first
        val stability = if (candidates.size > 1) Stability.LOW else Stability.HIGH
        return winner to stability
    }
}
