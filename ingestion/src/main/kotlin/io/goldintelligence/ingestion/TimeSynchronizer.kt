package io.goldintelligence.ingestion

import java.time.Duration
import java.time.Instant

/**
 * One synchronized cross-domain view of the world as of a single UTC
 * instant. `asOf` is the synchronization point; every record inside is
 * guaranteed to have originated at or before it (see TimeSynchronizer),
 * which is what makes this frame safe to feed into the engine without
 * look-ahead bias.
 */
data class SyncedFrame(
    val asOf: Instant,
    val marketTicks: Map<String, RawMarketTick>,
    val spot: RawSpotQuote?,
    val macro: Map<String, RawMacroRelease>,
    val news: List<RawNewsItem>
)

/**
 * Aligns Market Microstructure, Gold Spot, Macro and News records — each
 * arriving on its own cadence and its own timestamp — onto one `asOf`
 * instant. Two invariants are enforced:
 *
 *  1. No look-ahead: any record whose own timestamp is after `asOf` is
 *     excluded, never used, regardless of when it happened to be ingested
 *     (spec: Data Vintage / Backtesting Integrity).
 *  2. Freshest known: for each symbol/series, the latest record at or
 *     before `asOf` is kept.
 *
 * News is windowed by `newsLookback` rather than reduced to "latest one",
 * since multiple relevant news items can be simultaneously in play.
 */
class TimeSynchronizer(
    private val newsLookback: Duration = Duration.ofHours(24)
) {
    fun sync(
        asOf: Instant,
        marketTicks: List<RawMarketTick> = emptyList(),
        spotQuotes: List<RawSpotQuote> = emptyList(),
        macroReleases: List<RawMacroRelease> = emptyList(),
        newsItems: List<RawNewsItem> = emptyList()
    ): SyncedFrame {
        val latestMarketBySymbol = marketTicks
            .filter { !it.exchangeTimestamp.isAfter(asOf) }
            .groupBy { it.symbol }
            .mapValues { (_, ticks) -> ticks.maxBy { it.exchangeTimestamp } }

        val latestSpot = spotQuotes
            .filter { !it.exchangeTimestamp.isAfter(asOf) }
            .maxByOrNull { it.exchangeTimestamp }

        val latestMacroBySeries = macroReleases
            .filter { !it.releaseTimestamp.isAfter(asOf) }
            .groupBy { it.seriesId }
            .mapValues { (_, releases) -> releases.maxBy { it.releaseTimestamp } }

        val recentNews = newsItems
            .filter { !it.publishTimestamp.isAfter(asOf) }
            .filter { Duration.between(it.publishTimestamp, asOf) <= newsLookback }

        return SyncedFrame(
            asOf = asOf,
            marketTicks = latestMarketBySymbol,
            spot = latestSpot,
            macro = latestMacroBySeries,
            news = recentNews
        )
    }
}
