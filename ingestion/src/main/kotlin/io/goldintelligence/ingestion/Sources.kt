package io.goldintelligence.ingestion

import java.time.Instant

/**
 * One contract per data-architecture domain.
 *
 * SPEC v2 §14: the original `MarketMicrostructureSource` named a capability
 * the free stack cannot deliver — no free provider exposes order-book depth
 * for COMEX gold. The contract is therefore `FreeMarketSource`: OHLCV bars,
 * dated-contract quotes and the forward curve, which the free stack *can*
 * deliver. Microstructure is reconstructed downstream as an explicit proxy.
 *
 * Implementations live in the `:client` module. Nothing here fabricates a
 * connection or a value.
 */

interface FreeMarketSource {
    /** Latest known tick for `symbol`. */
    suspend fun latest(symbol: String): RawMarketTick?

    /** Ordered daily (or finer) history for `symbol`, oldest bar first. */
    suspend fun history(symbol: String, maxBars: Int = 512): TimeSeries?
}

interface SpotGoldSource {
    suspend fun latest(): RawSpotQuote?
}

interface MacroSource {
    /** Latest release for each requested official series id. */
    suspend fun latestReleases(seriesIds: Set<String>): List<RawMacroRelease>
}

interface NewsSource {
    /** News items published at or after `since`. */
    suspend fun recent(since: Instant): List<RawNewsItem>
}

/**
 * SPEC v2 §1.5 — COMEX forward curve. Dated contracts are quoted
 * individually; the curve is the ordered set of their settlements.
 */
interface ForwardCurveSource {
    /** contractCode → settlement price, in delivery-month order. */
    suspend fun curve(): Map<String, Double>
}

/** SPEC v2 §11A/§11B — positioning and credit inputs. */
interface PositioningSource {
    suspend fun netNonCommercialHistory(maxWeeks: Int = 200): TimeSeries?
}
