package io.goldintelligence.client

import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.LogStage
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * SPEC v2.1 §22 — broker feed for the live chart.
 *
 * The quote and the forming bar of every timeframe are read from the public
 * TradingView screener, which answers for the retail FX venues without a key.
 * The venue is selectable because the same instrument prints slightly
 * differently at each broker, and the chart must name the one it is drawing.
 */
class BrokerFeedProvider(
    private val http: HttpClient,
    private val log: DiagnosticLog = DiagnosticLog.shared
) {
    companion object {
        const val ID = "TRADINGVIEW_FEED"
        const val URL = "https://scanner.tradingview.com/global/scan"

        /** Beyond this, the venue's quoted spread is not describing the last price. */
        const val MAX_BIDASK_DEVIATION_BP = 25.0

        /** Verified live: all three quote XAU/USD in streaming mode. */
        val VENUES: List<Venue> = listOf(
            Venue("OANDA", "OANDA:XAUUSD", "OANDA — XAU/USD"),
            Venue("FXCM", "FX:XAUUSD", "FXCM — XAU/USD Spot"),
            Venue("FOREXCOM", "FOREXCOM:XAUUSD", "FOREX.com — Gold / U.S. Dollar")
        )

        val DEFAULT: Venue = VENUES.first()

        fun venue(id: String?): Venue =
            VENUES.firstOrNull { it.id.equals(id, true) || it.ticker.equals(id, true) } ?: DEFAULT
    }

    data class Venue(val id: String, val ticker: String, val label: String)

    /** The forming bar of one timeframe, as currently printed by the venue. */
    data class FormingBar(
        val timeframe: ChartTimeframe,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double
    )

    data class Snapshot(
        val venue: Venue,
        val quote: LiveQuote?,
        val bars: Map<ChartTimeframe, FormingBar>,
        val failureCode: String?
    )

    /**
     * One request returns the quote and the forming bar of every timeframe,
     * so the chart cannot show a price that disagrees with its own last bar.
     */
    fun snapshot(venue: Venue = DEFAULT, timeframes: List<ChartTimeframe> = ChartTimeframe.entries): Snapshot {
        val quoteColumns = listOf(
            "close", "open", "high", "low", "change", "change_abs",
            "bid", "ask", "volume", "update_mode", "time"
        )
        val barColumns = timeframes.flatMap { tf ->
            listOf("open${tf.tvSuffix}", "high${tf.tvSuffix}", "low${tf.tvSuffix}", "close${tf.tvSuffix}")
        }
        val columns = quoteColumns + barColumns
        val body = buildString {
            append("{\"symbols\":{\"tickers\":[").append(JsonWriter.str(venue.ticker))
            append("],\"query\":{\"types\":[]}},\"columns\":[")
            append(columns.joinToString(",") { JsonWriter.str(it) })
            append("]}")
        }

        val json = http.postJson(ID, URL, body) ?: run {
            log.error(
                LogStage.CHART, ID, "FEED_UNAVAILABLE",
                "no payload from the chart feed for ${venue.ticker}",
                key = CHART_KEY, detail = venue.label
            )
            return Snapshot(venue, null, emptyMap(), "FEED_UNAVAILABLE")
        }
        val row = json["data"]?.get(0) ?: run {
            log.error(
                LogStage.CHART, ID, "SYMBOL_NOT_QUOTED",
                "${venue.ticker} is not quoted by the feed", key = CHART_KEY
            )
            return Snapshot(venue, null, emptyMap(), "SYMBOL_NOT_QUOTED")
        }
        val d = row["d"]?.asArray ?: return Snapshot(venue, null, emptyMap(), "PAYLOAD_SHAPE")
        fun num(i: Int): Double? = d.getOrNull(i)?.asDouble
        fun text(i: Int): String? = d.getOrNull(i)?.asString

        val now = Instant.now()
        val last = num(0)
        if (last == null || last <= 0.0) {
            log.error(
                LogStage.CHART, ID, "PRICE_ABSENT",
                "feed answered without a usable last price", key = CHART_KEY
            )
            return Snapshot(venue, null, emptyMap(), "PRICE_ABSENT")
        }
        // The venue's `time` column is a session stamp, not the age of the
        // print: it is kept as provenance and never used as the bar clock.
        val quotedAt = num(10)?.toLong()?.let { Instant.ofEpochSecond(it) } ?: now

        // The screener's bid/ask are cached independently of the last price.
        // When they disagree with it they are dropped rather than drawn.
        var bid = num(6)
        var ask = num(7)
        val mid = if (bid != null && ask != null) (bid + ask) / 2.0 else null
        if (mid != null && last > 0.0) {
            val deviationBp = kotlin.math.abs(mid - last) / last * 10_000.0
            if (deviationBp > MAX_BIDASK_DEVIATION_BP) {
                log.warn(
                    LogStage.CHART, ID, "BIDASK_STALE",
                    "venue bid/ask is %.1f bp away from the last price; dropped".format(deviationBp),
                    key = CHART_KEY,
                    detail = "last=$last bid=$bid ask=$ask"
                )
                bid = null
                ask = null
            }
        }
        val quote = LiveQuote(
            symbol = venue.ticker,
            venue = venue.label,
            last = last,
            bid = bid,
            ask = ask,
            open = num(1),
            high = num(2),
            low = num(3),
            changePct = num(4),
            changeAbs = num(5),
            volume = num(8),
            updateMode = text(9),
            quotedAt = quotedAt,
            receivedAt = now
        )

        val bars = LinkedHashMap<ChartTimeframe, FormingBar>()
        timeframes.forEachIndexed { index, tf ->
            val base = quoteColumns.size + index * 4
            val o = num(base)
            val h = num(base + 1)
            val l = num(base + 2)
            val c = num(base + 3)
            if (o == null || h == null || l == null || c == null) {
                log.warn(
                    LogStage.CHART, ID, "BAR_INCOMPLETE",
                    "venue did not print a complete ${tf.code} bar",
                    key = CHART_KEY + "_" + tf.code
                )
                return@forEachIndexed
            }
            bars[tf] = FormingBar(tf, o, h, l, c)
        }

        if (quote.updateMode != null && !quote.updateMode.equals("streaming", true)) {
            log.warn(
                LogStage.CHART, ID, "FEED_NOT_STREAMING",
                "venue is in ${quote.updateMode} mode, not streaming", key = CHART_KEY
            )
        }
        log.info(
            LogStage.CHART, ID, "FEED_OK",
            "${venue.label} last $last, ${bars.size}/${timeframes.size} timeframes printed",
            key = CHART_KEY
        )
        return Snapshot(venue, quote, bars, null)
    }

    /** Daily seed candles for an instrument that trades the same metal. */
    fun seedCandles(timeframe: ChartTimeframe, limit: Int = 240): List<Candle> {
        val url = "https://api.kraken.com/0/public/OHLC?pair=PAXGUSD&interval=${timeframe.seedMinutes}"
        val json = http.getJson(SEED_ID, url) ?: run {
            log.warn(
                LogStage.CHART, SEED_ID, "SEED_UNAVAILABLE",
                "no seed history for ${timeframe.code}; the chart starts from live bars only",
                key = CHART_KEY + "_" + timeframe.code
            )
            return emptyList()
        }
        val errors = json["error"]?.asArray.orEmpty().mapNotNull { it.asString }
        if (errors.isNotEmpty()) {
            log.warn(
                LogStage.CHART, SEED_ID, "SEED_VENUE_ERROR", errors.joinToString(),
                key = CHART_KEY + "_" + timeframe.code
            )
            return emptyList()
        }
        val rows = json["result"]?.asObject
            ?.entries?.firstOrNull { it.key != "last" }?.value?.asArray.orEmpty()
        if (rows.isEmpty()) {
            log.warn(
                LogStage.CHART, SEED_ID, "SEED_EMPTY", "seed venue returned no candles",
                key = CHART_KEY + "_" + timeframe.code
            )
            return emptyList()
        }
        val candles = rows.mapNotNull { r ->
            val t = r[0]?.asDouble?.toLong() ?: return@mapNotNull null
            val o = r[1]?.asString?.toDoubleOrNull() ?: return@mapNotNull null
            val h = r[2]?.asString?.toDoubleOrNull() ?: return@mapNotNull null
            val l = r[3]?.asString?.toDoubleOrNull() ?: return@mapNotNull null
            val c = r[4]?.asString?.toDoubleOrNull() ?: return@mapNotNull null
            Candle(Instant.ofEpochSecond(t), o, h, l, c, BarOrigin.SEED_REBASED)
        }
        log.debug(
            LogStage.CHART, SEED_ID, "SEED_OK",
            "${candles.size} ${timeframe.code} seed candles",
            key = CHART_KEY + "_" + timeframe.code
        )
        return candles.takeLast(limit)
    }

    private val warnedOnce = ConcurrentHashMap<String, Boolean>()

    /** Logs a condition once per process, for conditions that would otherwise repeat every refresh. */
    fun warnOnce(code: String, message: String) {
        if (warnedOnce.putIfAbsent(code, true) == null) {
            log.warn(LogStage.CHART, ID, code, message, key = CHART_KEY)
        }
    }
}

const val CHART_KEY = "CHART_FEED"
const val SEED_ID = "KRAKEN"
