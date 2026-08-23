package io.goldintelligence.engine

import kotlin.math.abs

/**
 * Attribution Engine: every output score must be explainable
 * ("Final Bullish Score = +68 → Real Yield +18, DXY +12, ..."), never just
 * a bare number. Direct contribution is weight × factor score; the
 * Interaction Engine's cluster bonus/penalty is reported as its own
 * attributable entry rather than being silently folded into one factor.
 */
class LeaveOneOutAttributionEngine : AttributionAnalysisEngine {
    override fun attribute(
        weightedScores: List<Pair<FactorScore, Double>>,
        interactionEffect: Double
    ): List<AttributionEntry> {
        val direct = weightedScores.map { (factor, weight) ->
            AttributionEntry(factor.factorId, factor.score * weight)
        }
        val withInteraction = if (interactionEffect != 0.0) {
            direct + AttributionEntry("INTERACTION_EFFECT", interactionEffect)
        } else {
            direct
        }
        return withInteraction.sortedByDescending { abs(it.contribution) }
    }
}
