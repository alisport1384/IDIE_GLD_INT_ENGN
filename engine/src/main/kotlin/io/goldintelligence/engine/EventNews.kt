package io.goldintelligence.engine

/**
 * Pure scoring functions over already-observed event/news records (spec
 * layers "Event Engine" and "News Intelligence Layer"). Live ingestion
 * (economic calendars, news feeds, credentials) is out of scope for this
 * module and is deferred to the future server-side deployment; nothing here
 * fabricates a feed, only the scoring of a record already supplied by the
 * caller.
 */
class SurpriseEventEngine : EventEngine {
    override fun evaluate(event: EventRecord): Double? = event.surprise
}

class NoOpEventEngine : EventEngine {
    override fun evaluate(event: EventRecord): Double? = null
}

/**
 * News → Severity/Novelty/Credibility → Gold Relevance → Expected Impact,
 * per spec item 21. The weighting formula is a provisional Initial Prior
 * (Specification.CalibrationDefaults) pending recalibration against the
 * "Price Reaction Efficiency" and "Narrative vs Reality" feedback loops
 * described in the specification.
 */
class WeightedNewsIntelligenceEngine : NewsIntelligenceEngine {
    override fun evaluate(news: NewsRecord): NewsImpact {
        val d = CalibrationDefaults
        val rawScore = news.severity * d.NEWS_SEVERITY_WEIGHT +
            news.novelty * d.NEWS_NOVELTY_WEIGHT +
            news.credibility * d.NEWS_CREDIBILITY_WEIGHT
        val relevanceAdjusted = rawScore * (news.goldRelevance / 100.0)

        val label = when {
            relevanceAdjusted >= d.NEWS_HIGH_IMPACT_THRESHOLD -> "HIGH_IMPACT"
            relevanceAdjusted >= d.NEWS_MEDIUM_IMPACT_THRESHOLD -> "MEDIUM_IMPACT"
            else -> "LOW_IMPACT"
        }
        return NewsImpact(expectedImpactScore = relevanceAdjusted, label = label)
    }
}

class NoOpNewsIntelligenceEngine : NewsIntelligenceEngine {
    override fun evaluate(news: NewsRecord): NewsImpact = NewsImpact(0.0, "UNKNOWN")
}
