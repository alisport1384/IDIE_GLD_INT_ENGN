package io.goldintelligence.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * SPEC v2.1 §26. Every component is checked against a fixture whose answer is
 * known analytically or by construction, and every component is checked for
 * the refusal path — returning null on a sample too small to justify an
 * answer is part of the contract, not an edge case.
 */
class AdvancedInferenceTest {

    /* ---------------- Isotonic calibration ---------------- */

    @Test
    fun `isotonic fit recovers a monotone relationship`() {
        val rng = Random(7)
        // True probability rises with the score; the fit must track it.
        val samples = (0 until 2_000).map {
            val score = rng.nextDouble(-100.0, 100.0)
            val p = 1.0 / (1.0 + exp(-score / 40.0))
            score to (rng.nextDouble() < p)
        }
        val fit = IsotonicCalibration.fit(samples)
        assertNotNull(fit)
        fit!!
        assertTrue("fit must be non-decreasing", (1 until fit.probabilities.size).all {
            fit.probabilities[it] >= fit.probabilities[it - 1] - 1e-12
        })
        assertTrue(fit.probability(-80.0) < 0.35)
        assertTrue(fit.probability(80.0) > 0.65)
        assertTrue(fit.probability(-80.0) < fit.probability(80.0))
    }

    @Test
    fun `isotonic clamps outside the fitted range instead of extrapolating`() {
        val samples = (0 until 400).map { i ->
            val s = i / 4.0
            s to (i > 200)
        }
        val fit = IsotonicCalibration.fit(samples)!!
        assertEquals(fit.probability(-1_000.0), fit.probabilities.first(), 1e-12)
        assertEquals(fit.probability(1_000.0), fit.probabilities.last(), 1e-12)
    }

    @Test
    fun `isotonic refuses a sample below the floor`() {
        val samples = (0 until IsotonicCalibration.MIN_SAMPLE - 1).map { it.toDouble() to (it % 2 == 0) }
        assertNull(IsotonicCalibration.fit(samples))
    }

    /* ---------------- Brier / Murphy ---------------- */

    @Test
    fun `murphy decomposition reconstructs the brier score`() {
        val rng = Random(11)
        val samples = (0 until 3_000).map {
            val p = rng.nextDouble(0.05, 0.95)
            p to (rng.nextDouble() < p)
        }
        val r = BrierDecomposition.evaluate(samples)!!
        // BS = REL - RES + UNC, exact up to the within-bin terms of binning.
        assertEquals(r.brier, r.reliability - r.resolution + r.uncertainty, 0.01)
        assertTrue("a well-calibrated forecaster has low reliability", r.reliability < 0.01)
        assertTrue(r.skill > 0.0)
    }

    @Test
    fun `a perfect forecaster scores zero and a reversed one scores worst`() {
        val perfect = (0 until 500).map { (if (it % 2 == 0) 1.0 else 0.0) to (it % 2 == 0) }
        val worst = (0 until 500).map { (if (it % 2 == 0) 0.0 else 1.0) to (it % 2 == 0) }
        assertEquals(0.0, BrierDecomposition.evaluate(perfect)!!.brier, 1e-12)
        assertEquals(1.0, BrierDecomposition.evaluate(worst)!!.brier, 1e-12)
        assertTrue(BrierDecomposition.evaluate(worst)!!.skill < 0.0)
    }

    @Test
    fun `brier refuses a sample below the floor`() {
        assertNull(BrierDecomposition.evaluate(listOf(0.5 to true, 0.5 to false)))
    }

    /* ---------------- Split conformal ---------------- */

    @Test
    fun `conformal half width uses the finite sample rank`() {
        // Residuals 1..100: ceil((100+1) * 0.9) = 91 -> the 91st smallest.
        val residuals = (1..100).map { it.toDouble() }
        val band = ConformalInterval.band(residuals, alpha = 0.10)!!
        assertEquals(91.0, band.halfWidth, 1e-12)
        assertEquals(0.90, band.targetCoverage, 1e-12)
        assertEquals(0.91, band.realisedCoverage!!, 1e-12)
        assertEquals(100, band.sampleSize)
    }

    @Test
    fun `conformal coverage holds on exchangeable draws`() {
        val rng = Random(3)
        val calibration = (0 until 2_000).map { abs(rng.nextDouble() * 2.0 - 1.0) }
        val band = ConformalInterval.band(calibration, alpha = 0.10)!!
        val fresh = (0 until 5_000).map { abs(rng.nextDouble() * 2.0 - 1.0) }
        val covered = fresh.count { it <= band.halfWidth }.toDouble() / fresh.size
        assertTrue("nominal 90% coverage, measured $covered", covered >= 0.88)
    }

    @Test
    fun `conformal refuses a short or invalid sample`() {
        assertNull(ConformalInterval.band(listOf(1.0, 2.0), 0.10))
        assertNull(ConformalInterval.band((1..100).map { it.toDouble() }, alpha = 0.0))
    }

    /* ---------------- Effective number of bets ---------------- */

    @Test
    fun `identical columns count as one bet and orthogonal ones as all of them`() {
        val rng = Random(5)
        val base = (0 until 400).map { rng.nextDouble(-1.0, 1.0) }
        val clones = mapOf("a" to base, "b" to base, "c" to base, "d" to base)
        val enb = EffectiveBreadth.of(clones)!!
        assertEquals("perfectly correlated inputs are one witness", 1.0, enb, 0.05)

        val independent = (0 until 4).associate { k ->
            "f$k" to (0 until 400).map { rng.nextDouble(-1.0, 1.0) }
        }
        val spread = EffectiveBreadth.of(independent)!!
        assertTrue("independent inputs approach the column count: $spread", spread > 3.5)
        assertEquals(spread / 4.0, EffectiveBreadth.ratio(independent)!!, 1e-9)
    }

    @Test
    fun `breadth refuses fewer than two usable columns`() {
        assertNull(EffectiveBreadth.of(mapOf("a" to (1..100).map { it.toDouble() })))
        assertNull(EffectiveBreadth.of(mapOf("a" to listOf(1.0, 2.0), "b" to listOf(2.0, 1.0))))
    }

    @Test
    fun `jacobi reproduces known eigenvalues`() {
        // Correlation matrix [[1, r], [1, r]] has eigenvalues 1±r.
        val r = 0.6
        val eigen = Jacobi.eigenvalues(arrayOf(doubleArrayOf(1.0, r), doubleArrayOf(r, 1.0)))!!.sorted()
        assertEquals(1.0 - r, eigen[0], 1e-9)
        assertEquals(1.0 + r, eigen[1], 1e-9)
        // Trace is preserved for any symmetric matrix.
        val m = arrayOf(
            doubleArrayOf(2.0, -1.0, 0.0),
            doubleArrayOf(-1.0, 2.0, -1.0),
            doubleArrayOf(0.0, -1.0, 2.0)
        )
        assertEquals(6.0, Jacobi.eigenvalues(m)!!.sum(), 1e-9)
    }

    /* ---------------- Changepoint detection ---------------- */

    @Test
    fun `changepoint detector finds a variance break and dates it`() {
        val rng = Random(13)
        val calm = (0 until 400).map { rng.nextDouble(-0.5, 0.5) }
        val wild = (0 until 30).map { rng.nextDouble(-8.0, 8.0) }
        val posterior = ChangepointDetector(expectedRunLength = 250.0).process(calm + wild)!!
        assertTrue(
            "the run should be at most as old as the break: ${posterior.mapRunLength}",
            posterior.mapRunLength <= 60
        )
        assertTrue(posterior.youngRegimeProbability >= 0.0 && posterior.youngRegimeProbability <= 1.0)
    }

    @Test
    fun `a stationary series reports an old run and a stable regime`() {
        val rng = Random(17)
        val calm = (0 until 600).map { rng.nextDouble(-0.5, 0.5) }
        val posterior = ChangepointDetector(expectedRunLength = 250.0).process(calm)!!
        assertTrue("run length ${posterior.mapRunLength}", posterior.mapRunLength > ChangepointDetector.YOUNG_RUN)
        assertTrue(posterior.changeProbability < 0.25)
        assertEquals(Stability.HIGH, posterior.stability)
    }

    @Test
    fun `changepoint refuses a short series`() {
        assertNull(ChangepointDetector().process((1..10).map { it.toDouble() }))
    }

    /* ---------------- Trend validity ---------------- */

    @Test
    fun `variance ratio separates a trend from a random walk`() {
        val rng = Random(19)
        var p = 100.0
        val walk = ArrayList<Double>()
        repeat(800) { walk += p; p *= exp(rng.nextDouble(-0.01, 0.01)) }
        val rw = TrendValidityTest.evaluate(walk, lag = 5)!!
        assertTrue("a random walk should not be rejected: z=${rw.zStatistic}", abs(rw.zStatistic) < 3.0)

        var q = 100.0
        val trending = ArrayList<Double>()
        // A persistent drift plus small noise: variance grows faster than q.
        repeat(800) { trending += q; q *= exp(0.002 + rng.nextDouble(-0.001, 0.001)) }
        val tr = TrendValidityTest.evaluate(trending, lag = 5)!!
        assertTrue("trending VR should exceed 1: ${tr.varianceRatio}", tr.varianceRatio > 1.0)
    }

    @Test
    fun `momentum credibility is capped when the random walk stands`() {
        val v = TrendValidity(varianceRatio = 1.02, zStatistic = 0.4, hurst = 0.5, observations = 500, lag = 5)
        assertEquals("RANDOM_WALK", v.label)
        assertEquals(0.5, v.momentumCredibility, 1e-12)
        val mr = TrendValidity(0.6, -4.0, 0.35, 500, 5)
        assertEquals("MEAN_REVERTING", mr.label)
        assertEquals(0.25, mr.momentumCredibility, 1e-12)
        val tr = TrendValidity(1.5, 4.0, 0.65, 500, 5)
        assertEquals("TRENDING", tr.label)
        assertEquals(1.0, tr.momentumCredibility, 1e-12)
    }

    @Test
    fun `hurst of a random walk sits near one half`() {
        val rng = Random(23)
        val returns = (0 until 2_048).map { rng.nextDouble(-1.0, 1.0) }
        val h = TrendValidityTest.hurst(returns)!!
        assertTrue("H=$h", h in 0.35..0.65)
    }

    @Test
    fun `trend validity refuses a short series`() {
        assertNull(TrendValidityTest.evaluate((1..50).map { 100.0 + it }, lag = 5))
    }

    /* ---------------- Kalman local level ---------------- */

    @Test
    fun `local level filter tracks the level and smooths the noise`() {
        val rng = Random(29)
        val truth = 42.0
        val noisy = (0 until 300).map { truth + rng.nextDouble(-10.0, 10.0) }
        val f = LocalLevelFilter.run(noisy, signalToNoise = 0.02)!!
        assertTrue("filtered ${f.level} should sit near $truth", abs(f.level - truth) < 4.0)
        assertTrue(f.gain > 0.0 && f.gain < 1.0)
        assertTrue(f.standardError > 0.0)
    }

    @Test
    fun `local level filter refuses a short series`() {
        assertNull(LocalLevelFilter.run(listOf(1.0, 2.0, 3.0)))
    }

    /* ---------------- Robust aggregate ---------------- */

    @Test
    fun `fragility is flagged when one contribution carries the call`() {
        val balanced = listOf(8.0, 7.0, 9.0, 8.5, 7.5, 8.2)
        assertTrue(RobustAggregate.evaluate(8.1, balanced)!!.fragility < RobustAggregate.FRAGILE_GAP)

        // One factor at +90 drags the weighted composite far above what the
        // rest of the evidence says on its own.
        val lopsided = listOf(0.5, -0.4, 0.2, -0.1, 0.3, 90.0)
        val r = RobustAggregate.evaluate(45.0, lopsided)!!
        assertTrue("gap ${r.fragility}", r.fragile)
        assertTrue(abs(r.trimmed) < abs(r.weighted))
        assertEquals(6, r.contributors)
    }

    @Test
    fun `robust aggregate refuses too few contributors`() {
        assertNull(RobustAggregate.evaluate(1.0, listOf(1.0, 2.0, 3.0)))
    }

    /* ---------------- Bundle ---------------- */

    @Test
    fun `bundle quality is zero without skill and scales with sample size`() {
        val fit = IsotonicCalibration.fit((0 until 800).map { (it / 8.0 - 50.0) to (it % 3 != 0) })!!
        val noSkill = BrierReport(0.25, 0.0, 0.0, 0.25, 0.0, 0.5, 800, 10)
        val skilled = BrierReport(0.23, 0.001, 0.02, 0.25, 0.08, 0.5, 800, 10)

        assertEquals(
            0.0,
            InferenceBundle(
                calibration = mapOf(Horizon.D1 to fit),
                brier = mapOf(Horizon.D1 to noSkill)
            ).qualityFor(Horizon.D1),
            1e-12
        )
        val q = InferenceBundle(
            calibration = mapOf(Horizon.D1 to fit),
            brier = mapOf(Horizon.D1 to skilled)
        ).qualityFor(Horizon.D1)
        assertTrue("quality $q", q > 0.0 && q <= 1.0)
        assertEquals(0.0, InferenceBundle().qualityFor(Horizon.W1), 1e-12)
    }

    /* ---------------- Engine wiring ---------------- */

    @Test
    fun `an empty bundle leaves the engine exactly as it was`() {
        val input = snapshot()
        val plain = MultiHorizonEngine().evaluate(input, MarketContext(sigmaByHorizon = sigma()))
        val withEmpty = MultiHorizonEngine().evaluate(
            input,
            MarketContext(sigmaByHorizon = sigma(), inference = InferenceBundle()),
            plain.generatedAt
        )
        val a = plain.horizons.first { it.horizon == Horizon.D1 }
        val b = withEmpty.horizons.first { it.horizon == Horizon.D1 }
        assertEquals(a.goldBias!!, b.goldBias!!, 1e-12)
        assertEquals("UNCALIBRATED_NO_SAMPLE", a.probabilityStatus)
        assertEquals("UNCALIBRATED_NO_SAMPLE", b.probabilityStatus)
        assertTrue(!InferenceBundle().measured(Horizon.D1))
        assertEquals(ExpectedMoveEngine.GAUSSIAN, b.expectedMove!!.intervalSource)
    }

    @Test
    fun `a walk-forward bundle publishes a calibrated probability and a conformal band`() {
        val rng = Random(31)
        val fit = IsotonicCalibration.fit(
            (0 until 1_500).map {
                val s = rng.nextDouble(-100.0, 100.0)
                s to (rng.nextDouble() < 1.0 / (1.0 + exp(-s / 40.0)))
            }
        )!!
        val bundle = InferenceBundle(
            calibration = mapOf(Horizon.D1 to fit),
            brier = mapOf(Horizon.D1 to BrierReport(0.23, 0.002, 0.02, 0.25, 0.08, 0.5, 900, 10)),
            conformal = mapOf(Horizon.D1 to ConformalBand(1.21, 0.90, 0.903, 900)),
            breadthRatio = 0.5,
            trend = TrendValidity(1.4, 3.0, 0.6, 500, 5),
            sampleSize = 900,
            calibratedOn = listOf("F01_REAL_RATE", "F02_USD")
        )
        val report = MultiHorizonEngine().evaluate(
            snapshot(),
            MarketContext(sigmaByHorizon = sigma(), inference = bundle)
        )
        val d1 = report.horizons.first { it.horizon == Horizon.D1 }

        assertEquals("CALIBRATED_WALK_FORWARD", d1.probabilityStatus)
        assertNotNull(d1.probability)
        assertNotNull(d1.rawProbability)
        assertEquals(fit.probability(d1.goldBias!!), d1.probability!!, 1e-12)
        assertNotNull(d1.calibrationRecord)

        val band = d1.expectedMove!!
        assertEquals(ExpectedMoveEngine.CONFORMAL, band.intervalSource)
        assertEquals(1.21, band.halfWidthSigma, 1e-12)
        assertEquals(band.point - 1.21 * band.sigma, band.p5, 1e-9)
        assertNotNull(report.inference)

        // A horizon with no mapping is untouched and stays uncalibrated.
        val w1 = report.horizons.first { it.horizon == Horizon.W1 }
        assertEquals("UNCALIBRATED_NO_SAMPLE", w1.probabilityStatus)
        assertNull(w1.probability)
    }

    @Test
    fun `redundant evidence shrinks the agreement term`() {
        val engine = SpecConfidenceEngine()
        fun conf(agreement: Double) = engine.evaluate(
            ConfidenceInputs(
                dataQuality = 0.8, crossMarketConfirmation = 0.6, modelAgreement = agreement,
                regimeStability = Stability.HIGH, calibrationQuality = 0.5,
                conflictRatio = 0.1, regimeTransition = false, coverage = 1.0
            )
        ).value
        // Breadth ratio 0.25 pulls an agreement of 0.9 back toward 0.5.
        val corrected = 0.5 + (0.9 - 0.5) * 0.25
        assertEquals(0.6, corrected, 1e-12)
        assertTrue(conf(corrected) < conf(0.9))
    }

    @Test
    fun `a random walk tape shrinks the expected move without widening the band`() {
        val full = ExpectedMoveEngine.compute(Horizon.D1, sigma = 10.0, probability = 0.70)!!
        val shrunk = ExpectedMoveEngine.compute(
            Horizon.D1, sigma = 10.0, probability = 0.70, momentumCredibility = 0.5
        )!!
        assertEquals(full.point / 2.0, shrunk.point, 1e-12)
        assertEquals(full.halfWidthSigma, shrunk.halfWidthSigma, 1e-12)
    }

    /* ---------------- fixtures ---------------- */

    private fun sigma(): Map<Horizon, Double> = Horizon.entries.associateWith { 10.0 }

    private fun snapshot(): InputSnapshot {
        val now = java.time.Instant.parse("2026-01-02T00:00:00Z")
        val scores = GoldSpecification.baseFactorWeights.keys.mapIndexed { i, id ->
            FactorScore(
                factorId = id,
                score = if (i % 3 == 0) 40.0 else -15.0,
                quality = 0.8,
                asOf = now
            )
        }
        return InputSnapshot(
            observations = emptyMap(),
            features = FeatureSet(emptyMap()),
            factorScores = scores
        )
    }

    /** Guards the documented relationship that makes the sigma band comparable. */
    @Test
    fun `log returns of a constant series are zero`() {
        val closes = List(200) { 100.0 }
        val rs = (1 until closes.size).map { ln(closes[it] / closes[it - 1]) }
        assertEquals(0.0, rs.sum(), 1e-12)
        assertEquals(0.0, sqrt(rs.sumOf { it * it }), 1e-12)
    }
}

/**
 * SPEC v2.1 §26.3 — "measured and found to have no skill" must be published
 * as a different answer from "never measured".
 */
class CalibrationStatusTest {

    @Test
    fun `a measured horizon without skill is not reported as unmeasured`() {
        val fit = IsotonicCalibration.fit((0 until 600).map { (it / 3.0 - 100.0) to (it % 2 == 0) })!!
        val noSkill = BrierReport(0.2499, 0.0016, 0.0013, 0.2484, -0.0063, 0.54, 1646, 10)
        val bundle = InferenceBundle(
            calibration = mapOf(Horizon.D1 to fit),
            brier = mapOf(Horizon.D1 to noSkill),
            sampleSize = 1646
        )
        assertTrue(bundle.measured(Horizon.D1))
        assertTrue(!bundle.measured(Horizon.H4))
        assertEquals(0.0, bundle.qualityFor(Horizon.D1), 1e-12)

        val scores = GoldSpecification.baseFactorWeights.keys.map {
            FactorScore(it, 20.0, 0.8, java.time.Instant.parse("2026-01-02T00:00:00Z"))
        }
        val report = MultiHorizonEngine().evaluate(
            InputSnapshot(emptyMap(), FeatureSet(emptyMap()), scores),
            MarketContext(sigmaByHorizon = Horizon.entries.associateWith { 9.0 }, inference = bundle)
        )
        assertEquals(
            "UNCALIBRATED_NO_SKILL",
            report.horizons.first { it.horizon == Horizon.D1 }.probabilityStatus
        )
        assertEquals(
            "UNCALIBRATED_NO_SAMPLE",
            report.horizons.first { it.horizon == Horizon.H4 }.probabilityStatus
        )
        assertNull(report.horizons.first { it.horizon == Horizon.D1 }.probability)
    }
}

/**
 * SPEC v2.1 §27 — the second forecaster, the combination, the conditional
 * band and the per-leg information coefficient.
 */
class AdvancedInferenceV27Test {

    private fun rng(seed: Int) = kotlin.random.Random(seed)

    @Test
    fun `ridge logistic recovers a known linear separation`() {
        val r = rng(7)
        val rows = ArrayList<DoubleArray>()
        val y = ArrayList<Boolean>()
        repeat(1_200) {
            val a = r.nextDouble(-3.0, 3.0)
            val b = r.nextDouble(-3.0, 3.0)
            val noise = r.nextDouble(-0.5, 0.5)
            rows += doubleArrayOf(a, b, r.nextDouble(-3.0, 3.0))
            y += (1.5 * a - 1.0 * b + noise) > 0.0
        }
        val fit = RidgeLogistic.fit(rows, y)!!
        assertEquals(1_200, fit.sampleSize)
        // The informative coefficients must carry the right signs and dominate
        // the noise column.
        assertTrue("a ${fit.coefficients[0]}", fit.coefficients[0] > 0.0)
        assertTrue("b ${fit.coefficients[1]}", fit.coefficients[1] < 0.0)
        assertTrue(abs(fit.coefficients[2]) < abs(fit.coefficients[0]))
        assertTrue(fit.probability(doubleArrayOf(2.0, -2.0, 0.0))!! > 0.8)
        assertTrue(fit.probability(doubleArrayOf(-2.0, 2.0, 0.0))!! < 0.2)
    }

    @Test
    fun `the ridge penalty shrinks a pure-noise panel toward the base rate`() {
        val r = rng(11)
        val rows = (0 until 800).map { DoubleArray(6) { r.nextDouble(-2.0, 2.0) } }
        val y = (0 until 800).map { r.nextDouble() < 0.55 }
        val fit = RidgeLogistic.fit(rows, y)!!
        // No column carries information, so every slope must be small and the
        // intercept must sit near the base rate.
        fit.coefficients.forEach { assertTrue("slope $it", abs(it) < 0.35) }
        val p = fit.probability(DoubleArray(6))!!
        assertEquals(0.55, p, 0.08)
    }

    @Test
    fun `the ridge fit refuses a sample it cannot support`() {
        val r = rng(3)
        val rows = (0 until RidgeLogistic.MIN_SAMPLE - 1).map { DoubleArray(3) { r.nextDouble() } }
        assertNull(RidgeLogistic.fit(rows, rows.map { true }))
    }

    @Test
    fun `combination admits only members with positive out-of-sample skill`() {
        val r = rng(23)
        val outcomes = (0 until 600).map { r.nextDouble() < 0.5 }
        // A genuinely informative member, and one that is systematically wrong.
        val good = outcomes.map { if (it) 0.70 else 0.30 }
        val bad = outcomes.map { if (it) 0.25 else 0.75 }
        val (report, pooled) = ForecastCombination.evaluate(
            mapOf("GOOD" to good, "BAD" to bad), outcomes
        )!!
        assertEquals(1, report.usedCount)
        assertTrue(report.members.first { it.name == "GOOD" }.used)
        assertTrue(!report.members.first { it.name == "BAD" }.used)
        assertEquals(1.0, report.members.first { it.name == "GOOD" }.weight, 1e-9)
        assertEquals(outcomes.size, pooled.size)
        assertTrue("combined skill ${report.skill}", report.skill > 0.0)
    }

    @Test
    fun `a combination of worthless members publishes nothing`() {
        val r = rng(31)
        val outcomes = (0 until 400).map { r.nextDouble() < 0.5 }
        val noise = outcomes.indices.map { 0.5 + r.nextDouble(-0.02, 0.02) }
        val wrong = outcomes.map { if (it) 0.4 else 0.6 }
        val (report, pooled) = ForecastCombination.evaluate(
            mapOf("NOISE" to noise, "WRONG" to wrong), outcomes
        )!!
        assertEquals(0, report.usedCount)
        assertTrue(pooled.isEmpty())
    }

    @Test
    fun `pooling is in log-odds and is order independent`() {
        val a = ForecastCombination.pool(mapOf("x" to 0.5, "y" to 0.5), mapOf("x" to 0.8, "y" to 0.2))!!
        assertEquals(0.5, a, 1e-9)
        val b = ForecastCombination.pool(mapOf("y" to 0.5, "x" to 0.5), mapOf("y" to 0.2, "x" to 0.8))!!
        assertEquals(a, b, 1e-12)
        // A member with no live reading simply drops out, renormalised.
        val c = ForecastCombination.pool(mapOf("x" to 0.5, "y" to 0.5), mapOf("x" to 0.8))!!
        assertEquals(0.8, c, 1e-9)
        assertNull(ForecastCombination.pool(mapOf("x" to 0.5), emptyMap()))
    }

    @Test
    fun `the conditional band is narrower where the tape is calm`() {
        val r = rng(41)
        // Residuals scale with the conditioning variable, which is exactly the
        // case the pooled band gets wrong in both tails.
        val cond = ArrayList<Double>()
        val resid = ArrayList<Double>()
        repeat(1_500) {
            val v = r.nextDouble(0.2, 3.0)
            cond += v
            resid += abs(r.nextDouble(-1.0, 1.0)) * v
        }
        val m = MondrianConformalInterval.band(resid, cond, 0.10)!!
        assertEquals(3, m.buckets.size)
        val calm = m.buckets.first { it.label == "CALM" }
        val stressed = m.buckets.first { it.label == "STRESSED" }
        assertTrue("calm ${calm.band.halfWidth} < stressed ${stressed.band.halfWidth}",
            calm.band.halfWidth < stressed.band.halfWidth)
        // Each bucket keeps its own nominal coverage.
        m.buckets.forEach { assertTrue(it.band.realisedCoverage!! >= 0.88) }
        // Selection reads the conditioning value.
        assertEquals(calm.band.halfWidth, m.bandFor(0.3).halfWidth, 1e-12)
        assertEquals(stressed.band.halfWidth, m.bandFor(2.9).halfWidth, 1e-12)
        assertEquals(m.pooled.halfWidth, m.bandFor(null).halfWidth, 1e-12)
    }

    @Test
    fun `too few residuals fall back to the pooled band rather than invent buckets`() {
        val r = rng(43)
        val resid = (0 until 200).map { abs(r.nextDouble(-1.0, 1.0)) }
        val cond = (0 until 200).map { r.nextDouble() }
        val m = MondrianConformalInterval.band(resid, cond, 0.10)!!
        assertTrue(m.buckets.isEmpty())
        assertTrue(m.pooled.halfWidth > 0.0)
    }

    @Test
    fun `the information coefficient finds a real relation and rejects noise`() {
        val r = rng(53)
        val scores = (0 until 800).map { r.nextDouble(-100.0, 100.0) }
        val real = scores.map { it * 0.01 + r.nextDouble(-0.5, 0.5) }
        val ic = InformationCoefficient.evaluate("F01", scores, real)!!
        assertTrue("ic ${ic.rankCorrelation}", ic.rankCorrelation > 0.3)
        assertTrue(ic.significant)
        assertTrue(ic.informative)
        assertTrue(ic.hitRate > 0.5)

        val noise = scores.map { r.nextDouble(-1.0, 1.0) }
        val none = InformationCoefficient.evaluate("F02", scores, noise)!!
        assertTrue("noise ic ${none.rankCorrelation}", abs(none.rankCorrelation) < 0.1)
        assertTrue(!none.significant)
    }

    @Test
    fun `an inverted leg reports a negative coefficient rather than hiding it`() {
        val r = rng(59)
        val scores = (0 until 600).map { r.nextDouble(-100.0, 100.0) }
        val inverted = scores.map { -it * 0.01 + r.nextDouble(-0.4, 0.4) }
        val ic = InformationCoefficient.evaluate("F21", scores, inverted)!!
        assertTrue(ic.rankCorrelation < -0.3)
        assertTrue(ic.significant)
        assertTrue("an inverted leg is not informative", !ic.informative)
    }

    @Test
    fun `the bundle prefers the conditional band and falls back to the pooled one`() {
        val pooled = ConformalBand(1.80, 0.90, 0.901, 3000)
        val calm = ConformalBand(1.20, 0.90, 0.903, 1000)
        val m = MondrianConformal(
            buckets = listOf(ConformalBucket("CALM", Double.NEGATIVE_INFINITY, 1.0, calm)),
            pooled = pooled
        )
        val withCond = InferenceBundle(
            conformal = mapOf(Horizon.D1 to pooled),
            mondrian = mapOf(Horizon.D1 to m),
            liveVolatilityPct = 0.5
        )
        assertEquals(1.20, withCond.bandFor(Horizon.D1)!!.halfWidth, 1e-12)

        val outsideBucket = withCond.copy(liveVolatilityPct = 9.0)
        assertEquals(1.80, outsideBucket.bandFor(Horizon.D1)!!.halfWidth, 1e-12)

        val noCond = InferenceBundle(conformal = mapOf(Horizon.D1 to pooled))
        assertEquals(1.80, noCond.bandFor(Horizon.D1)!!.halfWidth, 1e-12)
        assertNull(InferenceBundle().bandFor(Horizon.D1))
    }

    @Test
    fun `the engine pools the members the replay admitted`() {
        val fit = IsotonicCalibration.fit((0 until 900).map { (it / 4.5 - 100.0) to (it > 430) })!!
        val combination = CombinationReport(
            members = listOf(
                CombinationMember("ISOTONIC_COMPOSITE", 0.24, 0.04, 0.5, 2000, true),
                CombinationMember("RIDGE_PANEL", 0.24, 0.04, 0.5, 2000, true)
            ),
            brier = 0.238, skill = 0.045, sampleSize = 2000
        )
        val bundle = InferenceBundle(
            calibration = mapOf(Horizon.D1 to fit),
            brier = mapOf(
                Horizon.D1 to BrierReport(0.238, 0.001, 0.012, 0.249, 0.045, 0.52, 2000, 10)
            ),
            combination = mapOf(Horizon.D1 to combination),
            sampleSize = 2000,
            liveRidgeProbability = 0.90
        )
        val scores = GoldSpecification.baseFactorWeights.keys.map {
            FactorScore(it, 60.0, 0.9, java.time.Instant.parse("2026-02-02T00:00:00Z"))
        }
        val report = MultiHorizonEngine().evaluate(
            InputSnapshot(emptyMap(), FeatureSet(emptyMap()), scores),
            MarketContext(sigmaByHorizon = Horizon.entries.associateWith { 10.0 }, inference = bundle)
        )
        val d1 = report.horizons.first { it.horizon == Horizon.D1 }
        assertEquals("CALIBRATED_WALK_FORWARD", d1.probabilityStatus)
        val published = d1.probability!!
        val isotonic = fit.probability(60.0)
        val ridge = 0.90
        // The published figure is the log-odds pool of both members, so it
        // sits strictly between them rather than equalling either one.
        assertTrue(
            "published $published, isotonic $isotonic, ridge $ridge",
            published > minOf(isotonic, ridge) && published < maxOf(isotonic, ridge)
        )

        // Drop the ridge reading and the engine falls back to the isotonic
        // member alone rather than to a figure nothing produced.
        val withoutRidge = MultiHorizonEngine().evaluate(
            InputSnapshot(emptyMap(), FeatureSet(emptyMap()), scores),
            MarketContext(
                sigmaByHorizon = Horizon.entries.associateWith { 10.0 },
                inference = bundle.copy(liveRidgeProbability = null)
            )
        ).horizons.first { it.horizon == Horizon.D1 }
        // The pool clamps the log-odds, so a lone member at the endpoint comes
        // back a hair inside it: the published probability never asserts
        // certainty, which is the intended behaviour.
        assertEquals(isotonic, withoutRidge.probability!!, 1e-5)
        assertTrue(withoutRidge.probability!! < 1.0)
    }
}
