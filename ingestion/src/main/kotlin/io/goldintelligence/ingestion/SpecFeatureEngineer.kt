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
        // SPEC v2.1 §25.1 — the Treasury CSV carries two years; the published
        // daily series carries twenty-three. A z-score is only as honest as
        // the population behind it, so the deeper history wins when present.
        val real10Deep = u.seriesOf(MarketUniverse.REAL10Y_DEEP)
        put(FeatureKeys.REAL_YIELD_ZSCORE,
            (real10Deep ?: real10)?.zScore(252), "z",
            if (real10Deep != null) MarketUniverse.REAL10Y_DEEP else MarketUniverse.REAL10Y)
        put(FeatureKeys.REAL_YIELD_TREND, real10?.slope(20), "slope/level", MarketUniverse.REAL10Y)

        // SPEC v2.1 §23 — intraday real yield: the quoted nominal ten-year
        // less the last published breakeven. The Treasury's own real series is
        // an end-of-day print, so between publications this is the only read
        // on where the real yield has moved.
        val nominalNow = u.scalar(MarketUniverse.US10Y_INTRADAY)
        val breakevenNow = u.seriesOf(MarketUniverse.BREAKEVEN10Y)?.last?.close
            ?: u.scalar(MarketUniverse.BREAKEVEN10Y)
        put(FeatureKeys.REAL_YIELD_INTRADAY_PROXY,
            if (nominalNow != null && breakevenNow != null) nominalNow - breakevenNow else null,
            "% (quoted 10Y − last breakeven)", MarketUniverse.US10Y_INTRADAY, isProxy = true,
            note = "Quoted nominal ten-year minus the most recent published breakeven; " +
                "the Treasury real curve is end-of-day only.")

        // SPEC v2.1 §25.1 — the market's long-run inflation expectation,
        // published daily: five-year inflation starting five years from now.
        put(FeatureKeys.INFLATION_EXPECTATION_5Y5Y,
            u.seriesOf(MarketUniverse.INFLATION_EXPECTATION_5Y5Y)?.last?.close, "%",
            MarketUniverse.INFLATION_EXPECTATION_5Y5Y,
            note = "Five-year, five-year forward inflation expectation rate.")

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
        // SPEC v2.1 §23 — with the published policy rate in hand the expected
        // path can be read directly: the 3-month bill prices where the market
        // thinks the rate will be, so bill minus policy rate is the expected
        // move itself rather than a trend standing in for one.
        val policyNow = u.scalar(MarketUniverse.POLICY_RATE_US)
        // SPEC v2.1 §25.3 — a 30-day fed funds future settles on the average
        // effective rate of its delivery month, so the strip IS the priced
        // path: no probability tree, no model. Twelve months out against the
        // published policy rate is the expected move.
        val impliedFar = u.scalar(MarketUniverse.FED_FUNDS_IMPLIED_12M)
        val impliedFront = u.scalar(MarketUniverse.FED_FUNDS_IMPLIED_FRONT)
        put(FeatureKeys.FED_IMPLIED_PATH_12M, impliedFar, "%",
            MarketUniverse.FED_FUNDS_IMPLIED_12M,
            note = "Rate implied by the 30-day fed funds future about twelve months out.")
        val futuresPath = when {
            impliedFar != null && policyNow != null -> impliedFar - policyNow
            impliedFar != null && impliedFront != null -> impliedFar - impliedFront
            else -> null
        }
        val billPath = if (us3m != null && policyNow != null) us3m - policyNow else null
        put(FeatureKeys.FED_EXPECTED_RATE_CHANGE, futuresPath ?: billPath ?: fedChange, "pp",
            when {
                futuresPath != null -> MarketUniverse.FED_FUNDS_IMPLIED_12M
                billPath != null -> MarketUniverse.POLICY_RATE_US
                else -> MarketUniverse.US02Y
            },
            isProxy = futuresPath == null && billPath == null,
            note = when {
                futuresPath != null ->
                    "Fed funds futures twelve months out against the published policy rate: " +
                        "the move the market is paying for."
                billPath != null ->
                    "Three-month bill against the published policy rate; the futures strip " +
                        "did not quote this cycle."
                else -> "Δ2Y(20d) stands in for the policy path; neither the strip nor the " +
                    "published rate was reachable this cycle."
            })
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
        // SPEC v2.1 §23 — released inflation prints against their published
        // consensus, which is a measurement; the official-statistics read and
        // the breakeven move remain, in that order, as fallbacks.
        val releaseSurprise = u.scalar(MarketUniverse.INFLATION_SURPRISE_MEASURED)?.div(50.0)
        val measuredSurprise = releaseSurprise ?: u.scalar(MarketUniverse.CONSENSUS_SURPRISE)
        val surpriseSource = when {
            releaseSurprise != null -> MarketUniverse.INFLATION_SURPRISE_MEASURED
            measuredSurprise != null -> MarketUniverse.CONSENSUS_SURPRISE
            else -> MarketUniverse.BREAKEVEN10Y
        }
        put(FeatureKeys.INFLATION_SURPRISE, measuredSurprise ?: beZ,
            if (measuredSurprise != null) "normalized (actual vs consensus)" else "z",
            surpriseSource,
            isProxy = measuredSurprise == null,
            note = when {
                releaseSurprise != null ->
                    "Mean normalised miss across released inflation prints versus their published consensus."
                measuredSurprise != null ->
                    "Official statistics read against the scheduled consensus."
                else -> "Market-implied inflation revision; no consensus print was reachable this cycle."
            })

        /* ---- F06 Economic surprise --------------------------------- */
        val spy = u.seriesOf(MarketUniverse.EQUITY_ETF)
        val tlt = u.seriesOf(MarketUniverse.LONG_BOND_ETF)
        val growthProxy = if (spy != null && tlt != null) {
            val a = spy.returnOver(20)
            val b = tlt.returnOver(20)
            if (a != null && b != null) (a - b) * 100.0 else null
        } else null
        // SPEC v2.1 §23 — a measured surprise index: the mean normalised gap
        // between what was printed and what was forecast across every release
        // of the last thirty days, in the spirit of the commercial indices but
        // computed from the public calendar's own actual-versus-consensus rows.
        val measuredEsi = u.scalar(MarketUniverse.SURPRISE_INDEX_MEASURED)
        put(FeatureKeys.ECONOMIC_SURPRISE_INDEX, measuredEsi ?: growthProxy,
            if (measuredEsi != null) "index (actual vs consensus, 30d)" else "pp (20d equity-bond spread)",
            if (measuredEsi != null) MarketUniverse.SURPRISE_INDEX_MEASURED else MarketUniverse.EQUITY_ETF,
            isProxy = measuredEsi == null,
            note = if (measuredEsi != null)
                "Mean normalised actual-minus-consensus across released US, EU, UK, JP and CN prints."
            else "PROXY_GROWTH. The calendar's released actuals were unreachable this cycle.")

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
        // SPEC v2.1 §25.5 — the risk read is now measured from text, not
        // inferred from option prices: the Baker-Bloom-Davis daily economic
        // policy uncertainty index counts the newspaper articles that discuss
        // policy uncertainty, and the equity-market uncertainty index does the
        // same for market-related uncertainty. Both are published daily with
        // no key. The GVZ/VIX premium stays as the fallback.
        val epuSeries = u.seriesOf(MarketUniverse.POLICY_UNCERTAINTY_DAILY)
        val newsUncSeries = u.seriesOf(MarketUniverse.NEWS_EQUITY_UNCERTAINTY)
        put(FeatureKeys.POLICY_UNCERTAINTY, epuSeries?.last?.close, "index (news count)",
            MarketUniverse.POLICY_UNCERTAINTY_DAILY,
            note = "Baker, Bloom & Davis daily Economic Policy Uncertainty index.")
        put(FeatureKeys.NEWS_UNCERTAINTY, newsUncSeries?.last?.close, "index (news count)",
            MarketUniverse.NEWS_EQUITY_UNCERTAINTY,
            note = "Equity-market-related economic uncertainty, counted from newspaper text.")

        // Percentile of the last five years puts the two indices on the
        // factor's own 0-100 scale without inventing a mapping.
        val epuPercentile = epuSeries?.let { s ->
            val pop = s.closes.takeLast(1260)
            if (pop.size >= 120) Stats.percentileRank(pop.last(), pop)?.times(100.0) else null
        }
        val newsPercentile = newsUncSeries?.let { s ->
            val pop = s.closes.takeLast(1260)
            if (pop.size >= 120) Stats.percentileRank(pop.last(), pop)?.times(100.0) else null
        }
        val geoMeasured = listOfNotNull(epuPercentile, newsPercentile)
            .takeIf { it.isNotEmpty() }?.average()
        put(FeatureKeys.GEOPOLITICAL_RISK_SCORE,
            geoMeasured ?: geoProxy?.coerceIn(0.0, 100.0), "0-100",
            if (geoMeasured != null) MarketUniverse.POLICY_UNCERTAINTY_DAILY else MarketUniverse.GVZ,
            isProxy = geoMeasured == null,
            note = if (geoMeasured != null)
                "Five-year percentile of the daily newspaper-derived uncertainty indices."
            else "GVZ/VIX premium z-score; the uncertainty indices did not answer this cycle.")
        val geoDeltaMeasured = epuSeries?.let { s ->
            val c = s.closes
            if (c.size < 25) null else {
                val recent = c.takeLast(5).average()
                val base = c.takeLast(25).take(20).average()
                if (base > 0.0) (recent / base - 1.0) * 100.0 else null
            }
        }
        val geoDelta = if (gvz != null && vix != null) {
            val g = gvz.returnOver(5)
            val v = vix.returnOver(5)
            if (g != null && v != null) (g - v) * 100.0 else null
        } else null
        put(FeatureKeys.GEOPOLITICAL_RISK_DELTA, geoDeltaMeasured ?: geoDelta, "% (5d vs 20d)",
            if (geoDeltaMeasured != null) MarketUniverse.POLICY_UNCERTAINTY_DAILY else MarketUniverse.GVZ,
            isProxy = geoDeltaMeasured == null)

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
        // SPEC v2.1 §25.1 — the published stress indices replace the composite.
        // STLFSI4 is already a standardised index (zero = average financial
        // stress), so it needs no second standardisation; the NFCI is on the
        // same scale. The ETF/VIX composite remains the fallback.
        val stlfsi = u.scalar(MarketUniverse.FINANCIAL_STRESS_STLFSI)
        val nfciNow = u.scalar(MarketUniverse.FINANCIAL_CONDITIONS_NFCI)
        val measuredStress = listOfNotNull(stlfsi, nfciNow).takeIf { it.isNotEmpty() }?.average()
        put(FeatureKeys.FINANCIAL_STRESS_SCORE,
            measuredStress ?: if (stress.isEmpty()) null else stress.average(),
            if (measuredStress != null) "index (0 = average stress)" else "z (composite)",
            if (measuredStress != null) MarketUniverse.FINANCIAL_STRESS_STLFSI else MarketUniverse.VIX,
            isProxy = measuredStress == null,
            note = if (measuredStress != null)
                "St. Louis Fed Financial Stress Index and the Chicago Fed National Financial " +
                    "Conditions Index, as published."
            else "Composite of VIX z, inverted HY/IG ratio z and SOFR-EFFR spread; " +
                "the published stress indices did not answer this cycle.")
        put(FeatureKeys.FINANCIAL_CONDITIONS, nfciNow, "index (0 = average)",
            MarketUniverse.FINANCIAL_CONDITIONS_NFCI,
            note = "Chicago Fed National Financial Conditions Index: positive is tighter than average.")

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
        // SPEC v2.1 §25.2 — measured from the published GLD option chain.
        // The risk reversal is the price difference between the two wings:
        // positive means the market pays more for upside than for downside.
        put(FeatureKeys.GOLD_RISK_REVERSAL, u.scalar(MarketUniverse.GOLD_RISK_REVERSAL_25D),
            "vol pts (25Δ call − put)", MarketUniverse.GOLD_RISK_REVERSAL_25D,
            note = "Implied volatility of the 25-delta call less the 25-delta put on the " +
                "nearest listed expiry beyond two weeks.")
        put(FeatureKeys.GOLD_PUT_CALL_OI, u.scalar(MarketUniverse.GOLD_PUT_CALL_OI),
            "put/call", MarketUniverse.GOLD_PUT_CALL_OI,
            note = "Put open interest over call open interest across every listed GLD contract.")

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

        // SPEC v2.1 §25.1 — net liquidity: the Fed's own balance sheet less
        // the Treasury's cash balance and the cash parked overnight in reverse
        // repo. Gold is priced in the dollars that are actually circulating.
        val netLiq = u.seriesOf(MarketUniverse.FED_NET_LIQUIDITY)
        put(FeatureKeys.FED_NET_LIQUIDITY, netLiq?.last?.close, "USD bn",
            MarketUniverse.FED_NET_LIQUIDITY,
            note = "Fed balance sheet minus the Treasury General Account minus overnight reverse repo.")
        put(FeatureKeys.FED_NET_LIQUIDITY_CHANGE, netLiq?.returnOver(13)?.times(100.0), "% (13w)",
            MarketUniverse.FED_NET_LIQUIDITY,
            note = "Thirteen-week change in net liquidity.")

        // SPEC v2.1 §25.3/§25.4 — the volatility of the bond market and the
        // slope of the equity fear curve, both quoted.
        put(FeatureKeys.BOND_VOLATILITY, u.scalar(MarketUniverse.BOND_VOL_MOVE), "index",
            MarketUniverse.BOND_VOL_MOVE,
            note = "ICE BofA MOVE index: implied volatility of US Treasuries.")
        val vix9d = u.scalar(MarketUniverse.VIX_9D)
        val vix3m = u.scalar(MarketUniverse.VIX_3M)
        put(FeatureKeys.VIX_TERM_SLOPE,
            if (vix9d != null && vix3m != null) vix3m - vix9d else null, "vol pts (3M − 9D)",
            MarketUniverse.VIX_3M,
            note = "Positive is a calm spot market pricing later risk; negative is acute stress now.")

        /* ---- F18 Credit -------------------------------------------- */
        // SPEC v2.1 §25.1 — the option-adjusted spread itself, standardised
        // against its own ten-year history. FRED's chart export serves it
        // without a key, which is what the earlier survey had ruled out.
        val oasSeries = u.seriesOf(MarketUniverse.HY_OAS)
        val oasZ = oasSeries?.let { s ->
            val pop = s.closes.takeLast(2520)
            if (pop.size >= 250) Stats.zScore(pop.last(), pop) else null
        }
        put(FeatureKeys.CREDIT_SPREAD_HY, oasZ ?: creditZ?.let { -it },
            if (oasZ != null) "z (ICE BofA HY OAS)" else "z (inverted HY/IG)",
            if (oasZ != null) MarketUniverse.HY_OAS else MarketUniverse.HY_ETF,
            isProxy = oasZ == null,
            note = if (oasZ != null)
                "ICE BofA US High Yield option-adjusted spread, standardised over ten years. " +
                    (oasSeries?.last?.close?.let { String.format(java.util.Locale.US, "Current %.2f pp.", it) } ?: "")
            else "ETF ratio proxy; the published OAS did not answer this cycle.")

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
        // SPEC v2.1 §27 — WTI spot itself, published daily, replaces the ETF.
        // The fund tracked the barrel with roll drag; the barrel does not.
        val wti = u.seriesOf(MarketUniverse.WTI_SPOT)
        val uso = u.seriesOf(MarketUniverse.OIL_ETF)
        val wtiMomentum = wti?.returnOver(20)?.times(100.0)
        put(FeatureKeys.WTI_SPOT, wti?.last?.close, "USD/bbl", MarketUniverse.WTI_SPOT)
        put(FeatureKeys.OIL_MOMENTUM_SPOT, wtiMomentum, "% (20d)", MarketUniverse.WTI_SPOT)
        put(
            FeatureKeys.OIL_MOMENTUM,
            wtiMomentum ?: uso?.returnOver(20)?.times(100.0),
            "% (20d)",
            if (wtiMomentum != null) MarketUniverse.WTI_SPOT else MarketUniverse.OIL_ETF,
            isProxy = wtiMomentum == null,
            note = if (wtiMomentum != null) "WTI spot as published, no roll drag."
            else "WTI spot unavailable this cycle; USO is the declared fallback and carries roll drag."
        )
        val brent = u.seriesOf(MarketUniverse.BRENT_SPOT)?.last?.close
        put(
            FeatureKeys.BRENT_WTI_SPREAD,
            if (brent != null && wti?.last?.close != null) brent - wti.last!!.close else null,
            "USD/bbl", MarketUniverse.BRENT_SPOT
        )
        put(FeatureKeys.OIL_VOLATILITY, u.seriesOf(MarketUniverse.OIL_VOL_OVX)?.last?.close,
            "index", MarketUniverse.OIL_VOL_OVX)

        /* ---- SPEC v2.1 §27 — the rest of the measured surface --------- */
        val real5 = u.seriesOf(MarketUniverse.REAL5Y_DEEP)?.last?.close
            ?: u.seriesOf(MarketUniverse.REAL_CURVE_5Y)?.last?.close
        val real30 = u.seriesOf(MarketUniverse.REAL30Y_DEEP)?.last?.close
            ?: u.seriesOf(MarketUniverse.REAL_CURVE_30Y)?.last?.close
        put(FeatureKeys.REAL_YIELD_5Y, real5, "%", MarketUniverse.REAL5Y_DEEP)
        put(
            FeatureKeys.REAL_CURVE_SLOPE,
            if (real5 != null && real30 != null) real30 - real5 else null,
            "pp (30y − 5y)", MarketUniverse.REAL30Y_DEEP
        )
        val be5 = u.seriesOf(MarketUniverse.BREAKEVEN5Y_DEEP)?.last?.close
        val be10 = u.seriesOf(MarketUniverse.BREAKEVEN10Y_DEEP)?.last?.close
        put(FeatureKeys.BREAKEVEN_5Y, be5, "%", MarketUniverse.BREAKEVEN5Y_DEEP)
        put(
            FeatureKeys.BREAKEVEN_SLOPE,
            if (be5 != null && be10 != null) be10 - be5 else null,
            "pp (10y − 5y)", MarketUniverse.BREAKEVEN10Y_DEEP
        )
        put(FeatureKeys.YIELD_CURVE_10Y_3M, u.seriesOf(MarketUniverse.CURVE_10Y3M_DEEP)?.last?.close,
            "pp", MarketUniverse.CURVE_10Y3M_DEEP)

        // Positioning, from the report that splits the speculative bucket.
        val mm = u.seriesOf(MarketUniverse.COT_MANAGED_MONEY_NET)
        put(FeatureKeys.COT_MANAGED_MONEY_Z, mm?.zScore(156), "z (3y)", MarketUniverse.COT_MANAGED_MONEY_NET)
        val comm = u.seriesOf(MarketUniverse.COT_COMMERCIAL_NET)
        put(FeatureKeys.COT_COMMERCIAL_Z, comm?.zScore(156), "z (3y)", MarketUniverse.COT_COMMERCIAL_NET)

        // Credit, across the quality ladder rather than one rung of it.
        put(FeatureKeys.CREDIT_SPREAD_IG, u.seriesOf(MarketUniverse.IG_OAS)?.last?.close,
            "pp (OAS)", MarketUniverse.IG_OAS)
        put(FeatureKeys.CREDIT_SPREAD_CCC, u.seriesOf(MarketUniverse.CCC_OAS)?.last?.close,
            "pp (OAS)", MarketUniverse.CCC_OAS)
        put(FeatureKeys.CREDIT_SPREAD_EM, u.seriesOf(MarketUniverse.EM_OAS)?.last?.close,
            "pp (OAS)", MarketUniverse.EM_OAS)

        // Liquidity, from the desk that runs it.
        val soma = u.seriesOf(MarketUniverse.SOMA_TOTAL)
        put(FeatureKeys.SOMA_CHANGE, soma?.returnOver(13)?.times(100.0), "% (13w)", MarketUniverse.SOMA_TOTAL)
        put(FeatureKeys.REPO_TAIL_SPREAD, u.seriesOf(MarketUniverse.SOFR_P99_SPREAD)?.last?.close,
            "bp", MarketUniverse.SOFR_P99_SPREAD)
        put(FeatureKeys.RESERVE_BALANCES, u.seriesOf(MarketUniverse.RESERVE_BALANCES)?.last?.close?.div(1000.0),
            "USD bn", MarketUniverse.RESERVE_BALANCES)

        // Growth turning point, published as an index around trend.
        val cli = u.seriesOf(MarketUniverse.OECD_CLI_US)
        put(FeatureKeys.OECD_LEADING_INDICATOR, cli?.last?.close, "index (100 = trend)",
            MarketUniverse.OECD_CLI_US)
        put(
            FeatureKeys.OECD_LEADING_CHANGE,
            if (cli != null && cli.size > 6) cli.last!!.close - cli.closeAt(cli.size - 7)!! else null,
            "index pts (6m)", MarketUniverse.OECD_CLI_US
        )

        // Cross-asset volatility tilt and the remaining measured reads.
        val vxn = u.seriesOf(MarketUniverse.NASDAQ_VOL_VXN)?.last?.close
        val vixDeep = u.seriesOf(MarketUniverse.VIX_DEEP)?.last?.close ?: u.seriesOf(MarketUniverse.VIX)?.last?.close
        put(
            FeatureKeys.VOL_DISPERSION_VXN_VIX,
            if (vxn != null && vixDeep != null && vixDeep > 0.0) vxn / vixDeep else null,
            "ratio", MarketUniverse.NASDAQ_VOL_VXN
        )
        put(FeatureKeys.INFECTIOUS_DISEASE_EMV, u.seriesOf(MarketUniverse.INFECTIOUS_DISEASE_EMV)?.last?.close,
            "index", MarketUniverse.INFECTIOUS_DISEASE_EMV)
        put(FeatureKeys.DOLLAR_AFE, u.seriesOf(MarketUniverse.DOLLAR_AFE_INDEX)?.last?.close,
            "index", MarketUniverse.DOLLAR_AFE_INDEX)
        put(FeatureKeys.USDCNY, u.seriesOf(MarketUniverse.USDCNY)?.last?.close,
            "CNY per USD", MarketUniverse.USDCNY)

        /* ---- F22 Global CB divergence -------------------------------- */
        val dxyTrend = dxy?.slope(60)
        val us2Trend = us2?.slope(60)
        // SPEC v2.1 §23 — the divergence is now read from the policy rates
        // themselves: the US rate against the mean of the other majors, moved
        // by how that gap has travelled over a year. The dollar-trend proxy is
        // kept only for cycles where the rate table is unreachable.
        val rateGap = u.scalar(MarketUniverse.POLICY_RATE_DIVERGENCE)
        val rateGapChange = u.scalar(MarketUniverse.POLICY_DIVERGENCE_CHANGE_12M)
        val measuredDivergence = if (rateGap != null) rateGap + (rateGapChange ?: 0.0) else null
        put(FeatureKeys.GLOBAL_CB_POLICY_DIVERGENCE,
            measuredDivergence
                ?: if (dxyTrend != null && us2Trend != null) (us2Trend - dxyTrend) * 100.0 else null,
            if (measuredDivergence != null) "pp (US − majors, incl. 12m drift)" else "divergence index",
            if (measuredDivergence != null) MarketUniverse.POLICY_RATE_DIVERGENCE else MarketUniverse.US02Y,
            isProxy = measuredDivergence == null,
            note = if (measuredDivergence != null)
                "Policy rates as published by the central banks: US less the mean of the euro area, " +
                    "UK, Japan, Switzerland and Canada, plus the twelve-month change in that gap."
            else "US front-end trend relative to the trade-weighted dollar trend.")

        /* ---- F10 Official sector (SPEC v2.1 §23) ---------------------
         * Previously NO_FREE_SOURCE. National authorities report their gold
         * holdings monthly under the IMF reserves template; the engine reads
         * the net change over the last three reported months, scaled by that
         * measure's own history, and the breadth of the move across reporting
         * countries. Nothing here is modelled or interpolated.
         */
        val cbNetZ = u.scalar(MarketUniverse.CB_GOLD_NET_3M_Z)
        val cbNetT = u.scalar(MarketUniverse.CB_GOLD_NET_3M_T)
        val cbBreadth = u.scalar(MarketUniverse.CB_GOLD_BREADTH)
        val cbReporters = u.scalar(MarketUniverse.CB_GOLD_REPORTERS)?.toInt()
        put(FeatureKeys.CENTRAL_BANK_NET_BUYING_3M,
            cbNetZ ?: cbNetT?.div(CB_NET_TONNES_PER_SIGMA),
            if (cbNetZ != null) "z (3m net, own history)" else "z (3m net ÷ ${CB_NET_TONNES_PER_SIGMA.toInt()} t)",
            MarketUniverse.CB_GOLD_NET_3M_T,
            note = cbNetT?.let {
                "%+.1f t reported over three months by %d countries.".format(it, cbReporters ?: 0)
            })
        put(FeatureKeys.CENTRAL_BANK_PROXY_FLOW, cbBreadth, "buyers−sellers (%)",
            MarketUniverse.CB_GOLD_BREADTH,
            note = "Share of reporting countries that added gold last month, less the share that sold.")

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

        /**
         * Fallback scale for the official-sector net change when too few
         * reporting windows exist to standardise it against its own history.
         * Quarterly official buying has run near this magnitude for a decade,
         * so one unit is one typical quarter.
         */
        const val CB_NET_TONNES_PER_SIGMA = 150.0

        fun staleness(asOf: Instant, now: Instant): Duration = Duration.between(asOf, now)
        fun absMax(vararg xs: Double?): Double? = xs.filterNotNull().maxByOrNull { abs(it) }
    }
}
