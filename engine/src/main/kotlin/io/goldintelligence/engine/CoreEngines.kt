package io.goldintelligence.engine

class StrictDataValidator : DataValidator {
    override fun validate(snapshot: InputSnapshot): Boolean =
        snapshot.observations.values.all {
            it.source.isNotBlank() &&
            it.quality in 0.0..1.0 &&
            it.timestamp != null
        }
}

class IdentityFeatureEngineer : FeatureEngineer {
    override fun transform(snapshot: InputSnapshot): FeatureSet = snapshot.features
}

class SuppliedFactorEngine : FactorEngine {
    override fun score(features: FeatureSet): List<FactorScore> = emptyList()
}

class SnapshotRegimeEngine : RegimeEngine {
    override fun detect(snapshot: InputSnapshot): Pair<Regime, Stability> =
        snapshot.regime to snapshot.regimeStability
}

class NormalizingDynamicWeightEngine : DynamicWeightEngine {
    override fun calculate(
        baseWeights: Map<String, Double>,
        regime: Regime,
        dominance: Map<String, Double>,
        quality: Map<String, Double>,
        timeDecay: Map<String, Double>
    ): List<DynamicWeight> {
        val effective = baseWeights.mapValues { (id, base) ->
            base *
                RegimeAdjustments.multiplierFor(id, regime) *
                (dominance[id] ?: 1.0) *
                (quality[id] ?: 1.0) *
                (timeDecay[id] ?: 1.0)
        }
        val total = effective.values.sum()
        if (total <= 0.0) return effective.keys.map { DynamicWeight(it, 0.0) }
        return effective.map { (id, weight) -> DynamicWeight(id, weight / total) }
    }
}

class NoProbabilityModel : ProbabilityModel {
    override fun predict(snapshot: InputSnapshot): ProbabilityDistribution? = null
}

class NoOpCalibrator : ProbabilityCalibrator {
    override fun calibrate(raw: ProbabilityDistribution): ProbabilityDistribution = raw
}

class EvidenceConfidenceEngine : ConfidenceEngine {
    override fun calculate(
        snapshot: InputSnapshot,
        distribution: ProbabilityDistribution?
    ): Double? {
        if (distribution == null) return null
        val quality = snapshot.observations.values
            .map { it.quality }
            .ifEmpty { return null }
            .average()
        val contradictionPenalty = (snapshot.contradictions.size * 0.1).coerceAtMost(0.8)
        return (quality - contradictionPenalty).coerceIn(0.0, 1.0)
    }
}

class NoHistoricalAnalogueEngine : HistoricalAnalogueEngine {
    override fun find(snapshot: InputSnapshot): List<Scenario> = emptyList()
}

class NoInvalidationEngine : InvalidationEngine {
    override fun invalidate(snapshot: InputSnapshot): String? = null
}
