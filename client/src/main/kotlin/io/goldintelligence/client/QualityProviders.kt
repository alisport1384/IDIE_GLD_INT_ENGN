package io.goldintelligence.client

import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.LogStage
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * SPEC v2.1 §23 — sources added to raise measured data quality.
 *
 * Everything here is keyless and free, and every one of them replaces a proxy
 * or an absent input with an official or primary measurement:
 *
 *  - [ImfReservesProvider]      official-sector gold holdings, monthly, per country (F10)
 *  - [EconomicCalendarProvider] scheduled events with consensus *and* actual (F05, F06, event clock)
 *  - [PolicyRateProvider]       central-bank policy rates, monthly (F03, F22)
 *  - [CoinbaseDepthProvider]    a third independent L2 gold book (F14)
 */

/* ------------------------------------------------------------------ */
/* IMF — International Reserves and Foreign Currency Liquidity         */
/* ------------------------------------------------------------------ */

/** One month of reported official gold holdings, in tonnes. */
data class ReserveObservation(val country: String, val period: YearMonth, val tonnes: Double)

data class OfficialGoldFlow(
    /** Net change in reported holdings over the last three reported months, tonnes. */
    val net3mTonnes: Double,
    /** That change expressed against its own 3-month-sum history. */
    val net3mZ: Double?,
    /** Buyers minus sellers over the last month, as a share of reporters, ×100. */
    val breadthIndex: Double,
    val reportingCountries: Int,
    val latestPeriod: YearMonth,
    val observations: Int
)

/**
 * Monthly official gold holdings straight from the IMF reserves template.
 *
 * This is the series the World Gold Council's quarterly tables are built from,
 * one publication step earlier: countries report monthly and the IMF releases
 * roughly three weeks after month end. Reading it directly removes the only
 * factor the engine had no free source for.
 *
 * Line 56 of the template is "gold (including gold deposits and, if
 * appropriate, gold swapped)", measured in fine troy ounces.
 */
class ImfReservesProvider(
    private val http: HttpClient,
    private val log: DiagnosticLog = DiagnosticLog.shared
) {
    fun officialGoldFlow(now: Instant = Instant.now(), monthsBack: Long = 42): OfficialGoldFlow? {
        val start = YearMonth.from(now.atZone(ZoneOffset.UTC)).minusMonths(monthsBack)
        val url = "$BASE?startPeriod=${start.year}-${"%02d".format(start.monthValue)}"
        val response = http.get(Providers.IMF_RESERVES.id, url, "application/xml")
        if (!response.ok) return null
        val body = response.body

        val rows = parse(body)
        if (rows.isEmpty()) {
            log.warn(
                LogStage.PARSE, Providers.IMF_RESERVES.id, "RESERVES_EMPTY",
                "the reserves template returned no usable observation", key = KEY
            )
            return null
        }

        val byCountry = rows.groupBy { it.country }
            .mapValues { (_, v) -> v.sortedBy { it.period } }
        val latest = rows.maxOf { it.period }
        val prior = latest.minusMonths(3)

        var net = 0.0
        var buyers = 0
        var sellers = 0
        var counted = 0
        byCountry.forEach { (_, series) ->
            val now3 = series.lastOrNull { it.period == latest } ?: return@forEach
            val then = series.lastOrNull { it.period == prior } ?: return@forEach
            net += now3.tonnes - then.tonnes
            counted++
            val previousMonth = series.lastOrNull { it.period == latest.minusMonths(1) }
            if (previousMonth != null) {
                val step = now3.tonnes - previousMonth.tonnes
                if (step > BREADTH_FLOOR_T) buyers++ else if (step < -BREADTH_FLOOR_T) sellers++
            }
        }
        if (counted == 0) {
            log.warn(
                LogStage.PARSE, Providers.IMF_RESERVES.id, "RESERVES_NO_PAIR",
                "no country reported both $prior and $latest", key = KEY
            )
            return null
        }

        val history = rollingThreeMonthSums(byCountry, latest)
        val z = zOf(net, history)
        val breadth = (buyers - sellers).toDouble() / counted * 100.0

        log.info(
            LogStage.QUALITY, Providers.IMF_RESERVES.id, "RESERVES_OK",
            "%.1f t net over %s→%s across %d reporting countries (breadth %+.0f)"
                .format(net, prior, latest, counted, breadth),
            key = KEY
        )
        return OfficialGoldFlow(
            net3mTonnes = net,
            net3mZ = z,
            breadthIndex = breadth.coerceIn(-100.0, 100.0),
            reportingCountries = counted,
            latestPeriod = latest,
            observations = rows.size
        )
    }

    /**
     * The feed carries occasional national scale errors — a holding restated
     * by a factor of a thousand mid-series, or a constant that implies more
     * gold than exists. Such a country is dropped and named, never rescaled:
     * inferring the intended scale would be guessing.
     */
    internal fun parse(xml: String): List<ReserveObservation> {
        val out = mutableListOf<ReserveObservation>()
        val rejected = linkedSetOf<String>()
        var cursor = 0
        while (true) {
            val seriesAt = xml.indexOf("<Series ", cursor)
            if (seriesAt < 0) break
            val seriesEnd = xml.indexOf("</Series>", seriesAt).let { if (it < 0) xml.length else it }
            val header = xml.substring(seriesAt, minOf(seriesAt + 400, xml.length))
            val country = attribute(header, "COUNTRY")
            cursor = seriesEnd + 1
            if (country == null) continue

            val block = xml.substring(seriesAt, seriesEnd)
            var obsCursor = 0
            val local = mutableListOf<ReserveObservation>()
            while (true) {
                val obsAt = block.indexOf("<Obs ", obsCursor)
                if (obsAt < 0) break
                val obsEnd = block.indexOf("/>", obsAt).let { if (it < 0) block.length else it }
                val obs = block.substring(obsAt, obsEnd)
                obsCursor = obsEnd + 1
                val period = attribute(obs, "TIME_PERIOD")?.let { parsePeriod(it) } ?: continue
                val ounces = attribute(obs, "OBS_VALUE")?.toDoubleOrNull() ?: continue
                val tonnes = ounces * TONNES_PER_OUNCE
                if (tonnes > MAX_PLAUSIBLE_TONNES || tonnes < 0.0) {
                    rejected += country
                    continue
                }
                local += ReserveObservation(country, period, tonnes)
            }
            // A thousand-fold restatement inside one country invalidates the
            // whole series: its deltas would be fiction either side of the break.
            val jumped = local.sortedBy { it.period }.zipWithNext()
                .any { (a, b) -> abs(b.tonnes - a.tonnes) > MAX_MONTHLY_STEP_T }
            if (jumped) {
                rejected += country
            } else {
                out += local
            }
        }
        if (rejected.isNotEmpty()) {
            log.warn(
                LogStage.QUALITY, Providers.IMF_RESERVES.id, "RESERVES_IMPLAUSIBLE",
                "dropped ${rejected.size} country series with an implausible scale: " +
                    rejected.joinToString(),
                key = KEY
            )
        }
        return out
    }

    private fun rollingThreeMonthSums(
        byCountry: Map<String, List<ReserveObservation>>,
        latest: YearMonth
    ): List<Double> {
        val sums = mutableListOf<Double>()
        var end = latest.minusMonths(1)
        repeat(HISTORY_WINDOWS) {
            val start = end.minusMonths(3)
            var total = 0.0
            var pairs = 0
            byCountry.forEach { (_, series) ->
                val a = series.lastOrNull { it.period == end }
                val b = series.lastOrNull { it.period == start }
                if (a != null && b != null) {
                    total += a.tonnes - b.tonnes
                    pairs++
                }
            }
            if (pairs > 0) sums += total
            end = end.minusMonths(1)
        }
        return sums
    }

    private fun zOf(value: Double, history: List<Double>): Double? {
        if (history.size < MIN_HISTORY) return null
        val mean = history.average()
        val variance = history.sumOf { (it - mean) * (it - mean) } / history.size
        val sd = sqrt(variance)
        return if (sd <= 1e-9) null else (value - mean) / sd
    }

    private fun parsePeriod(raw: String): YearMonth? = try {
        val cleaned = raw.replace("-M", "-")
        val parts = cleaned.split("-")
        if (parts.size < 2) null else YearMonth.of(parts[0].toInt(), parts[1].toInt())
    } catch (_: Exception) {
        null
    }

    private fun attribute(fragment: String, name: String): String? {
        val marker = "$name=\""
        val at = fragment.indexOf(marker)
        if (at < 0) return null
        val from = at + marker.length
        val to = fragment.indexOf('"', from)
        return if (to < 0) null else fragment.substring(from, to)
    }

    companion object {
        const val KEY = "CB_GOLD_RESERVES"
        const val BASE =
            "https://api.imf.org/external/sdmx/2.1/data/IRFCL/.IRFCLDT1_IRFCL56V_FTO..M"

        /** 1 fine troy ounce = 31.1034768 g. */
        const val TONNES_PER_OUNCE = 31.1034768 / 1_000_000.0

        /** Above the largest holding that has ever existed; a reporting error. */
        const val MAX_PLAUSIBLE_TONNES = 9_000.0

        /** No central bank has ever moved this much gold in a single month. */
        const val MAX_MONTHLY_STEP_T = 400.0

        /** A country must move more than this to count as a buyer or a seller. */
        const val BREADTH_FLOOR_T = 0.1

        const val HISTORY_WINDOWS = 24
        const val MIN_HISTORY = 8
    }
}

/* ------------------------------------------------------------------ */
/* Economic calendar with released actuals                             */
/* ------------------------------------------------------------------ */

data class CalendarRelease(
    val indicator: String,
    val country: String,
    val time: Instant,
    val importance: Int,
    val actual: Double,
    val forecast: Double,
    val previous: Double?
) {
    /**
     * Surprise normalised by the size of the expectation, so a 0.1 pp miss on
     * a 2 % forecast and a 10 k miss on a 200 k forecast are comparable.
     */
    val normalised: Double
        get() {
            val scale = max(abs(forecast), max(abs(previous ?: 0.0), 1e-6))
            return ((actual - forecast) / scale).coerceIn(-1.0, 1.0)
        }
}

data class CalendarSnapshot(
    val events: List<CalendarEvent>,
    val releases: List<CalendarRelease>
) {
    fun hoursToNext(now: Instant, highOnly: Boolean = true): Double? = events
        .filter { it.time.isAfter(now) && (!highOnly || it.highImpact) }
        .minByOrNull { it.time }
        ?.let { Duration.between(now, it.time).toMinutes() / 60.0 }

    fun highImpactWithin(now: Instant, hours: Long): Int {
        val until = now.plus(Duration.ofHours(hours))
        return events.count { it.highImpact && it.time.isAfter(now) && it.time.isBefore(until) }
    }

    /**
     * The surprise carried by the most recent releases that actually matter:
     * the mean over the last [count] high-importance prints. This is the
     * "consensus surprise" in its literal sense — what was printed against
     * what was forecast — and needs no second source to pair against.
     */
    fun latestConsensusSurprise(minImportance: Int = 1, count: Int = 3): Double? {
        val used = releases.filter { it.importance >= minImportance }.takeLast(count)
        if (used.isEmpty()) return null
        return used.map { it.normalised }.average()
    }

    /** Mean normalised surprise over the recent releases, in [-100, 100]. */
    fun surpriseIndex(minImportance: Int = 0, filter: (CalendarRelease) -> Boolean = { true }): Double? {
        val used = releases.filter { it.importance >= minImportance && filter(it) }
        if (used.size < MIN_RELEASES) return null
        return used.map { it.normalised }.average() * 100.0
    }

    companion object {
        const val MIN_RELEASES = 5
    }
}

/**
 * The public economic calendar behind TradingView's own calendar page.
 *
 * Unlike the weekly file the engine used before, every past row carries the
 * **actual** print next to the consensus, which turns the economic-surprise
 * and inflation-surprise inputs from market-implied proxies into measured
 * values, and gives an exact clock to the next scheduled release.
 */
class EconomicCalendarProvider(
    private val http: HttpClient,
    private val log: DiagnosticLog = DiagnosticLog.shared
) {
    fun snapshot(now: Instant = Instant.now()): CalendarSnapshot? {
        val from = now.minus(Duration.ofDays(LOOKBACK_DAYS))
        val to = now.plus(Duration.ofDays(LOOKAHEAD_DAYS))
        val url = "$BASE?from=${iso(from)}&to=${iso(to)}&countries=$COUNTRIES"
        val json = http.getJson(Providers.CALENDAR.id, url, headers = HEADERS) ?: return null
        val result = json["result"]?.asArray.orEmpty()
        if (result.isEmpty()) {
            log.warn(
                LogStage.PARSE, Providers.CALENDAR.id, "CALENDAR_EMPTY",
                "the calendar returned no event", key = KEY
            )
            return null
        }

        val events = mutableListOf<CalendarEvent>()
        val releases = mutableListOf<CalendarRelease>()
        result.forEach { e ->
            val time = e["date"]?.asString?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: return@forEach
            val indicator = e["indicator"]?.asString ?: e["title"]?.asString ?: return@forEach
            val country = e["country"]?.asString ?: "?"
            val importance = e["importance"]?.asDouble?.toInt() ?: 0
            val actual = e["actual"]?.asDouble
            val forecast = e["forecast"]?.asDouble
            val previous = e["previous"]?.asDouble

            events += CalendarEvent(
                title = e["title"]?.asString ?: indicator,
                country = country,
                time = time,
                impact = when {
                    importance >= 1 -> "High"
                    importance == 0 -> "Medium"
                    else -> "Low"
                },
                forecast = forecast?.toString(),
                previous = previous?.toString()
            )
            if (actual != null && forecast != null && time.isBefore(now)) {
                releases += CalendarRelease(indicator, country, time, importance, actual, forecast, previous)
            }
        }

        log.info(
            LogStage.NETWORK, Providers.CALENDAR.id, "CALENDAR_OK",
            "${events.size} scheduled events, ${releases.size} with a released actual",
            key = KEY
        )
        return CalendarSnapshot(events.sortedBy { it.time }, releases.sortedBy { it.time })
    }

    private fun iso(at: Instant): String =
        at.atZone(ZoneOffset.UTC).toLocalDateTime().toString().take(19) + ".000Z"

    companion object {
        const val KEY = "CALENDAR_RELEASES"
        const val BASE = "https://economic-calendar.tradingview.com/events"
        const val COUNTRIES = "US,EU,CN,GB,JP"
        const val LOOKBACK_DAYS = 30L
        const val LOOKAHEAD_DAYS = 14L
        val HEADERS = mapOf(
            "Origin" to "https://www.tradingview.com",
            "Referer" to "https://www.tradingview.com/"
        )
    }
}

/* ------------------------------------------------------------------ */
/* BIS — central bank policy rates                                     */
/* ------------------------------------------------------------------ */

data class PolicyRates(
    val rates: Map<String, Double>,
    val changes12m: Map<String, Double>,
    val asOf: String
) {
    val us: Double? get() = rates["US"]

    /** US policy rate minus the mean of the other reporting majors. */
    val divergence: Double?
        get() {
            val u = us ?: return null
            val peers = rates.filterKeys { it != "US" }.values
            return if (peers.isEmpty()) null else u - peers.average()
        }

    /** How that gap has moved over a year: the direction of the divergence. */
    val divergenceChange12m: Double?
        get() {
            val u = changes12m["US"] ?: return null
            val peers = changes12m.filterKeys { it != "US" }.values
            return if (peers.isEmpty()) null else u - peers.average()
        }
}

/**
 * Policy rates as published by the BIS, sourced from the central banks
 * themselves. Replaces the dollar-trend proxy that stood in for global policy
 * divergence with the rates the divergence is actually made of.
 */
class PolicyRateProvider(
    private val http: HttpClient,
    private val log: DiagnosticLog = DiagnosticLog.shared
) {
    fun rates(): PolicyRates? {
        val json = http.getJson(Providers.BIS.id, URL, headers = HEADERS) ?: return null
        val root = json["data"] ?: json
        val structure = root["structure"] ?: return null
        val areaValues = structure["dimensions"]?.get("series")?.asArray
            ?.firstOrNull { it["id"]?.asString == "REF_AREA" }
            ?.get("values")?.asArray
            ?.mapNotNull { it["id"]?.asString }
            ?: return null
        val periods = structure["dimensions"]?.get("observation")?.asArray
            ?.firstOrNull()?.get("values")?.asArray
            ?.mapNotNull { it["id"]?.asString }
            ?: return null
        val series = root["dataSets"]?.get(0)?.get("series")?.asObject ?: return null

        val latest = mutableMapOf<String, Double>()
        val change = mutableMapOf<String, Double>()
        series.forEach { (key, node) ->
            val areaIndex = key.split(":").getOrNull(1)?.toIntOrNull() ?: return@forEach
            val area = areaValues.getOrNull(areaIndex) ?: return@forEach
            val observations = node["observations"]?.asObject ?: return@forEach
            val points = observations.mapNotNull { (idx, arr) ->
                val i = idx.toIntOrNull() ?: return@mapNotNull null
                val v = arr[0]?.asDouble ?: return@mapNotNull null
                i to v
            }.sortedBy { it.first }
            if (points.isEmpty()) return@forEach
            val label = LABELS[area] ?: area
            latest[label] = points.last().second
            val yearAgo = points.firstOrNull { it.first <= points.last().first - 12 }
            if (yearAgo != null) change[label] = points.last().second - yearAgo.second
        }
        if (latest.isEmpty()) return null

        val asOf = periods.lastOrNull().orEmpty()
        log.info(
            LogStage.NETWORK, Providers.BIS.id, "POLICY_RATES_OK",
            latest.entries.joinToString { "${it.key} ${"%.2f".format(it.value)}%" } + " ($asOf)",
            key = KEY
        )
        return PolicyRates(latest, change, asOf)
    }

    companion object {
        const val KEY = "POLICY_RATE_DIVERGENCE"
        const val URL =
            "https://stats.bis.org/api/v1/data/WS_CBPOL/M.US+XM+GB+JP+CH+CA/all?lastNObservations=18"
        val HEADERS = mapOf("Accept" to "application/vnd.sdmx.data+json;version=1.0.0")
        val LABELS = mapOf(
            "US" to "US", "XM" to "EA", "GB" to "GB",
            "JP" to "JP", "CH" to "CH", "CA" to "CA"
        )
    }
}

/* ------------------------------------------------------------------ */
/* Coinbase — a third independent L2 gold book                         */
/* ------------------------------------------------------------------ */

/**
 * PAXG/USD level-2 book from a US-regulated venue, quoted in dollars rather
 * than a stablecoin. A third independent book makes the depth measurement
 * robust to one venue thinning out, which is what the aggregate quality rule
 * (half mean, half minimum) rewards.
 */
class CoinbaseDepthProvider(
    private val http: HttpClient,
    private val log: DiagnosticLog = DiagnosticLog.shared
) {
    fun depth(now: Instant = Instant.now()): OrderBookDepth? {
        val json = http.getJson(Providers.COINBASE.id, URL) ?: return null
        val bids = json["bids"]?.asArray.orEmpty()
        val asks = json["asks"]?.asArray.orEmpty()
        if (bids.isEmpty() || asks.isEmpty()) {
            log.warn(
                LogStage.PARSE, Providers.COINBASE.id, "BOOK_EMPTY",
                "no level-2 rows returned", key = KEY
            )
            return null
        }
        fun price(row: Json): Double? = row[0]?.asString?.toDoubleOrNull()
        fun size(row: Json): Double? = row[1]?.asString?.toDoubleOrNull()

        val bestBid = price(bids[0]) ?: return null
        val bestAsk = price(asks[0]) ?: return null
        val bidVolume = bids.take(LEVELS).sumOf { size(it) ?: 0.0 }
        val askVolume = asks.take(LEVELS).sumOf { size(it) ?: 0.0 }
        if (bidVolume <= 0.0 || askVolume <= 0.0) return null

        return OrderBookDepth(
            venue = "Coinbase",
            instrument = "PAXG/USD",
            bestBid = bestBid,
            bestAsk = bestAsk,
            bidVolume = bidVolume,
            askVolume = askVolume,
            bidLevels = minOf(bids.size, LEVELS),
            askLevels = minOf(asks.size, LEVELS),
            asOf = now
        )
    }

    companion object {
        const val KEY = "ORDER_BOOK_DEPTH"
        const val URL = "https://api.exchange.coinbase.com/products/PAXG-USD/book?level=2"
        const val LEVELS = 100
    }
}
