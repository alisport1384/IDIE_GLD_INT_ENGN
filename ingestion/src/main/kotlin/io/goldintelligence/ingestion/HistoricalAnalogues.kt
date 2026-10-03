package io.goldintelligence.ingestion

import io.goldintelligence.engine.Direction
import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.HistoricalAnalogueEngine
import io.goldintelligence.engine.InputSnapshot
import io.goldintelligence.engine.LogStage
import io.goldintelligence.engine.Scenario
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * SPEC v2.1 §24 — historical analogues from the stack's own history.
 *
 * The earlier survey recorded this as unreachable because no free dataset of
 * *labelled* episodes exists — nobody publishes "2013-04-12: taper tantrum,
 * bearish gold" as a machine-readable series. That is still true, and this
 * engine does not pretend otherwise: it labels nothing.
 *
 * What it does is the part that does not need labels. The daily histories the
 * app already downloads reach back to 2004 (22 years, ~5,500 sessions). Each
 * session is described by the same handful of standardised conditions the
 * engine reads today — gold trend and volatility, the dollar, equities, long
 * bonds, and equity fear — and the sessions whose description is closest to
 * today's are reported together with **what gold actually did next**.
 *
 * Nothing is modelled, interpolated or labelled:
 *  - a match is a real date, its distance is published,
 *  - the outcome is the realised forward return from that date,
 *  - matches inside the lookback of the current window, or within
 *    [MIN_SEPARATION_DAYS] of a better match, are excluded so the set cannot
 *    be one episode counted five times,
 *  - with fewer than [MIN_DIMENSIONS] usable conditions or [MIN_HISTORY]
 *    sessions, it reports nothing rather than a weak answer.
 */
class SeriesAnalogueEngine(
    private val log: DiagnosticLog = DiagnosticLog.shared
) : HistoricalAnalogueEngine {

    /** Set by the ingestion client before each evaluation. */
    @Volatile
    var universe: MarketUniverse? = null

    /** The matches behind the last [find], for the presentation layer. */
    @Volatile
    var lastMatches: List<Match> = emptyList()
        private set

    data class Match(
        val date: java.time.LocalDate,
        /** Standardised distance from today's conditions, in sigma. */
        val distance: Double,
        /** Realised gold return over the following [FORWARD_DAYS], percent. */
        val forwardReturnPct: Double,
        val dimensions: Int
    ) {
        val direction: Direction
            get() = when {
                forwardReturnPct >= DIRECTION_FLOOR_PCT -> Direction.BULLISH
                forwardReturnPct <= -DIRECTION_FLOOR_PCT -> Direction.BEARISH
                else -> Direction.NEUTRAL
            }

        /** 1.0 at zero distance, decaying over the sigma scale. */
        val similarity: Double get() = (1.0 / (1.0 + distance / SIMILARITY_SCALE)).coerceIn(0.0, 1.0)
    }

    override fun find(snapshot: InputSnapshot): List<Scenario> {
        val u = universe ?: return empty("NO_UNIVERSE", "no market universe was handed to the matcher")
        val gold = u.seriesOf(MarketUniverse.GOLD_PROXY_ETF)
            ?: u.seriesOf(MarketUniverse.GOLD_SPOT_HISTORY)
            ?: return empty("NO_GOLD_HISTORY", "no gold history long enough to match against")

        val closes = gold.bars.map { it.close }
        val dates = gold.bars.map { it.timestamp.atZone(ZoneOffset.UTC).toLocalDate() }
        if (closes.size < MIN_HISTORY) {
            return empty(
                "HISTORY_TOO_SHORT",
                "${closes.size} sessions of gold history, ${MIN_HISTORY} required"
            )
        }

        // Each condition is a column of the same length as the gold history,
        // aligned by position; a column that cannot be built is simply absent.
        val columns = LinkedHashMap<String, DoubleArray>()
        trend(closes, MOMENTUM_DAYS)?.let { columns["gold_trend"] = it }
        volatility(closes, VOL_DAYS)?.let { columns["gold_vol"] = it }
        aligned(u, MarketUniverse.EQUITY_ETF, closes.size)?.let { s ->
            trend(s, MOMENTUM_DAYS)?.let { columns["equity_trend"] = it }
        }
        aligned(u, MarketUniverse.LONG_BOND_ETF, closes.size)?.let { s ->
            trend(s, MOMENTUM_DAYS)?.let { columns["bond_trend"] = it }
        }
        aligned(u, MarketUniverse.DOLLAR_ETF, closes.size)?.let { s ->
            trend(s, MOMENTUM_DAYS)?.let { columns["dollar_trend"] = it }
        }
        aligned(u, MarketUniverse.VIX, closes.size)?.let { columns["fear"] = it.copyOf() }
        aligned(u, MarketUniverse.SILVER_PROXY_ETF, closes.size)?.let { s ->
            trend(s, MOMENTUM_DAYS)?.let { columns["silver_trend"] = it }
        }

        if (columns.size < MIN_DIMENSIONS) {
            return empty(
                "TOO_FEW_CONDITIONS",
                "${columns.size} usable conditions, $MIN_DIMENSIONS required"
            )
        }

        val standardised = columns.mapValues { (_, col) -> standardise(col) }
        val last = closes.lastIndex
        val today = standardised.mapNotNull { (k, col) -> col[last]?.let { k to it } }.toMap()
        if (today.size < MIN_DIMENSIONS) {
            return empty("TODAY_INCOMPLETE", "today is described by only ${today.size} conditions")
        }

        // Candidates need a full forward window, and must sit outside the
        // window today's own description was computed over.
        val latestCandidate = last - FORWARD_DAYS - MOMENTUM_DAYS
        val candidates = ArrayList<Match>(latestCandidate.coerceAtLeast(0))
        for (i in MOMENTUM_DAYS..latestCandidate) {
            var sum = 0.0
            var dims = 0
            today.forEach { (k, v) ->
                val x = standardised[k]?.get(i)
                if (x != null) {
                    val d = x - v
                    sum += d * d
                    dims++
                }
            }
            if (dims < MIN_DIMENSIONS) continue
            val distance = sqrt(sum / dims)
            val from = closes[i]
            val to = closes[i + FORWARD_DAYS]
            if (from <= 0.0) continue
            candidates += Match(dates[i], distance, (to / from - 1.0) * 100.0, dims)
        }
        if (candidates.isEmpty()) {
            return empty("NO_CANDIDATE", "no historical session carried a full forward window")
        }

        // Non-overlapping: once a date is taken, its neighbourhood is spent.
        val chosen = ArrayList<Match>(MATCHES)
        for (c in candidates.sortedBy { it.distance }) {
            if (chosen.size >= MATCHES) break
            if (chosen.none { abs(java.time.temporal.ChronoUnit.DAYS.between(it.date, c.date)) < MIN_SEPARATION_DAYS }) {
                chosen += c
            }
        }
        lastMatches = chosen

        val up = chosen.count { it.direction == Direction.BULLISH }
        val down = chosen.count { it.direction == Direction.BEARISH }
        val median = chosen.map { it.forwardReturnPct }.sorted().let { xs ->
            if (xs.size % 2 == 1) xs[xs.size / 2] else (xs[xs.size / 2 - 1] + xs[xs.size / 2]) / 2.0
        }
        log.info(
            LogStage.REGIME, "SeriesAnalogueEngine", "ANALOGUES_FOUND",
            "%d matches over %d sessions and %d conditions; %d up / %d down, median %+.2f%% over %d sessions"
                .format(chosen.size, closes.size, today.size, up, down, median, FORWARD_DAYS),
            key = KEY
        )

        return chosen.map { m ->
            Scenario(
                name = DATE.format(m.date),
                probability = m.similarity,
                trigger = "distance %.2fσ across %d conditions · gold %+.2f%% in the %d sessions that followed"
                    .format(m.distance, m.dimensions, m.forwardReturnPct, FORWARD_DAYS),
                expectedDirection = m.direction,
                expectedMagnitude = m.forwardReturnPct,
                invalidation = "Match is descriptive, not predictive: it is void once today's conditions " +
                    "move more than %.2fσ from %s.".format(SIMILARITY_SCALE, DATE.format(m.date))
            )
        }
    }

    private fun empty(code: String, message: String): List<Scenario> {
        lastMatches = emptyList()
        log.warn(LogStage.REGIME, "SeriesAnalogueEngine", code, message, key = KEY)
        return emptyList()
    }

    /** Series aligned to the gold history by trailing length, or null. */
    private fun aligned(u: MarketUniverse, id: String, size: Int): DoubleArray? {
        val s = u.seriesOf(id) ?: return null
        val closes = s.closes
        if (closes.size < MIN_HISTORY / 4) return null
        val out = DoubleArray(size) { Double.NaN }
        // Both series end "now"; align from the right so the most recent
        // session of each sits at the same index.
        val n = minOf(size, closes.size)
        for (k in 0 until n) out[size - 1 - k] = closes[closes.size - 1 - k]
        return out
    }

    private fun trend(values: List<Double>, window: Int): DoubleArray? =
        trend(values.toDoubleArray(), window)

    private fun trend(values: DoubleArray, window: Int): DoubleArray? {
        if (values.size <= window) return null
        val out = DoubleArray(values.size) { Double.NaN }
        for (i in window until values.size) {
            val a = values[i]
            val b = values[i - window]
            if (!a.isNaN() && !b.isNaN() && b > 0.0) out[i] = (a / b - 1.0) * 100.0
        }
        return out
    }

    private fun volatility(values: List<Double>, window: Int): DoubleArray? {
        if (values.size <= window + 1) return null
        val out = DoubleArray(values.size) { Double.NaN }
        for (i in window until values.size) {
            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            for (k in (i - window + 1)..i) {
                val prev = values[k - 1]
                if (prev <= 0.0) continue
                val r = values[k] / prev - 1.0
                sum += r
                sumSq += r * r
                n++
            }
            if (n > 2) {
                val mean = sum / n
                val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
                out[i] = sqrt(variance) * 100.0
            }
        }
        return out
    }

    /** z-score against the whole sample, so distances are comparable. */
    private fun standardise(col: DoubleArray): Array<Double?> {
        val finite = col.filter { !it.isNaN() }
        val out = arrayOfNulls<Double>(col.size)
        if (finite.size < MIN_HISTORY / 4) return out
        val mean = finite.average()
        val variance = finite.sumOf { (it - mean) * (it - mean) } / finite.size
        val sd = sqrt(variance)
        if (sd <= 1e-12) return out
        for (i in col.indices) if (!col[i].isNaN()) out[i] = (col[i] - mean) / sd
        return out
    }

    companion object {
        const val KEY = "HISTORICAL_ANALOGUE"

        /** Trading sessions used to describe a regime. */
        const val MOMENTUM_DAYS = 60

        /** Sessions used for the realised-volatility description. */
        const val VOL_DAYS = 20

        /** Forward window whose realised return is reported, in sessions. */
        const val FORWARD_DAYS = 20

        /** How many non-overlapping matches are published. */
        const val MATCHES = 4

        /** Calendar days two matches must be apart to count as separate. */
        const val MIN_SEPARATION_DAYS = 180L

        const val MIN_HISTORY = 400
        const val MIN_DIMENSIONS = 4

        /** Distance at which similarity has fallen to one half. */
        const val SIMILARITY_SCALE = 1.0

        /** Below this the forward move is called neutral, in percent. */
        const val DIRECTION_FLOOR_PCT = 1.0

        private val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
