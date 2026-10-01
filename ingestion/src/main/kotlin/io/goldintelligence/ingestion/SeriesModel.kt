package io.goldintelligence.ingestion

import java.time.Instant
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

data class Bar(
    val timestamp: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double? = null
)

/**
 * An ordered price history for one symbol, oldest bar first. All statistics
 * below return null rather than a default when the window is not fully
 * populated — a half-filled z-score is a fabricated number, not a weak one.
 */
data class TimeSeries(
    val symbol: String,
    val bars: List<Bar>,
    val frequency: Frequency = Frequency.DAILY
) {
    val last: Bar? get() = bars.lastOrNull()
    val closes: List<Double> get() = bars.map { it.close }
    val size: Int get() = bars.size

    fun closeAt(indexFromEnd: Int): Double? = bars.getOrNull(bars.size - 1 - indexFromEnd)?.close

    fun returnOver(periods: Int): Double? {
        val now = last?.close ?: return null
        val then = closeAt(periods) ?: return null
        if (then == 0.0) return null
        return (now - then) / then
    }

    fun logReturns(window: Int = bars.size): List<Double> {
        val xs = closes.takeLast(window + 1)
        if (xs.size < 2) return emptyList()
        return (1 until xs.size).mapNotNull { i ->
            val a = xs[i - 1]
            val b = xs[i]
            if (a > 0.0 && b > 0.0) ln(b / a) else null
        }
    }

    /** Sample standard deviation of log returns over `window` bars. */
    fun realizedVol(window: Int = 20): Double? {
        val rs = logReturns(window)
        if (rs.size < 5) return null
        return Stats.stdev(rs)
    }

    /** Realized sigma in price units over one bar of this series. */
    fun sigmaPrice(window: Int = 20): Double? {
        val vol = realizedVol(window) ?: return null
        val price = last?.close ?: return null
        return vol * price
    }

    fun zScore(window: Int = 252): Double? {
        val xs = closes.takeLast(window)
        if (xs.size < 20) return null
        return Stats.zScore(xs.last(), xs)
    }

    /** Z-score of the most recent one-bar change, i.e. a sigma-move. */
    fun moveSigma(window: Int = 252): Double? {
        val rs = logReturns(window)
        if (rs.size < 20) return null
        val sd = Stats.stdev(rs) ?: return null
        if (sd <= 0.0) return null
        return (rs.last() - rs.average()) / sd
    }

    fun sma(window: Int): Double? {
        val xs = closes.takeLast(window)
        if (xs.size < window) return null
        return xs.average()
    }

    fun slope(window: Int): Double? {
        val xs = closes.takeLast(window)
        if (xs.size < 3) return null
        return Stats.slope(xs)
    }
}

object Stats {
    fun mean(xs: List<Double>): Double? = if (xs.isEmpty()) null else xs.average()

    fun stdev(xs: List<Double>): Double? {
        if (xs.size < 2) return null
        val m = xs.average()
        val v = xs.sumOf { (it - m) * (it - m) } / (xs.size - 1)
        return sqrt(v)
    }

    fun zScore(value: Double, population: List<Double>): Double? {
        val sd = stdev(population) ?: return null
        if (sd <= 0.0) return null
        return (value - population.average()) / sd
    }

    /** Ordinary least-squares slope against an integer index, normalized by level. */
    fun slope(xs: List<Double>): Double? {
        val n = xs.size
        if (n < 3) return null
        val mx = (n - 1) / 2.0
        val my = xs.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (i - mx) * (xs[i] - my)
            den += (i - mx) * (i - mx)
        }
        if (den <= 0.0) return null
        val raw = num / den
        return if (abs(my) > 0.0) raw / abs(my) else raw
    }

    fun correlation(a: List<Double>, b: List<Double>): Double? {
        val n = minOf(a.size, b.size)
        if (n < 3) return null
        val x = a.takeLast(n)
        val y = b.takeLast(n)
        val mx = x.average()
        val my = y.average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in 0 until n) {
            num += (x[i] - mx) * (y[i] - my)
            dx += (x[i] - mx) * (x[i] - mx)
            dy += (y[i] - my) * (y[i] - my)
        }
        val den = sqrt(dx * dy)
        return if (den <= 0.0) null else (num / den).coerceIn(-1.0, 1.0)
    }

    /** Maps an unbounded z-score onto the engine's -100..+100 factor scale. */
    fun zToScore(z: Double?, cap: Double = 3.0): Double? {
        if (z == null) return null
        return (z / cap).coerceIn(-1.0, 1.0) * 100.0
    }

    /** Maps a ratio/percentage change onto the -100..+100 factor scale. */
    fun ratioToScore(x: Double?, scale: Double): Double? {
        if (x == null || scale <= 0.0) return null
        return (x / scale).coerceIn(-1.0, 1.0) * 100.0
    }

    fun percentileRank(value: Double, population: List<Double>): Double? {
        if (population.size < 10) return null
        val below = population.count { it <= value }
        return below.toDouble() / population.size
    }
}
