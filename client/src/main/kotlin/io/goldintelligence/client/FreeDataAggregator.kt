package io.goldintelligence.client

import io.goldintelligence.engine.DataQuality
import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.Horizon
import io.goldintelligence.engine.LogStage
import io.goldintelligence.engine.Tier
import io.goldintelligence.ingestion.DataPoint
import io.goldintelligence.ingestion.Bar
import io.goldintelligence.ingestion.Frequency
import io.goldintelligence.ingestion.LicenseClass
import io.goldintelligence.ingestion.MarketUniverse
import io.goldintelligence.ingestion.ProviderHealth
import io.goldintelligence.ingestion.QualityGates
import io.goldintelligence.ingestion.TimeSeries
import io.goldintelligence.ingestion.ValidationStatus
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Polling cadence per SPEC v2 §12.1. Daily histories are large and change
 * once a session, so they are cached far longer than quotes; the baseline is
 * sized to stay below every provider's observed tolerance rather than to
 * refresh as fast as technically possible.
 */
data class RefreshPolicy(
    val quoteTtl: Duration = Duration.ofSeconds(60),
    val historyTtl: Duration = Duration.ofHours(12),
    val curveTtl: Duration = Duration.ofHours(6),
    val weeklyTtl: Duration = Duration.ofHours(24),
    val includeYahoo: Boolean = true
)

/**
 * Fetches the entire free data stack and normalizes it into one
 * [MarketUniverse], with a [DataPoint] of full provenance behind every value.
 *
 * Failure of any single provider degrades the universe — the affected series
 * is absent and the provider is marked in `providerHealth` — but never
 * fabricates a substitute and never aborts the refresh.
 */
class FreeDataAggregator(
    private val http: HttpClient = HttpClient(),
    private val policy: RefreshPolicy = RefreshPolicy(),
    private val log: DiagnosticLog = DiagnosticLog.shared
) {
    private val cboe = CboeProvider(http)
    private val goldApi = GoldApiProvider(http)
    private val treasury = TreasuryProvider(http)
    private val cftc = CftcProvider(http)
    private val nyFed = NyFedProvider(http)
    private val oecd = OecdProvider(http)
    private val fx = FxProvider(http)
    private val yahoo = YahooProvider(http)

    // SPEC v2.1 — providers that close the previously unreachable gaps.
    private val tradingView = TradingViewProvider(http, log)
    private val kraken = KrakenProvider(http, log)
    private val okx = OkxProvider(http, log)
    private val swissquote = SwissquoteProvider(http, log)
    private val sina = SinaProvider(http, log)
    private val calendar = ForexFactoryProvider(http, log)
    private val bls = BlsProvider(http, log)
    private val goldPriceOrg = GoldPriceOrgProvider(http)
    private val wgc = WorldGoldCouncilProvider(http, log)

    // SPEC v2.1 §23 — sources that replace the last proxies with measurements.
    private val reserves = ImfReservesProvider(http, log)
    private val releases = EconomicCalendarProvider(http, log)
    private val policyRates = PolicyRateProvider(http, log)
    private val coinbase = CoinbaseDepthProvider(http, log)

    // SPEC v2.1 §25 — measured macro, options and news-derived series.
    private val fred = FredProvider(http)

    /** Last calendar read, exposed so the EVENTS screen can render it. */
    @Volatile
    var lastCalendar: List<CalendarEvent> = emptyList()
        private set

    /** Last captured order books, exposed for the microstructure rows. */
    @Volatile
    var lastBooks: List<OrderBookDepth> = emptyList()
        private set

    @Volatile
    var lastOtcTiers: List<SizeTierQuote> = emptyList()
        private set

    @Volatile
    var lastGoldCurve: List<Pair<String, Double>> = emptyList()
        private set

    private class Cached<T>(val value: T, val at: Instant)

    private val historyCache = HashMap<String, Cached<TimeSeries>>()
    private val quoteCache = HashMap<String, Cached<CboeQuote>>()
    private var treasuryCache: Cached<Pair<Map<LocalDate, Map<String, Double>>, Map<LocalDate, Map<String, Double>>>>? = null
    private var fxCache: Cached<Map<LocalDate, Map<String, Double>>>? = null
    private var cotCache: Cached<List<CftcProvider.CotRow>>? = null
    private var cotDetailCache: Cached<List<CftcProvider.CotDetail>>? = null
    private var somaCache: Cached<TimeSeries>? = null
    private var cliCache: Cached<TimeSeries>? = null
    private var curveCache: Cached<Map<String, Double>>? = null
    private var fundingCache: Cached<TimeSeries>? = null
    private var tvCache: Cached<Map<String, TvQuote>>? = null
    private var goldHistoryCache: Cached<TimeSeries>? = null
    private var benchmarkCache: Cached<TimeSeries>? = null
    private var calendarCache: Cached<List<CalendarEvent>>? = null
    private var blsCache: Cached<Map<String, Double>>? = null
    private var releasesCache: Cached<CalendarSnapshot>? = null
    private var reservesCache: Cached<OfficialGoldFlow>? = null
    private var policyCache: Cached<PolicyRates>? = null
    private val fredCache = HashMap<String, Cached<TimeSeries>>()
    private var optionCache: Cached<OptionChain>? = null

    private fun <T> fresh(c: Cached<T>?, ttl: Duration, now: Instant): T? =
        c?.takeIf { Duration.between(it.at, now) < ttl }?.value

    /** Daily-history symbols: Cboe index symbols use the `^` prefix here. */
    private val historySymbols = listOf(
        MarketUniverse.GOLD_PROXY_ETF to "GLD",
        MarketUniverse.SILVER_PROXY_ETF to "SLV",
        MarketUniverse.EQUITY_ETF to "SPY",
        MarketUniverse.LONG_BOND_ETF to "TLT",
        MarketUniverse.HY_ETF to "HYG",
        MarketUniverse.IG_ETF to "LQD",
        MarketUniverse.OIL_ETF to "USO",
        // SPEC v2.1 §26 — the real-rate and dollar legs of the replay panel.
        // Both were already quoted; the replay needs their history as well.
        MarketUniverse.TIPS_ETF to "TIP",
        MarketUniverse.DOLLAR_ETF to "UUP",
        MarketUniverse.VIX to "^VIX",
        MarketUniverse.GVZ to "^GVZ",
        MarketUniverse.SKEW to "^SKEW"
    )

    private val quoteSymbols = listOf(
        MarketUniverse.GOLD_PROXY_ETF to "GLD",
        MarketUniverse.SILVER_PROXY_ETF to "SLV",
        MarketUniverse.TIPS_ETF to "TIP",
        MarketUniverse.HY_ETF to "HYG",
        MarketUniverse.IG_ETF to "LQD",
        MarketUniverse.OIL_ETF to "USO",
        MarketUniverse.EQUITY_ETF to "SPY",
        MarketUniverse.LONG_BOND_ETF to "TLT",
        MarketUniverse.MINERS_ETF to "GDX",
        MarketUniverse.CHINA_ETF to "FXI",
        MarketUniverse.DOLLAR_ETF to "UUP",
        MarketUniverse.VIX to "^VIX",
        MarketUniverse.GVZ to "^GVZ",
        MarketUniverse.OVX to "^OVX",
        MarketUniverse.SKEW to "^SKEW",
        MarketUniverse.SPX to "^SPX"
    )

    fun refresh(now: Instant = Instant.now()): MarketUniverse {
        val series = LinkedHashMap<String, TimeSeries>()
        val scalars = LinkedHashMap<String, Double>()
        val points = LinkedHashMap<String, DataPoint>()

        fun record(
            id: String,
            value: Double?,
            unit: String,
            provider: ProviderSpec,
            observedAt: Instant?,
            frequency: Frequency,
            expectedPeriod: Duration,
            isProxy: Boolean = false,
            proxyOf: String? = null,
            datasetSize: Int = 1,
            min: Double? = null,
            max: Double? = null
        ) {
            if (value == null) {
                log.warn(
                    LogStage.FEATURE, provider.id, "VALUE_ABSENT",
                    "provider returned no value for this series", key = id
                )
                return
            }
            val ts = observedAt ?: now
            val verdict = QualityGates.check(
                value = value,
                observationTimestamp = ts,
                now = now,
                spec = QualityGates.Spec(maxAge = expectedPeriod.multipliedBy(3), min = min, max = max),
                datasetSize = datasetSize
            )
            if (!verdict.accepted) {
                log.error(
                    LogStage.QUALITY, provider.id, "GATE_" + verdict.failed.joinToString("+"),
                    "rejected by quality gate", key = id,
                    detail = "value=$value observedAt=$ts"
                )
                return
            }
            if (verdict.status == ValidationStatus.DEGRADED) {
                log.warn(
                    LogStage.QUALITY, provider.id, "DEGRADED",
                    "accepted but flagged: " + verdict.failed.joinToString().ifBlank { "stale" }, key = id
                )
            }
            val age = Duration.between(ts, now).seconds.coerceAtLeast(0)
            val quality = DataQuality.score(
                tier = provider.tier,
                ageSeconds = age,
                expectedPeriodSeconds = expectedPeriod.seconds,
                isProxy = isProxy,
                validationPassed = verdict.status == ValidationStatus.PASSED
            )
            points[id] = DataPoint(
                seriesId = id,
                value = value,
                unit = unit,
                observationTimestamp = ts,
                releaseTimestamp = if (frequency == Frequency.WEEKLY || frequency == Frequency.DAILY) ts else null,
                ingestTimestamp = now,
                source = provider.displayName,
                sourceUrl = provider.baseUrl,
                licenseClass = provider.license,
                tier = if (isProxy) Tier.PROXY else provider.tier,
                quality = quality,
                isProxy = isProxy,
                proxyOf = proxyOf,
                revision = null,
                vintage = now,
                frequency = frequency,
                latencySeconds = age,
                validationStatus = verdict.status,
                minHorizon = if (frequency == Frequency.WEEKLY) Horizon.W1
                else if (frequency == Frequency.DAILY) Horizon.D1 else Horizon.M5
            )
            scalars[id] = value
            log.debug(LogStage.FEATURE, provider.id, "INGESTED", "$value $unit", key = id)
        }

        /* ---- Cboe quotes ------------------------------------------- */
        val quotes = LinkedHashMap<String, CboeQuote>()
        for ((id, symbol) in quoteSymbols) {
            val cached = fresh(quoteCache[id], policy.quoteTtl, now)
            val q = cached ?: cboe.quote(symbol)?.also { quoteCache[id] = Cached(it, now) }
            if (q != null) {
                quotes[id] = q
                record(id, q.price, "price", Providers.CBOE, q.asOf, Frequency.MINUTE, Duration.ofMinutes(20))
                if (id == MarketUniverse.GOLD_PROXY_ETF) {
                    // Top-of-book depth is the only microstructure the free stack exposes.
                    record(MarketUniverse.GLD_BID_SIZE, q.bidSize, "contracts", Providers.CBOE, q.asOf,
                        Frequency.MINUTE, Duration.ofMinutes(20))
                    record(MarketUniverse.GLD_ASK_SIZE, q.askSize, "contracts", Providers.CBOE, q.asOf,
                        Frequency.MINUTE, Duration.ofMinutes(20))
                    if (q.bid != null && q.ask != null && q.bid > 0.0) {
                        record(MarketUniverse.GLD_SPREAD_BP, (q.ask - q.bid) / q.bid * 10_000.0, "bp",
                            Providers.CBOE, q.asOf, Frequency.MINUTE, Duration.ofMinutes(20))
                    }
                    record(MarketUniverse.GLD_IV30, q.iv30, "vol %", Providers.CBOE, q.asOf,
                        Frequency.MINUTE, Duration.ofMinutes(20))
                }
            }
        }

        /* ---- Cboe daily history ------------------------------------ */
        for ((id, symbol) in historySymbols) {
            val cached = fresh(historyCache[id], policy.historyTtl, now)
            // SPEC v2.1 §24 and §26 — the analogue matcher and the
            // walk-forward replay are only as deep as the histories they are
            // given, so the series they read are kept in full. The same JSON
            // is downloaded either way.
            val depth = if (id in DEEP_SERIES) DEEP_HISTORY_BARS else DEFAULT_HISTORY_BARS
            val s = cached ?: cboe.history(symbol, maxBars = depth)?.also { historyCache[id] = Cached(it, now) }
            if (s != null && s.bars.isNotEmpty()) {
                // Splice the live quote onto the daily history so the newest bar is today's.
                series[id] = spliceQuote(s, quotes[id])
            }
        }

        /* ---- Spot gold and silver ----------------------------------- */
        goldApi.spot("XAU")?.let {
            record(MarketUniverse.GOLD_SPOT, it.price, "USD/oz", Providers.GOLD_API, it.asOf,
                Frequency.TICK, Duration.ofMinutes(5), min = 100.0, max = 100_000.0)
        }
        goldApi.spot("XAG")?.let {
            record(MarketUniverse.SILVER_SPOT, it.price, "USD/oz", Providers.GOLD_API, it.asOf,
                Frequency.TICK, Duration.ofMinutes(5), min = 1.0, max = 10_000.0)
        }

        /* ---- Treasury curves ---------------------------------------- */
        val year = LocalDate.now(ZoneOffset.UTC).year
        val curves = fresh(treasuryCache, policy.historyTtl, now) ?: run {
            val nominal = treasury.curve(TreasuryProvider.CurveType.NOMINAL, listOf(year - 1, year))
            val real = treasury.curve(TreasuryProvider.CurveType.REAL, listOf(year - 1, year))
            val pair = nominal to real
            if (nominal.isNotEmpty() || real.isNotEmpty()) treasuryCache = Cached(pair, now)
            pair
        }
        val nominalCurve = curves.first
        val realCurve = curves.second

        treasury.seriesOf(nominalCurve, TreasuryProvider.T10Y, MarketUniverse.US10Y)?.let {
            series[MarketUniverse.US10Y] = it
            record(MarketUniverse.US10Y, it.last?.close, "%", Providers.TREASURY, it.last?.timestamp,
                Frequency.DAILY, Duration.ofDays(1), min = -5.0, max = 25.0, datasetSize = it.size)
        }
        treasury.seriesOf(nominalCurve, TreasuryProvider.T2Y, MarketUniverse.US02Y)?.let {
            series[MarketUniverse.US02Y] = it
            record(MarketUniverse.US02Y, it.last?.close, "%", Providers.TREASURY, it.last?.timestamp,
                Frequency.DAILY, Duration.ofDays(1), min = -5.0, max = 25.0, datasetSize = it.size)
        }
        treasury.seriesOf(nominalCurve, TreasuryProvider.T3M, MarketUniverse.US03M)?.let {
            series[MarketUniverse.US03M] = it
            record(MarketUniverse.US03M, it.last?.close, "%", Providers.TREASURY, it.last?.timestamp,
                Frequency.DAILY, Duration.ofDays(1), min = -5.0, max = 25.0, datasetSize = it.size)
        }
        treasury.seriesOf(nominalCurve, TreasuryProvider.T30Y, MarketUniverse.US30Y)?.let {
            series[MarketUniverse.US30Y] = it
            record(MarketUniverse.US30Y, it.last?.close, "%", Providers.TREASURY, it.last?.timestamp,
                Frequency.DAILY, Duration.ofDays(1), datasetSize = it.size)
        }
        // SPEC v2.1 §27 — the whole published real curve, not only the ten
        // year. The slope of real yields is a different statement from their
        // level, and both are in the same file the app already downloads.
        for ((tenor, id) in listOf(
            TreasuryProvider.T5Y to MarketUniverse.REAL_CURVE_5Y,
            TreasuryProvider.T7Y to MarketUniverse.REAL_CURVE_7Y,
            TreasuryProvider.T10Y to MarketUniverse.REAL_CURVE_10Y,
            TreasuryProvider.T20Y to MarketUniverse.REAL_CURVE_20Y,
            TreasuryProvider.T30Y to MarketUniverse.REAL_CURVE_30Y
        )) {
            treasury.seriesOf(realCurve, tenor, id)?.let { s2 ->
                series[id] = s2
                record(id, s2.last?.close, "%", Providers.TREASURY, s2.last?.timestamp,
                    Frequency.DAILY, Duration.ofDays(4), datasetSize = s2.size, min = -10.0, max = 20.0)
            }
        }

        treasury.seriesOf(realCurve, TreasuryProvider.T10Y, MarketUniverse.REAL10Y)?.let {
            series[MarketUniverse.REAL10Y] = it
            record(MarketUniverse.REAL10Y, it.last?.close, "%", Providers.TREASURY, it.last?.timestamp,
                Frequency.DAILY, Duration.ofDays(1), min = -10.0, max = 15.0, datasetSize = it.size)
        }
        treasury.seriesOf(realCurve, TreasuryProvider.T5Y, MarketUniverse.REAL05Y)?.let {
            series[MarketUniverse.REAL05Y] = it
            record(MarketUniverse.REAL05Y, it.last?.close, "%", Providers.TREASURY, it.last?.timestamp,
                Frequency.DAILY, Duration.ofDays(1), datasetSize = it.size)
        }

        /* ---- Breakeven: nominal minus real, same publication --------- */
        buildBreakeven(nominalCurve, realCurve)?.let {
            series[MarketUniverse.BREAKEVEN10Y] = it
            record(MarketUniverse.BREAKEVEN10Y, it.last?.close, "%", Providers.TREASURY, it.last?.timestamp,
                Frequency.DAILY, Duration.ofDays(1), datasetSize = it.size)
        }

        /* ---- FX and the synthetic dollar index ----------------------- */
        val fxSeries = fresh(fxCache, policy.historyTtl, now) ?: run {
            val to = LocalDate.now(ZoneOffset.UTC)
            val got = fx.series(to.minusDays(560), to)
            if (got.isNotEmpty()) fxCache = Cached(got, now)
            got
        }
        if (fxSeries.isNotEmpty()) {
            fx.dxySeries(fxSeries)?.let {
                series[MarketUniverse.DXY_SYNTHETIC] = it
                record(MarketUniverse.DXY_SYNTHETIC, it.last?.close, "index", Providers.ECB_FX,
                    it.last?.timestamp, Frequency.DAILY, Duration.ofDays(1),
                    isProxy = true, proxyOf = "ICE_DXY", datasetSize = it.size, min = 30.0, max = 250.0)
            }
            fx.currencySeries(fxSeries, "CNY", MarketUniverse.FX_CNY)?.let {
                series[MarketUniverse.FX_CNY] = it
                record(MarketUniverse.FX_CNY, it.last?.close, "CNY/USD", Providers.ECB_FX,
                    it.last?.timestamp, Frequency.DAILY, Duration.ofDays(1), datasetSize = it.size)
            }
            fx.currencySeries(fxSeries, "INR", MarketUniverse.FX_INR)?.let {
                series[MarketUniverse.FX_INR] = it
                record(MarketUniverse.FX_INR, it.last?.close, "INR/USD", Providers.ECB_FX,
                    it.last?.timestamp, Frequency.DAILY, Duration.ofDays(1), datasetSize = it.size)
            }
            fx.currencySeries(fxSeries, "EUR", MarketUniverse.FX_EUR)?.let { series[MarketUniverse.FX_EUR] = it }
            fx.currencySeries(fxSeries, "JPY", MarketUniverse.FX_JPY)?.let { series[MarketUniverse.FX_JPY] = it }
        }

        /* ---- NY Fed reference rates ---------------------------------- */
        val fundingSeries = fresh(fundingCache, policy.historyTtl, now) ?: nyFed.fundingSpreadSeries().also {
            if (it != null) fundingCache = Cached(it, now)
        }
        if (fundingSeries != null) {
            series[MarketUniverse.FUNDING_SPREAD] = fundingSeries
            record(MarketUniverse.FUNDING_SPREAD, fundingSeries.last?.close, "bp", Providers.NY_FED,
                fundingSeries.last?.timestamp, Frequency.DAILY, Duration.ofDays(1), datasetSize = fundingSeries.size)
        }
        val rates = nyFed.latestRates()
        rates["SOFR"]?.let {
            record(MarketUniverse.SOFR, it, "%", Providers.NY_FED, now, Frequency.DAILY, Duration.ofDays(1))
        }
        rates["EFFR"]?.let {
            record(MarketUniverse.EFFR, it, "%", Providers.NY_FED, now, Frequency.DAILY, Duration.ofDays(1))
        }

        /* ---- CFTC positioning ---------------------------------------- */
        val cot = fresh(cotCache, policy.weeklyTtl, now) ?: cftc.netNonCommercial().also {
            if (it.isNotEmpty()) cotCache = Cached(it, now)
        }
        cftc.toSeries(cot, MarketUniverse.COT_NET_NONCOMM)?.let {
            series[MarketUniverse.COT_NET_NONCOMM] = it
            record(MarketUniverse.COT_NET_NONCOMM, it.last?.close, "contracts", Providers.CFTC,
                it.last?.timestamp, Frequency.WEEKLY, Duration.ofDays(7), datasetSize = it.size)
            it.last?.volume?.let { oi ->
                record(MarketUniverse.COT_OPEN_INTEREST, oi, "contracts", Providers.CFTC,
                    it.last?.timestamp, Frequency.WEEKLY, Duration.ofDays(7))
            }
        }

        /* ---- SPEC v2.1 §27 — the disaggregated report ------------------
           The legacy "non-commercial" bucket mixes managed money with other
           reportables. The CFTC publishes the split; the app now reads it. */
        val cotDetail = fresh(cotDetailCache, policy.weeklyTtl, now) ?: cftc.disaggregated().also {
            if (it.isNotEmpty()) cotDetailCache = Cached(it, now)
        }
        cftc.detailSeries(cotDetail, MarketUniverse.COT_MANAGED_MONEY_NET) { it.managedMoneyNet }?.let {
            series[MarketUniverse.COT_MANAGED_MONEY_NET] = it
            record(MarketUniverse.COT_MANAGED_MONEY_NET, it.last?.close, "contracts", Providers.CFTC,
                it.last?.timestamp, Frequency.WEEKLY, Duration.ofDays(7), datasetSize = it.size)
        }
        cftc.detailSeries(cotDetail, MarketUniverse.COT_COMMERCIAL_NET) { it.commercialNet }?.let {
            series[MarketUniverse.COT_COMMERCIAL_NET] = it
            record(MarketUniverse.COT_COMMERCIAL_NET, it.last?.close, "contracts", Providers.CFTC,
                it.last?.timestamp, Frequency.WEEKLY, Duration.ofDays(7), datasetSize = it.size)
        }

        /* ---- SPEC v2.1 §27 — the desk's own balance sheet and repo tail */
        val soma = fresh(somaCache, policy.historyTtl, now) ?: nyFed.somaTotal()?.also {
            somaCache = Cached(it, now)
        }
        soma?.let {
            series[MarketUniverse.SOMA_TOTAL] = it
            record(MarketUniverse.SOMA_TOTAL, it.last?.close, "USD bn", Providers.NY_FED,
                it.last?.timestamp, Frequency.WEEKLY, Duration.ofDays(10), datasetSize = it.size,
                min = 0.0, max = 20_000.0)
        }
        nyFed.sofrTailSeries()?.let {
            series[MarketUniverse.SOFR_P99_SPREAD] = it
            record(MarketUniverse.SOFR_P99_SPREAD, it.last?.close, "bp", Providers.NY_FED,
                it.last?.timestamp, Frequency.DAILY, Duration.ofDays(4), datasetSize = it.size,
                min = -50.0, max = 500.0)
        }

        /* ---- SPEC v2.1 §27 — OECD composite leading indicator ---------- */
        val cli = fresh(cliCache, policy.historyTtl, now) ?: oecd.compositeLeadingIndicator()?.also {
            cliCache = Cached(it, now)
        }
        cli?.let {
            series[MarketUniverse.OECD_CLI_US] = it
            record(MarketUniverse.OECD_CLI_US, it.last?.close, "index (100 = trend)", Providers.OECD,
                it.last?.timestamp, Frequency.MONTHLY, Duration.ofDays(45), datasetSize = it.size,
                min = 80.0, max = 120.0)
        }

        /* ---- COMEX forward curve (optional) --------------------------- */
        if (policy.includeYahoo) {
            val curve = fresh(curveCache, policy.curveTtl, now) ?: run {
                val got = yahoo.forwardCurve(frontAndDeferred())
                if (got.isNotEmpty()) curveCache = Cached(got, now)
                got
            }
            val ordered = curve.entries.toList()
            ordered.firstOrNull()?.let {
                record(MarketUniverse.GOLD_FUT_FRONT, it.value, "USD/oz", Providers.YAHOO, now,
                    Frequency.MINUTE, Duration.ofMinutes(30))
            }
            ordered.lastOrNull()?.takeIf { ordered.size > 1 }?.let {
                record(MarketUniverse.GOLD_FUT_DEFERRED, it.value, "USD/oz", Providers.YAHOO, now,
                    Frequency.MINUTE, Duration.ofMinutes(30))
            }
            yahoo.chart("GC=F", "1d", "2y")?.let { series[MarketUniverse.GOLD_FUT_FRONT] = it }
        }

        /* ---- SPEC v2.1 · TradingView: curve, open interest, regional ---- */
        val tv = fresh(tvCache, policy.quoteTtl, now) ?: run {
            val got = tradingView.quotes(
                TradingViewProvider.CORE + TradingViewProvider.GOLD_CURVE + TradingViewProvider.MACRO
            )
            if (got.isNotEmpty()) tvCache = Cached(got, now)
            got
        }

        if (tv.isNotEmpty()) {
            lastGoldCurve = tradingView.goldCurve(tv)

            tv["COMEX:GC1!"]?.let { q ->
                record(MarketUniverse.GOLD_FUT_FRONT, q.close, "USD/oz", Providers.TRADINGVIEW, now,
                    Frequency.MINUTE, Duration.ofMinutes(30), min = 100.0, max = 100_000.0)
                // The gap the first survey recorded as WEEKLY_ONLY: a daily
                // open-interest print, not the CFTC weekly report.
                record(MarketUniverse.GOLD_OI_DAILY, q.openInterest, "contracts", Providers.TRADINGVIEW, now,
                    Frequency.DAILY, Duration.ofDays(1), min = 0.0)
                record(MarketUniverse.GOLD_FUT_VOLUME, q.volume, "contracts", Providers.TRADINGVIEW, now,
                    Frequency.DAILY, Duration.ofDays(1), min = 0.0)
            }
            lastGoldCurve.lastOrNull()?.let { (_, px) ->
                record(MarketUniverse.GOLD_FUT_DEFERRED, px, "USD/oz", Providers.TRADINGVIEW, now,
                    Frequency.MINUTE, Duration.ofMinutes(30), min = 100.0, max = 100_000.0)
            }
            tv["TVC:DXY"]?.close?.let { dxy ->
                record(MarketUniverse.DXY_INDEX, dxy, "index", Providers.TRADINGVIEW, now,
                    Frequency.MINUTE, Duration.ofMinutes(30), min = 30.0, max = 250.0)
            }
            // SPEC v2.1 §23 — the Treasury curve is an end-of-day publication.
            // The quoted 10-year yield moves all session, and paired with the
            // last published breakeven it gives the real yield an intraday read.
            tv["TVC:US10Y"]?.close?.let {
                record(MarketUniverse.US10Y_INTRADAY, it, "%", Providers.TRADINGVIEW, now,
                    Frequency.MINUTE, Duration.ofMinutes(30), min = -5.0, max = 25.0)
            }
            tv["TVC:US02Y"]?.close?.let {
                record(MarketUniverse.US02Y_INTRADAY, it, "%", Providers.TRADINGVIEW, now,
                    Frequency.MINUTE, Duration.ofMinutes(30), min = -5.0, max = 25.0)
            }
            tv["SHFE:AU1!"]?.close?.let {
                record(MarketUniverse.SHFE_GOLD_CNY_G, it, "CNY/g", Providers.TRADINGVIEW, now,
                    Frequency.MINUTE, Duration.ofHours(24), min = 1.0)
            }
            tv["MCX:GOLD1!"]?.close?.let {
                record(MarketUniverse.MCX_GOLD_INR_10G, it, "INR/10g", Providers.TRADINGVIEW, now,
                    Frequency.MINUTE, Duration.ofHours(24), min = 1.0)
            }

            /* ---- SPEC v2.1 §25.3 — the priced policy path ------------- */
            // A 30-day fed funds future settles on the average effective rate
            // of its month, so 100 minus the price IS the rate the market is
            // paying for. No model, no probability tree.
            fun impliedRate(ticker: String): Double? =
                tv[ticker]?.close?.takeIf { it in 80.0..101.0 }?.let { 100.0 - it }

            impliedRate("CBOT:ZQ1!")?.let {
                record(MarketUniverse.FED_FUNDS_IMPLIED_FRONT, it, "%", Providers.TRADINGVIEW, now,
                    Frequency.MINUTE, Duration.ofHours(24), min = -1.0, max = 20.0)
            }
            // The furthest contract that quoted, ~12 months out.
            listOf("CBOT:ZQM2027", "CBOT:ZQH2027", "CBOT:ZQZ2026")
                .firstNotNullOfOrNull { impliedRate(it) }
                ?.let {
                    record(MarketUniverse.FED_FUNDS_IMPLIED_12M, it, "%", Providers.TRADINGVIEW, now,
                        Frequency.MINUTE, Duration.ofHours(24), min = -1.0, max = 20.0)
                }

            tv["TVC:MOVE"]?.close?.let {
                record(MarketUniverse.BOND_VOL_MOVE, it, "index", Providers.TRADINGVIEW, now,
                    Frequency.MINUTE, Duration.ofHours(24), min = 10.0, max = 400.0)
            }
            listOf(
                "TVC:DE10Y" to MarketUniverse.DE10Y,
                "TVC:JP10Y" to MarketUniverse.JP10Y,
                "TVC:GB10Y" to MarketUniverse.GB10Y,
                "TVC:CN10Y" to MarketUniverse.CN10Y
            ).forEach { (ticker, id) ->
                tv[ticker]?.close?.let {
                    record(id, it, "%", Providers.TRADINGVIEW, now,
                        Frequency.MINUTE, Duration.ofHours(24), min = -5.0, max = 40.0)
                }
            }
            tv["CRYPTO:BTCUSD"]?.close?.let {
                record(MarketUniverse.BTC_SPOT, it, "USD", Providers.TRADINGVIEW, now,
                    Frequency.MINUTE, Duration.ofMinutes(30), min = 1.0)
            }
        }

        /* ---- SPEC v2.1 §25.1 — FRED, keyless CSV ----------------------- */
        // Each series is one request and is cached for the history TTL; the
        // whole block is skipped when the breaker for FRED is open.
        fun fredSeries(fredId: String, universeId: String, since: String): TimeSeries? {
            val cached = fresh(fredCache[universeId], policy.historyTtl, now)
            val s = cached ?: fred.series(fredId, since)?.also { fredCache[universeId] = Cached(it, now) }
            if (s != null) series[universeId] = s
            return s
        }

        val hyOas = fredSeries("BAMLH0A0HYM2", MarketUniverse.HY_OAS, "2015-01-01")
        hyOas?.last?.close?.let {
            record(MarketUniverse.HY_OAS, it, "pp (OAS)", Providers.FRED, hyOas.last!!.timestamp,
                Frequency.DAILY, Duration.ofDays(4), datasetSize = hyOas.size, min = 0.0, max = 40.0)
        }
        val nfci = fredSeries("NFCI", MarketUniverse.FINANCIAL_CONDITIONS_NFCI, "2010-01-01")
        nfci?.last?.close?.let {
            record(MarketUniverse.FINANCIAL_CONDITIONS_NFCI, it, "index", Providers.FRED,
                nfci.last!!.timestamp, Frequency.WEEKLY, Duration.ofDays(10),
                datasetSize = nfci.size, min = -5.0, max = 15.0)
        }
        val stlfsi = fredSeries("STLFSI4", MarketUniverse.FINANCIAL_STRESS_STLFSI, "2010-01-01")
        stlfsi?.last?.close?.let {
            record(MarketUniverse.FINANCIAL_STRESS_STLFSI, it, "index", Providers.FRED,
                stlfsi.last!!.timestamp, Frequency.WEEKLY, Duration.ofDays(10),
                datasetSize = stlfsi.size, min = -10.0, max = 20.0)
        }
        val epu = fredSeries("USEPUINDXD", MarketUniverse.POLICY_UNCERTAINTY_DAILY, "2003-01-01")
        epu?.last?.close?.let {
            record(MarketUniverse.POLICY_UNCERTAINTY_DAILY, it, "index", Providers.FRED,
                epu.last!!.timestamp, Frequency.DAILY, Duration.ofDays(4),
                datasetSize = epu.size, min = 0.0, max = 2000.0)
        }
        val newsUnc = fredSeries("WLEMUINDXD", MarketUniverse.NEWS_EQUITY_UNCERTAINTY, "2003-01-01")
        newsUnc?.last?.close?.let {
            record(MarketUniverse.NEWS_EQUITY_UNCERTAINTY, it, "index", Providers.FRED,
                newsUnc.last!!.timestamp, Frequency.DAILY, Duration.ofDays(4),
                datasetSize = newsUnc.size, min = 0.0, max = 2000.0)
        }
        val fwdInf = fredSeries("T5YIFR", MarketUniverse.INFLATION_EXPECTATION_5Y5Y, "2003-01-02")
        fwdInf?.last?.close?.let {
            record(MarketUniverse.INFLATION_EXPECTATION_5Y5Y, it, "%", Providers.FRED,
                fwdInf.last!!.timestamp, Frequency.DAILY, Duration.ofDays(4),
                datasetSize = fwdInf.size, min = -2.0, max = 15.0)
        }
        fredSeries("DFII10", MarketUniverse.REAL10Y_DEEP, "2003-01-02")
        fredSeries("T10YIE", MarketUniverse.BREAKEVEN10Y_DEEP, "2003-01-02")
        fredSeries("GVZCLS", MarketUniverse.GVZ_DEEP, "2008-06-03")
        val broadUsd = fredSeries("DTWEXBGS", MarketUniverse.DOLLAR_BROAD_INDEX, "2006-01-02")
        broadUsd?.last?.close?.let {
            record(MarketUniverse.DOLLAR_BROAD_INDEX, it, "index (Jan-2006=100)", Providers.FRED,
                broadUsd.last!!.timestamp, Frequency.DAILY, Duration.ofDays(6),
                datasetSize = broadUsd.size, min = 50.0, max = 250.0)
        }

        /* ---- SPEC v2.1 §27 — the measured factor panel ---------------
           Every series below is the published series itself, at the depth its
           publisher offers, because the §26 replay reads them instead of the
           ETF stand-ins it used before. One request each, same keyless CSV
           route, same cache and breaker as the block above. */
        for ((fredId, universeId, since) in DEEP_FRED) {
            fredSeries(fredId, universeId, since)
        }
        for ((universeId, unit, bounds) in DEEP_FRED_PUBLISHED) {
            val s2 = series[universeId] ?: continue
            val bar = s2.last ?: continue
            record(
                universeId, bar.close, unit, Providers.FRED, bar.timestamp,
                Frequency.DAILY, Duration.ofDays(6), datasetSize = s2.size,
                min = bounds.first, max = bounds.second
            )
        }

        // Net liquidity: the Fed's balance sheet less the Treasury's cash
        // balance and the cash parked in reverse repo. All three are published
        // weekly or daily by the Fed itself; the subtraction is the only step.
        val walcl = fredSeries("WALCL", MarketUniverse.FED_BALANCE_SHEET, "2015-01-01")
        val tga = fredSeries("WTREGEN", MarketUniverse.TREASURY_GENERAL_ACCOUNT, "2015-01-01")
        val rrp = fredSeries("RRPONTSYD", MarketUniverse.REVERSE_REPO, "2015-01-01")
        if (walcl != null && tga != null && rrp != null) {
            val netSeries = netLiquidity(walcl, tga, rrp)
            if (netSeries != null) {
                series[MarketUniverse.FED_NET_LIQUIDITY] = netSeries
                netSeries.last?.let { bar ->
                    record(MarketUniverse.FED_NET_LIQUIDITY, bar.close, "USD bn", Providers.FRED,
                        bar.timestamp, Frequency.WEEKLY, Duration.ofDays(10),
                        datasetSize = netSeries.size, min = 0.0, max = 20_000.0)
                }
            }
        }

        /* ---- SPEC v2.1 §25.2 — the published GLD option chain ---------- */
        val chain = fresh(optionCache, policy.curveTtl, now)
            ?: cboe.optionChain("GLD")?.also { optionCache = Cached(it, now) }
        if (chain != null) {
            record(MarketUniverse.GOLD_OPTION_OPEN_INTEREST, chain.openInterestTotal, "contracts",
                Providers.CBOE, chain.asOf, Frequency.DAILY, Duration.ofDays(2), min = 0.0)
            chain.putCallOpenInterest?.let {
                record(MarketUniverse.GOLD_PUT_CALL_OI, it, "put/call", Providers.CBOE, chain.asOf,
                    Frequency.DAILY, Duration.ofDays(2), min = 0.0, max = 20.0)
            }
            chain.putCallVolume?.let {
                record(MarketUniverse.GOLD_PUT_CALL_VOLUME, it, "put/call", Providers.CBOE, chain.asOf,
                    Frequency.DAILY, Duration.ofDays(2), min = 0.0, max = 20.0)
            }
            chain.riskReversal25?.let {
                record(MarketUniverse.GOLD_RISK_REVERSAL_25D, it, "vol pts (25Δ call − put)",
                    Providers.CBOE, chain.asOf, Frequency.DAILY, Duration.ofDays(2),
                    min = -40.0, max = 40.0)
            }
            chain.ivTermSlope?.let {
                record(MarketUniverse.GOLD_IV_TERM_SLOPE, it, "vol pts (far − near)", Providers.CBOE,
                    chain.asOf, Frequency.DAILY, Duration.ofDays(2), min = -40.0, max = 40.0)
            }
            log.info(
                LogStage.PARSE, Providers.CBOE.id, "OPTION_CHAIN_OK",
                "%d listed GLD contracts; put/call OI %s, 25Δ risk reversal %s".format(
                    chain.contracts.size,
                    chain.putCallOpenInterest?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "n/a",
                    chain.riskReversal25?.let { String.format(java.util.Locale.US, "%+.2f", it) } ?: "n/a"
                ),
                key = MarketUniverse.GOLD_RISK_REVERSAL_25D
            )
        }

        /* ---- SPEC v2.1 §25.4 — the shape of the equity fear curve ------ */
        listOf("^VIX9D" to MarketUniverse.VIX_9D, "^VIX3M" to MarketUniverse.VIX_3M)
            .forEach { (symbol, id) ->
                val cached = fresh(quoteCache[id], policy.quoteTtl, now)
                val q = cached ?: cboe.quote(symbol)?.also { quoteCache[id] = Cached(it, now) }
                q?.let {
                    record(id, it.price, "index", Providers.CBOE, it.asOf,
                        Frequency.MINUTE, Duration.ofHours(24), min = 1.0, max = 200.0)
                }
            }

        /* ---- SPEC v2.1 · Shanghai Gold Exchange Au(T+D) ----------------- */
        val sgeCnyPerGram = sina.shanghaiGoldCnyPerGram()
        if (sgeCnyPerGram != null) {
            record(MarketUniverse.SGE_GOLD_CNY_G, sgeCnyPerGram, "CNY/g", Providers.SINA_SGE, now,
                Frequency.MINUTE, Duration.ofHours(24), min = 1.0)
        }

        /* ---- SPEC v2.1 · regional physical premia ----------------------- */
        val spotUsdOz = scalars[MarketUniverse.GOLD_SPOT]
        val usdCny = series[MarketUniverse.FX_CNY]?.last?.close
        val usdInr = series[MarketUniverse.FX_INR]?.last?.close
        val chinaCnyPerGram = scalars[MarketUniverse.SGE_GOLD_CNY_G]
            ?: scalars[MarketUniverse.SHFE_GOLD_CNY_G]
        if (spotUsdOz != null && usdCny != null && usdCny > 0 && chinaCnyPerGram != null) {
            val chinaUsdOz = chinaCnyPerGram / usdCny * SinaProvider.GRAMS_PER_TROY_OUNCE
            record(MarketUniverse.CHINA_PREMIUM_PCT, (chinaUsdOz / spotUsdOz - 1.0) * 100.0, "%",
                Providers.SINA_SGE, now, Frequency.DAILY, Duration.ofHours(24), min = -30.0, max = 30.0)
        } else {
            log.warn(
                LogStage.FEATURE, "FreeDataAggregator", "PREMIUM_INPUTS_MISSING",
                "need spot, USDCNY and a Chinese gold quote", key = MarketUniverse.CHINA_PREMIUM_PCT,
                detail = "spot=$spotUsdOz usdCny=$usdCny cnyPerGram=$chinaCnyPerGram"
            )
        }
        val mcx = scalars[MarketUniverse.MCX_GOLD_INR_10G]
        if (spotUsdOz != null && usdInr != null && usdInr > 0 && mcx != null) {
            val indiaUsdOz = mcx / 10.0 / usdInr * SinaProvider.GRAMS_PER_TROY_OUNCE
            record(MarketUniverse.INDIA_PREMIUM_PCT, (indiaUsdOz / spotUsdOz - 1.0) * 100.0, "%",
                Providers.TRADINGVIEW, now, Frequency.DAILY, Duration.ofHours(24), min = -30.0, max = 60.0)
        } else {
            log.warn(
                LogStage.FEATURE, "FreeDataAggregator", "PREMIUM_INPUTS_MISSING",
                "need spot, USDINR and the MCX quote", key = MarketUniverse.INDIA_PREMIUM_PCT,
                detail = "spot=$spotUsdOz usdInr=$usdInr mcx=$mcx"
            )
        }

        /* ---- SPEC v2.1 · real gold order books -------------------------- */
        val books = listOfNotNull(kraken.depth(), okx.depth(), coinbase.depth(now))
        lastBooks = books
        if (books.isEmpty()) {
            log.error(
                LogStage.FEATURE, "FreeDataAggregator", "NO_ORDER_BOOK",
                "neither gold venue returned a book", key = MarketUniverse.BOOK_IMBALANCE
            )
        } else {
            val bidVol = books.sumOf { it.bidVolume }
            val askVol = books.sumOf { it.askVolume }
            val total = bidVol + askVol
            if (total > 0) {
                record(MarketUniverse.BOOK_IMBALANCE, (bidVol - askVol) / total, "-1..1",
                    Providers.KRAKEN, now, Frequency.TICK, Duration.ofMinutes(10), min = -1.0, max = 1.0)
            }
            record(MarketUniverse.BOOK_BID_VOLUME, bidVol, "oz", Providers.KRAKEN, now,
                Frequency.TICK, Duration.ofMinutes(10), min = 0.0)
            record(MarketUniverse.BOOK_ASK_VOLUME, askVol, "oz", Providers.KRAKEN, now,
                Frequency.TICK, Duration.ofMinutes(10), min = 0.0)
            record(MarketUniverse.BOOK_SPREAD_BP, books.minOf { it.spreadBp }, "bp",
                Providers.KRAKEN, now, Frequency.TICK, Duration.ofMinutes(10), min = 0.0)
            record(MarketUniverse.BOOK_DEPTH_LEVELS, books.sumOf { it.bidLevels + it.askLevels }.toDouble(),
                "levels", Providers.KRAKEN, now, Frequency.TICK, Duration.ofMinutes(10), min = 0.0)
        }

        val tiers = swissquote.tiers()
        lastOtcTiers = tiers
        tiers.minByOrNull { it.spreadBp }?.let {
            record(MarketUniverse.OTC_SPREAD_BP, it.spreadBp, "bp", Providers.SWISSQUOTE, now,
                Frequency.TICK, Duration.ofMinutes(10), min = 0.0)
        }

        /* ---- SPEC v2.1 · gold's own daily history ----------------------- */
        val goldHistory = fresh(goldHistoryCache, policy.historyTtl, now)
            ?: (kraken.dailyHistory() ?: okx.dailyHistory())?.also { goldHistoryCache = Cached(it, now) }
        if (goldHistory != null && goldHistory.bars.size >= 30) {
            series[MarketUniverse.GOLD_SPOT_HISTORY] = goldHistory
            record(MarketUniverse.GOLD_SPOT_HISTORY, goldHistory.last?.close, "USD/oz",
                Providers.KRAKEN, goldHistory.last?.timestamp, Frequency.DAILY, Duration.ofDays(1),
                datasetSize = goldHistory.bars.size, min = 100.0, max = 100_000.0)
        }

        /* ---- SPEC v2.1 · LBMA benchmark via the World Gold Council ------ */
        val benchmark = fresh(benchmarkCache, policy.weeklyTtl, now)
            ?: wgc.benchmark()?.series?.also { benchmarkCache = Cached(it, now) }
        if (benchmark != null && benchmark.bars.isNotEmpty()) {
            series[MarketUniverse.LBMA_BENCHMARK] = benchmark
            record(MarketUniverse.LBMA_BENCHMARK, benchmark.last?.close, "USD/oz",
                Providers.WGC, benchmark.last?.timestamp, Frequency.MONTHLY, Duration.ofDays(35),
                datasetSize = benchmark.bars.size, min = 1.0)
        }

        /* ---- SPEC v2.1 · cross-source spot agreement -------------------- */
        val spotReadings = listOfNotNull(
            scalars[MarketUniverse.GOLD_SPOT],
            goldPriceOrg.latest()?.gold,
            tv["TVC:GOLD"]?.close,
            tv["OANDA:XAUUSD"]?.close,
            books.firstOrNull()?.mid
        )
        if (spotReadings.size >= 2) {
            val mean = spotReadings.average()
            val dispersion = (spotReadings.max() - spotReadings.min()) / mean * 10_000.0
            record(MarketUniverse.SPOT_CONSENSUS_BP, dispersion, "bp", Providers.GOLDPRICE_ORG, now,
                Frequency.TICK, Duration.ofMinutes(15), min = 0.0)
            log.info(
                LogStage.QUALITY, "FreeDataAggregator", "SPOT_CROSSCHECK",
                "${spotReadings.size} independent spot sources agree to ${"%.1f".format(dispersion)} bp",
                key = MarketUniverse.SPOT_CONSENSUS_BP
            )
        }

        /* ---- SPEC v2.1 §23 · calendar with released actuals -------------
         * The primary calendar carries the actual print next to the consensus,
         * which is what turns "surprise" from a market-implied guess into a
         * measurement. The weekly file stays as the fallback for the schedule
         * alone, so a calendar outage still leaves the event clock running.
         */
        val snapshot = fresh(releasesCache, policy.curveTtl, now) ?: releases.snapshot(now)?.also {
            releasesCache = Cached(it, now)
        }
        val fallbackEvents = if (snapshot != null) emptyList() else
            fresh(calendarCache, policy.curveTtl, now) ?: calendar.events().also {
                if (it.isNotEmpty()) calendarCache = Cached(it, now)
            }
        val events = snapshot?.events ?: fallbackEvents
        val calendarProvider = if (snapshot != null) Providers.CALENDAR else Providers.FOREXFACTORY
        lastCalendar = events
        if (events.isNotEmpty()) {
            val horizon = now.plus(Duration.ofHours(24))
            val upcoming = events.filter { it.time.isAfter(now) }
            record(
                MarketUniverse.CALENDAR_HIGH_IMPACT_24H,
                upcoming.count { it.highImpact && it.time.isBefore(horizon) }.toDouble(),
                "events", calendarProvider, now, Frequency.DAILY, Duration.ofHours(12), min = 0.0
            )
            // A high-impact release is the one the engine wants to time against;
            // when the calendar holds none, the next scheduled release of any
            // weight is recorded instead, and declared as the substitute it is.
            val nextHigh = upcoming.firstOrNull { it.highImpact }
            val next = nextHigh ?: upcoming.minByOrNull { it.time }
            if (next != null) {
                record(
                    MarketUniverse.CALENDAR_NEXT_EVENT_HOURS,
                    Duration.between(now, next.time).toMinutes() / 60.0,
                    "h", calendarProvider, now, Frequency.DAILY, Duration.ofHours(12),
                    isProxy = nextHigh == null,
                    proxyOf = if (nextHigh == null) MarketUniverse.CALENDAR_NEXT_EVENT_HOURS else null,
                    min = 0.0
                )
            }
        }
        if (snapshot != null) {
            snapshot.surpriseIndex(minImportance = 0)?.let {
                record(
                    MarketUniverse.SURPRISE_INDEX_MEASURED, it, "index (actual vs consensus)",
                    Providers.CALENDAR, now, Frequency.DAILY, Duration.ofHours(24),
                    datasetSize = snapshot.releases.size, min = -100.0, max = 100.0
                )
            }
            snapshot.surpriseIndex(minImportance = -1) { r ->
                val n = r.indicator.lowercase()
                n.contains("inflation") || n.contains("cpi") || n.contains("ppi") ||
                    n.contains("price index") || n.contains("pce")
            }?.let {
                record(
                    MarketUniverse.INFLATION_SURPRISE_MEASURED, it, "index (actual vs consensus)",
                    Providers.CALENDAR, now, Frequency.MONTHLY, Duration.ofDays(35),
                    min = -100.0, max = 100.0
                )
            }
        }

        /* ---- SPEC v2.1 · official actuals, for a real surprise ---------- */
        val official = fresh(blsCache, policy.weeklyTtl, now) ?: buildMap {
            bls.cpiYoY()?.let { put(MarketUniverse.CPI_YOY, it) }
            bls.unemploymentRate()?.let { put(MarketUniverse.UNEMPLOYMENT_RATE, it) }
        }.also { if (it.isNotEmpty()) blsCache = Cached(it, now) }
        official[MarketUniverse.CPI_YOY]?.let {
            record(MarketUniverse.CPI_YOY, it, "%", Providers.BLS, now,
                Frequency.MONTHLY, Duration.ofDays(35), min = -20.0, max = 50.0)
        }
        official[MarketUniverse.UNEMPLOYMENT_RATE]?.let {
            record(MarketUniverse.UNEMPLOYMENT_RATE, it, "%", Providers.BLS, now,
                Frequency.MONTHLY, Duration.ofDays(35), min = 0.0, max = 40.0)
        }
        // SPEC v2.1 §23 — the calendar carries the actual next to the consensus,
        // so the surprise no longer depends on finding an independent print of
        // the same release; the official-statistics pairing stays as the
        // fallback for a cycle where the calendar is unreachable.
        val measuredConsensus = snapshot?.latestConsensusSurprise()
        (measuredConsensus ?: consensusSurprise(events, official))?.let {
            record(
                MarketUniverse.CONSENSUS_SURPRISE, it, "normalized (actual vs consensus)",
                if (measuredConsensus != null) Providers.CALENDAR else Providers.FOREXFACTORY,
                now, Frequency.MONTHLY, Duration.ofDays(35), min = -10.0, max = 10.0
            )
        }

        /* ---- SPEC v2.1 §23 · official sector ---------------------------
         * Monthly gold holdings as reported by national authorities. This is
         * the measurement the central-bank demand factor was waiting for; no
         * part of it is modelled.
         */
        val officialGold = fresh(reservesCache, policy.weeklyTtl, now)
            ?: reserves.officialGoldFlow(now)?.also { reservesCache = Cached(it, now) }
        if (officialGold != null) {
            val reportedAt = officialGold.latestPeriod.atEndOfMonth()
                .atStartOfDay(ZoneOffset.UTC).toInstant()
            record(
                MarketUniverse.CB_GOLD_NET_3M_T, officialGold.net3mTonnes, "t",
                Providers.IMF_RESERVES, reportedAt, Frequency.MONTHLY, Duration.ofDays(45),
                datasetSize = officialGold.observations, min = -2_000.0, max = 2_000.0
            )
            officialGold.net3mZ?.let {
                record(
                    MarketUniverse.CB_GOLD_NET_3M_Z, it, "z",
                    Providers.IMF_RESERVES, reportedAt, Frequency.MONTHLY, Duration.ofDays(45),
                    datasetSize = officialGold.observations, min = -10.0, max = 10.0
                )
            }
            record(
                MarketUniverse.CB_GOLD_BREADTH, officialGold.breadthIndex, "buyers−sellers (%)",
                Providers.IMF_RESERVES, reportedAt, Frequency.MONTHLY, Duration.ofDays(45),
                datasetSize = officialGold.reportingCountries, min = -100.0, max = 100.0
            )
            record(
                MarketUniverse.CB_GOLD_REPORTERS, officialGold.reportingCountries.toDouble(),
                "countries", Providers.IMF_RESERVES, reportedAt, Frequency.MONTHLY,
                Duration.ofDays(45), min = 0.0, max = 250.0
            )
        }

        /* ---- SPEC v2.1 §23 · policy rates ------------------------------- */
        val cbRates = fresh(policyCache, policy.weeklyTtl, now)
            ?: policyRates.rates()?.also { policyCache = Cached(it, now) }
        if (cbRates != null) {
            cbRates.us?.let {
                record(
                    MarketUniverse.POLICY_RATE_US, it, "%", Providers.BIS, now,
                    Frequency.MONTHLY, Duration.ofDays(45), min = -5.0, max = 30.0
                )
            }
            cbRates.divergence?.let {
                record(
                    MarketUniverse.POLICY_RATE_DIVERGENCE, it, "pp (US − majors)",
                    Providers.BIS, now, Frequency.MONTHLY, Duration.ofDays(45),
                    datasetSize = cbRates.rates.size, min = -20.0, max = 20.0
                )
            }
            cbRates.divergenceChange12m?.let {
                record(
                    MarketUniverse.POLICY_DIVERGENCE_CHANGE_12M, it, "pp over 12m",
                    Providers.BIS, now, Frequency.MONTHLY, Duration.ofDays(45),
                    datasetSize = cbRates.changes12m.size, min = -20.0, max = 20.0
                )
            }
        }

        log.info(
            LogStage.FEATURE, "FreeDataAggregator", "REFRESH_COMPLETE",
            "${series.size} series, ${scalars.size} scalars, ${points.size} provenance records"
        )

        return MarketUniverse(
            asOf = now,
            series = series,
            scalars = scalars,
            points = points,
            providerHealth = http.healthSnapshot()
        )
    }

    fun providerHealth(): Map<String, ProviderHealth> = http.healthSnapshot()

    /**
     * Standardised surprise where a surveyed consensus and an official actual
     * describe the same release. Only releases whose actual is published by a
     * statistical agency are used, so the surprise is measured, not guessed.
     */
    private fun consensusSurprise(
        events: List<CalendarEvent>,
        official: Map<String, Double>
    ): Double? {
        val pairs = ArrayList<Pair<Double, Double>>()
        official[MarketUniverse.CPI_YOY]?.let { actual ->
            events.firstOrNull { it.country == "USD" && it.title.startsWith("CPI y/y", true) }
                ?.forecastValue?.let { pairs += actual to it }
        }
        official[MarketUniverse.UNEMPLOYMENT_RATE]?.let { actual ->
            events.firstOrNull { it.country == "USD" && it.title.startsWith("Unemployment Rate", true) }
                ?.forecastValue?.let { pairs += actual to it }
        }
        if (pairs.isEmpty()) {
            log.debug(
                LogStage.FEATURE, "FreeDataAggregator", "NO_MATCHED_RELEASE",
                "no release this week pairs a consensus with a published actual",
                key = MarketUniverse.CONSENSUS_SURPRISE
            )
            return null
        }
        // Normalised by the consensus level so percentage-point series and
        // index series are comparable.
        return pairs.map { (actual, forecast) ->
            val scale = kotlin.math.max(kotlin.math.abs(forecast), 0.1)
            (actual - forecast) / scale
        }.average()
    }

    /** Replaces the last daily bar with the live quote when the quote is newer. */
    private fun spliceQuote(s: TimeSeries, q: CboeQuote?): TimeSeries {
        if (q == null) return s
        val lastBar = s.last ?: return s
        val quoteDay = q.asOf.atZone(ZoneOffset.UTC).toLocalDate()
        val lastDay = lastBar.timestamp.atZone(ZoneOffset.UTC).toLocalDate()
        val live = io.goldintelligence.ingestion.Bar(
            timestamp = q.asOf,
            open = q.open ?: lastBar.close,
            high = q.high ?: maxOf(q.price, lastBar.close),
            low = q.low ?: minOf(q.price, lastBar.close),
            close = q.price,
            volume = q.volume
        )
        return if (quoteDay.isAfter(lastDay)) {
            s.copy(bars = s.bars + live)
        } else {
            s.copy(bars = s.bars.dropLast(1) + live)
        }
    }

    private fun buildBreakeven(
        nominal: Map<LocalDate, Map<String, Double>>,
        real: Map<LocalDate, Map<String, Double>>
    ): TimeSeries? {
        val bars = nominal.keys.intersect(real.keys).sorted().mapNotNull { d ->
            val n = nominal[d]?.get(TreasuryProvider.T10Y) ?: return@mapNotNull null
            val r = real[d]?.get(TreasuryProvider.T10Y) ?: return@mapNotNull null
            val v = n - r
            io.goldintelligence.ingestion.Bar(d.atStartOfDay(ZoneOffset.UTC).toInstant(), v, v, v, v, null)
        }
        return if (bars.size < 10) null else TimeSeries(MarketUniverse.BREAKEVEN10Y, bars, Frequency.DAILY)
    }

    /** Dated COMEX gold contracts, nearest first. Yahoo requires the `.CMX` suffix. */
    private fun frontAndDeferred(): List<String> {
        val today = LocalDate.now(ZoneOffset.UTC)
        val months = listOf(2 to 'G', 4 to 'J', 6 to 'M', 8 to 'Q', 12 to 'Z')
        val out = ArrayList<String>()
        var year = today.year
        var added = 0
        while (added < 4 && year <= today.year + 2) {
            for ((m, code) in months) {
                if (year == today.year && m < today.monthValue) continue
                out += "GC$code${year % 100}.CMX"
                added++
                if (added >= 4) break
            }
            year++
        }
        return out
    }

    /**
     * SPEC v2.1 §25.1 — net liquidity, in billions of dollars.
     *
     * `WALCL` and `WTREGEN` are published in millions, `RRPONTSYD` in
     * billions; all three are aligned to the weekly balance-sheet dates and
     * the other two are read as of that date, never interpolated.
     */
    private fun netLiquidity(walcl: TimeSeries, tga: TimeSeries, rrp: TimeSeries): TimeSeries? {
        fun asOf(s: TimeSeries, t: Instant): Double? =
            s.bars.lastOrNull { !it.timestamp.isAfter(t) }?.close
        val bars = walcl.bars.mapNotNull { b ->
            val cash = asOf(tga, b.timestamp) ?: return@mapNotNull null
            val repo = asOf(rrp, b.timestamp) ?: return@mapNotNull null
            val net = (b.close - cash) / 1000.0 - repo
            if (net <= 0.0) null else Bar(b.timestamp, net, net, net, net)
        }
        return if (bars.size < 8) null else TimeSeries(MarketUniverse.FED_NET_LIQUIDITY, bars, Frequency.WEEKLY)
    }

    companion object {
        /** Daily bars kept for a series the factor layer only reads recently. */
        const val DEFAULT_HISTORY_BARS = 600

        /**
         * Daily bars kept for the series the analogue matcher describes a
         * session with (SPEC §24). Cboe publishes roughly 5,500 sessions for
         * these symbols; the whole file is downloaded either way, so the only
         * cost of keeping it is memory, and the matcher's reach depends on it.
         */
        const val DEEP_HISTORY_BARS = 6_000

        /** Series the analogue matcher reads. */
        val ANALOGUE_SERIES = setOf(
            MarketUniverse.GOLD_PROXY_ETF,
            MarketUniverse.SILVER_PROXY_ETF,
            MarketUniverse.EQUITY_ETF,
            MarketUniverse.LONG_BOND_ETF,
            MarketUniverse.VIX
        )

        /**
         * Series the §26 walk-forward replay builds its factor panel from.
         * These are already downloaded for the factor layer; the replay needs
         * the depth, and a short panel would cap the calibration sample at the
         * shortest member rather than at the gold history.
         */
        val CALIBRATION_SERIES = setOf(
            MarketUniverse.TIPS_ETF,
            MarketUniverse.DOLLAR_ETF,
            MarketUniverse.HY_ETF,
            MarketUniverse.OIL_ETF
        )

        /** Every series kept at full depth. */
        val DEEP_SERIES: Set<String> = ANALOGUE_SERIES + CALIBRATION_SERIES

        /**
         * Universe ids fetched at the publisher's full depth rather than at
         * the two years the screens need. The §26 replay reads these, so a
         * leg of its panel must appear here or the replay is short-changed.
         */
        val DEEP_HISTORY_IDS: Set<String>
            get() = DEEP_SERIES + DEEP_FRED.map { it.second } + setOf(
                MarketUniverse.REAL10Y_DEEP,
                MarketUniverse.BREAKEVEN10Y_DEEP,
                MarketUniverse.GVZ_DEEP,
                MarketUniverse.DOLLAR_BROAD_INDEX,
                MarketUniverse.POLICY_UNCERTAINTY_DAILY,
                MarketUniverse.NEWS_EQUITY_UNCERTAINTY,
                MarketUniverse.INFLATION_EXPECTATION_5Y5Y
            )

        /**
         * SPEC v2.1 §27 — measured daily series, fetched at the depth the
         * publisher offers rather than the two years the screens need, because
         * the §26 replay reads them. `since` is the first observation each
         * series actually has; asking for earlier returns the same file.
         *
         * Triple: FRED id, universe id, first observation date.
         */
        val DEEP_FRED: List<Triple<String, String, String>> = listOf(
            Triple("DFII5", MarketUniverse.REAL5Y_DEEP, "2003-01-02"),
            Triple("DFII30", MarketUniverse.REAL30Y_DEEP, "2010-02-22"),
            Triple("T5YIE", MarketUniverse.BREAKEVEN5Y_DEEP, "2003-01-02"),
            Triple("DGS10", MarketUniverse.US10Y_DEEP, "2000-01-03"),
            Triple("DGS2", MarketUniverse.US02Y_DEEP, "2000-01-03"),
            Triple("T10Y2Y", MarketUniverse.CURVE_10Y2Y_DEEP, "2000-01-03"),
            Triple("T10Y3M", MarketUniverse.CURVE_10Y3M_DEEP, "2000-01-03"),
            Triple("VIXCLS", MarketUniverse.VIX_DEEP, "2000-01-03"),
            Triple("VXNCLS", MarketUniverse.NASDAQ_VOL_VXN, "2001-02-02"),
            Triple("OVXCLS", MarketUniverse.OIL_VOL_OVX, "2007-05-10"),
            Triple("DCOILWTICO", MarketUniverse.WTI_SPOT, "2000-01-04"),
            Triple("DCOILBRENTEU", MarketUniverse.BRENT_SPOT, "2000-01-04"),
            Triple("DHHNGSP", MarketUniverse.NATGAS_SPOT, "2000-01-10"),
            Triple("BAMLC0A0CM", MarketUniverse.IG_OAS, "2015-01-01"),
            Triple("BAMLH0A3HYC", MarketUniverse.CCC_OAS, "2015-01-01"),
            Triple("BAMLEMCBPIOAS", MarketUniverse.EM_OAS, "2015-01-01"),
            Triple("DTWEXAFEGS", MarketUniverse.DOLLAR_AFE_INDEX, "2006-01-02"),
            Triple("DEXUSEU", MarketUniverse.EURUSD, "2000-01-03"),
            Triple("DEXJPUS", MarketUniverse.USDJPY, "2000-01-03"),
            Triple("DEXCHUS", MarketUniverse.USDCNY, "2000-01-03"),
            Triple("DEXUSUK", MarketUniverse.GBPUSD, "2000-01-03"),
            Triple("WRESBAL", MarketUniverse.RESERVE_BALANCES, "2003-01-01"),
            Triple("DFF", MarketUniverse.FED_FUNDS_EFFECTIVE, "2000-01-01"),
            Triple("INFECTDISEMVTRACKD", MarketUniverse.INFECTIOUS_DISEASE_EMV, "2000-01-01")
        )

        /** Which of the above also publish a current reading to the screens. */
        val DEEP_FRED_PUBLISHED: List<Triple<String, String, Pair<Double, Double>>> = listOf(
            Triple(MarketUniverse.REAL5Y_DEEP, "%", -10.0 to 20.0),
            Triple(MarketUniverse.REAL30Y_DEEP, "%", -10.0 to 20.0),
            Triple(MarketUniverse.BREAKEVEN5Y_DEEP, "%", -5.0 to 15.0),
            Triple(MarketUniverse.US02Y_DEEP, "%", -5.0 to 25.0),
            Triple(MarketUniverse.CURVE_10Y2Y_DEEP, "pp", -10.0 to 10.0),
            Triple(MarketUniverse.CURVE_10Y3M_DEEP, "pp", -10.0 to 10.0),
            Triple(MarketUniverse.NASDAQ_VOL_VXN, "index", 0.0 to 200.0),
            Triple(MarketUniverse.OIL_VOL_OVX, "index", 0.0 to 400.0),
            Triple(MarketUniverse.WTI_SPOT, "USD/bbl", 0.0 to 400.0),
            Triple(MarketUniverse.BRENT_SPOT, "USD/bbl", 0.0 to 400.0),
            Triple(MarketUniverse.NATGAS_SPOT, "USD/MMBtu", 0.0 to 100.0),
            Triple(MarketUniverse.IG_OAS, "pp (OAS)", 0.0 to 15.0),
            Triple(MarketUniverse.CCC_OAS, "pp (OAS)", 0.0 to 60.0),
            Triple(MarketUniverse.EM_OAS, "pp (OAS)", 0.0 to 40.0),
            Triple(MarketUniverse.DOLLAR_AFE_INDEX, "index", 50.0 to 250.0),
            Triple(MarketUniverse.EURUSD, "USD per EUR", 0.1 to 5.0),
            Triple(MarketUniverse.USDJPY, "JPY per USD", 10.0 to 1000.0),
            Triple(MarketUniverse.USDCNY, "CNY per USD", 1.0 to 50.0),
            Triple(MarketUniverse.GBPUSD, "USD per GBP", 0.1 to 5.0),
            Triple(MarketUniverse.RESERVE_BALANCES, "USD mn", 0.0 to 1.0e8),
            Triple(MarketUniverse.FED_FUNDS_EFFECTIVE, "%", -2.0 to 30.0),
            Triple(MarketUniverse.INFECTIOUS_DISEASE_EMV, "index", 0.0 to 200.0)
        )
    }
}
