package io.goldintelligence.client

import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.LogStage
import io.goldintelligence.ingestion.Bar
import io.goldintelligence.ingestion.TimeSeries
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Providers added in SPEC v2.1 to close the gaps that the first free-stack
 * survey had classified OUT_OF_STACK / UNREACHABLE / WEEKLY_ONLY.
 *
 * Each one was probed live before being written; none needs a key, an
 * account or a paid tier.
 *
 *  - [TradingViewProvider]  COMEX forward curve *with daily open interest*,
 *                           MCX India and SHFE China gold futures, the real
 *                           DXY index and the Treasury yield quotes.
 *  - [KrakenProvider]       Full L2 order book and daily history for PAXG/USD
 *                           (one token = one fine troy ounce of allocated
 *                           gold), i.e. a real gold depth-of-book.
 *  - [OkxProvider]          Second independent gold order book (XAUT/USDT).
 *  - [SwissquoteProvider]   OTC bid/ask by size tier — institutional depth.
 *  - [SinaProvider]         Shanghai Gold Exchange Au(T+D) in CNY per gram.
 *  - [ForexFactoryProvider] Economic calendar with consensus forecasts.
 *  - [BlsProvider]          Official U.S. actuals (CPI, unemployment).
 *  - [GoldPriceOrgProvider] Independent spot cross-check.
 *  - [WorldGoldCouncilProvider] LBMA-based benchmark history from the WGC.
 */

/* ------------------------------------------------------------------ */
/* TradingView screener                                                */
/* ------------------------------------------------------------------ */

data class TvQuote(
    val ticker: String,
    val name: String,
    val description: String?,
    val close: Double?,
    val openInterest: Double?,
    val volume: Double?,
    val currency: String?,
    val expiration: Int?
)

class TradingViewProvider(
    private val http: HttpClient,
    private val log: DiagnosticLog = DiagnosticLog.shared
) {
    companion object {
        const val ID = "TRADINGVIEW"
        const val URL = "https://scanner.tradingview.com/global/scan"

        /** COMEX gold delivery months, nearest first. Probed live: all quote. */
        val GOLD_CURVE = listOf(
            "COMEX:GCV2026", "COMEX:GCX2026", "COMEX:GCZ2026",
            "COMEX:GCG2027", "COMEX:GCJ2027", "COMEX:GCM2027",
            "COMEX:GCQ2027", "COMEX:GCZ2027"
        )

        val CORE = listOf(
            "COMEX:GC1!",      // front continuous, carries daily open interest
            "COMEX:SI1!",
            "COMEX:HG1!",
            "NYMEX:CL1!",
            "MCX:GOLD1!",      // India, INR per 10 g
            "SHFE:AU1!",       // China, CNY per gram
            "TVC:DXY",         // the real dollar index, not a basket proxy
            "TVC:US10Y",
            "TVC:US02Y",
            "TVC:VIX",
            "TVC:GOLD",
            "OANDA:XAUUSD",
            "FX_IDC:XAUUSD"
        )

        /**
         * SPEC v2.1 §25.3 — the policy path the market is actually paying for,
         * the volatility of the bond market, the sovereign curves gold is
         * priced against outside the dollar, and the competing store of value.
         * All probed live on this screener; none needs a second provider.
         */
        val MACRO = listOf(
            "CBOT:ZQ1!",       // 30-day fed funds futures, front month
            "CBOT:ZQZ2026",
            "CBOT:ZQH2027",
            "CBOT:ZQM2027",    // ~12 months out: the priced policy rate
            "TVC:MOVE",        // ICE BofA bond-market option volatility
            "TVC:DE10Y",
            "TVC:JP10Y",
            "TVC:GB10Y",
            "TVC:CN10Y",
            "CRYPTO:BTCUSD"
        )
    }

    private val columns = listOf(
        "name", "description", "close", "open_interest", "volume", "currency", "expiration"
    )

    fun quotes(tickers: List<String>): Map<String, TvQuote> {
        if (tickers.isEmpty()) return emptyMap()
        val body = buildString {
            append("{\"symbols\":{\"tickers\":[")
            append(tickers.joinToString(",") { JsonWriter.str(it) })
            append("],\"query\":{\"types\":[]}},\"columns\":[")
            append(columns.joinToString(",") { JsonWriter.str(it) })
            append("]}")
        }
        val json = http.postJson(ID, URL, body) ?: run {
            log.error(LogStage.NETWORK, ID, "SCAN_FAILED", "screener returned no payload")
            return emptyMap()
        }
        val rows = json["data"]?.asArray.orEmpty()
        if (rows.isEmpty()) {
            log.warn(LogStage.PARSE, ID, "SCAN_EMPTY", "screener answered with zero rows")
            return emptyMap()
        }
        val out = LinkedHashMap<String, TvQuote>()
        rows.forEach { row ->
            val symbol = row["s"]?.asString ?: return@forEach
            val d = row["d"]?.asArray ?: return@forEach
            fun at(i: Int) = d.getOrNull(i)
            out[symbol] = TvQuote(
                ticker = symbol,
                name = at(0)?.asString ?: symbol,
                description = at(1)?.asString,
                close = at(2)?.asDouble,
                openInterest = at(3)?.asDouble,
                volume = at(4)?.asDouble,
                currency = at(5)?.asString,
                expiration = at(6)?.asDouble?.toInt()
            )
        }
        val missing = tickers - out.keys
        if (missing.isNotEmpty()) {
            log.warn(
                LogStage.PARSE, ID, "SYMBOLS_UNQUOTED",
                "${missing.size} symbol(s) not quoted", detail = missing.joinToString()
            )
        }
        log.info(LogStage.NETWORK, ID, "SCAN_OK", "${out.size}/${tickers.size} symbols quoted")
        return out
    }

    /** Dated COMEX gold curve, ordered by expiry. */
    fun goldCurve(quotes: Map<String, TvQuote>): List<Pair<String, Double>> =
        GOLD_CURVE.mapNotNull { t ->
            val q = quotes[t] ?: return@mapNotNull null
            val c = q.close ?: return@mapNotNull null
            q.name to c
        }
}

/* ------------------------------------------------------------------ */
/* Order books                                                         */
/* ------------------------------------------------------------------ */

/** One side-aggregated view of a limit order book. */
data class OrderBookDepth(
    val venue: String,
    val instrument: String,
    val bestBid: Double,
    val bestAsk: Double,
    val bidVolume: Double,
    val askVolume: Double,
    val bidLevels: Int,
    val askLevels: Int,
    val asOf: Instant
) {
    val mid: Double get() = (bestBid + bestAsk) / 2.0
    val spreadBp: Double get() = if (mid > 0) (bestAsk - bestBid) / mid * 10_000.0 else 0.0

    /** (bid − ask) / (bid + ask) over the whole captured depth, in [-1, 1]. */
    val imbalance: Double
        get() {
            val total = bidVolume + askVolume
            return if (total > 0) (bidVolume - askVolume) / total else 0.0
        }
}

class KrakenProvider(
    private val http: HttpClient,
    private val log: DiagnosticLog = DiagnosticLog.shared
) {
    companion object {
        const val ID = "KRAKEN"
        const val PAIR = "PAXGUSD"
    }

    /** Depth of book for allocated gold. 1 PAXG = 1 fine troy ounce. */
    fun depth(levels: Int = 100): OrderBookDepth? {
        val json = http.getJson(ID, "https://api.kraken.com/0/public/Depth?pair=$PAIR&count=$levels")
            ?: return null
        val err = json["error"]?.asArray.orEmpty()
        if (err.isNotEmpty()) {
            log.error(
                LogStage.PARSE, ID, "VENUE_ERROR", "Kraken returned an error",
                key = "GOLD_ORDER_BOOK", detail = err.mapNotNull { it.asString }.joinToString()
            )
            return null
        }
        val book = json["result"]?.asObject?.values?.firstOrNull() ?: run {
            log.warn(LogStage.PARSE, ID, "BOOK_MISSING", "no result block", key = "GOLD_ORDER_BOOK")
            return null
        }
        val bids = book["bids"]?.asArray.orEmpty()
        val asks = book["asks"]?.asArray.orEmpty()
        if (bids.isEmpty() || asks.isEmpty()) {
            log.warn(LogStage.PARSE, ID, "BOOK_EMPTY", "one side of the book is empty", key = "GOLD_ORDER_BOOK")
            return null
        }
        fun px(e: Json?) = e?.get(0)?.asDouble
        fun qty(e: Json?) = e?.get(1)?.asDouble ?: 0.0
        val bestBid = px(bids.firstOrNull()) ?: return null
        val bestAsk = px(asks.firstOrNull()) ?: return null
        return OrderBookDepth(
            venue = "Kraken",
            instrument = "PAXG/USD (allocated gold, 1 token = 1 oz)",
            bestBid = bestBid,
            bestAsk = bestAsk,
            bidVolume = bids.sumOf { qty(it) },
            askVolume = asks.sumOf { qty(it) },
            bidLevels = bids.size,
            askLevels = asks.size,
            asOf = Instant.now()
        )
    }

    /** Daily OHLC for gold itself rather than an ETF wrapper. */
    fun dailyHistory(): TimeSeries? {
        val json = http.getJson(ID, "https://api.kraken.com/0/public/OHLC?pair=$PAIR&interval=1440")
            ?: return null
        val rows = json["result"]?.asObject
            ?.entries?.firstOrNull { it.key != "last" }?.value?.asArray.orEmpty()
        if (rows.isEmpty()) {
            log.warn(LogStage.PARSE, KrakenProvider.ID, "OHLC_EMPTY", "no candles", key = "GOLD_SPOT_HISTORY")
            return null
        }
        val bars = rows.mapNotNull { r ->
            val t = r[0]?.asDouble ?: return@mapNotNull null
            Bar(
                timestamp = Instant.ofEpochSecond(t.toLong()),
                open = r[1]?.asDouble ?: return@mapNotNull null,
                high = r[2]?.asDouble ?: return@mapNotNull null,
                low = r[3]?.asDouble ?: return@mapNotNull null,
                close = r[4]?.asDouble ?: return@mapNotNull null,
                volume = r[6]?.asDouble ?: 0.0
            )
        }
        return if (bars.isEmpty()) null else TimeSeries("GOLD_SPOT_HISTORY", bars)
    }

    /** Recent best bid/ask ticks — a realised spread series. */
    fun spreadBpSeries(): List<Double> {
        val json = http.getJson(ID, "https://api.kraken.com/0/public/Spread?pair=$PAIR") ?: return emptyList()
        val rows = json["result"]?.asObject
            ?.entries?.firstOrNull { it.key != "last" }?.value?.asArray.orEmpty()
        return rows.mapNotNull { r ->
            val b = r[1]?.asDouble ?: return@mapNotNull null
            val a = r[2]?.asDouble ?: return@mapNotNull null
            val mid = (a + b) / 2.0
            if (mid > 0) (a - b) / mid * 10_000.0 else null
        }
    }
}

class OkxProvider(
    private val http: HttpClient,
    private val log: DiagnosticLog = DiagnosticLog.shared
) {
    companion object {
        const val ID = "OKX"
        const val INST = "XAUT-USDT"
    }

    fun depth(levels: Int = 100): OrderBookDepth? {
        val json = http.getJson(ID, "https://www.okx.com/api/v5/market/books?instId=$INST&sz=$levels")
            ?: return null
        if (json["code"]?.asString != "0") {
            log.error(
                LogStage.PARSE, ID, "VENUE_ERROR", json["msg"]?.asString ?: "non-zero code",
                key = "GOLD_ORDER_BOOK_2"
            )
            return null
        }
        val d = json["data"]?.get(0) ?: return null
        val bids = d["bids"]?.asArray.orEmpty()
        val asks = d["asks"]?.asArray.orEmpty()
        if (bids.isEmpty() || asks.isEmpty()) return null
        val bestBid = bids[0][0]?.asDouble ?: return null
        val bestAsk = asks[0][0]?.asDouble ?: return null
        return OrderBookDepth(
            venue = "OKX",
            instrument = "XAUT/USDT (Tether Gold, 1 token = 1 oz)",
            bestBid = bestBid,
            bestAsk = bestAsk,
            bidVolume = bids.sumOf { it[1]?.asDouble ?: 0.0 },
            askVolume = asks.sumOf { it[1]?.asDouble ?: 0.0 },
            bidLevels = bids.size,
            askLevels = asks.size,
            asOf = Instant.now()
        )
    }

    fun dailyHistory(limit: Int = 300): TimeSeries? {
        val json = http.getJson(
            ID, "https://www.okx.com/api/v5/market/history-candles?instId=$INST&bar=1D&limit=$limit"
        ) ?: return null
        val rows = json["data"]?.asArray.orEmpty()
        val bars = rows.mapNotNull { r ->
            val ms = r[0]?.asDouble ?: return@mapNotNull null
            Bar(
                timestamp = Instant.ofEpochMilli(ms.toLong()),
                open = r[1]?.asDouble ?: return@mapNotNull null,
                high = r[2]?.asDouble ?: return@mapNotNull null,
                low = r[3]?.asDouble ?: return@mapNotNull null,
                close = r[4]?.asDouble ?: return@mapNotNull null,
                volume = r[5]?.asDouble ?: 0.0
            )
        }.sortedBy { it.timestamp }
        return if (bars.isEmpty()) null else TimeSeries("GOLD_SPOT_HISTORY_2", bars)
    }
}

/** OTC quotes banded by trade size — the closest free analogue of depth. */
data class SizeTierQuote(val tier: String, val bid: Double, val ask: Double) {
    val spreadBp: Double get() = if (bid + ask > 0) (ask - bid) / ((ask + bid) / 2.0) * 10_000.0 else 0.0
}

class SwissquoteProvider(
    private val http: HttpClient,
    private val log: DiagnosticLog = DiagnosticLog.shared
) {
    companion object {
        const val ID = "SWISSQUOTE"
        const val URL = "https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/XAU/USD"
    }

    fun tiers(): List<SizeTierQuote> {
        val json = http.getJson(ID, URL) ?: return emptyList()
        val platforms = json.asArray
        if (platforms.isEmpty()) {
            log.warn(LogStage.PARSE, ID, "NO_PLATFORMS", "empty quote array", key = "GOLD_OTC_DEPTH")
            return emptyList()
        }
        val out = LinkedHashMap<String, SizeTierQuote>()
        platforms.forEach { p ->
            p["spreadProfilePrices"]?.asArray.orEmpty().forEach { sp ->
                val tier = sp["spreadProfile"]?.asString ?: return@forEach
                val bid = sp["bid"]?.asDouble ?: return@forEach
                val ask = sp["ask"]?.asDouble ?: return@forEach
                val existing = out[tier]
                if (existing == null || (ask - bid) < (existing.ask - existing.bid)) {
                    out[tier] = SizeTierQuote(tier, bid, ask)
                }
            }
        }
        return out.values.sortedBy { it.spreadBp }
    }
}

/* ------------------------------------------------------------------ */
/* Regional physical markets                                           */
/* ------------------------------------------------------------------ */

/** Shanghai Gold Exchange Au(T+D), quoted in CNY per gram. */
class SinaProvider(
    private val http: HttpClient,
    private val log: DiagnosticLog = DiagnosticLog.shared
) {
    companion object {
        const val ID = "SINA_SGE"
        const val URL = "https://hq.sinajs.cn/list=gds_AUTD"

        /** Same quote from Eastmoney, used when Sina refuses the request. */
        const val BACKUP_ID = "EASTMONEY_SGE"
        const val BACKUP_URL =
            "https://push2.eastmoney.com/api/qt/stock/get?secid=118.AUTD&fields=f43,f57,f58"
        const val GRAMS_PER_TROY_OUNCE = 31.1034768

        private val REFERER = mapOf(
            "Referer" to "https://finance.sina.com.cn/",
            "Accept" to "*/*"
        )
    }

    /** CNY per gram from the primary feed, else from the backup, else null. */
    fun shanghaiGoldCnyPerGram(): Double? = sina() ?: eastmoney()

    private fun sina(): Double? {
        val text = http.getText(ID, URL, REFERER) ?: return null
        val payload = text.substringAfter('"', "").substringBefore('"')
        if (payload.isBlank()) {
            log.warn(LogStage.PARSE, ID, "QUOTE_UNPARSED", "no quoted payload", key = "SGE_AUTD")
            return null
        }
        val fields = payload.split(',')
        // Field 0 is the last traded price; fields 3/4 are the day's high/low.
        val last = fields.getOrNull(0)?.trim()?.toDoubleOrNull()
        if (last == null || last <= 0.0) {
            log.warn(
                LogStage.PARSE, ID, "QUOTE_NOT_NUMERIC", "price field is not a number",
                key = "SGE_AUTD", detail = payload.take(120)
            )
            return null
        }
        return last
    }

    /**
     * Eastmoney publishes the same Au(T+D) last price as an integer in units
     * of 0.01 CNY per gram.
     */
    private fun eastmoney(): Double? {
        val json = http.getJson(BACKUP_ID, BACKUP_URL) ?: return null
        val raw = json["data"]?.get("f43")?.asDouble
        if (raw == null || raw <= 0.0) {
            log.warn(
                LogStage.PARSE, BACKUP_ID, "BACKUP_QUOTE_ABSENT",
                "Eastmoney returned no Au(T+D) price", key = "SGE_AUTD"
            )
            return null
        }
        val price = raw / 100.0
        log.info(
            LogStage.NETWORK, BACKUP_ID, "BACKUP_USED",
            "Sina unavailable; Au(T+D) taken from Eastmoney at $price CNY/g", key = "SGE_AUTD"
        )
        return price
    }
}

/* ------------------------------------------------------------------ */
/* Calendar and official actuals                                       */
/* ------------------------------------------------------------------ */

data class CalendarEvent(
    val title: String,
    val country: String,
    val time: Instant,
    val impact: String,
    val forecast: String?,
    val previous: String?
) {
    val highImpact: Boolean get() = impact.equals("High", true)
    val forecastValue: Double? get() = numeric(forecast)
    val previousValue: Double? get() = numeric(previous)

    private fun numeric(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val cleaned = s.trim().removeSuffix("%").replace(",", "")
        val scale = when {
            cleaned.endsWith("K", true) -> 1_000.0
            cleaned.endsWith("M", true) -> 1_000_000.0
            cleaned.endsWith("B", true) -> 1_000_000_000.0
            cleaned.endsWith("T", true) -> 1_000_000_000_000.0
            else -> 1.0
        }
        val core = if (scale > 1.0) cleaned.dropLast(1) else cleaned
        return core.toDoubleOrNull()?.times(scale)
    }
}

/**
 * Consensus forecasts for the scheduled macro calendar. This is the free
 * replacement for a terminal consensus feed: `forecast` is the surveyed
 * market expectation published ahead of the release.
 */
class ForexFactoryProvider(
    private val http: HttpClient,
    private val log: DiagnosticLog = DiagnosticLog.shared
) {
    companion object {
        const val ID = "FOREXFACTORY"
        const val URL = "https://nfs.faireconomy.media/ff_calendar_thisweek.json"
    }

    fun events(): List<CalendarEvent> {
        val json = http.getJson(ID, URL) ?: return emptyList()
        val rows = json.asArray
        if (rows.isEmpty()) {
            log.warn(LogStage.PARSE, ID, "CALENDAR_EMPTY", "no events in payload", key = "ECONOMIC_CALENDAR")
            return emptyList()
        }
        var unparsed = 0
        val out = rows.mapNotNull { r ->
            val title = r["title"]?.asString ?: return@mapNotNull null
            val raw = r["date"]?.asString ?: return@mapNotNull null
            val t = try {
                java.time.OffsetDateTime.parse(raw).toInstant()
            } catch (_: Exception) {
                unparsed++
                return@mapNotNull null
            }
            CalendarEvent(
                title = title,
                country = r["country"]?.asString ?: "",
                time = t,
                impact = r["impact"]?.asString ?: "Unknown",
                forecast = r["forecast"]?.asString?.takeIf { it.isNotBlank() },
                previous = r["previous"]?.asString?.takeIf { it.isNotBlank() }
            )
        }
        if (unparsed > 0) {
            log.warn(
                LogStage.PARSE, ID, "DATE_UNPARSED", "$unparsed event(s) had an unreadable timestamp",
                key = "ECONOMIC_CALENDAR"
            )
        }
        log.info(LogStage.NETWORK, ID, "CALENDAR_OK", "${out.size} events")
        return out.sortedBy { it.time }
    }
}

/** Official U.S. statistics. The public v1 endpoint needs no registration. */
class BlsProvider(
    private val http: HttpClient,
    private val log: DiagnosticLog = DiagnosticLog.shared
) {
    companion object {
        const val ID = "BLS"
        const val CPI_ALL_URBAN = "CUUR0000SA0"
        const val UNEMPLOYMENT = "LNS14000000"
    }

    data class Observation(val year: Int, val period: String, val value: Double)

    fun series(seriesId: String): List<Observation> {
        val json = http.getJson(ID, "https://api.bls.gov/publicAPI/v1/timeseries/data/$seriesId")
            ?: return emptyList()
        if (json["status"]?.asString != "REQUEST_SUCCEEDED") {
            log.error(
                LogStage.PARSE, ID, "REQUEST_REFUSED",
                json["status"]?.asString ?: "unknown status", key = seriesId
            )
            return emptyList()
        }
        val data = json["Results"]?.get("series")?.get(0)?.get("data")?.asArray.orEmpty()
        return data.mapNotNull { d ->
            val y = d["year"]?.asString?.toIntOrNull() ?: return@mapNotNull null
            val p = d["period"]?.asString ?: return@mapNotNull null
            val v = d["value"]?.asDouble ?: return@mapNotNull null
            Observation(y, p, v)
        }.sortedWith(compareBy({ it.year }, { it.period }))
    }

    /** Year-over-year percentage change of the headline CPI index. */
    fun cpiYoY(): Double? {
        val s = series(CPI_ALL_URBAN)
        if (s.size < 13) {
            log.warn(LogStage.QUALITY, ID, "SERIES_TOO_SHORT", "need 13 monthly points, got ${s.size}", key = "CPI_YOY")
            return null
        }
        val last = s.last()
        val yearAgo = s.firstOrNull { it.year == last.year - 1 && it.period == last.period }
            ?: return null
        if (yearAgo.value <= 0) return null
        return (last.value / yearAgo.value - 1.0) * 100.0
    }

    fun unemploymentRate(): Double? = series(UNEMPLOYMENT).lastOrNull()?.value
}

/* ------------------------------------------------------------------ */
/* Independent spot and benchmark history                              */
/* ------------------------------------------------------------------ */

class GoldPriceOrgProvider(private val http: HttpClient) {
    companion object {
        const val ID = "GOLDPRICE_ORG"
        const val URL = "https://data-asg.goldprice.org/dbXRates/USD"
    }

    data class Quote(val gold: Double, val silver: Double, val asOf: Instant)

    fun latest(): Quote? {
        val json = http.getJson(ID, URL, mapOf("Referer" to "https://goldprice.org/")) ?: return null
        val item = json["items"]?.get(0) ?: return null
        val xau = item["xauPrice"]?.asDouble ?: return null
        val xag = item["xagPrice"]?.asDouble ?: return null
        val ts = json["ts"]?.asDouble?.toLong()
        return Quote(xau, xag, ts?.let { Instant.ofEpochMilli(it) } ?: Instant.now())
    }
}

/**
 * World Gold Council price series. The WGC publishes the LBMA-based monthly
 * benchmark back to 1970, which is the reference the specification wanted the
 * LBMA feed for.
 */
class WorldGoldCouncilProvider(
    private val http: HttpClient,
    private val log: DiagnosticLog = DiagnosticLog.shared
) {
    companion object {
        const val ID = "WGC"
        const val URL = "https://fsapi.gold.org/api/goldprice/v11/chart/price/USD/max/false"
    }

    data class Benchmark(val series: TimeSeries, val asOfDate: LocalDate?)

    fun benchmark(): Benchmark? {
        val json = http.getJson(ID, URL) ?: return null
        val points = json["chartData"]?.get("USD")?.asArray.orEmpty()
        if (points.isEmpty()) {
            log.warn(LogStage.PARSE, ID, "BENCHMARK_EMPTY", "no price points", key = "LBMA_BENCHMARK")
            return null
        }
        val bars = points.mapNotNull { p ->
            val ms = p[0]?.asDouble ?: return@mapNotNull null
            val v = p[1]?.asDouble ?: return@mapNotNull null
            Bar(Instant.ofEpochMilli(ms.toLong()), v, v, v, v, 0.0)
        }
        val asOf = json["chartData"]?.get("asOfDate")?.asString?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
        return Benchmark(TimeSeries("LBMA_BENCHMARK", bars), asOf)
    }
}

/** Shared helper: UTC date of an instant, used when aligning daily series. */
internal fun Instant.utcDate(): LocalDate = atZone(ZoneOffset.UTC).toLocalDate()
