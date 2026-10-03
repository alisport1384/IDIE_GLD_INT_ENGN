package io.goldintelligence.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * SPEC v2.1 §26 — the inference layer.
 *
 * Everything in this file is pure arithmetic over numbers that are already in
 * the report: no I/O, no provider, no hidden state. Each routine is a named,
 * published method with a reference, and each one refuses to answer rather
 * than returning a number it cannot justify from the sample it was given.
 *
 * The point of the layer is not to add opinions. It is to stop the engine
 * overstating what it knows:
 *
 *  - [IsotonicCalibration] turns a score into a probability that was actually
 *    observed, instead of a logistic curve nobody fitted.
 *  - [BrierDecomposition] says how much of the error is miscalibration and how
 *    much is genuine difficulty (Murphy 1973).
 *  - [ConformalInterval] gives a band with a finite-sample coverage guarantee
 *    instead of a multiple of sigma (Vovk, Gammerman & Shafer 2005).
 *  - [EffectiveBreadth] counts how many *independent* witnesses are speaking,
 *    not how many factors happen to agree (Meucci 2009).
 *  - [ChangepointDetector] reports the posterior age of the current regime
 *    rather than a rule-based label (Adams & MacKay 2007).
 *  - [TrendValidity] says whether the tape is trending or mean-reverting at
 *    all, so momentum is not read into noise (Lo & MacKinlay 1988).
 *  - [LocalLevelFilter] removes the jitter from the composite without lagging
 *    it the way a moving average does (Kalman 1960).
 *  - [RobustAggregate] shows whether the composite survives the removal of its
 *    loudest contributors.
 */

/* ------------------------------------------------------------------ */
/* Isotonic calibration — pool-adjacent-violators                      */
/* ------------------------------------------------------------------ */

/**
 * The monotone step function that best fits observed outcomes to scores in
 * least squares, fitted by the pool-adjacent-violators algorithm.
 *
 * Monotone is the right constraint here and not a convenience: a higher
 * composite score must never map to a lower probability, whatever the sample
 * noise says.
 */
class IsotonicCalibration private constructor(
    /** Ascending breakpoints on the score axis. */
    val scores: DoubleArray,
    /** Fitted probability at each breakpoint, non-decreasing. */
    val probabilities: DoubleArray,
    val sampleSize: Int
) {

    /** Probability for [score], linearly interpolated between breakpoints. */
    fun probability(score: Double): Double {
        if (scores.isEmpty()) return 0.5
        if (score <= scores.first()) return probabilities.first()
        if (score >= scores.last()) return probabilities.last()
        var lo = 0
        var hi = scores.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (scores[mid] <= score) lo = mid else hi = mid
        }
        val span = scores[hi] - scores[lo]
        if (span <= 0.0) return probabilities[lo]
        val t = (score - scores[lo]) / span
        return probabilities[lo] + t * (probabilities[hi] - probabilities[lo])
    }

    companion object {
        /** Below this the fit is noise, and the caller is told so with null. */
        const val MIN_SAMPLE = 120

        /**
         * @param samples (score, outcome) pairs; outcome true means the event
         *                happened. Order does not matter.
         */
        fun fit(samples: List<Pair<Double, Boolean>>): IsotonicCalibration? {
            if (samples.size < MIN_SAMPLE) return null
            val sorted = samples.sortedBy { it.first }
            // Pool adjacent violators: merge neighbouring blocks until the
            // sequence of block means is non-decreasing.
            val value = ArrayList<Double>(sorted.size)
            val weight = ArrayList<Double>(sorted.size)
            val at = ArrayList<Double>(sorted.size)
            for ((s, o) in sorted) {
                value += if (o) 1.0 else 0.0
                weight += 1.0
                at += s
                while (value.size > 1 && value[value.size - 2] > value[value.size - 1]) {
                    val w = weight[weight.size - 2] + weight[weight.size - 1]
                    val v = (value[value.size - 2] * weight[weight.size - 2] +
                        value[value.size - 1] * weight[weight.size - 1]) / w
                    val x = at[at.size - 1]
                    value.removeAt(value.size - 1); value[value.size - 1] = v
                    weight.removeAt(weight.size - 1); weight[weight.size - 1] = w
                    at.removeAt(at.size - 1); at[at.size - 1] = x
                }
            }
            if (value.size < 2) return null
            return IsotonicCalibration(at.toDoubleArray(), value.toDoubleArray(), samples.size)
        }
    }
}

/* ------------------------------------------------------------------ */
/* Brier score and the Murphy decomposition                            */
/* ------------------------------------------------------------------ */

/**
 * `BS = reliability − resolution + uncertainty` (Murphy 1973).
 *
 *  - **reliability** is miscalibration: how far the observed frequency in a
 *    bin is from the probability that was promised. Lower is better, zero is
 *    perfect calibration.
 *  - **resolution** is how far the bins separate from the base rate — the
 *    forecast's ability to say something other than "average". Higher is
 *    better.
 *  - **uncertainty** is `o(1−o)` and belongs to the problem, not the model.
 *  - **skill** is `1 − BS/uncertainty`: positive means better than always
 *    predicting the base rate.
 */
data class BrierReport(
    val brier: Double,
    val reliability: Double,
    val resolution: Double,
    val uncertainty: Double,
    val skill: Double,
    val baseRate: Double,
    val sampleSize: Int,
    val bins: Int
)

object BrierDecomposition {

    const val MIN_SAMPLE = 60
    const val DEFAULT_BINS = 10

    /**
     * Quantile binning, not equal width: equal-width bins collapse when the
     * probability mass sits in one tail, which is exactly what happens to a
     * directional model.
     */
    fun evaluate(
        samples: List<Pair<Double, Boolean>>,
        bins: Int = DEFAULT_BINS
    ): BrierReport? {
        if (samples.size < MIN_SAMPLE || bins < 2) return null
        val n = samples.size
        val brier = samples.sumOf { (p, o) ->
            val y = if (o) 1.0 else 0.0
            (p - y) * (p - y)
        } / n
        val baseRate = samples.count { it.second }.toDouble() / n
        val uncertainty = baseRate * (1.0 - baseRate)

        val sorted = samples.sortedBy { it.first }
        val perBin = max(1, n / bins)
        var reliability = 0.0
        var resolution = 0.0
        var used = 0
        var index = 0
        while (index < n) {
            val end = min(n, index + perBin)
            // Absorb a short trailing bin rather than scoring it on its own.
            val stop = if (n - end < perBin / 2) n else end
            val slice = sorted.subList(index, stop)
            val pBar = slice.sumOf { it.first } / slice.size
            val oBar = slice.count { it.second }.toDouble() / slice.size
            val w = slice.size.toDouble() / n
            reliability += w * (pBar - oBar) * (pBar - oBar)
            resolution += w * (oBar - baseRate) * (oBar - baseRate)
            used++
            index = stop
        }
        val skill = if (uncertainty <= 1e-12) 0.0 else 1.0 - brier / uncertainty
        return BrierReport(brier, reliability, resolution, uncertainty, skill, baseRate, n, used)
    }
}

/* ------------------------------------------------------------------ */
/* Split conformal prediction                                          */
/* ------------------------------------------------------------------ */

/**
 * Split conformal prediction (Vovk, Gammerman & Shafer 2005; Lei et al. 2018).
 *
 * Given the absolute residuals of a model on data it did not fit, the
 * `⌈(n+1)(1−α)⌉`-th smallest residual is a half-width whose coverage is at
 * least `1−α` in finite samples, with no distributional assumption — only
 * exchangeability.
 *
 * Exchangeability is the honest caveat on a price series and it is published
 * with the band: [Interval.realisedCoverage] is measured, not assumed, so a
 * reader can see when the guarantee is not being met.
 */
data class ConformalBand(
    val halfWidth: Double,
    val targetCoverage: Double,
    val realisedCoverage: Double?,
    val sampleSize: Int
)

object ConformalInterval {

    const val MIN_SAMPLE = 60

    /**
     * @param residuals absolute errors on held-out data.
     * @param alpha miscoverage rate; 0.10 gives a 90 % band.
     */
    fun band(residuals: List<Double>, alpha: Double = 0.10): ConformalBand? {
        val clean = residuals.filter { it.isFinite() && it >= 0.0 }
        if (clean.size < MIN_SAMPLE || alpha <= 0.0 || alpha >= 1.0) return null
        val sorted = clean.sorted()
        val n = sorted.size
        // Finite-sample correction: the rank is taken over n+1, which makes the
        // band slightly conservative rather than slightly short.
        val rank = Math.ceil((n + 1) * (1.0 - alpha)).toInt()
        val half = sorted[min(n - 1, max(0, rank - 1))]
        val covered = clean.count { it <= half }.toDouble() / n
        return ConformalBand(half, 1.0 - alpha, covered, n)
    }
}

/* ------------------------------------------------------------------ */
/* Effective number of independent bets                                */
/* ------------------------------------------------------------------ */

/**
 * Meucci (2009), *Managing Diversification*: the exponential Shannon entropy
 * of the normalised eigenvalues of the correlation matrix — the number of
 * genuinely independent directions the inputs span.
 *
 * Twelve factors driven by the dollar are not twelve witnesses. The engine's
 * agreement term reads this so that agreement between clones is not counted
 * as corroboration.
 */
object EffectiveBreadth {

    const val MIN_OBSERVATIONS = 30

    /**
     * @param columns name → aligned history, all of the same length.
     * @return effective number of independent columns in `[1, n]`, or null
     *         when there is not enough history to estimate a correlation.
     */
    fun of(columns: Map<String, List<Double>>): Double? {
        val usable = columns.values.filter { it.size >= MIN_OBSERVATIONS }
        if (usable.size < 2) return null
        val n = usable.minOf { it.size }
        val mat = usable.map { it.takeLast(n) }
        val corr = correlationMatrix(mat) ?: return null
        val eigen = Jacobi.eigenvalues(corr) ?: return null
        val positive = eigen.filter { it > 1e-10 }
        val total = positive.sum()
        if (total <= 1e-10) return null
        var entropy = 0.0
        for (l in positive) {
            val p = l / total
            if (p > 1e-12) entropy -= p * ln(p)
        }
        return exp(entropy).coerceIn(1.0, usable.size.toDouble())
    }

    /** Breadth as a fraction of the maximum: 1.0 means every input is its own bet. */
    fun ratio(columns: Map<String, List<Double>>): Double? {
        val usable = columns.values.count { it.size >= MIN_OBSERVATIONS }
        if (usable < 2) return null
        return of(columns)?.div(usable.toDouble())
    }

    internal fun correlationMatrix(columns: List<List<Double>>): Array<DoubleArray>? {
        val k = columns.size
        val means = DoubleArray(k) { i -> columns[i].average() }
        val sds = DoubleArray(k) { i ->
            val m = means[i]
            sqrt(columns[i].sumOf { (it - m) * (it - m) } / (columns[i].size - 1).coerceAtLeast(1))
        }
        if (sds.any { !it.isFinite() || it <= 1e-12 }) return null
        val out = Array(k) { DoubleArray(k) }
        val n = columns[0].size
        for (i in 0 until k) {
            for (j in i until k) {
                var acc = 0.0
                for (t in 0 until n) acc += (columns[i][t] - means[i]) * (columns[j][t] - means[j])
                val c = acc / ((n - 1).coerceAtLeast(1) * sds[i] * sds[j])
                out[i][j] = c
                out[j][i] = c
            }
            out[i][i] = 1.0
        }
        return out
    }
}

/** Eigenvalues of a small symmetric matrix by the cyclic Jacobi rotation method. */
object Jacobi {
    private const val MAX_SWEEPS = 100

    fun eigenvalues(matrix: Array<DoubleArray>): List<Double>? {
        val n = matrix.size
        if (n == 0 || matrix.any { it.size != n }) return null
        val a = Array(n) { i -> matrix[i].copyOf() }
        repeat(MAX_SWEEPS) {
            var off = 0.0
            for (i in 0 until n) for (j in i + 1 until n) off += a[i][j] * a[i][j]
            if (off <= 1e-14) return (0 until n).map { a[it][it] }
            for (p in 0 until n) {
                for (q in p + 1 until n) {
                    if (abs(a[p][q]) <= 1e-15) continue
                    val theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q])
                    val t = (if (theta >= 0) 1.0 else -1.0) /
                        (abs(theta) + sqrt(theta * theta + 1.0))
                    val c = 1.0 / sqrt(t * t + 1.0)
                    val s = t * c
                    for (k in 0 until n) {
                        val akp = a[k][p]
                        val akq = a[k][q]
                        a[k][p] = c * akp - s * akq
                        a[k][q] = s * akp + c * akq
                    }
                    for (k in 0 until n) {
                        val apk = a[p][k]
                        val aqk = a[q][k]
                        a[p][k] = c * apk - s * aqk
                        a[q][k] = s * apk + c * aqk
                    }
                }
            }
        }
        return (0 until n).map { a[it][it] }
    }
}

/* ------------------------------------------------------------------ */
/* Bayesian online changepoint detection                               */
/* ------------------------------------------------------------------ */

/**
 * The posterior age of the current regime.
 *
 * [mapRunLength] is the most probable number of observations since the last
 * changepoint; [changeProbability] is the posterior mass on "the regime reset
 * at this observation"; [youngRegimeProbability] is the mass on a regime
 * younger than [ChangepointDetector.YOUNG_RUN].
 */
data class RunLengthPosterior(
    val mapRunLength: Int,
    val changeProbability: Double,
    val youngRegimeProbability: Double,
    val observations: Int
) {
    /** Stability read for the confidence formula. */
    val stability: Stability
        get() = when {
            youngRegimeProbability >= 0.50 -> Stability.LOW
            youngRegimeProbability >= 0.20 -> Stability.MEDIUM
            mapRunLength >= ChangepointDetector.YOUNG_RUN -> Stability.HIGH
            else -> Stability.MEDIUM
        }
}

/**
 * Adams & MacKay (2007), *Bayesian Online Changepoint Detection*: a forward
 * recursion over the run length with a constant hazard and a Normal-Inverse-
 * Gamma conjugate predictive (Student-t), pruned to the most probable run
 * lengths so the cost stays linear.
 *
 * The engine previously labelled the regime from thresholds. This replaces the
 * label with a posterior that says how old the current regime is and how much
 * belief sits on it having just ended.
 */
class ChangepointDetector(
    /** Expected observations between changepoints; hazard = 1/this. */
    private val expectedRunLength: Double = 250.0,
    private val maxHypotheses: Int = 200
) {

    fun process(series: List<Double>): RunLengthPosterior? {
        val data = series.filter { it.isFinite() }
        if (data.size < MIN_OBSERVATIONS) return null
        val hazard = 1.0 / expectedRunLength

        // Conjugate Normal-Inverse-Gamma parameters, one per live run length.
        var mu = doubleArrayOf(MU0)
        var kappa = doubleArrayOf(KAPPA0)
        var alpha = doubleArrayOf(ALPHA0)
        var beta = doubleArrayOf(BETA0)
        var r = doubleArrayOf(1.0)

        for (x in data) {
            val m = r.size
            val pred = DoubleArray(m) { i -> studentT(x, mu[i], kappa[i], alpha[i], beta[i]) }

            val next = DoubleArray(m + 1)
            var reset = 0.0
            for (i in 0 until m) {
                next[i + 1] = r[i] * pred[i] * (1.0 - hazard)
                reset += r[i] * pred[i] * hazard
            }
            next[0] = reset
            val evidence = next.sum()
            if (!evidence.isFinite() || evidence <= 0.0) return null
            for (i in next.indices) next[i] /= evidence

            // Conjugate update, prepended with the fresh prior for run length 0.
            val nMu = DoubleArray(m + 1)
            val nKappa = DoubleArray(m + 1)
            val nAlpha = DoubleArray(m + 1)
            val nBeta = DoubleArray(m + 1)
            nMu[0] = MU0; nKappa[0] = KAPPA0; nAlpha[0] = ALPHA0; nBeta[0] = BETA0
            for (i in 0 until m) {
                nMu[i + 1] = (kappa[i] * mu[i] + x) / (kappa[i] + 1.0)
                nKappa[i + 1] = kappa[i] + 1.0
                nAlpha[i + 1] = alpha[i] + 0.5
                nBeta[i + 1] = beta[i] + kappa[i] * (x - mu[i]) * (x - mu[i]) / (2.0 * (kappa[i] + 1.0))
            }

            // Prune: keep the leading hypotheses, which carry essentially all
            // of the mass once the recursion has settled.
            val keep = min(next.size, maxHypotheses)
            r = next.copyOf(keep)
            val mass = r.sum()
            if (mass <= 0.0) return null
            for (i in r.indices) r[i] /= mass
            mu = nMu.copyOf(keep)
            kappa = nKappa.copyOf(keep)
            alpha = nAlpha.copyOf(keep)
            beta = nBeta.copyOf(keep)
        }

        var best = 0
        for (i in r.indices) if (r[i] > r[best]) best = i
        val young = r.take(min(YOUNG_RUN, r.size)).sum()
        return RunLengthPosterior(
            mapRunLength = best,
            changeProbability = r[0],
            youngRegimeProbability = young.coerceIn(0.0, 1.0),
            observations = data.size
        )
    }

    /** Student-t posterior predictive of the Normal-Inverse-Gamma model. */
    private fun studentT(x: Double, m: Double, k: Double, a: Double, b: Double): Double {
        val df = 2.0 * a
        val scale = b * (k + 1.0) / (a * k)
        if (!scale.isFinite() || scale <= 0.0) return 0.0
        val z = (x - m) * (x - m) / (df * scale)
        val logPdf = lnGamma((df + 1.0) / 2.0) - lnGamma(df / 2.0) -
            0.5 * ln(PI * df * scale) - ((df + 1.0) / 2.0) * ln(1.0 + z)
        return exp(logPdf)
    }

    /** Lanczos approximation; accurate well beyond what this recursion needs. */
    private fun lnGamma(x: Double): Double {
        val g = doubleArrayOf(
            676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012,
            9.9843695780195716e-6, 1.5056327351493116e-7
        )
        if (x < 0.5) return ln(PI / kotlin.math.sin(PI * x)) - lnGamma(1.0 - x)
        val z = x - 1.0
        var a = 0.99999999999980993
        for (i in g.indices) a += g[i] / (z + i + 1.0)
        val t = z + g.size - 0.5
        return 0.5 * ln(2.0 * PI) + (z + 0.5) * ln(t) - t + ln(a)
    }

    companion object {
        const val MIN_OBSERVATIONS = 60
        /** A regime younger than this is "just started". */
        const val YOUNG_RUN = 15
        private const val MU0 = 0.0
        private const val KAPPA0 = 1.0
        private const val ALPHA0 = 1.0
        private const val BETA0 = 1.0
    }
}

/* ------------------------------------------------------------------ */
/* Trend validity                                                      */
/* ------------------------------------------------------------------ */

/**
 * Whether the series is trending, random, or mean-reverting right now.
 *
 * [varianceRatio] is Lo & MacKinlay (1988): the variance of q-period returns
 * divided by q times the variance of one-period returns. One is a random walk,
 * above one is persistence, below one is reversal. [zStatistic] is the
 * heteroskedasticity-robust test statistic; |z| under 1.96 means the random
 * walk cannot be rejected at 5 %.
 *
 * [hurst] is the rescaled-range exponent: 0.5 is a random walk.
 */
data class TrendValidity(
    val varianceRatio: Double,
    val zStatistic: Double,
    val hurst: Double?,
    val observations: Int,
    val lag: Int
) {
    val randomWalkRejected: Boolean get() = abs(zStatistic) >= 1.96

    /** Multiplier a momentum reading deserves, in `[0,1]`. */
    val momentumCredibility: Double
        get() = when {
            !randomWalkRejected -> 0.5
            varianceRatio > 1.0 -> 1.0
            else -> 0.25
        }

    val label: String
        get() = when {
            !randomWalkRejected -> "RANDOM_WALK"
            varianceRatio > 1.0 -> "TRENDING"
            else -> "MEAN_REVERTING"
        }
}

object TrendValidityTest {

    const val MIN_OBSERVATIONS = 120

    fun evaluate(closes: List<Double>, lag: Int = 5): TrendValidity? {
        if (closes.size < MIN_OBSERVATIONS || lag < 2) return null
        val r = ArrayList<Double>(closes.size - 1)
        for (i in 1 until closes.size) {
            val a = closes[i - 1]
            val b = closes[i]
            if (a <= 0.0 || b <= 0.0) return null
            r += ln(b / a)
        }
        val n = r.size
        if (n < lag * 4) return null
        val mu = r.average()
        var var1 = 0.0
        for (x in r) var1 += (x - mu) * (x - mu)
        var1 /= (n - 1).toDouble()
        if (var1 <= 1e-18) return null

        // Overlapping q-period sums, with the Lo-MacKinlay unbiased scaling.
        val m = (n - lag + 1)
        if (m < 2) return null
        var varQ = 0.0
        for (i in 0..(n - lag)) {
            var s = 0.0
            for (j in 0 until lag) s += r[i + j]
            val d = s - lag * mu
            varQ += d * d
        }
        varQ /= (m.toDouble() * lag.toDouble())
        val vr = varQ / var1

        // Heteroskedasticity-robust variance of the ratio (Lo & MacKinlay 1988).
        var theta = 0.0
        for (j in 1 until lag) {
            var delta = 0.0
            var denom = 0.0
            for (t in j until n) {
                val a = (r[t] - mu) * (r[t] - mu)
                val b = (r[t - j] - mu) * (r[t - j] - mu)
                delta += a * b
            }
            for (t in 0 until n) {
                val a = (r[t] - mu) * (r[t] - mu)
                denom += a
            }
            if (denom <= 1e-18) return null
            delta /= (denom * denom / n)
            val w = 2.0 * (lag - j) / lag
            theta += w * w * delta
        }
        if (theta <= 0.0 || !theta.isFinite()) return null
        val z = (vr - 1.0) / sqrt(theta / n)
        return TrendValidity(vr, z, hurst(r), n, lag)
    }

    /** Rescaled-range exponent over dyadic window sizes. */
    internal fun hurst(returns: List<Double>): Double? {
        val n = returns.size
        if (n < 64) return null
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        var size = 8
        while (size <= n / 2) {
            val blocks = n / size
            var acc = 0.0
            var used = 0
            for (b in 0 until blocks) {
                val slice = returns.subList(b * size, (b + 1) * size)
                val m = slice.average()
                var cum = 0.0
                var lo = 0.0
                var hi = 0.0
                for (v in slice) {
                    cum += v - m
                    if (cum < lo) lo = cum
                    if (cum > hi) hi = cum
                }
                var sd = 0.0
                for (v in slice) sd += (v - m) * (v - m)
                sd = sqrt(sd / slice.size)
                if (sd > 1e-15 && hi - lo > 0.0) {
                    acc += (hi - lo) / sd
                    used++
                }
            }
            if (used > 0) {
                xs += ln(size.toDouble())
                ys += ln(acc / used)
            }
            size *= 2
        }
        if (xs.size < 3) return null
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            num += (xs[i] - mx) * (ys[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        if (den <= 1e-15) return null
        return num / den
    }
}

/* ------------------------------------------------------------------ */
/* Local-level Kalman filter                                           */
/* ------------------------------------------------------------------ */

/**
 * The one-dimensional local-level (random walk plus noise) Kalman filter
 * (Kalman 1960). Unlike a moving average it does not lag by half its window:
 * the gain adapts to how much of the observed variation is signal.
 *
 * Used to publish a de-jittered composite beside the raw one, so a reader can
 * see when a sign flip is movement and when it is noise.
 */
data class FilteredLevel(
    val level: Double,
    val variance: Double,
    val gain: Double,
    val observations: Int
) {
    /** Standard deviation of the filtered level. */
    val standardError: Double get() = sqrt(max(0.0, variance))
}

object LocalLevelFilter {

    const val MIN_OBSERVATIONS = 10

    /**
     * @param signalToNoise ratio of process to observation variance. Small
     *        values smooth hard; 0.05 keeps roughly a twenty-observation memory.
     */
    fun run(series: List<Double>, signalToNoise: Double = 0.05): FilteredLevel? {
        val data = series.filter { it.isFinite() }
        if (data.size < MIN_OBSERVATIONS) return null
        val mean = data.average()
        var obsVar = data.sumOf { (it - mean) * (it - mean) } / (data.size - 1)
        if (!obsVar.isFinite() || obsVar <= 1e-12) obsVar = 1e-12
        val procVar = obsVar * signalToNoise

        var x = data.first()
        var p = obsVar
        var gain = 0.0
        for (i in 1 until data.size) {
            p += procVar
            gain = p / (p + obsVar)
            x += gain * (data[i] - x)
            p *= (1.0 - gain)
        }
        return FilteredLevel(x, p, gain, data.size)
    }
}

/* ------------------------------------------------------------------ */
/* Robust aggregation                                                  */
/* ------------------------------------------------------------------ */

/**
 * How much of the composite survives the removal of its loudest contributors.
 *
 * [weighted] is the published composite, [trimmed] drops the extreme tails,
 * [median] ignores magnitude entirely. [fragility] is the gap between the
 * weighted and the trimmed reading on the composite's own scale: a large gap
 * means one or two factors are carrying the whole call.
 */
data class RobustnessReport(
    val weighted: Double,
    val trimmed: Double,
    val median: Double,
    val fragility: Double,
    val contributors: Int
) {
    val fragile: Boolean get() = fragility >= RobustAggregate.FRAGILE_GAP
}

object RobustAggregate {

    const val MIN_CONTRIBUTORS = 5
    const val TRIM_FRACTION = 0.20
    const val FRAGILE_GAP = 20.0

    /**
     * @param weighted the published composite, on the ±100 factor scale.
     * @param scores the individual factor scores behind it, same scale and
     *        deliberately unweighted: the comparison asks what the evidence
     *        says when no factor is allowed to shout.
     */
    fun evaluate(weighted: Double, scores: List<Double>): RobustnessReport? {
        val values = scores.filter { it.isFinite() }
        if (values.size < MIN_CONTRIBUTORS || !weighted.isFinite()) return null
        val sorted = values.sorted()
        val cut = (sorted.size * TRIM_FRACTION).toInt().coerceAtMost((sorted.size - 1) / 2)
        val core = sorted.subList(cut, sorted.size - cut)
        val trimmed = core.average()
        val mid = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        return RobustnessReport(
            weighted = weighted,
            trimmed = trimmed,
            median = mid,
            fragility = abs(weighted - trimmed),
            contributors = sorted.size
        )
    }
}

/* ------------------------------------------------------------------ */
/* The bundle the engine consumes                                      */
/* ------------------------------------------------------------------ */

/**
 * Everything the inference layer produced this cycle. Every field is nullable
 * and absence is published rather than filled in.
 */
data class InferenceBundle(
    /** Per-horizon empirical probability mapping, fitted walk-forward. */
    val calibration: Map<Horizon, IsotonicCalibration> = emptyMap(),
    /** Per-horizon scoring record of that mapping. */
    val brier: Map<Horizon, BrierReport> = emptyMap(),
    /** Per-horizon conformal half-width of the forward move, in per cent. */
    val conformal: Map<Horizon, ConformalBand> = emptyMap(),
    val effectiveBreadth: Double? = null,
    val breadthRatio: Double? = null,
    val runLength: RunLengthPosterior? = null,
    val trend: TrendValidity? = null,
    val filtered: FilteredLevel? = null,
    val robustness: RobustnessReport? = null,
    /* SPEC v2.1 §27 */
    /** Volatility-conditional conformal bands, refining [conformal]. */
    val mondrian: Map<Horizon, MondrianConformal> = emptyMap(),
    /** Which forecasters entered the published probability, and how they scored. */
    val combination: Map<Horizon, CombinationReport> = emptyMap(),
    /** Out-of-sample information carried by each leg of the panel. */
    val legInformation: Map<Horizon, List<LegInformation>> = emptyMap(),
    /** Sessions the walk-forward replay actually scored. */
    val sampleSize: Int = 0,
    /** Factor ids whose history took part in the replay. */
    val calibratedOn: List<String> = emptyList(),
    /** Of those, the ones replayed on the published series rather than a stand-in. */
    val measuredOn: List<String> = emptyList(),
    /** Normative weight the replay panel reproduces. */
    val coveredWeight: Double = 0.0,
    /** Of that, the share carried by measured series. */
    val measuredWeight: Double = 0.0,
    /** The panel model's reading of today, when every leg is populated. */
    val liveRidgeProbability: Double? = null,
    /** Today's trailing volatility, for selecting the conditional band. */
    val liveVolatilityPct: Double? = null
) {
    /** The band to publish for [horizon]: conditional when available. */
    fun bandFor(horizon: Horizon): ConformalBand? =
        mondrian[horizon]?.bandFor(liveVolatilityPct) ?: conformal[horizon]

    /**
     * Fraction of the calibration requirement met, for the confidence formula.
     * Zero means no probability may be published.
     */
    fun qualityFor(horizon: Horizon): Double {
        val n = calibration[horizon]?.sampleSize ?: return 0.0
        val skill = brier[horizon]?.skill ?: return 0.0
        if (skill <= 0.0) return 0.0
        val size = min(1.0, n.toDouble() / REQUIRED_SAMPLE)
        return (size * min(1.0, skill / REFERENCE_SKILL)).coerceIn(0.0, 1.0)
    }

    /**
     * Whether the horizon was actually measured. "Measured and found to have
     * no skill" is a different statement from "never measured", and the two
     * must not collapse into the same status on screen.
     */
    fun measured(horizon: Horizon): Boolean = brier.containsKey(horizon)

    companion object {
        /** Sessions at which the calibration sample counts as complete. */
        const val REQUIRED_SAMPLE = 750.0
        /** Brier skill at which the mapping counts as fully informative. */
        const val REFERENCE_SKILL = 0.05
    }
}

/* ================================================================== */
/* SPEC v2.1 §27 — a second forecaster, a combination, and a sharper    */
/* interval. Everything below is pure: no I/O, no new dependency.       */
/* ================================================================== */

/**
 * Ridge-regularised logistic regression, fitted by iteratively reweighted
 * least squares with a Tikhonov penalty on the slopes (the intercept is left
 * unpenalised).
 *
 * §26 mapped one number — the normatively weighted composite — to a
 * probability. That mapping cannot discover that two factors matter jointly,
 * or that one of them has been carrying the whole composite. This model reads
 * the factor panel as a vector and estimates the directional mapping from the
 * data, under a penalty strong enough that it cannot chase noise.
 *
 * It does **not** re-weight the published composite. The factor weight table
 * is normative and untouched; this is a separate forecaster whose probability
 * is combined with the isotonic one in [ForecastCombination], and whose own
 * out-of-sample score decides whether it is used at all.
 *
 * Reference: Hoerl & Kennard (1970) for the ridge penalty; le Cessie & van
 * Houwelingen (1992) for its use in logistic regression.
 */
class RidgeLogistic private constructor(
    val intercept: Double,
    val coefficients: DoubleArray,
    val centre: DoubleArray,
    val scale: DoubleArray,
    val sampleSize: Int,
    val lambda: Double,
    val iterations: Int
) {

    /** Probability of the positive class for one raw (unstandardised) row. */
    fun probability(row: DoubleArray): Double? {
        if (row.size != coefficients.size) return null
        var z = intercept
        for (j in row.indices) {
            val x = row[j]
            if (!x.isFinite()) return null
            z += coefficients[j] * ((x - centre[j]) / scale[j])
        }
        return 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))
    }

    companion object {
        const val MIN_SAMPLE = 200
        const val MAX_ITERATIONS = 40
        const val TOLERANCE = 1e-7
        /** Penalty on the standardised slopes; one free parameter, fixed. */
        const val LAMBDA = 8.0

        /**
         * @param rows design matrix, one row per observation, raw units.
         * @param outcomes the binary outcome for each row.
         */
        fun fit(
            rows: List<DoubleArray>,
            outcomes: List<Boolean>,
            lambda: Double = LAMBDA
        ): RidgeLogistic? {
            if (rows.size != outcomes.size || rows.size < MIN_SAMPLE) return null
            val p = rows.firstOrNull()?.size ?: return null
            if (p == 0 || rows.any { it.size != p || it.any { v -> !v.isFinite() } }) return null
            val n = rows.size

            // Standardise so one penalty applies evenly to every column.
            val centre = DoubleArray(p)
            val scale = DoubleArray(p)
            for (j in 0 until p) {
                var sum = 0.0
                for (r in rows) sum += r[j]
                val mean = sum / n
                var ss = 0.0
                for (r in rows) {
                    val d = r[j] - mean
                    ss += d * d
                }
                val sd = sqrt(ss / max(1, n - 1))
                centre[j] = mean
                scale[j] = if (sd > 1e-9) sd else 1.0
            }
            val x = Array(n) { i -> DoubleArray(p) { j -> (rows[i][j] - centre[j]) / scale[j] } }
            val y = DoubleArray(n) { if (outcomes[it]) 1.0 else 0.0 }

            // IRLS on the augmented (intercept + slopes) system.
            val dim = p + 1
            val beta = DoubleArray(dim)
            beta[0] = run {
                val base = (y.sum() / n).coerceIn(1e-4, 1.0 - 1e-4)
                ln(base / (1.0 - base))
            }
            var iterations = 0
            repeat(MAX_ITERATIONS) {
                iterations++
                val hessian = Array(dim) { DoubleArray(dim) }
                val gradient = DoubleArray(dim)
                for (i in 0 until n) {
                    var z = beta[0]
                    for (j in 0 until p) z += beta[j + 1] * x[i][j]
                    val mu = 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))
                    val w = (mu * (1.0 - mu)).coerceAtLeast(1e-6)
                    val resid = y[i] - mu
                    gradient[0] += resid
                    for (j in 0 until p) gradient[j + 1] += resid * x[i][j]
                    hessian[0][0] += w
                    for (j in 0 until p) {
                        val wx = w * x[i][j]
                        hessian[0][j + 1] += wx
                        hessian[j + 1][0] += wx
                        for (k in 0 until p) hessian[j + 1][k + 1] += wx * x[i][k]
                    }
                }
                // Penalty: slopes only, never the intercept.
                for (j in 1 until dim) {
                    gradient[j] -= lambda * beta[j]
                    hessian[j][j] += lambda
                }
                val step = solve(hessian, gradient) ?: return@repeat
                var delta = 0.0
                for (j in 0 until dim) {
                    beta[j] += step[j]
                    delta = max(delta, abs(step[j]))
                }
                if (delta < TOLERANCE) return@repeat
            }
            if (beta.any { !it.isFinite() }) return null
            return RidgeLogistic(
                intercept = beta[0],
                coefficients = DoubleArray(p) { beta[it + 1] },
                centre = centre,
                scale = scale,
                sampleSize = n,
                lambda = lambda,
                iterations = iterations
            )
        }

        /** Gaussian elimination with partial pivoting. Null when singular. */
        internal fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
            for (col in 0 until n) {
                var pivot = col
                for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
                if (abs(m[pivot][col]) < 1e-12) return null
                val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
                val d = m[col][col]
                for (j in col..n) m[col][j] /= d
                for (r in 0 until n) {
                    if (r == col) continue
                    val f = m[r][col]
                    if (f == 0.0) continue
                    for (j in col..n) m[r][j] -= f * m[col][j]
                }
            }
            return DoubleArray(n) { m[it][n] }.takeIf { row -> row.all { it.isFinite() } }
        }
    }
}

/** One candidate forecaster's out-of-sample record and its combination weight. */
data class CombinationMember(
    val name: String,
    val brier: Double,
    val skill: Double,
    val weight: Double,
    val sampleSize: Int,
    val used: Boolean
)

/** The published combination: which members entered it and how they scored. */
data class CombinationReport(
    val members: List<CombinationMember>,
    val brier: Double,
    val skill: Double,
    val sampleSize: Int
) {
    val usedCount: Int get() = members.count { it.used }
}

/**
 * Out-of-sample forecast combination.
 *
 * Bates & Granger (1969) established that a combination usually beats its
 * members; Timmermann (2006) reviews why, and why simple weights are hard to
 * beat. Members are pooled in **log-odds** space, which keeps the result a
 * proper probability and is the standard logarithmic opinion pool.
 *
 * Two rules protect the result from the combination's own overfitting:
 *
 *  1. a member enters only if its own out-of-sample Brier skill is positive;
 *  2. weights are proportional to that skill, so a barely-informative member
 *     cannot outvote a strong one, and are not re-estimated per period.
 *
 * With no admissible member the combination reports itself empty and the
 * caller publishes nothing.
 */
object ForecastCombination {

    const val MIN_SAMPLE = 150

    /**
     * @param candidates name → that member's out-of-sample probabilities,
     *        aligned with [outcomes].
     */
    fun evaluate(
        candidates: Map<String, List<Double>>,
        outcomes: List<Boolean>
    ): Pair<CombinationReport, List<Double>>? {
        if (outcomes.size < MIN_SAMPLE) return null
        val valid = candidates.filterValues { it.size == outcomes.size }
        if (valid.isEmpty()) return null

        val base = outcomes.count { it }.toDouble() / outcomes.size
        val uncertainty = base * (1.0 - base)
        if (uncertainty <= 0.0) return null

        val scored = valid.mapValues { (_, ps) ->
            var sum = 0.0
            for (i in ps.indices) {
                val o = if (outcomes[i]) 1.0 else 0.0
                val d = ps[i] - o
                sum += d * d
            }
            sum / ps.size
        }
        val skills = scored.mapValues { (_, bs) -> 1.0 - bs / uncertainty }
        val admissible = skills.filterValues { it > 0.0 }

        if (admissible.isEmpty()) {
            val members = valid.keys.map {
                CombinationMember(it, scored.getValue(it), skills.getValue(it), 0.0, outcomes.size, false)
            }
            return CombinationReport(members, Double.NaN, Double.NaN, outcomes.size) to emptyList()
        }

        val total = admissible.values.sum()
        val weights = admissible.mapValues { (_, s) -> s / total }

        // Logarithmic opinion pool: weighted mean of the log-odds.
        val pooled = DoubleArray(outcomes.size)
        for (i in outcomes.indices) {
            var z = 0.0
            for ((name, w) in weights) {
                val p = valid.getValue(name)[i].coerceIn(1e-6, 1.0 - 1e-6)
                z += w * ln(p / (1.0 - p))
            }
            pooled[i] = 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))
        }
        var sum = 0.0
        for (i in outcomes.indices) {
            val o = if (outcomes[i]) 1.0 else 0.0
            val d = pooled[i] - o
            sum += d * d
        }
        val brier = sum / outcomes.size
        val members = valid.keys.map {
            CombinationMember(
                it, scored.getValue(it), skills.getValue(it),
                weights[it] ?: 0.0, outcomes.size, weights.containsKey(it)
            )
        }
        return CombinationReport(members, brier, 1.0 - brier / uncertainty, outcomes.size) to pooled.toList()
    }

    /** Apply the published weights to one live set of member probabilities. */
    fun pool(weights: Map<String, Double>, live: Map<String, Double>): Double? {
        var z = 0.0
        var used = 0.0
        for ((name, w) in weights) {
            val p = live[name]?.takeIf { it.isFinite() } ?: continue
            z += w * ln(p.coerceIn(1e-6, 1.0 - 1e-6) / (1.0 - p.coerceIn(1e-6, 1.0 - 1e-6)))
            used += w
        }
        if (used <= 0.0) return null
        return 1.0 / (1.0 + exp(-(z / used).coerceIn(-30.0, 30.0)))
    }
}

/** A conformal band computed inside one condition of a Mondrian taxonomy. */
data class ConformalBucket(
    val label: String,
    val lowerBound: Double,
    val upperBound: Double,
    val band: ConformalBand
)

/** The conditional band set, plus the pooled band it refines. */
data class MondrianConformal(
    val buckets: List<ConformalBucket>,
    val pooled: ConformalBand
) {
    /** The band to publish for a session whose conditioning value is [x]. */
    fun bandFor(x: Double?): ConformalBand {
        if (x == null || !x.isFinite()) return pooled
        return buckets.firstOrNull { x >= it.lowerBound && x < it.upperBound }?.band ?: pooled
    }
}

/**
 * Mondrian (label-conditional) conformal prediction.
 *
 * The pooled split-conformal band of §26 is marginally valid: it covers 90 %
 * of sessions *on average*, which means it is too wide in a calm tape and too
 * narrow in a violent one. Vovk et al. (2005, ch. 4) condition the quantile on
 * a taxonomy computed from information available before the outcome; here the
 * taxonomy is the trailing-volatility tercile, which is known at the time of
 * the forecast and so preserves the guarantee inside each bucket.
 *
 * Each bucket publishes its own realised coverage. A bucket with too few
 * residuals falls back to the pooled band rather than reporting a quantile it
 * cannot support.
 */
object MondrianConformalInterval {

    const val MIN_BUCKET = 120
    val DEFAULT_CUTS = listOf(1.0 / 3.0, 2.0 / 3.0)
    val DEFAULT_LABELS = listOf("CALM", "NORMAL", "STRESSED")

    /**
     * @param residuals absolute residuals in sigma units.
     * @param conditioning the value the taxonomy reads, same order and length.
     */
    fun band(
        residuals: List<Double>,
        conditioning: List<Double>,
        alpha: Double
    ): MondrianConformal? {
        val pooled = ConformalInterval.band(residuals, alpha) ?: return null
        if (residuals.size != conditioning.size) return MondrianConformal(emptyList(), pooled)

        val sortedCond = conditioning.filter { it.isFinite() }.sorted()
        if (sortedCond.size < MIN_BUCKET * DEFAULT_LABELS.size) {
            return MondrianConformal(emptyList(), pooled)
        }
        val edges = DEFAULT_CUTS.map { q ->
            val idx = ((sortedCond.size - 1) * q).toInt().coerceIn(0, sortedCond.size - 1)
            sortedCond[idx]
        }
        val bounds = buildList {
            add(Double.NEGATIVE_INFINITY to edges[0])
            add(edges[0] to edges[1])
            add(edges[1] to Double.POSITIVE_INFINITY)
        }
        val buckets = ArrayList<ConformalBucket>()
        for ((i, b) in bounds.withIndex()) {
            val subset = residuals.indices
                .filter { conditioning[it].isFinite() && conditioning[it] >= b.first && conditioning[it] < b.second }
                .map { residuals[it] }
            if (subset.size < MIN_BUCKET) continue
            val band = ConformalInterval.band(subset, alpha) ?: continue
            buckets += ConformalBucket(DEFAULT_LABELS[i], b.first, b.second, band)
        }
        return MondrianConformal(buckets, pooled)
    }
}

/** Out-of-sample information carried by one leg of the panel. */
data class LegInformation(
    val factorId: String,
    val rankCorrelation: Double,
    val hitRate: Double,
    val sampleSize: Int
) {
    /**
     * Student t for the null that the rank correlation is zero:
     * `t = IC·√(n−2) / √(1−IC²)`. With a few thousand sessions an IC of a few
     * hundredths is distinguishable from zero even though it is far too small
     * to move a Brier score, and saying so is more useful than rounding it to
     * "no signal".
     */
    val tStatistic: Double
        get() {
            if (sampleSize < 3) return 0.0
            val r = rankCorrelation.coerceIn(-0.999999, 0.999999)
            return r * sqrt((sampleSize - 2).toDouble()) / sqrt(1.0 - r * r)
        }

    /** Two-sided 5 % on the normal approximation. */
    val significant: Boolean get() = abs(tStatistic) > 1.96

    /** Whether the leg pointed the right way more often than a coin. */
    val informative: Boolean get() = rankCorrelation > 0.0 && hitRate > 0.5
}

/**
 * Spearman rank correlation between each leg's score and the forward return it
 * was supposed to anticipate — the information coefficient of the quantitative
 * literature (Grinold & Kahn, *Active Portfolio Management*).
 *
 * Rank correlation rather than Pearson because the scores are bounded and
 * saturate, so their linear relation to returns is not the question; whether
 * a higher score goes with a higher return is.
 */
object InformationCoefficient {

    const val MIN_SAMPLE = 200

    fun evaluate(factorId: String, scores: List<Double>, forward: List<Double>): LegInformation? {
        if (scores.size != forward.size) return null
        val pairs = scores.indices
            .filter { scores[it].isFinite() && forward[it].isFinite() }
            .map { scores[it] to forward[it] }
        if (pairs.size < MIN_SAMPLE) return null
        val rx = ranks(pairs.map { it.first })
        val ry = ranks(pairs.map { it.second })
        val ic = pearson(rx, ry) ?: return null
        val hits = pairs.count { (s, f) -> (s > 0.0 && f > 0.0) || (s < 0.0 && f < 0.0) }
        val directional = pairs.count { (s, _) -> s != 0.0 }
        if (directional == 0) return null
        return LegInformation(factorId, ic, hits.toDouble() / directional, pairs.size)
    }

    private fun pearson(a: List<Double>, b: List<Double>): Double? {
        if (a.size != b.size || a.size < 2) return null
        val ma = a.average()
        val mb = b.average()
        var num = 0.0
        var va = 0.0
        var vb = 0.0
        for (i in a.indices) {
            val da = a[i] - ma
            val db = b[i] - mb
            num += da * db
            va += da * da
            vb += db * db
        }
        val den = sqrt(va * vb)
        return if (den <= 0.0) null else (num / den).takeIf { it.isFinite() }
    }

    /** Average ranks, ties shared. */
    internal fun ranks(values: List<Double>): List<Double> {
        val order = values.indices.sortedBy { values[it] }
        val out = DoubleArray(values.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && values[order[j + 1]] == values[order[i]]) j++
            val mean = (i + j) / 2.0 + 1.0
            for (k in i..j) out[order[k]] = mean
            i = j + 1
        }
        return out.toList()
    }
}
