package io.goldintelligence.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

class SpecV2Test {

    /* ---- §D2 weight table ---- */

    @Test
    fun `base weights sum to one`() {
        assertEquals(1.0, GoldSpecification.baseFactorWeights.values.sum(), 1e-9)
        assertTrue(GoldSpecification.weightsAreNormalized())
    }

    @Test
    fun `every declared factor carries a non-zero prior`() {
        assertEquals(22, GoldSpecification.baseFactorWeights.size)
        assertEquals(22, FactorCatalog.factors.size)
        GoldSpecification.baseFactorWeights.forEach { (id, w) ->
            assertTrue("$id must carry a non-zero prior", w > 0.0)
            assertNotNull("$id must exist in the catalog", FactorCatalog.byId[id])
        }
    }

    @Test
    fun `group subtotals reconcile with the factor table`() {
        assertEquals(1.0, FactorCatalog.groupWeights.values.sum(), 1e-9)
        assertEquals(0.52, FactorCatalog.groupWeights["MONETARY"]!!, 1e-9)
        assertEquals(0.12, FactorCatalog.groupWeights["RISK"]!!, 1e-9)
    }

    /* ---- §D1 horizon gating ---- */

    @Test
    fun `coverage rises monotonically with horizon`() {
        var previous = -1.0
        for (h in Horizon.ordered) {
            val c = HorizonGate.coverage(h)
            assertTrue("coverage must be a share", c in 0.0..1.0)
            assertTrue("coverage must not fall as the horizon lengthens", c >= previous)
            previous = c
        }
        assertEquals(1.0, HorizonGate.coverage(Horizon.W1), 1e-9)
    }

    @Test
    fun `five minute coverage reflects the thin free intraday stack`() {
        assertEquals(0.35, HorizonGate.coverage(Horizon.M5), 1e-9)
        assertEquals(0.88, HorizonGate.coverage(Horizon.D1), 1e-9)
    }

    @Test
    fun `gating renormalizes active weights to one`() {
        val gate = HorizonGate.gate(Horizon.M5, GoldSpecification.baseFactorWeights.keys)
        assertEquals(1.0, gate.renormalizedBaseWeights.values.sum(), 1e-9)
        assertTrue(gate.gatedFactorIds.contains("F01_REAL_RATE"))
        assertTrue(gate.activeFactorIds.contains("F02_USD"))
    }

    @Test
    fun `short horizons never publish a numeric probability`() {
        assertEquals(HorizonMode.DIRECTIONAL_ONLY, HorizonGate.modeFor(Horizon.M5))
        assertEquals(HorizonMode.DIRECTIONAL_ONLY, HorizonGate.modeFor(Horizon.M15))
        assertEquals(HorizonMode.CALIBRATED, HorizonGate.modeFor(Horizon.H1))
    }

    /* ---- §D4.1 quality ---- */

    @Test
    fun `quality decays with staleness and is capped for proxies`() {
        val day = 86_400L
        assertEquals(1.0, DataQuality.score(Tier.A, 0, day), 1e-9)
        assertTrue(DataQuality.score(Tier.A, day * 4, day) <= CalibrationDefaults.QUALITY_STALENESS_FLOOR + 1e-9)
        assertTrue(DataQuality.score(Tier.A, 0, day, isProxy = true) <= CalibrationDefaults.QUALITY_PROXY_CEILING)
    }

    @Test
    fun `aggregate quality is pulled down by the weakest input`() {
        val balanced = DataQuality.aggregate(listOf(0.9, 0.9))
        val oneWeak = DataQuality.aggregate(listOf(0.9, 0.1))
        assertTrue(oneWeak < balanced - 0.3)
    }

    /* ---- §D4.3 dominance ---- */

    @Test
    fun `dominance reflects explanatory power not score magnitude`() {
        // Gold must have a varying first difference, otherwise no series can
        // correlate with it and dominance is undefined rather than low.
        val steps = listOf(1.0, -2.0, 3.0, 0.5, -1.5, 2.5, -0.5, 1.0)
        val gold = ArrayList<Double>().apply {
            var v = 100.0
            repeat(40) { add(v); v += steps[it % steps.size] }
        }
        val tracking = gold.map { 10.0 + it * 2.0 }
        val noise = (1..40).map { if (it % 2 == 0) 5.0 else -5.0 }
        val d = RollingR2DominanceEngine().dominance(
            mapOf("TRACK" to tracking, "NOISE" to noise), gold
        )
        assertTrue(d["TRACK"]!! > d["NOISE"]!!)
        assertTrue(d["TRACK"]!! <= CalibrationDefaults.DOMINANCE_MAX + 1e-9)
        assertTrue(d["NOISE"]!! >= CalibrationDefaults.DOMINANCE_MIN - 1e-9)
    }

    /* ---- §D4.4 time decay ---- */

    @Test
    fun `half life is honoured exactly`() {
        val now = Instant.parse("2026-01-02T00:00:00Z")
        val asOf = now.minusSeconds((CalibrationDefaults.TIME_DECAY_HALF_LIFE_HOURS * 3600).toLong())
        assertEquals(0.5, TimeDecay.factor(asOf, now), 1e-6)
    }

    /* ---- §D4.5 cross-market confirmation ---- */

    @Test
    fun `confirmation counts only witnesses that actually moved`() {
        val w = listOf(
            CrossMarketConfirmation.Witness("DXY", 1.0),
            CrossMarketConfirmation.Witness("US10Y", 1.0),
            CrossMarketConfirmation.Witness("SILVER", -1.0),
            CrossMarketConfirmation.Witness("OIL", null)
        )
        val r = CrossMarketConfirmation.compute(w, Direction.BULLISH)
        assertEquals(2.0 / 3.0, r.value!!, 1e-9)
        assertTrue(r.missing.contains("OIL"))
    }

    /* ---- §D4.6 confidence ---- */

    @Test
    fun `confidence is scaled by coverage`() {
        val engine = SpecConfidenceEngine()
        fun run(coverage: Double) = engine.evaluate(
            ConfidenceInputs(
                dataQuality = 0.9,
                crossMarketConfirmation = 0.9,
                modelAgreement = 0.9,
                regimeStability = Stability.HIGH,
                calibrationQuality = 1.0,
                conflictRatio = 0.0,
                regimeTransition = false,
                coverage = coverage
            )
        )
        val full = run(1.0)
        val thin = run(0.35)
        assertEquals(full.beforeCoverage * 0.35, thin.value, 1e-9)
        assertTrue(thin.value < full.value)
    }

    /* ---- §D4.7 expected move ---- */

    @Test
    fun `expected move reports a band not a point assertion`() {
        val band = ExpectedMoveEngine.compute(Horizon.D1, 10.0, 0.75)
        assertNotNull(band)
        assertEquals(5.0, band!!.point, 1e-9)
        assertTrue(band.p5 < band.point && band.point < band.p95)
        assertNull(ExpectedMoveEngine.compute(Horizon.D1, null, 0.75))
    }

    /* ---- §D4.8 kill switch ---- */

    @Test
    fun `kill switch needs two simultaneous failures`() {
        val one = KillSwitch.evaluate(0.4, Regime.NORMAL, 0.1, null, 0.9)
        assertFalse(one.engaged)
        val two = KillSwitch.evaluate(0.4, Regime.NORMAL, 0.1, null, 0.2)
        assertTrue(two.engaged)
        assertEquals(2, two.reasons.size)
    }

    /* ---- §D5 causal layer ---- */

    @Test
    fun `sensitivity is arithmetic and reports no causal claim`() {
        val weighted = listOf(
            FactorScore("F01_REAL_RATE", -50.0) to 0.5,
            FactorScore("F02_USD", 20.0) to 0.5
        )
        val delta = CausalGraph.sensitivity(weighted, "F01_REAL_RATE", 0.0)
        assertEquals(25.0, delta!!, 1e-9)
        assertNull(CausalGraph.sensitivity(weighted, "F99_UNKNOWN", 0.0))
    }

    @Test
    fun `causal edges reference known nodes only`() {
        val known = FactorCatalog.byId.keys + "GOLD"
        CausalGraph.edges.forEach {
            assertTrue("unknown node ${it.from}", it.from in known)
            assertTrue("unknown node ${it.to}", it.to in known)
            assertTrue(abs(it.sign) == 1)
        }
    }

    /* ---- end-to-end ---- */

    @Test
    fun `multi horizon evaluation produces all six horizons`() {
        val now = Instant.parse("2026-02-02T12:00:00Z")
        val scores = listOf(
            FactorScore("F01_REAL_RATE", -40.0, 0.9, now),
            FactorScore("F02_USD", -30.0, 0.9, now),
            FactorScore("F13_MARKET_MOMENTUM", 20.0, 0.8, now)
        )
        val snapshot = InputSnapshot(
            observations = emptyMap(),
            features = FeatureSet(mapOf(FeatureKeys.VIX_ZSCORE to 0.2)),
            factorScores = scores
        )
        val report = MultiHorizonEngine().evaluate(snapshot, MarketContext(), now)
        assertEquals(6, report.horizons.size)
        assertEquals(listOf("5m", "15m", "1H", "4H", "1D", "1W"), report.horizons.map { it.horizon.code })
        report.horizons.forEach {
            if (it.mode == HorizonMode.DIRECTIONAL_ONLY) assertNull(it.probability)
        }
        val d1 = report.horizons.first { it.horizon == Horizon.D1 }
        assertTrue(d1.coverage > report.horizons.first { it.horizon == Horizon.M5 }.coverage)
    }

    @Test
    fun `an uncalibrated model never emits a probability`() {
        val now = Instant.parse("2026-02-02T12:00:00Z")
        val snapshot = InputSnapshot(
            observations = emptyMap(),
            factorScores = listOf(FactorScore("F01_REAL_RATE", 80.0, 1.0, now))
        )
        val report = MultiHorizonEngine().evaluate(snapshot, MarketContext(calibrationQuality = null), now)
        report.horizons.forEach { assertNull(it.probability) }
    }
}
