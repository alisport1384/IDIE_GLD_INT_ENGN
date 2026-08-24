package io.goldintelligence.ingestion

import java.time.Instant

/**
 * One contract per data-architecture domain, matching the decided feed
 * layout:
 *   Market Core        → COMEX Gold Futures (e.g. Databento)
 *   Gold Spot           → a dedicated Spot reference feed
 *   Macro                → official sources (Treasury / FRED / CME)
 *   News / Fed          → an independent news feed
 *
 * No concrete provider client is implemented in this module — no
 * credentials or provider network access exist in this environment.
 * Implementing one of these interfaces (e.g. `DatabentoMarketSource`) is
 * exactly the seam where the future server-side deployment plugs in a real
 * client; nothing here fabricates a connection or a value.
 */

interface MarketMicrostructureSource {
    /** Latest known tick for `symbol` (e.g. "GCZ26" front-month COMEX Gold future). */
    suspend fun latest(symbol: String): RawMarketTick?
}

interface SpotGoldSource {
    suspend fun latest(): RawSpotQuote?
}

interface MacroSource {
    /** Latest release for each requested official series id (e.g. "US10Y_REAL_YIELD", "CPI"). */
    suspend fun latestReleases(seriesIds: Set<String>): List<RawMacroRelease>
}

interface NewsSource {
    /** News items published at or after `since`. */
    suspend fun recent(since: Instant): List<RawNewsItem>
}
