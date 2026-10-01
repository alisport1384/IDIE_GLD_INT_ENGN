package io.goldintelligence.engine

import kotlin.math.abs

/**
 * Shock detection using the explicit multi-sigma thresholds given verbatim
 * in the specification ("DXY 5σ Move + 10Y 4σ Move + Gold 5σ Move + VIX
 * Spike") together with the "Normal Regime probably broken" combination
 * rule: shock is ACTIVE only when at least MIN_TRIGGERED_FOR_SHOCK
 * conditions fire simultaneously.
 */
class RuleBasedShockDetectionEngine : ShockDetectionEngine {
    override fun detect(snapshot: InputSnapshot, features: FeatureSet): ShockDetail {
        val v = features.values
        val triggered = mutableListOf<String>()

        v[FeatureKeys.DXY_MOVE_SIGMA]?.let { s ->
            if (abs(s) >= ShockThresholds.DXY_SIGMA) triggered += "DXY %.1fσ Move".format(s)
        }
        v[FeatureKeys.US10Y_MOVE_SIGMA]?.let { s ->
            if (abs(s) >= ShockThresholds.US10Y_SIGMA) triggered += "US10Y %.1fσ Move".format(s)
        }
        v[FeatureKeys.GOLD_MOVE_SIGMA]?.let { s ->
            if (abs(s) >= ShockThresholds.GOLD_SIGMA) triggered += "Gold %.1fσ Move".format(s)
        }
        v[FeatureKeys.VIX_ZSCORE]?.let { s ->
            if (s >= ShockThresholds.VIX_SPIKE_ZSCORE) triggered += "VIX Spike"
        }

        val active = triggered.size >= ShockThresholds.MIN_TRIGGERED_FOR_SHOCK
        return ShockDetail(active = active, triggeredConditions = triggered)
    }
}
