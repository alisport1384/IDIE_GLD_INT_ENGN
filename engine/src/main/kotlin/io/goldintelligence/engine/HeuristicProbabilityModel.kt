package io.goldintelligence.engine

import kotlin.math.exp

/**
 * Liquidity Conditions (spec item 7). Uses Dollar Funding Stress as the
 * primary signal, per the specification's emphasis on distinguishing Dollar
 * Strength from Dollar Liquidity Stress. Threshold is the same provisional
 * LIQUIDITY_STRESS_THRESHOLD used by the Regime Engine.
 */
class RuleBasedLiquidityEngine : LiquidityAssessmentEngine {
    override fun assess(features: FeatureSet): Liquidity {
        val stress = features.values[FeatureKeys.DOLLAR_FUNDING_STRESS] ?: return Liquidity.UNKNOWN
        return if (stress >= CalibrationDefaults.LIQUIDITY_STRESS_THRESHOLD) {
            Liquidity.STRESSED
        } else {
            Liquidity.NORMAL
        }
    }
}

/**
 * NOT a substitute for the specification's Probability Engine (which
 * requires a trained, back-tested, Platt/Isotonic-calibrated model, per
 * spec items 16–17). This is a transparent, deterministic fallback so
 * Probability/Confidence are not permanently "N/A" before that model
 * exists: it takes an equal base-weight average of the supplied
 * FactorScores and passes it through a logistic transform. Its output must
 * never be presented to an end user as a calibrated probability.
 */
class HeuristicProbabilityModel : ProbabilityModel {
    override fun predict(snapshot: InputSnapshot): ProbabilityDistribution? {
        val scores = snapshot.factorScores
        if (scores.isEmpty()) return null

        var weightedSum = 0.0
        var totalWeight = 0.0
        for (factor in scores) {
            val weight = GoldSpecification.baseFactorWeights[factor.factorId] ?: 0.0
            weightedSum += factor.score * weight
            totalWeight += weight
        }
        if (totalWeight <= 0.0) return null

        val avgBias = (weightedSum / totalWeight).coerceIn(-100.0, 100.0)
        val up = 1.0 / (1.0 + exp(-avgBias / 25.0))
        return ProbabilityDistribution(up = up, down = 1.0 - up, neutral = 0.0)
    }
}
