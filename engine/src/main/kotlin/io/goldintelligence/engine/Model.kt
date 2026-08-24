package io.goldintelligence.engine

import java.time.Instant

enum class Direction { BULLISH, BEARISH, NEUTRAL }
enum class SignalState { VALID, WEAK, INVALID, UNKNOWN }
enum class Uncertainty { HIGH_CONFIDENCE, LOW_CONFIDENCE, INSUFFICIENT_INFORMATION }
enum class Regime {
    NORMAL, MONETARY_EASING, MONETARY_TIGHTENING, INFLATION,
    GEOPOLITICAL_CRISIS, LIQUIDITY_STRESS, RISK_ON, RISK_OFF,
    GROWTH_SLOWDOWN, REGIME_TRANSITION, UNKNOWN
}
enum class Stability { HIGH, MEDIUM, LOW, UNKNOWN }
enum class Liquidity { NORMAL, STRESSED, UNKNOWN }
enum class ShockState { NONE, ACTIVE, UNKNOWN }
enum class DivergenceType { MACRO, FLOW, POSITIONING, NEWS, PRICE, CROSS_MARKET }
enum class ConflictLevel { LOW, MEDIUM, HIGH, UNKNOWN }

data class Observation(
    val value: Double,
    val timestamp: Instant,
    val releaseTimestamp: Instant? = null,
    val source: String,
    val expected: Double? = null,
    val previous: Double? = null,
    val actual: Double? = null,
    val revision: Double? = null,
    val quality: Double = 1.0,
    val latencySeconds: Long = 0
)

data class FeatureSet(
    val values: Map<String, Double> = emptyMap()
)

data class FactorScore(
    val factorId: String,
    val score: Double,
    val quality: Double = 1.0,
    val asOf: Instant? = null
)

data class DynamicWeight(
    val factorId: String,
    val weight: Double
)

data class InputSnapshot(
    val observations: Map<String, Observation>,
    val features: FeatureSet = FeatureSet(),
    val factorScores: List<FactorScore> = emptyList(),
    val regime: Regime = Regime.UNKNOWN,
    val regimeStability: Stability = Stability.UNKNOWN,
    val liquidity: Liquidity = Liquidity.UNKNOWN,
    val shock: ShockState = ShockState.UNKNOWN,
    val contradictions: List<String> = emptyList(),
    val dominantFactor: String? = null,
    val events: List<EventRecord> = emptyList(),
    val news: List<NewsRecord> = emptyList()
) {
    companion object {
        fun empty() = InputSnapshot(emptyMap())
    }
}

data class ProbabilityDistribution(
    val up: Double? = null,
    val neutral: Double? = null,
    val down: Double? = null
)

data class Scenario(
    val name: String,
    val probability: Double?,
    val trigger: String,
    val expectedDirection: Direction,
    val expectedMagnitude: Double?,
    val invalidation: String
)

data class ShockDetail(
    val active: Boolean,
    val triggeredConditions: List<String>
)

data class DivergenceRecord(
    val type: DivergenceType,
    val description: String
)

data class AttributionEntry(
    val factorId: String,
    val contribution: Double
)

data class EventRecord(
    val eventType: String,
    val country: String,
    val expected: Double?,
    val previous: Double?,
    val actual: Double?,
    val timestamp: Instant,
    val releaseTimestamp: Instant? = null
) {
    val surprise: Double?
        get() = if (actual != null && expected != null) actual - expected else null
}

data class NewsRecord(
    val eventType: String,
    val country: String,
    val severity: Double,
    val novelty: Double,
    val credibility: Double,
    val goldRelevance: Double,
    val direction: Direction,
    val timestamp: Instant
)

data class NewsImpact(
    val expectedImpactScore: Double,
    val label: String
)

data class GoldIntelligenceState(
    val direction: Direction,
    val probability: Double?,
    val confidence: Double?,
    val regime: Regime,
    val regimeStability: Stability,
    val dominantFactor: String?,
    val primaryDrivers: List<String>,
    val contradictions: List<String>,
    val crossMarketConfirmation: Double?,
    val newsState: String,
    val liquidity: Liquidity,
    val shock: ShockState,
    val expectedMove: Double?,
    val uncertainty: Uncertainty,
    val scenarios: List<Scenario>,
    val invalidation: String?,
    val signalState: SignalState,
    val goldBias: Double? = null,
    val attribution: List<AttributionEntry> = emptyList(),
    val conflict: ConflictLevel = ConflictLevel.UNKNOWN,
    val conflictNotes: List<String> = emptyList(),
    val shockDetail: ShockDetail? = null,
    val divergences: List<DivergenceRecord> = emptyList(),
    val scenarioOutlook: List<Scenario> = emptyList()
)
