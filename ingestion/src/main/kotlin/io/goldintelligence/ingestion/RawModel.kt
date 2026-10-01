package io.goldintelligence.ingestion

import java.time.Instant

/**
 * Raw records as received from each provider, before any synchronization or
 * feature engineering. All timestamps are UTC `Instant`. Every record keeps
 * both the origin timestamp (`exchangeTimestamp` / `releaseTimestamp` /
 * `publishTimestamp`) and `ingestTimestamp` (when this system received it),
 * so latency and point-in-time correctness (no look-ahead) can always be
 * reconstructed — per the specification's Timestamp Integrity / Data
 * Vintage requirements.
 */

/** Market Core — e.g. COMEX Gold Futures via Databento. */
data class RawMarketTick(
    val symbol: String,
    val price: Double,
    val volume: Double? = null,
    val bid: Double? = null,
    val ask: Double? = null,
    val openInterest: Double? = null,
    val exchangeTimestamp: Instant,
    val ingestTimestamp: Instant,
    val source: String
)

/** Gold Spot — a dedicated reference Spot feed, kept distinct from futures. */
data class RawSpotQuote(
    val price: Double,
    val bid: Double? = null,
    val ask: Double? = null,
    val exchangeTimestamp: Instant,
    val ingestTimestamp: Instant,
    val source: String
)

/** Macro — official sources (Treasury / FRED / CME, etc.). */
data class RawMacroRelease(
    val seriesId: String,
    val actual: Double?,
    val expected: Double? = null,
    val previous: Double? = null,
    val revision: Double? = null,
    val releaseTimestamp: Instant,
    val ingestTimestamp: Instant,
    val source: String
)

/** News / Fed — an independent news feed. */
data class RawNewsItem(
    val headline: String,
    val body: String? = null,
    val entities: List<String> = emptyList(),
    val publishTimestamp: Instant,
    val ingestTimestamp: Instant,
    val source: String
)
