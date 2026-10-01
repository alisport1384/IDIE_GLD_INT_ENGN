package io.goldintelligence.ingestion

import io.goldintelligence.engine.Tier
import java.time.Instant

/**
 * The normalized, provider-independent view the feature layer consumes.
 * Clients populate it; nothing downstream knows which provider a series
 * came from except through [DataPoint] provenance.
 */
data class MarketUniverse(
    val asOf: Instant,
    /** Canonical series id → ordered history. */
    val series: Map<String, TimeSeries> = emptyMap(),
    /** Canonical series id → latest scalar reading. */
    val scalars: Map<String, Double> = emptyMap(),
    /** Full provenance record for every ingested value. */
    val points: Map<String, DataPoint> = emptyMap(),
    /** Provider id → reachability / circuit-breaker state. */
    val providerHealth: Map<String, ProviderHealth> = emptyMap()
) {
    fun seriesOf(id: String): TimeSeries? = series[id]?.takeIf { it.bars.isNotEmpty() }
    fun scalar(id: String): Double? = scalars[id]
    fun point(id: String): DataPoint? = points[id]

    companion object {
        // --- canonical series ids -------------------------------------
        const val GOLD_SPOT = "GOLD_SPOT"
        const val SILVER_SPOT = "SILVER_SPOT"
        const val GOLD_PROXY_ETF = "GLD"
        const val SILVER_PROXY_ETF = "SLV"
        const val TIPS_ETF = "TIP"
        const val HY_ETF = "HYG"
        const val IG_ETF = "LQD"
        const val OIL_ETF = "USO"
        const val EQUITY_ETF = "SPY"
        const val LONG_BOND_ETF = "TLT"
        const val MINERS_ETF = "GDX"
        const val CHINA_ETF = "FXI"
        const val DOLLAR_ETF = "UUP"
        const val VIX = "VIX"
        const val GVZ = "GVZ"
        const val OVX = "OVX"
        const val SKEW = "SKEW"
        const val SPX = "SPX"
        const val DXY_SYNTHETIC = "DXY_SYNTHETIC"
        const val US10Y = "US10Y"
        const val US02Y = "US02Y"
        const val US03M = "US03M"
        const val US30Y = "US30Y"
        const val REAL10Y = "REAL10Y"
        const val REAL05Y = "REAL05Y"
        const val REAL30Y = "REAL30Y"
        const val BREAKEVEN10Y = "BREAKEVEN10Y"
        const val SOFR = "SOFR"
        const val EFFR = "EFFR"
        const val COT_NET_NONCOMM = "COT_NET_NONCOMM"
        const val COT_OPEN_INTEREST = "COT_OPEN_INTEREST"
        const val FX_CNY = "FX_CNY"
        const val FX_INR = "FX_INR"
        const val FX_EUR = "FX_EUR"
        const val FX_JPY = "FX_JPY"
        const val FUNDING_SPREAD = "FUNDING_SPREAD"
        const val GLD_BID_SIZE = "GLD_BID_SIZE"
        const val GLD_ASK_SIZE = "GLD_ASK_SIZE"
        const val GLD_SPREAD_BP = "GLD_SPREAD_BP"
        const val GLD_IV30 = "GLD_IV30"
        const val GOLD_FUT_FRONT = "GOLD_FUT_FRONT"
        const val GOLD_FUT_DEFERRED = "GOLD_FUT_DEFERRED"
    }
}

/** Circuit-breaker / reachability state of one provider (SPEC v2 §12.1). */
data class ProviderHealth(
    val providerId: String,
    val reachable: Boolean,
    val httpStatus: Int?,
    val latencyMillis: Long?,
    val lastSuccess: Instant?,
    val message: String? = null,
    val circuitOpenUntil: Instant? = null
) {
    val state: String
        get() = when {
            circuitOpenUntil != null -> "CIRCUIT_OPEN"
            reachable -> "OK"
            else -> "FAILED"
        }
}

/**
 * A single feature with its full provenance, so the presentation layer can
 * label every indicator with its source, tier, proxy status and freshness
 * instead of showing a bare number (SPEC v2 §18 display rules).
 */
data class FeatureValue(
    val key: String,
    val value: Double,
    val unit: String,
    val source: String,
    val tier: Tier,
    val isProxy: Boolean,
    val quality: Double,
    val asOf: Instant,
    val note: String? = null
)

data class FeatureBundle(val values: Map<String, FeatureValue> = emptyMap()) {
    fun numeric(): Map<String, Double> = values.mapValues { it.value.value }
    operator fun get(key: String): FeatureValue? = values[key]
    fun valueOf(key: String): Double? = values[key]?.value
}
