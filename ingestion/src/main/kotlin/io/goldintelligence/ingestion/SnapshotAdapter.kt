package io.goldintelligence.ingestion

import io.goldintelligence.engine.InputSnapshot
import io.goldintelligence.engine.NewsRecord
import io.goldintelligence.engine.Observation
import java.time.Duration

/**
 * News → NewsRecord requires severity/novelty/credibility/goldRelevance,
 * none of which are derivable from a raw headline without an NLP/News-
 * scoring component. That component does not exist yet, so it is exposed
 * as an explicit seam rather than being defaulted/fabricated here.
 */
interface NewsScoringAdapter {
    fun score(items: List<RawNewsItem>): List<NewsRecord>
}

/** Fail-safe default: no scorer plugged in yet → no NewsRecords, never invented ones. */
class NoOpNewsScoringAdapter : NewsScoringAdapter {
    override fun score(items: List<RawNewsItem>): List<NewsRecord> = emptyList()
}

/**
 * Converts a SyncedFrame into the engine's InputSnapshot.observations.
 * Only mechanical field mapping happens here (price/actual → value,
 * origin timestamp → timestamp, provider name → source). Feature
 * engineering (z-scores, momentum, surprises, trends) remains the
 * responsibility of a FeatureEngineer implementation, not this adapter —
 * this module's job ends at "one synchronized, correctly time-stamped raw
 * observation set", per the specification's own layering
 * (RAW DATA → DATA VALIDATION → FEATURE ENGINEERING → ...).
 */
class SnapshotAdapter(
    private val newsScoringAdapter: NewsScoringAdapter = NoOpNewsScoringAdapter()
) {
    fun toInputSnapshot(frame: SyncedFrame): InputSnapshot {
        val observations = mutableMapOf<String, Observation>()

        frame.marketTicks.forEach { (symbol, tick) ->
            observations[symbol] = Observation(
                value = tick.price,
                timestamp = tick.exchangeTimestamp,
                releaseTimestamp = null,
                source = tick.source,
                quality = 1.0,
                latencySeconds = Duration.between(tick.exchangeTimestamp, tick.ingestTimestamp).seconds
            )
        }

        frame.spot?.let { spot ->
            observations["GOLD_SPOT"] = Observation(
                value = spot.price,
                timestamp = spot.exchangeTimestamp,
                releaseTimestamp = null,
                source = spot.source,
                quality = 1.0,
                latencySeconds = Duration.between(spot.exchangeTimestamp, spot.ingestTimestamp).seconds
            )
        }

        frame.macro.forEach { (seriesId, release) ->
            val actual = release.actual
            if (actual != null) {
                observations[seriesId] = Observation(
                    value = actual,
                    timestamp = release.releaseTimestamp,
                    releaseTimestamp = release.releaseTimestamp,
                    source = release.source,
                    expected = release.expected,
                    previous = release.previous,
                    actual = release.actual,
                    revision = release.revision,
                    quality = 1.0,
                    latencySeconds = Duration.between(release.releaseTimestamp, release.ingestTimestamp).seconds
                )
            }
            // actual == null (not yet released / withheld): omitted rather than
            // filled with a placeholder value — Missing Data Policy, spec item 17.
        }

        return InputSnapshot(
            observations = observations,
            news = newsScoringAdapter.score(frame.news)
        )
    }
}
