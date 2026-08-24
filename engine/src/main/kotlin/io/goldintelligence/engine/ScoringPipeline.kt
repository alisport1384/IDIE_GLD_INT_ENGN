package io.goldintelligence.engine

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp

/**
 * Deterministic Factor Scoring → Dynamic Weighting → Interaction → Gold Bias
 * pipeline, matching the specification's "Weighted Score → Regime Adjustment
 * → Final Gold Score" architecture. It operates purely on the FactorScore
 * values already present in the InputSnapshot (or produced by a
 * FactorEngine); it never fabricates factor values itself.
 */
class ScoringPipeline(
    private val dynamicWeightEngine: DynamicWeightEngine = NormalizingDynamicWeightEngine(),
    private val interactionEngine: FactorInteractionEngine = ClusterFactorInteractionEngine(),
    private val contradictionEngine: ContradictionAnalysisEngine = RuleBasedContradictionAnalysisEngine(),
    private val attributionEngine: AttributionAnalysisEngine = LeaveOneOutAttributionEngine(),
    private val divergenceEngine: DivergenceAnalysisEngine = RuleBasedDivergenceAnalysisEngine()
) {
    data class Result(
        val goldBias: Double?,
        val attribution: List<AttributionEntry>,
        val conflict: ConflictLevel,
        val conflictNotes: List<String>,
        val divergences: List<DivergenceRecord>,
        val dominantFactor: String?,
        val weights: List<DynamicWeight>
    )

    fun run(
        scores: List<FactorScore>,
        features: FeatureSet,
        regime: Regime,
        now: Instant
    ): Result {
        if (scores.isEmpty()) {
            return Result(
                goldBias = null,
                attribution = emptyList(),
                conflict = ConflictLevel.UNKNOWN,
                conflictNotes = emptyList(),
                divergences = emptyList(),
                dominantFactor = null,
                weights = emptyList()
            )
        }

        val dominance = informationDominance(scores)
        val quality = scores.associate { it.factorId to it.quality.coerceIn(0.0, 1.0) }
        val decay = scores.associate { it.factorId to timeDecay(it.asOf, now) }
        val presentIds = scores.map { it.factorId }.toSet()
        val relevantBaseWeights = GoldSpecification.baseFactorWeights.filterKeys { it in presentIds }

        val weights = dynamicWeightEngine.calculate(
            baseWeights = relevantBaseWeights,
            regime = regime,
            dominance = dominance,
            quality = quality,
            timeDecay = decay
        )
        val weightById = weights.associate { it.factorId to it.weight }

        val weightedScores = scores.map { factor -> factor to (weightById[factor.factorId] ?: 0.0) }
        val directSum = weightedScores.sumOf { (factor, weight) -> factor.score * weight }
        val interaction = interactionEngine.adjust(scores)
        val goldBias = (directSum + interaction).coerceIn(-100.0, 100.0)

        val (conflict, conflictNotes) = contradictionEngine.analyze(weightedScores)
        val attribution = attributionEngine.attribute(weightedScores, interaction)
        val divergences = divergenceEngine.analyze(scores, features)
        val dominantFactor = attribution.maxByOrNull { abs(it.contribution) }?.factorId

        return Result(
            goldBias = goldBias,
            attribution = attribution,
            conflict = conflict,
            conflictNotes = conflictNotes,
            divergences = divergences,
            dominantFactor = dominantFactor,
            weights = weights
        )
    }

    private fun informationDominance(scores: List<FactorScore>): Map<String, Double> {
        val maxMagnitude = scores.maxOfOrNull { abs(it.score) }?.takeIf { it > 0.0 }
            ?: return scores.associate { it.factorId to 1.0 }
        return scores.associate { factor ->
            val relative = abs(factor.score) / maxMagnitude
            factor.factorId to (1.0 + relative * CalibrationDefaults.DOMINANCE_BOOST_MAX)
        }
    }

    private fun timeDecay(asOf: Instant?, now: Instant): Double {
        if (asOf == null) return 1.0
        val ageHours = Duration.between(asOf, now).toMinutes() / 60.0
        if (ageHours <= 0.0) return 1.0
        return exp(-ageHours / CalibrationDefaults.TIME_DECAY_HALF_LIFE_HOURS)
    }
}
