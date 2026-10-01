package io.goldintelligence.client

import io.goldintelligence.engine.DataQuality
import io.goldintelligence.engine.Horizon
import io.goldintelligence.engine.Tier
import io.goldintelligence.ingestion.DataPoint
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
    private val policy: RefreshPolicy = RefreshPolicy()
) {
    private val cboe = CboeProvider(http)
    private val goldApi = GoldApiProvider(http)
    private val treasury = TreasuryProvider(http)
    private val cftc = CftcProvider(http)
    private val nyFed = NyFedProvider(http)
    private val fx = FxProvider(http)
    private val yahoo = YahooProvider(http)

    private class Cached<T>(val value: T, val at: Instant)

    private val historyCache = HashMap<String, Cached<TimeSeries>>()
    private val quoteCache = HashMap<String, Cached<CboeQuote>>()
    private var treasuryCache: Cached<Pair<Map<LocalDate, Map<String, Double>>, Map<LocalDate, Map<String, Double>>>>? = null
    private var fxCache: Cached<Map<LocalDate, Map<String, Double>>>? = null
    private var cotCache: Cached<List<CftcProvider.CotRow>>? = null
    private var curveCache: Cached<Map<String, Double>>? = null
    private var fundingCache: Cached<TimeSeries>? = null

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
            if (value == null) return
            val ts = observedAt ?: now
            val verdict = QualityGates.check(
                value = value,
                observationTimestamp = ts,
                now = now,
                spec = QualityGates.Spec(maxAge = expectedPeriod.multipliedBy(3), min = min, max = max),
                datasetSize = datasetSize
            )
            if (!verdict.accepted) return
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
            val s = cached ?: cboe.history(symbol)?.also { historyCache[id] = Cached(it, now) }
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

        return MarketUniverse(
            asOf = now,
            series = series,
            scalars = scalars,
            points = points,
            providerHealth = http.healthSnapshot()
        )
    }

    fun providerHealth(): Map<String, ProviderHealth> = http.healthSnapshot()

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
}
