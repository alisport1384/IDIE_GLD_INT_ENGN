package io.goldintelligence.engine

interface DataValidator {
    fun validate(snapshot: InputSnapshot): Boolean
}

interface FeatureEngineer {
    fun transform(snapshot: InputSnapshot): FeatureSet
}

interface FactorEngine {
    fun score(features: FeatureSet): List<FactorScore>
}

interface RegimeEngine {
    fun detect(snapshot: InputSnapshot): Pair<Regime, Stability>
}

interface DynamicWeightEngine {
    fun calculate(
        baseWeights: Map<String, Double>,
        regime: Regime,
        dominance: Map<String, Double>,
        quality: Map<String, Double>,
        timeDecay: Map<String, Double>
    ): List<DynamicWeight>
}

interface ProbabilityModel {
    fun predict(snapshot: InputSnapshot): ProbabilityDistribution?
}

interface ProbabilityCalibrator {
    fun calibrate(raw: ProbabilityDistribution): ProbabilityDistribution
}

interface ConfidenceEngine {
    fun calculate(snapshot: InputSnapshot, distribution: ProbabilityDistribution?): Double?
}

interface HistoricalAnalogueEngine {
    fun find(snapshot: InputSnapshot): List<Scenario>
}

interface InvalidationEngine {
    fun invalidate(snapshot: InputSnapshot): String?
}

interface ShockDetectionEngine {
    fun detect(snapshot: InputSnapshot, features: FeatureSet): ShockDetail
}

interface DivergenceAnalysisEngine {
    fun analyze(scores: List<FactorScore>, features: FeatureSet): List<DivergenceRecord>
}

interface ContradictionAnalysisEngine {
    fun analyze(weightedScores: List<Pair<FactorScore, Double>>): Pair<ConflictLevel, List<String>>
}

interface FactorInteractionEngine {
    fun adjust(scores: List<FactorScore>): Double
}

interface AttributionAnalysisEngine {
    fun attribute(
        weightedScores: List<Pair<FactorScore, Double>>,
        interactionEffect: Double
    ): List<AttributionEntry>
}

interface ScenarioOutlookEngine {
    fun buildScenarios(scores: List<FactorScore>, goldBias: Double?): List<Scenario>
}

interface EventEngine {
    fun evaluate(event: EventRecord): Double?
}

interface LiquidityAssessmentEngine {
    fun assess(features: FeatureSet): Liquidity
}

interface NewsIntelligenceEngine {
    fun evaluate(news: NewsRecord): NewsImpact
}
