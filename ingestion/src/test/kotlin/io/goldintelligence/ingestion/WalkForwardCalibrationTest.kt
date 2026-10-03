package io.goldintelligence.ingestion

import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.GoldSpecification
import io.goldintelligence.engine.Horizon
import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC v2.1 §26.1. The replay is checked for the two things that make it
 * worth having: it must refuse to answer when the history cannot support an
 * answer, and when it does answer, the mapping must be fitted on a strictly
 * earlier slice than the one it is scored on.
 */
class WalkForwardCalibrationTest {

    private val start = Instant.parse("2004-01-02T00:00:00Z")

    private fun bars(n: Int, f: (Int) -> Double) = (0 until n).map { i ->
        val c = f(i)
        Bar(start.plus(Duration.ofDays(i.toLong())), c, c, c, c)
    }

    /** A random walk, so no mapping can be fitted that is not real. */
    private fun walk(n: Int, seed: Int, drift: Double = 0.0): List<Bar> {
        val rng = Random(seed)
        var p = 100.0
        return bars(n) { p *= exp(drift + rng.nextDouble(-0.012, 0.012)); p }
    }

    /** A level series that wanders around [base] — yields, spreads, indices. */
    private fun level(n: Int, seed: Int, base: Double, step: Double): List<Bar> {
        val rng = Random(seed)
        var v = base
        return bars(n) { v = (v + rng.nextDouble(-step, step)).coerceAtLeast(0.01); v }
    }

    /** The measured panel §27 expects, every leg present. */
    private fun universe(n: Int, full: Boolean = true): MarketUniverse {
        val series = mutableMapOf(
            MarketUniverse.GOLD_PROXY_ETF to TimeSeries("GLD", walk(n, 1))
        )
        if (full) {
            series[MarketUniverse.REAL10Y_DEEP] = TimeSeries("DFII10", level(n, 2, 1.5, 0.03))
            series[MarketUniverse.DOLLAR_BROAD_INDEX] = TimeSeries("DTWEXBGS", walk(n, 3))
            series[MarketUniverse.US02Y_DEEP] = TimeSeries("DGS2", level(n, 8, 3.0, 0.04))
            series[MarketUniverse.CURVE_10Y2Y_DEEP] = TimeSeries("T10Y2Y", level(n, 9, 0.5, 0.03))
            series[MarketUniverse.BREAKEVEN10Y_DEEP] = TimeSeries("T10YIE", level(n, 10, 2.3, 0.02))
            series[MarketUniverse.POLICY_UNCERTAINTY_DAILY] = TimeSeries("EPU", level(n, 11, 120.0, 12.0))
            series[MarketUniverse.VIX_DEEP] = TimeSeries("VIXCLS", walk(n, 4))
            series[MarketUniverse.GVZ_DEEP] = TimeSeries("GVZCLS", walk(n, 12))
            series[MarketUniverse.EQUITY_ETF] = TimeSeries("SPY", walk(n, 5))
            series[MarketUniverse.HY_ETF] = TimeSeries("HYG", walk(n, 6))
            series[MarketUniverse.WTI_SPOT] = TimeSeries("DCOILWTICO", walk(n, 7))
            series[MarketUniverse.US10Y_DEEP] = TimeSeries("DGS10", level(n, 13, 4.0, 0.04))
        }
        return MarketUniverse(asOf = start.plus(Duration.ofDays(n.toLong())), series = series)
    }

    private fun calibrator() = WalkForwardCalibration(DiagnosticLog(enabled = false))

    @Test
    fun `no universe yields an empty bundle rather than an invented one`() {
        val bundle = calibrator().run()
        assertTrue(bundle.calibration.isEmpty())
        assertTrue(bundle.brier.isEmpty())
        assertEquals(0.0, bundle.qualityFor(Horizon.D1), 1e-12)
    }

    @Test
    fun `history shorter than the minimum yields nothing`() {
        val c = calibrator()
        c.universe = universe(WalkForwardCalibration.MIN_HISTORY - 1)
        val bundle = c.run()
        assertTrue(bundle.calibration.isEmpty())
        assertEquals(0, bundle.sampleSize)
    }

    @Test
    fun `too few legs yields nothing`() {
        val c = calibrator()
        c.universe = universe(2_000, full = false)
        val bundle = c.run()
        assertTrue(bundle.calibration.isEmpty())
    }

    @Test
    fun `a full panel calibrates the daily and weekly horizons only`() {
        val c = calibrator()
        c.universe = universe(3_000)
        val bundle = c.run()

        assertTrue(bundle.calibration.containsKey(Horizon.D1))
        assertTrue(bundle.calibration.containsKey(Horizon.W1))
        // Daily bars cannot resolve an intraday horizon, so none is claimed.
        listOf(Horizon.M5, Horizon.M15, Horizon.H1, Horizon.H4).forEach {
            assertNull(bundle.calibration[it])
            assertNull(bundle.brier[it])
            assertEquals(0.0, bundle.qualityFor(it), 1e-12)
        }
        assertEquals(WalkForwardCalibration.PANEL.size, bundle.calibratedOn.size)
    }

    @Test
    fun `the scored rows are all forward of the window that fitted them`() {
        val n = 3_000
        val c = calibrator()
        c.universe = universe(n)
        val bundle = c.run()
        val fit = bundle.calibration.getValue(Horizon.D1)
        val record = bundle.brier.getValue(Horizon.D1)

        // Each refit sees everything up to its origin and scores only the
        // block after it, so the scored count is the replayable rows minus
        // the warm-up, rounded down to whole blocks, and the published fit is
        // the last and largest one.
        // Every leg must be populated, so the first replayable session is the
        // widest leg window in, not merely the volatility window.
        val lead = maxOf(WalkForwardCalibration.VOL_WINDOW, WalkForwardCalibration.PANEL.maxOf { it.window })
        val replayable = n - lead - 1
        val blocks = (replayable - WalkForwardCalibration.WARMUP) / WalkForwardCalibration.REFIT_EVERY
        assertEquals(
            WalkForwardCalibration.WARMUP + (blocks - 1) * WalkForwardCalibration.REFIT_EVERY,
            fit.sampleSize
        )
        assertTrue("the published fit uses the most history", fit.sampleSize >= WalkForwardCalibration.WARMUP)
        assertEquals(blocks * WalkForwardCalibration.REFIT_EVERY, record.sampleSize)
        assertTrue(record.sampleSize >= WalkForwardCalibration.MIN_TEST)
        assertTrue("the warm-up must not be scored", record.sampleSize < replayable)
    }

    @Test
    fun `a random walk earns no calibration quality`() {
        val c = calibrator()
        c.universe = universe(3_000)
        val bundle = c.run()
        // Independent random walks carry no information about gold's next
        // session; the bundle must not hand the engine a licence to publish.
        val record = bundle.brier.getValue(Horizon.D1)
        assertTrue("skill ${record.skill} should be near zero on noise", record.skill < 0.02)
        assertTrue(bundle.qualityFor(Horizon.D1) < 0.5)
    }

    @Test
    fun `the murphy identity holds on the replayed record`() {
        val c = calibrator()
        c.universe = universe(3_000)
        val record = c.run().brier.getValue(Horizon.D1)
        assertEquals(
            record.brier,
            record.reliability - record.resolution + record.uncertainty,
            0.02
        )
        assertTrue(record.uncertainty in 0.0..0.25)
        assertTrue(record.baseRate in 0.3..0.7)
    }

    @Test
    fun `the conformal band is measured and reports its realised coverage`() {
        val c = calibrator()
        c.universe = universe(3_000)
        val band = c.run().conformal.getValue(Horizon.D1)
        assertEquals(0.90, band.targetCoverage, 1e-12)
        assertNotNull(band.realisedCoverage)
        assertTrue("realised ${band.realisedCoverage}", band.realisedCoverage!! >= 0.88)
        assertTrue("half width ${band.halfWidth} is in sigma units", band.halfWidth in 0.1..10.0)
        assertTrue(band.sampleSize >= WalkForwardCalibration.MIN_TEST)
    }

    @Test
    fun `the structural reads are produced from the same history`() {
        val c = calibrator()
        c.universe = universe(3_000)
        val bundle = c.run()

        val enb = bundle.effectiveBreadth
        assertNotNull(enb)
        assertTrue("ENB $enb", enb!! >= 1.0 && enb <= WalkForwardCalibration.PANEL.size.toDouble())
        assertTrue(
            "independent walks should spread across most of the bets",
            bundle.breadthRatio!! > 0.5
        )

        assertNotNull(bundle.runLength)
        assertTrue(bundle.runLength!!.changeProbability in 0.0..1.0)
        assertNotNull(bundle.trend)
        assertTrue(bundle.trend!!.momentumCredibility in 0.0..1.0)
        assertNotNull(bundle.filtered)
        assertNotNull(bundle.robustness)
        assertTrue(bundle.robustness!!.contributors >= RobustContributorFloor)
    }

    @Test
    fun `the panel reports the normative weight it actually reproduces`() {
        val declared = WalkForwardCalibration.PANEL.sumOf {
            GoldSpecification.baseFactorWeights.getValue(it.factorId)
        }
        assertEquals(declared, WalkForwardCalibration.coveredWeight, 1e-12)
        assertTrue("the panel must not claim the whole table", WalkForwardCalibration.coveredWeight < 1.0)
        // Every proxy must name a factor that exists in the normative table,
        // and no factor may be claimed twice.
        WalkForwardCalibration.PANEL.forEach {
            assertTrue(it.factorId in GoldSpecification.baseFactorWeights)
        }
        assertEquals(
            WalkForwardCalibration.PANEL.size,
            WalkForwardCalibration.PANEL.map { it.factorId }.distinct().size
        )
    }

    @Test
    fun `a directional panel earns skill the noise panel could not`() {
        // A dollar that cycles slowly, so its trailing trend is persistent,
        // and a gold price that moves against that trailing trend. The panel
        // measures exactly that trend, so a real mapping exists and the
        // replay must find it out of sample rather than report the same null
        // it correctly reports on noise.
        val n = 3_000
        val rng = Random(99)
        val dollarCloses = (0 until n).map { 100.0 * exp(0.12 * kotlin.math.sin(2 * Math.PI * it / 260.0)) }
        val dollar = bars(n) { dollarCloses[it] }

        var g = 100.0
        val gold = bars(n) { i ->
            val trend = if (i < 20) 0.0 else dollarCloses[i] / dollarCloses[i - 20] - 1.0
            g *= exp(-0.45 * trend + rng.nextDouble(-0.0015, 0.0015))
            g
        }
        // The remaining legs are present but almost flat, so they neither
        // add signal nor drown the one that exists.
        fun flat(seed: Int): List<Bar> {
            val r = Random(seed)
            var p = 100.0
            return bars(n) { p *= exp(r.nextDouble(-0.0002, 0.0002)); p }
        }
        val series = mapOf(
            MarketUniverse.GOLD_PROXY_ETF to TimeSeries("GLD", gold),
            MarketUniverse.DOLLAR_ETF to TimeSeries("UUP", dollar),
            MarketUniverse.TIPS_ETF to TimeSeries("TIP", flat(21)),
            MarketUniverse.VIX to TimeSeries("VIX", flat(22)),
            MarketUniverse.EQUITY_ETF to TimeSeries("SPY", flat(23)),
            MarketUniverse.HY_ETF to TimeSeries("HYG", flat(24)),
            MarketUniverse.OIL_ETF to TimeSeries("USO", flat(25))
        )
        val c = calibrator()
        c.universe = MarketUniverse(asOf = start.plus(Duration.ofDays(n.toLong())), series = series)
        val bundle = c.run()

        val record = bundle.brier.getValue(Horizon.D1)
        assertTrue("skill ${record.skill}", record.skill > 0.0)
        assertTrue("resolution ${record.resolution}", record.resolution > 0.0)
        assertTrue("quality", bundle.qualityFor(Horizon.D1) > 0.0)
        val fit = bundle.calibration.getValue(Horizon.D1)
        assertTrue(
            "the mapping must separate the extremes",
            fit.probability(60.0) > fit.probability(-60.0)
        )
    }

    private companion object {
        const val RobustContributorFloor = 5
    }
}
