package io.goldintelligence.ingestion

import io.goldintelligence.engine.CalibrationDefaults
import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.FeatureKeys
import io.goldintelligence.engine.FeatureSet
import io.goldintelligence.engine.LogStage
import io.goldintelligence.engine.Tier
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Builds the canonical feature contract from a [MarketUniverse].
 *
 * Every feature carries its own provenance and quality. A feature whose
 * inputs are missing is simply absent — it is never emitted as zero, and no
 * substitute series is silently swapped in. Where the free stack can only
 * supply an approximation, the feature is marked `isProxy` and its quality is
 * capped at QUALITY_PROXY_CEILING, which propagates into the factor weight
 * and into Confidence.
 */
class SpecFeatureEngineer(private val log: DiagnosticLog = DiagnosticLog.shared) {

    fun build(u: MarketUniverse): FeatureBundle {
        val out = LinkedHashMap<String, FeatureValue>()
        val now = u.asOf

        fun put(
            key: String,
            value: Double?,
            unit: String,
            sourceId: String,
            isProxy: Boolean = false,
            note: String? = null,
            tierOverride: Tier? = null,
            qualityOverride: Double? = null
        ) {
            if (value == null || value.isNaN() || value.isInfinite()) {
                log.warn(
                    LogStage.FEATURE, "SpecFeatureEngineer",
                    if (value == null) "INPUT_MISSING" else "NOT_FINITE",
                    if (value == null) "required input absent from the universe (source $sourceId)"
                    else "computed value was $value",
                    key = key,
                    detail = "source=$sourceId unit=$unit"
                )
                return
            }
            val p = u.point(sourceId)
            val tier = tierOverride ?: p?.tier ?: Tier.C
            val asOf = p?.observationTimestamp ?: now
            val baseQuality = qualityOverride ?: p?.quality ?: 0.7
            val quality = if (isProxy) minOf(baseQuality, CalibrationDefaults.QUALITY_PROXY_CEILING) else baseQuality
            out[key] = FeatureValue(
                key = key,
                value = value,
                unit = unit,
                source = p?.source ?: sourceId,
                tier = if (isProxy) Tier.PROXY else tier,
                isProxy = isProxy,
                quality = quality.coerceIn(0.0, 1.0),
                asOf = asOf,
                note = note
            )
            log.debug(
                LogStage.FEATURE, "SpecFeatureEngineer",
                if (isProxy) "OK_PROXY" else "OK",
                String.format(java.util.Locale.US, "%.6g", value) + " " + unit,
                key = key
            )
        }

        /* ---- F01 Real yield -------------------------------------- */
        val real10 = u.seriesOf(MarketUniverse.REAL10Y)
        put(FeatureKeys.REAL_YIELD, real10?.last?.close ?: u.scalar(MarketUniverse.REAL10Y),
            "%", MarketUniverse.REAL10Y)
        put(FeatureKeys.REAL_YIELD_ZSCORE, real10?.zScore(252), "z", MarketUniverse.REAL10Y)
        put(FeatureKeys.REAL_YIELD_TREND, real10?.slope(20), "slope/level", MarketUniverse.REAL10Y)

        /* ---- F02 Dollar ------------------------------------------- */
        // SPEC v2.1: the real index is now quoted, so the currency-basket
        // replication is only the fallback. The history still comes from the
        // ECB series — the index level is anchored to the live quote.
        val dxyReal = u.scalar(MarketUniverse.DXY_INDEX)
        val dxy = u.seriesOf(MarketUniverse.DXY_SYNTHETIC)
        val dxyProxy = dxyReal == null && (u.point(MarketUniverse.DXY_SYNTHETIC)?.isProxy ?: true)
        val dxySource = if (dxyReal != null) MarketUniverse.DXY_INDEX else MarketUniverse.DXY_SYNTHETIC
        put(FeatureKeys.DXY, dxyReal ?: dxy?.last?.close ?: u.scalar(MarketUniverse.DXY_SYNTHETIC),
            "index", dxySource, isProxy = dxyProxy,
            note = if (dxyReal != null) "ICE U.S. Dollar Index, quoted."
            else "Trade-weighted replication from ECB reference rates.")
        put(FeatureKeys.DXY_ZSCORE, dxy?.zScore(252), "z", MarketUniverse.DXY_SYNTHETIC, isProxy = dxyProxy)
        put(FeatureKeys.DXY_TREND, dxy?.slope(20), "slope/level", MarketUniverse.DXY_SYNTHETIC, isProxy = dxyProxy)
        put(FeatureKeys.DXY_MOVE_SIGMA, dxy?.moveSigma(252), "sigma", MarketUniverse.DXY_SYNTHETIC, isProxy = dxyProxy)

        /* ---- F03 Fed expectations ---------------------------------- */
        val us2 = u.seriesOf(MarketUniverse.US02Y)
        val us3m = u.scalar(MarketUniverse.US03M)
        val effr = u.scalar(MarketUniverse.EFFR)
        // Δ2Y over 20 sessions is the market's revision of the policy path.
        val fedChange = us2?.let { s ->
            val a = s.closeAt(0)
            val b = s.closeAt(20)
            if (a != null && b != null) a - b else null
        }
        put(FeatureKeys.FED_EXPECTED_RATE_CHANGE, fedChange, "pp", MarketUniverse.US02Y,
            isProxy = true, note = "Δ2Y(20d) stands in for OIS-implied policy path; CME FedWatch is not accessible.")
        // 3M bill relative to the effective funds rate: positive ⇒ market prices hikes.
        put(FeatureKeys.FED_SURPRISE, if (us3m != null && effr != null) us3m - effr else null,
            "pp", MarketUniverse.US03M, isProxy = true)

        /* ---- F04 Treasury curve ------------------------------------ */
        val us10 = u.seriesOf(MarketUniverse.US10Y)
        put(FeatureKeys.US10Y_YIELD, us10?.last?.close ?: u.scalar(MarketUniverse.US10Y), "%", MarketUniverse.US10Y)
        put(FeatureKeys.US10Y_MOVE_SIGMA, us10?.moveSigma(252), "sigma", MarketUniverse.US10Y)
        val y10 = us10?.last?.close ?: u.scalar(MarketUniverse.US10Y)
        val y2 = us2?.last?.close ?: u.scalar(MarketUniverse.US02Y)
        put(FeatureKeys.YIELD_CURVE_10Y_2Y, if (y10 != null && y2 != null) y10 - y2 else null,
            "pp", MarketUniverse.US10Y)

        /* ---- F05 Inflation ----------------------------------------- */
        val be = u.seriesOf(MarketUniverse.BREAKEVEN10Y)
        val beChange = be?.let { s ->
            val a = s.closeAt(0)
            val b = s.closeAt(20)
            if (a != null && b != null) a - b else null
        }
        put(FeatureKeys.BREAKEVEN_CHANGE, beChange, "pp", MarketUniverse.BREAKEVEN10Y,
            note = "10Y nominal minus 10Y TIPS, both from the same Treasury publication.")
        // Surprise proxy: 20d breakeven change scaled by its own annual sigma.
        val beZ = be?.let { s ->
            val diffs = s.closes.takeLast(253).let { xs ->
                if (xs.size < 25) emptyList() else (1 until xs.size).map { xs[it] - xs[it - 1] }
            }
            val sd = Stats.stdev(diffs)
            if (beChange != null && sd != null && sd > 0.0) beChange / (sd * 4.47) else null
        }
        val measuredSurprise = u.scalar(MarketUniverse.CONSENSUS_SURPRISE)
        put(FeatureKeys.INFLATION_SURPRISE, measuredSurprise ?: beZ,
            if (measuredSurprise != null) "normalized (actual vs consensus)" else "z",
            if (measuredSurprise != null) MarketUniverse.CONSENSUS_SURPRISE else MarketUniverse.BREAKEVEN10Y,
            isProxy = measuredSurprise == null,
            note = "Market-implied inflation revision; consensus-vs-actual CPI surprise needs a paid consensus feed.")

        /* ---- F06 Economic surprise --------------------------------- */
        val spy = u.seriesOf(MarketUniverse.EQUITY_ETF)
        val tlt = u.seriesOf(MarketUniverse.LONG_BOND_ETF)
        val growthProxy = if (spy != null && tlt != null) {
            val a = spy.returnOver(20)
            val b = tlt.returnOver(20)
            if (a != null && b != null) (a - b) * 100.0 else null
        } else null
        put(FeatureKeys.ECONOMIC_SURPRISE_INDEX, growthProxy, "pp (20d equity-bond spread)",
            MarketUniverse.EQUITY_ETF, isProxy = true,
            note = "PROXY_GROWTH. Citi ESI and Bloomberg consensus are not free; regional Fed surveys are not machine-readable.")

        /* ---- F07 Geopolitical risk --------------------------------- */
        val gvz = u.seriesOf(MarketUniverse.GVZ)
        val vix = u.seriesOf(MarketUniverse.VIX)
        val skew = u.seriesOf(MarketUniverse.SKEW)
        // Gold-specific fear relative to broad equity fear isolates the safe-haven bid.
        val geoProxy = if (gvz != null && vix != null) {
            val ratios = gvz.closes.takeLast(252).zip(vix.closes.takeLast(252)) { g, v -> if (v > 0) g / v else Double.NaN }
                .filter { !it.isNaN() }
            val z = if (ratios.size >= 30) Stats.zScore(ratios.last(), ratios) else null
            z?.let { 50.0 + 16.0 * it }
        } else null
        put(FeatureKeys.GEOPOLITICAL_RISK_SCORE, geoProxy?.coerceIn(0.0, 100.0), "0-100",
            MarketUniverse.GVZ, isProxy = true,
            note = "GVZ/VIX premium z-score. GDELT is rate-limited from server IP ranges and is behind a circuit breaker.")
        val geoDelta = if (gvz != null && vix != null) {
            val g = gvz.returnOver(5)
            val v = vix.returnOver(5)
            if (g != null && v != null) (g - v) * 100.0 else null
        } else null
        put(FeatureKeys.GEOPOLITICAL_RISK_DELTA, geoDelta, "pp (5d)", MarketUniverse.GVZ, isProxy = true)

        /* ---- F08 Financial stress ---------------------------------- */
        put(FeatureKeys.VIX, vix?.last?.close ?: u.scalar(MarketUniverse.VIX), "index", MarketUniverse.VIX)
        put(FeatureKeys.VIX_ZSCORE, vix?.zScore(252), "z", MarketUniverse.VIX)
        val sofr = u.scalar(MarketUniverse.SOFR)
        val fundingSpreadBp = u.scalar(MarketUniverse.FUNDING_SPREAD)
            ?: if (sofr != null && effr != null) (sofr - effr) * 100.0 else null
        // Standardized against its own history: two basis points of SOFR-EFFR is
        // routine, so the raw spread cannot be compared to a sigma threshold.
        val fundingSeries = u.seriesOf(MarketUniverse.FUNDING_SPREAD)
        val fundingZ = fundingSeries?.zScore(250)
        val hyg = u.seriesOf(MarketUniverse.HY_ETF)
        val lqd = u.seriesOf(MarketUniverse.IG_ETF)
        val creditRatioSeries = if (hyg != null && lqd != null) {
            hyg.closes.takeLast(252).zip(lqd.closes.takeLast(252)) { h, l -> if (l > 0) h / l else Double.NaN }
                .filter { !it.isNaN() }
        } else emptyList()
        val creditZ = if (creditRatioSeries.size >= 30) Stats.zScore(creditRatioSeries.last(), creditRatioSeries) else null
        val stress = listOfNotNull(
            vix?.zScore(252),
            creditZ?.let { -it },
            fundingZ
        )
        put(FeatureKeys.FINANCIAL_STRESS_SCORE, if (stress.isEmpty()) null else stress.average(),
            "z (composite)", MarketUniverse.VIX, isProxy = true,
            note = "Composite of VIX z, inverted HY/IG ratio z and SOFR-EFFR spread; OFR FSI is published with a lag.")

        /* ---- F09 Gold ETF flow ------------------------------------- */
        val gld = u.seriesOf(MarketUniverse.GOLD_PROXY_ETF)
        val flowSeries = gld?.bars?.takeLast(252)?.mapNotNull { b ->
            b.volume?.let { v -> v * b.close * (if (b.close >= b.open) 1.0 else -1.0) }
        } ?: emptyList()
        val flowZ = if (flowSeries.size >= 30) Stats.zScore(flowSeries.last(), flowSeries) else null
        put(FeatureKeys.ETF_FLOW_ZSCORE, flowZ, "z", MarketUniverse.GOLD_PROXY_ETF, isProxy = true,
            note = "Signed dollar-volume proxy. True creations/redemptions require the sponsor's daily holdings file.")
        put(FeatureKeys.ETF_HOLDINGS_CHANGE, gld?.returnOver(5)?.times(100.0), "% (5d price)",
            MarketUniverse.GOLD_PROXY_ETF, isProxy = true)

        /* ---- F11 COT positioning ----------------------------------- */
        val cot = u.seriesOf(MarketUniverse.COT_NET_NONCOMM)
        val cotZ = cot?.zScore(156)
        put(FeatureKeys.COT_NET_POSITION_ZSCORE, cotZ, "z (3y)", MarketUniverse.COT_NET_NONCOMM)
        if (cotZ != null) {
            put(FeatureKeys.COT_EXTREME_LONG, if (cotZ >= CalibrationDefaults.COT_EXTREME_ZSCORE) 1.0 else 0.0,
                "flag", MarketUniverse.COT_NET_NONCOMM)
            put(FeatureKeys.COT_EXTREME_SHORT, if (cotZ <= -CalibrationDefaults.COT_EXTREME_ZSCORE) 1.0 else 0.0,
                "flag", MarketUniverse.COT_NET_NONCOMM)
        }

        /* ---- F13 Momentum ------------------------------------------ */
        val goldPx = u.seriesOf(MarketUniverse.GOLD_FUT_FRONT) ?: gld
        val goldSourceId = if (u.seriesOf(MarketUniverse.GOLD_FUT_FRONT) != null)
            MarketUniverse.GOLD_FUT_FRONT else MarketUniverse.GOLD_PROXY_ETF
        val goldIsProxy = goldSourceId == MarketUniverse.GOLD_PROXY_ETF
        put(FeatureKeys.GOLD_RETURN, goldPx?.returnOver(1)?.times(100.0), "%", goldSourceId, isProxy = goldIsProxy)
        val mom = listOfNotNull(
            goldPx?.returnOver(5)?.times(100.0),
            goldPx?.returnOver(20)?.times(50.0),
            goldPx?.slope(20)?.times(1000.0)
        )
        put(FeatureKeys.GOLD_MOMENTUM, if (mom.isEmpty()) null else mom.average(), "composite",
            goldSourceId, isProxy = goldIsProxy)
        put(FeatureKeys.GOLD_MOVE_SIGMA, goldPx?.moveSigma(252), "sigma", goldSourceId, isProxy = goldIsProxy)

        /* ---- F14 Microstructure ------------------------------------
         * SPEC v2.1: aggregated depth of book for allocated gold, captured
         * from the Kraken PAXG/USD and OKX XAUT/USDT limit books (one token
         * is one fine troy ounce). This is measured depth, not a proxy, so
         * the proxy ceiling no longer applies. Top-of-book ETF sizes and the
         * intrabar close position remain as ordered fallbacks.
         */
        val lastBar = goldPx?.last
        val bookImbalance = u.scalar(MarketUniverse.BOOK_IMBALANCE)
        val bidSize = u.scalar(MarketUniverse.GLD_BID_SIZE)
        val askSize = u.scalar(MarketUniverse.GLD_ASK_SIZE)
        val topOfBook = if (bidSize != null && askSize != null && (bidSize + askSize) > 0.0) {
            (bidSize - askSize) / (bidSize + askSize)
        } else null
        val intrabar = lastBar?.let { b ->
            val range = b.high - b.low
            if (range > 0.0) ((b.close - b.low) / range - 0.5) * 2.0 else null
        }
        val levels = u.scalar(MarketUniverse.BOOK_DEPTH_LEVELS)?.toInt()
        put(
            FeatureKeys.MICROSTRUCTURE_IMBALANCE,
            bookImbalance ?: topOfBook ?: intrabar,
            when {
                bookImbalance != null -> "-1..1 (L2 depth)"
                topOfBook != null -> "-1..1 (top-of-book)"
                else -> "-1..1 (bar position)"
            },
            when {
                bookImbalance != null -> MarketUniverse.BOOK_IMBALANCE
                topOfBook != null -> MarketUniverse.GLD_BID_SIZE
                else -> goldSourceId
            },
            isProxy = bookImbalance == null,
            note = when {
                bookImbalance != null ->
                    "Full limit-order-book imbalance over ${levels ?: 0} captured levels " +
                        "(Kraken PAXG/USD and OKX XAUT/USDT, allocated gold)."
                topOfBook != null -> "Displayed ETF bid/ask size imbalance; venue books were unavailable."
                else -> "Intrabar close position; no book was available this cycle."
            }
        )
        put(FeatureKeys.BOOK_IMBALANCE, bookImbalance, "-1..1", MarketUniverse.BOOK_IMBALANCE,
            note = "Signed depth imbalance across every captured price level.")
        val bidVol = u.scalar(MarketUniverse.BOOK_BID_VOLUME)
        val askVol = u.scalar(MarketUniverse.BOOK_ASK_VOLUME)
        put(FeatureKeys.BOOK_DEPTH_TOTAL,
            if (bidVol != null && askVol != null) bidVol + askVol else null,
            "oz resting", MarketUniverse.BOOK_BID_VOLUME,
            note = "Total resting size on both sides of the captured books.")
        put(FeatureKeys.BOOK_SPREAD_BP, u.scalar(MarketUniverse.BOOK_SPREAD_BP), "bp",
            MarketUniverse.BOOK_SPREAD_BP, note = "Tightest top-of-book spread across the gold venues.")
        put(FeatureKeys.OTC_SPREAD_BP, u.scalar(MarketUniverse.OTC_SPREAD_BP), "bp",
            MarketUniverse.OTC_SPREAD_BP,
            note = "Swissquote OTC best bid/offer, tightest size tier.")

        /* ---- F15 Options / volatility ------------------------------ */
        val iv30 = u.scalar(MarketUniverse.GLD_IV30)
        val gvzLast = iv30 ?: gvz?.last?.close ?: u.scalar(MarketUniverse.GVZ)
        val realizedAnnual = goldPx?.realizedVol(20)?.times(ln(252.0).let { exp(0.5 * it) })
        put(FeatureKeys.GOLD_REALIZED_VOL_ZSCORE, goldPx?.let { s ->
            val window = (20..40).mapNotNull { k ->
                if (s.bars.size > k + 20) TimeSeries(s.symbol, s.bars.dropLast(k)).realizedVol(20) else null
            }
            val current = s.realizedVol(20)
            if (current != null && window.size >= 10) Stats.zScore(current, window + current) else null
        }, "z", goldSourceId, isProxy = goldIsProxy)
        // IV minus RV: positive ⇒ options market pricing more risk than recent tape.
        put(FeatureKeys.GOLD_IV_SKEW,
            if (gvzLast != null && realizedAnnual != null) gvzLast - realizedAnnual * 100.0 else null,
            "vol pts (IV-RV)",
            if (iv30 != null) MarketUniverse.GLD_IV30 else MarketUniverse.GVZ, isProxy = true,
            note = (if (iv30 != null) "GLD 30-day implied" else "GVZ") +
                " minus 20d realized. A true 25-delta risk reversal needs the full GLD option chain.")

        /* ---- F16 Cross-asset --------------------------------------- */
        val goldSpot = u.scalar(MarketUniverse.GOLD_SPOT)
        val silverSpot = u.scalar(MarketUniverse.SILVER_SPOT)
        put(FeatureKeys.GOLD_SILVER_RATIO,
            if (goldSpot != null && silverSpot != null && silverSpot > 0.0) goldSpot / silverSpot else null,
            "ratio", MarketUniverse.GOLD_SPOT)
        val slv = u.seriesOf(MarketUniverse.SILVER_PROXY_ETF)
        val gsRatioSeries = if (gld != null && slv != null) {
            gld.closes.takeLast(252).zip(slv.closes.takeLast(252)) { g, s -> if (s > 0) g / s else Double.NaN }
                .filter { !it.isNaN() }
        } else emptyList()
        put(FeatureKeys.GOLD_SILVER_RATIO_ZSCORE,
            if (gsRatioSeries.size >= 30) Stats.zScore(gsRatioSeries.last(), gsRatioSeries) else null,
            "z", MarketUniverse.GOLD_PROXY_ETF, isProxy = true,
            note = "GLD/SLV ratio z-score; ETF pair stands in for the spot ratio history.")

        /* ---- §1.5 COMEX forward curve ------------------------------- */
        val front = u.seriesOf(MarketUniverse.GOLD_FUT_FRONT)?.last?.close ?: u.scalar(MarketUniverse.GOLD_FUT_FRONT)
        val deferred = u.seriesOf(MarketUniverse.GOLD_FUT_DEFERRED)?.last?.close ?: u.scalar(MarketUniverse.GOLD_FUT_DEFERRED)
        put(FeatureKeys.FUTURES_BASIS,
            if (front != null && goldSpot != null && goldSpot > 0.0) (front - goldSpot) / goldSpot * 100.0 else null,
            "% (front vs spot)", MarketUniverse.GOLD_FUT_FRONT)
        put(FeatureKeys.TERM_STRUCTURE_SLOPE,
            if (front != null && deferred != null && front > 0.0) (deferred - front) / front * 100.0 else null,
            "% (deferred vs front)", MarketUniverse.GOLD_FUT_DEFERRED)

        /* ---- F17 Liquidity ----------------------------------------- */
        put(FeatureKeys.DOLLAR_FUNDING_STRESS, fundingZ, "z (SOFR-EFFR)", MarketUniverse.FUNDING_SPREAD,
            note = "Official NY Fed reference rates, standardized over 250 sessions. Raw spread: " +
                (fundingSpreadBp?.let { String.format(java.util.Locale.US, "%.2f bp", it) } ?: "n/a"))

        /* ---- F18 Credit -------------------------------------------- */
        put(FeatureKeys.CREDIT_SPREAD_HY, creditZ?.let { -it }, "z (inverted HY/IG)", MarketUniverse.HY_ETF,
            isProxy = true, note = "ETF ratio proxy; ICE BofA OAS requires a FRED API key.")

        /* ---- F19 / F20 China & India -------------------------------- */
        val cny = u.seriesOf(MarketUniverse.FX_CNY)
        val inr = u.seriesOf(MarketUniverse.FX_INR)
        // SPEC v2.1: the physical premium is now measured directly.
        // A positive premium means the regional market is bidding gold above
        // the international price — the demand signal the factor wants.
        val chinaPremium = u.scalar(MarketUniverse.CHINA_PREMIUM_PCT)
        val indiaPremium = u.scalar(MarketUniverse.INDIA_PREMIUM_PCT)
        put(FeatureKeys.CHINA_PREMIUM, chinaPremium, "% vs international",
            MarketUniverse.CHINA_PREMIUM_PCT,
            note = "Shanghai Gold Exchange Au(T+D) converted at the ECB CNY reference rate.")
        put(FeatureKeys.INDIA_PREMIUM, indiaPremium, "% vs international",
            MarketUniverse.INDIA_PREMIUM_PCT,
            note = "MCX front-month gold converted at the ECB INR reference rate; " +
                "includes Indian import duty and GST, so the level is structurally positive.")
        put(FeatureKeys.CHINA_DEMAND_INDEX,
            chinaPremium ?: cny?.returnOver(20)?.times(-100.0),
            if (chinaPremium != null) "% (Shanghai premium)" else "% (CNY strength, 20d)",
            if (chinaPremium != null) MarketUniverse.CHINA_PREMIUM_PCT else MarketUniverse.FX_CNY,
            isProxy = chinaPremium == null,
            note = if (chinaPremium != null) "Measured Shanghai premium over the international price."
            else "Shanghai quote unavailable this cycle; CNY strength is the fallback.")
        put(FeatureKeys.INDIA_DEMAND_INDEX,
            indiaPremium?.let { it - INDIA_STRUCTURAL_WEDGE_PCT } ?: inr?.returnOver(20)?.times(-100.0),
            if (indiaPremium != null) "% (MCX premium, duty-adjusted)" else "% (INR strength, 20d)",
            if (indiaPremium != null) MarketUniverse.INDIA_PREMIUM_PCT else MarketUniverse.FX_INR,
            isProxy = indiaPremium == null,
            note = if (indiaPremium != null)
                "MCX premium less the $INDIA_STRUCTURAL_WEDGE_PCT% statutory duty and tax wedge, " +
                    "so only the discretionary part drives the factor."
            else "MCX quote unavailable this cycle; INR strength is the fallback.")

        /* ---- F21 Oil ------------------------------------------------ */
        val uso = u.seriesOf(MarketUniverse.OIL_ETF)
        put(FeatureKeys.OIL_MOMENTUM, uso?.returnOver(20)?.times(100.0), "% (20d)", MarketUniverse.OIL_ETF,
            isProxy = true, note = "USO tracks WTI with roll drag; EIA spot requires an API key.")

        /* ---- F22 Global CB divergence -------------------------------- */
        val dxyTrend = dxy?.slope(60)
        val us2Trend = us2?.slope(60)
        put(FeatureKeys.GLOBAL_CB_POLICY_DIVERGENCE,
            if (dxyTrend != null && us2Trend != null) (us2Trend - dxyTrend) * 100.0 else null,
            "divergence index", MarketUniverse.US02Y, isProxy = true,
            note = "US front-end trend relative to the trade-weighted dollar trend.")

        /* ---- F12 Physical demand (SPEC v2.1) -------------------------
         * Previously NO_FREE_SOURCE. The two largest physical markets now
         * quote: the premium a regional market pays over the international
         * price is the cleanest observable read on physical demand. India's
         * statutory wedge is removed first so the two are comparable.
         */
        val physicalParts = listOfNotNull(
            chinaPremium,
            indiaPremium?.let { it - INDIA_STRUCTURAL_WEDGE_PCT }
        )
        put(FeatureKeys.PHYSICAL_DEMAND_INDEX,
            if (physicalParts.isEmpty()) null else physicalParts.average(),
            "% (regional premium)",
            if (chinaPremium != null) MarketUniverse.CHINA_PREMIUM_PCT else MarketUniverse.INDIA_PREMIUM_PCT,
            note = "Mean premium paid in Shanghai and Mumbai over the international price, " +
                "net of Indian duty. Positive means the physical market is bidding above the paper price.")

        /* ---- SPEC v2.1 · daily open interest ------------------------- */
        val oiDaily = u.scalar(MarketUniverse.GOLD_OI_DAILY)
        val cotOi = u.seriesOf(MarketUniverse.COT_NET_NONCOMM)
        put(FeatureKeys.OPEN_INTEREST, oiDaily, "contracts", MarketUniverse.GOLD_OI_DAILY,
            note = "COMEX front-month open interest, published daily.")
        if (oiDaily != null && cotOi != null) {
            val weekly = cotOi.bars.mapNotNull { it.volume }.takeLast(156)
            if (weekly.size >= 30) {
                put(FeatureKeys.OPEN_INTEREST_ZSCORE, Stats.zScore(oiDaily, weekly + oiDaily), "z",
                    MarketUniverse.GOLD_OI_DAILY,
                    note = "Daily print standardized against three years of CFTC weekly open interest.")
                put(FeatureKeys.OPEN_INTEREST_CHANGE,
                    weekly.lastOrNull()?.let { if (it > 0) (oiDaily / it - 1.0) * 100.0 else null },
                    "% vs last CFTC report", MarketUniverse.GOLD_OI_DAILY)
            }
        }

        /* ---- SPEC v2.1 · benchmark and source agreement -------------- */
        val benchmark = u.seriesOf(MarketUniverse.LBMA_BENCHMARK)?.last?.close
        put(FeatureKeys.LBMA_BENCHMARK, benchmark, "USD/oz", MarketUniverse.LBMA_BENCHMARK,
            note = "LBMA-based benchmark published by the World Gold Council.")
        put(FeatureKeys.LBMA_DEVIATION,
            if (benchmark != null && goldSpot != null && benchmark > 0.0)
                (goldSpot / benchmark - 1.0) * 100.0 else null,
            "% (spot vs benchmark)", MarketUniverse.LBMA_BENCHMARK,
            note = "Spot against the most recent published benchmark fixing.")
        put(FeatureKeys.SPOT_SOURCE_DISPERSION, u.scalar(MarketUniverse.SPOT_CONSENSUS_BP), "bp",
            MarketUniverse.SPOT_CONSENSUS_BP,
            note = "Range across the independent spot feeds. A wide range means the feeds disagree.")

        /* ---- SPEC v2.1 · calendar and consensus ---------------------- */
        put(FeatureKeys.CALENDAR_HIGH_IMPACT_24H, u.scalar(MarketUniverse.CALENDAR_HIGH_IMPACT_24H),
            "events", MarketUniverse.CALENDAR_HIGH_IMPACT_24H,
            note = "High-impact scheduled releases inside the next 24 hours.")
        put(FeatureKeys.CALENDAR_HOURS_TO_EVENT, u.scalar(MarketUniverse.CALENDAR_NEXT_EVENT_HOURS),
            "h", MarketUniverse.CALENDAR_NEXT_EVENT_HOURS,
            note = "Hours until the next high-impact release.")
        put(FeatureKeys.CPI_YOY, u.scalar(MarketUniverse.CPI_YOY), "% y/y", MarketUniverse.CPI_YOY,
            note = "Headline CPI for all urban consumers, published by the BLS.")
        put(FeatureKeys.UNEMPLOYMENT_RATE, u.scalar(MarketUniverse.UNEMPLOYMENT_RATE), "%",
            MarketUniverse.UNEMPLOYMENT_RATE, note = "Civilian unemployment rate, published by the BLS.")
        val surprise = u.scalar(MarketUniverse.CONSENSUS_SURPRISE)
        put(FeatureKeys.CONSENSUS_SURPRISE, surprise, "normalized", MarketUniverse.CONSENSUS_SURPRISE,
            note = "Published actual against the surveyed consensus for the same release.")

        /* ---- Macro state --------------------------------------------- */
        // Estrella-Mishkin style term-spread mapping; declared as derived, not observed.
        val spread10y3m = if (y10 != null && us3m != null) y10 - us3m else null
        put(FeatureKeys.RECESSION_PROBABILITY,
            spread10y3m?.let { s -> (1.0 / (1.0 + exp(2.0 + 1.3 * s))).coerceIn(0.0, 1.0) },
            "probability", MarketUniverse.US10Y, isProxy = true,
            note = "Term-spread logistic map (10Y-3M). Derived, not an observed series.")

        return FeatureBundle(out)
    }

    /** Bridge to the engine's untyped feature contract. */
    fun toFeatureSet(bundle: FeatureBundle): FeatureSet = FeatureSet(bundle.numeric())

    companion object {
        /**
         * Indian import duty plus GST, the statutory part of the MCX premium.
         * Subtracting it leaves the discretionary demand component, which is
         * what the factor is supposed to read.
         */
        const val INDIA_STRUCTURAL_WEDGE_PCT = 9.0

        fun staleness(asOf: Instant, now: Instant): Duration = Duration.between(asOf, now)
        fun absMax(vararg xs: Double?): Double? = xs.filterNotNull().maxByOrNull { abs(it) }
    }
}
