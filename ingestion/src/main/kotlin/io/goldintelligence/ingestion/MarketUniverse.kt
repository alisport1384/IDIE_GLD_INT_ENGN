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

        // --- SPEC v2.1: ids that close the previously unreachable gaps ----
        /** Real ICE dollar index, replaces the synthetic currency basket. */
        const val DXY_INDEX = "DXY_INDEX"
        /** COMEX gold front-month open interest, published daily. */
        const val GOLD_OI_DAILY = "GOLD_OI_DAILY"
        const val GOLD_FUT_VOLUME = "GOLD_FUT_VOLUME"
        /** Depth-of-book, aggregated over the captured levels. */
        const val BOOK_IMBALANCE = "BOOK_IMBALANCE"
        const val BOOK_BID_VOLUME = "BOOK_BID_VOLUME"
        const val BOOK_ASK_VOLUME = "BOOK_ASK_VOLUME"
        const val BOOK_SPREAD_BP = "BOOK_SPREAD_BP"
        const val BOOK_DEPTH_LEVELS = "BOOK_DEPTH_LEVELS"
        const val OTC_SPREAD_BP = "OTC_SPREAD_BP"
        /** Regional physical markets. */
        const val SGE_GOLD_CNY_G = "SGE_GOLD_CNY_G"
        const val SHFE_GOLD_CNY_G = "SHFE_GOLD_CNY_G"
        const val MCX_GOLD_INR_10G = "MCX_GOLD_INR_10G"
        const val CHINA_PREMIUM_PCT = "CHINA_PREMIUM_PCT"
        const val INDIA_PREMIUM_PCT = "INDIA_PREMIUM_PCT"
        /** LBMA-based benchmark published by the World Gold Council. */
        const val LBMA_BENCHMARK = "LBMA_BENCHMARK"
        /** Cross-source spot dispersion, in basis points. */
        const val SPOT_CONSENSUS_BP = "SPOT_CONSENSUS_BP"
        /** Calendar / consensus. */
        const val CALENDAR_HIGH_IMPACT_24H = "CALENDAR_HIGH_IMPACT_24H"
        const val CALENDAR_NEXT_EVENT_HOURS = "CALENDAR_NEXT_EVENT_HOURS"
        const val CPI_YOY = "CPI_YOY"
        const val UNEMPLOYMENT_RATE = "UNEMPLOYMENT_RATE"
        const val CONSENSUS_SURPRISE = "CONSENSUS_SURPRISE"
        /** Spot history sourced from a gold instrument, not an ETF wrapper. */
        const val GOLD_SPOT_HISTORY = "GOLD_SPOT_HISTORY"

        // --- SPEC v2.1 §23: measured replacements for the last proxies ----
        /** Official-sector gold, net change over three reported months, tonnes. */
        const val CB_GOLD_NET_3M_T = "CB_GOLD_NET_3M_T"
        /** The same change against its own 24-window history. */
        const val CB_GOLD_NET_3M_Z = "CB_GOLD_NET_3M_Z"
        /** Buyers minus sellers among reporting countries, ×100. */
        const val CB_GOLD_BREADTH = "CB_GOLD_BREADTH"
        const val CB_GOLD_REPORTERS = "CB_GOLD_REPORTERS"
        /** Mean normalised surprise over recent released prints, ×100. */
        const val SURPRISE_INDEX_MEASURED = "SURPRISE_INDEX_MEASURED"
        /** The same, restricted to inflation releases. */
        const val INFLATION_SURPRISE_MEASURED = "INFLATION_SURPRISE_MEASURED"
        /** Policy rates as published by the BIS. */
        const val POLICY_RATE_US = "POLICY_RATE_US"
        const val POLICY_RATE_DIVERGENCE = "POLICY_RATE_DIVERGENCE"
        const val POLICY_DIVERGENCE_CHANGE_12M = "POLICY_DIVERGENCE_CHANGE_12M"
        /** Quoted Treasury yields, intraday, against the end-of-day curve. */
        const val US10Y_INTRADAY = "US10Y_INTRADAY"
        const val US02Y_INTRADAY = "US02Y_INTRADAY"

        /* ---- SPEC v2.1 §25 — measured macro, options and news ------- */

        /** FRED series, published daily or weekly, no key required. */
        const val HY_OAS = "HY_OAS"
        const val FINANCIAL_CONDITIONS_NFCI = "FINANCIAL_CONDITIONS_NFCI"
        const val FINANCIAL_STRESS_STLFSI = "FINANCIAL_STRESS_STLFSI"
        const val FED_BALANCE_SHEET = "FED_BALANCE_SHEET"
        const val TREASURY_GENERAL_ACCOUNT = "TREASURY_GENERAL_ACCOUNT"
        const val REVERSE_REPO = "REVERSE_REPO"
        const val FED_NET_LIQUIDITY = "FED_NET_LIQUIDITY"
        const val POLICY_UNCERTAINTY_DAILY = "POLICY_UNCERTAINTY_DAILY"
        const val NEWS_EQUITY_UNCERTAINTY = "NEWS_EQUITY_UNCERTAINTY"
        const val INFLATION_EXPECTATION_5Y5Y = "INFLATION_EXPECTATION_5Y5Y"
        const val REAL10Y_DEEP = "REAL10Y_DEEP"
        const val BREAKEVEN10Y_DEEP = "BREAKEVEN10Y_DEEP"
        const val GVZ_DEEP = "GVZ_DEEP"
        const val DOLLAR_BROAD_INDEX = "DOLLAR_BROAD_INDEX"

        /** Market-implied policy path, from the fed funds futures strip. */
        const val FED_FUNDS_IMPLIED_FRONT = "FED_FUNDS_IMPLIED_FRONT"
        const val FED_FUNDS_IMPLIED_12M = "FED_FUNDS_IMPLIED_12M"

        /** Volatility of the bond market, and the shape of the equity fear curve. */
        const val BOND_VOL_MOVE = "BOND_VOL_MOVE"
        const val VIX_9D = "VIX_9D"
        const val VIX_3M = "VIX_3M"

        /** Sovereign ten-year yields outside the United States. */
        const val DE10Y = "DE10Y"
        const val JP10Y = "JP10Y"
        const val GB10Y = "GB10Y"
        const val CN10Y = "CN10Y"

        /** Measured from the published GLD option chain. */
        const val GOLD_PUT_CALL_OI = "GOLD_PUT_CALL_OI"
        const val GOLD_PUT_CALL_VOLUME = "GOLD_PUT_CALL_VOLUME"
        const val GOLD_RISK_REVERSAL_25D = "GOLD_RISK_REVERSAL_25D"
        const val GOLD_IV_TERM_SLOPE = "GOLD_IV_TERM_SLOPE"
        const val GOLD_OPTION_OPEN_INTEREST = "GOLD_OPTION_OPEN_INTEREST"

        /** Competing store of value, for the cross-asset read. */
        const val BTC_SPOT = "BTC_SPOT"

        /* SPEC v2.1 §27 — measured daily history, keyless FRED CSV. Each one
           is the published series itself, not a stand-in, and each is kept at
           full depth because the §26 replay reads it. */
        const val REAL5Y_DEEP = "REAL5Y_DEEP"
        const val REAL30Y_DEEP = "REAL30Y_DEEP"
        const val BREAKEVEN5Y_DEEP = "BREAKEVEN5Y_DEEP"
        const val US10Y_DEEP = "US10Y_DEEP"
        const val US02Y_DEEP = "US02Y_DEEP"
        const val CURVE_10Y2Y_DEEP = "CURVE_10Y2Y_DEEP"
        const val CURVE_10Y3M_DEEP = "CURVE_10Y3M_DEEP"
        const val VIX_DEEP = "VIX_DEEP"
        const val NASDAQ_VOL_VXN = "NASDAQ_VOL_VXN"
        const val OIL_VOL_OVX = "OIL_VOL_OVX"
        const val WTI_SPOT = "WTI_SPOT"
        const val BRENT_SPOT = "BRENT_SPOT"
        const val NATGAS_SPOT = "NATGAS_SPOT"
        const val IG_OAS = "IG_OAS"
        const val CCC_OAS = "CCC_OAS"
        const val EM_OAS = "EM_OAS"
        const val DOLLAR_AFE_INDEX = "DOLLAR_AFE_INDEX"
        const val EURUSD = "EURUSD"
        const val USDJPY = "USDJPY"
        const val USDCNY = "USDCNY"
        const val GBPUSD = "GBPUSD"
        const val RESERVE_BALANCES = "RESERVE_BALANCES"
        const val FED_FUNDS_EFFECTIVE = "FED_FUNDS_EFFECTIVE"
        const val INFECTIOUS_DISEASE_EMV = "INFECTIOUS_DISEASE_EMV"

        /* SPEC v2.1 §27 — measured, from sources other than FRED. */
        const val REAL_CURVE_5Y = "REAL_CURVE_5Y"
        const val REAL_CURVE_7Y = "REAL_CURVE_7Y"
        const val REAL_CURVE_10Y = "REAL_CURVE_10Y"
        const val REAL_CURVE_20Y = "REAL_CURVE_20Y"
        const val REAL_CURVE_30Y = "REAL_CURVE_30Y"
        const val COT_MANAGED_MONEY_NET = "COT_MANAGED_MONEY_NET"
        const val COT_COMMERCIAL_NET = "COT_COMMERCIAL_NET"
        const val SOMA_TOTAL = "SOMA_TOTAL"
        const val SOFR_EFFR_SPREAD = "SOFR_EFFR_SPREAD"
        const val SOFR_P99_SPREAD = "SOFR_P99_SPREAD"
        const val OECD_CLI_US = "OECD_CLI_US"
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
