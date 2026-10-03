package io.goldintelligence.engine

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/* ------------------------------------------------------------------ */
/* SPEC v2 §D4.1 — Data quality score                                  */
/* ------------------------------------------------------------------ */

enum class Tier { A, B, C, D, PROXY }

/**
 * Quality is a product of four independent penalties rather than a single
 * hand-set number: source tier, staleness relative to the series' own
 * expected refresh period, proxy status, and validation outcome.
 *
 * `quality = tierFactor × stalenessFactor × proxyFactor × validationFactor`
 */
object DataQuality {
    fun tierFactor(tier: Tier): Double = when (tier) {
        Tier.A -> 1.00
        Tier.B -> 0.90
        Tier.C -> 0.75
        Tier.D -> 0.55
        Tier.PROXY -> 0.60
    }

    /**
     * Full quality while the value is within its own publication grace, then
     * a linear decay to QUALITY_STALENESS_FLOOR at three times the expected
     * period; anything older is floored, never zero-filled.
     *
     * The grace exists because a daily series is legitimately more than
     * twenty-four hours old across weekends and holidays: measuring staleness
     * against wall-clock alone would mark every Monday morning reading as
     * degraded when nothing is actually wrong with it.
     */
    fun stalenessFactor(ageSeconds: Long, expectedPeriodSeconds: Long): Double {
        if (expectedPeriodSeconds <= 0L) return 1.0
        val period = expectedPeriodSeconds.toDouble()
        val grace = period * CalibrationDefaults.QUALITY_STALENESS_GRACE
        val expiry = period * CalibrationDefaults.QUALITY_STALENESS_EXPIRY
        val age = ageSeconds.toDouble()
        if (age <= grace) return 1.0
        if (age >= expiry) return CalibrationDefaults.QUALITY_STALENESS_FLOOR
        val progressed = (age - grace) / (expiry - grace)
        return max(
            CalibrationDefaults.QUALITY_STALENESS_FLOOR,
            1.0 - (1.0 - CalibrationDefaults.QUALITY_STALENESS_FLOOR) * progressed
        )
    }

    fun score(
        tier: Tier,
        ageSeconds: Long,
        expectedPeriodSeconds: Long,
        isProxy: Boolean = false,
        validationPassed: Boolean = true
    ): Double {
        val base = tierFactor(tier) * stalenessFactor(ageSeconds, expectedPeriodSeconds)
        val proxied = if (isProxy) min(base, CalibrationDefaults.QUALITY_PROXY_CEILING) else base
        return (if (validationPassed) proxied else proxied * 0.5).coerceIn(0.0, 1.0)
    }

    /** Aggregate quality of a factor built from several inputs: weakest-link-weighted mean. */
    fun aggregate(components: List<Double>): Double {
        if (components.isEmpty()) return 0.0
        val mean = components.average()
        val worst = components.min()
        return (0.5 * mean + 0.5 * worst).coerceIn(0.0, 1.0)
    }
}

/* ------------------------------------------------------------------ */
/* SPEC v2 §D4.3 — Information dominance                               */
/* ------------------------------------------------------------------ */

/**
 * v1 derived dominance from the magnitude of the factor's own current score,
 * which feeds the score back into its own weight and mechanically amplifies
 * whichever factor happens to be extreme. Dominance is instead the factor's
 * recent *explanatory* power: the rolling R² of Δfactor against Δgold over
 * DOMINANCE_WINDOW_BARS, mapped onto [DOMINANCE_MIN, DOMINANCE_MAX].
 */
class RollingR2DominanceEngine(
    private val window: Int = CalibrationDefaults.DOMINANCE_WINDOW_BARS
) {
    /**
     * @param factorHistory factorId → recent values, oldest first
     * @param goldHistory recent gold prices, oldest first, same cadence
     */
    fun dominance(
        factorHistory: Map<String, List<Double>>,
        goldHistory: List<Double>
    ): Map<String, Double> {
        if (goldHistory.size < 4) return factorHistory.keys.associateWith { 1.0 }
        val gold = diffs(goldHistory.takeLast(window + 1))
        return factorHistory.mapValues { (_, series) ->
            if (series.size < 4) return@mapValues 1.0
            val f = diffs(series.takeLast(window + 1))
            val n = min(f.size, gold.size)
            if (n < 3) return@mapValues 1.0
            val r = pearson(f.takeLast(n), gold.takeLast(n))
            val r2 = (r * r).coerceIn(0.0, 1.0)
            CalibrationDefaults.DOMINANCE_MIN +
                r2 * (CalibrationDefaults.DOMINANCE_MAX - CalibrationDefaults.DOMINANCE_MIN)
        }
    }

    private fun diffs(xs: List<Double>): List<Double> =
        if (xs.size < 2) emptyList() else (1 until xs.size).map { xs[it] - xs[it - 1] }

    companion object {
        fun pearson(a: List<Double>, b: List<Double>): Double {
            val n = min(a.size, b.size)
            if (n < 2) return 0.0
            val ma = a.take(n).average()
            val mb = b.take(n).average()
            var num = 0.0
            var da = 0.0
            var db = 0.0
            for (i in 0 until n) {
                val x = a[i] - ma
                val y = b[i] - mb
                num += x * y
                da += x * x
                db += y * y
            }
            val den = sqrt(da * db)
            return if (den <= 0.0) 0.0 else (num / den).coerceIn(-1.0, 1.0)
        }
    }
}

/* ------------------------------------------------------------------ */
/* SPEC v2 §D4.4 — Time decay                                          */
/* ------------------------------------------------------------------ */

object TimeDecay {
    fun factor(asOf: Instant?, now: Instant, tauHours: Double = CalibrationDefaults.TIME_DECAY_TAU_HOURS): Double {
        if (asOf == null) return 1.0
        val ageHours = Duration.between(asOf, now).toMinutes() / 60.0
        if (ageHours <= 0.0) return 1.0
        return exp(-ageHours / tauHours)
    }

    fun halfLifeOf(tauHours: Double): Double = tauHours * ln(2.0)
}

/* ------------------------------------------------------------------ */
/* SPEC v2 §D4.5 — Cross-Market Confirmation                           */
/* ------------------------------------------------------------------ */

/**
 * CMC is the share of the eight witness markets whose observed move agrees
 * with the thesis direction, weighted by each witness's own reliability.
 * Witnesses: DXY, US10Y, Real Yield, Silver, Oil, VIX, ETF flow, Futures basis.
 */
object CrossMarketConfirmation {
    data class Witness(
        val id: String,
        /** Signed move of the witness, already oriented so that positive = bullish for gold. */
        val goldOrientedMove: Double?,
        val reliability: Double = 1.0
    )

    val witnessIds = listOf(
        "DXY", "US10Y", "REAL_YIELD", "SILVER", "OIL", "VIX", "ETF_FLOW", "FUTURES_BASIS"
    )

    data class Result(
        val value: Double?,
        val agreeing: List<String>,
        val disagreeing: List<String>,
        val missing: List<String>
    )

    fun compute(witnesses: List<Witness>, thesis: Direction): Result {
        val missing = witnesses.filter { it.goldOrientedMove == null }.map { it.id } +
            witnessIds.filter { id -> witnesses.none { it.id == id } }
        if (thesis == Direction.NEUTRAL) {
            return Result(null, emptyList(), emptyList(), missing.distinct())
        }
        val present = witnesses.filter { it.goldOrientedMove != null && abs(it.goldOrientedMove) > 0.0 }
        if (present.isEmpty()) return Result(null, emptyList(), emptyList(), missing.distinct())

        val sign = if (thesis == Direction.BULLISH) 1.0 else -1.0
        val agreeing = present.filter { (it.goldOrientedMove!! * sign) > 0.0 }
        val disagreeing = present - agreeing.toSet()
        val wTotal = present.sumOf { it.reliability }
        val wAgree = agreeing.sumOf { it.reliability }
        val value = if (wTotal <= 0.0) null else (wAgree / wTotal).coerceIn(0.0, 1.0)
        return Result(value, agreeing.map { it.id }, disagreeing.map { it.id }, missing.distinct())
    }
}

/* ------------------------------------------------------------------ */
/* SPEC v2 §D4.6 — Confidence                                          */
/* ------------------------------------------------------------------ */

data class ConfidenceInputs(
    val dataQuality: Double,
    val crossMarketConfirmation: Double?,
    val modelAgreement: Double?,
    val regimeStability: Stability,
    val calibrationQuality: Double?,
    val conflictRatio: Double,
    val regimeTransition: Boolean,
    val coverage: Double
)

data class ConfidenceBreakdown(
    val value: Double,
    val beforeCoverage: Double,
    val components: Map<String, Double>
)

/**
 * v1 computed Confidence as "average quality minus 0.1 per contradiction",
 * an unbounded ad-hoc penalty unrelated to the magnitude of the disagreement.
 * The corrected form is an explicit weighted composition, then scaled by the
 * horizon's coverage.
 */
class SpecConfidenceEngine {
    fun evaluate(inputs: ConfidenceInputs): ConfidenceBreakdown {
        val d = CalibrationDefaults
        val stability = when (inputs.regimeStability) {
            Stability.HIGH -> 1.0
            Stability.MEDIUM -> 0.6
            Stability.LOW -> 0.25
            Stability.UNKNOWN -> 0.4
        }
        val cmc = inputs.crossMarketConfirmation ?: 0.5
        val agreement = inputs.modelAgreement ?: 0.5
        val calibration = inputs.calibrationQuality ?: 0.0

        val positive =
            d.CONF_W_DATA_QUALITY * inputs.dataQuality.coerceIn(0.0, 1.0) +
                d.CONF_W_CMC * cmc.coerceIn(0.0, 1.0) +
                d.CONF_W_MODEL_AGREEMENT * agreement.coerceIn(0.0, 1.0) +
                d.CONF_W_REGIME_STABILITY * stability +
                d.CONF_W_CALIBRATION * calibration.coerceIn(0.0, 1.0)

        val negative =
            d.CONF_P_CONFLICT * inputs.conflictRatio.coerceIn(0.0, 1.0) +
                (if (inputs.regimeTransition) d.CONF_P_TRANSITION else 0.0)

        val before = (positive - negative).coerceIn(0.0, 1.0)
        val final = (before * inputs.coverage.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)

        return ConfidenceBreakdown(
            value = final,
            beforeCoverage = before,
            components = linkedMapOf(
                "DataQuality" to inputs.dataQuality,
                "CrossMarketConfirmation" to cmc,
                "ModelAgreement" to agreement,
                "RegimeStability" to stability,
                "CalibrationQuality" to calibration,
                "ConflictRatio" to inputs.conflictRatio,
                "RegimeTransition" to if (inputs.regimeTransition) 1.0 else 0.0,
                "Coverage" to inputs.coverage
            )
        )
    }
}

/* ------------------------------------------------------------------ */
/* SPEC v2 §D4.7 — Expected move                                       */
/* ------------------------------------------------------------------ */

/**
 * `ExpectedMove = k(h) × σ(h) × (2P − 1)`, reported as a regime-conditional
 * P5–P95 band rather than a single number. v1 emitted a point estimate with
 * no stated dispersion, which is not a forecast but an assertion.
 */
object ExpectedMoveEngine {
    fun k(horizon: Horizon): Double = when (horizon) {
        Horizon.M5 -> 0.80
        Horizon.M15 -> 0.85
        Horizon.H1 -> 0.90
        Horizon.H4 -> 0.95
        Horizon.D1 -> 1.00
        Horizon.W1 -> 1.05
    }

    data class Band(
        val point: Double,
        val p5: Double,
        val p95: Double,
        val sigma: Double,
        /**
         * `GAUSSIAN` ⇒ the ±1.645σ normal quantile, assumed. `CONFORMAL` ⇒ a
         * half-width measured on out-of-sample residuals with a finite-sample
         * coverage guarantee (SPEC v2.1 §26.2).
         */
        val intervalSource: String = GAUSSIAN,
        /** Multiple of sigma used for the band half-width. */
        val halfWidthSigma: Double = GAUSSIAN_Q
    )

    /** ±1.645σ, the 90 % two-sided normal interval. */
    const val GAUSSIAN_Q = 1.645
    const val GAUSSIAN = "GAUSSIAN"
    const val CONFORMAL = "CONFORMAL"

    /**
     * @param sigma realized volatility of gold over the horizon, in price units
     * @param probability directional probability in [0,1]; null ⇒ no band
     * @param conformalHalfWidth measured half-width in sigma units; when
     *        present it replaces the assumed normal quantile (§26.2)
     * @param momentumCredibility in [0,1] from the variance-ratio test; the
     *        point estimate is scaled by it, so a directional call made on a
     *        tape that tests as a random walk is not asserted at full size
     *        (§26.6). Null leaves the point estimate untouched.
     */
    fun compute(
        horizon: Horizon,
        sigma: Double?,
        probability: Double?,
        conformalHalfWidth: Double? = null,
        momentumCredibility: Double? = null
    ): Band? {
        if (sigma == null || sigma <= 0.0 || probability == null) return null
        val edge = (2.0 * probability.coerceIn(0.0, 1.0)) - 1.0
        val credibility = momentumCredibility?.coerceIn(0.0, 1.0) ?: 1.0
        val point = k(horizon) * sigma * edge * credibility
        val q = conformalHalfWidth?.takeIf { it.isFinite() && it > 0.0 } ?: GAUSSIAN_Q
        return Band(
            point = point,
            p5 = point - q * sigma,
            p95 = point + q * sigma,
            sigma = sigma,
            intervalSource = if (conformalHalfWidth != null && conformalHalfWidth > 0.0) CONFORMAL else GAUSSIAN,
            halfWidthSigma = q
        )
    }
}

/* ------------------------------------------------------------------ */
/* SPEC v2 §D4.8 — Kill switch                                         */
/* ------------------------------------------------------------------ */

data class KillSwitchResult(val engaged: Boolean, val reasons: List<String>)

/**
 * Publication is suppressed when at least KILL_MIN_TRIGGERS independent
 * failure conditions hold simultaneously. The engine degrades to
 * INSUFFICIENT_INFORMATION rather than emitting a number it cannot defend.
 */
object KillSwitch {
    fun evaluate(
        dataQuality: Double,
        regime: Regime,
        conflictRatio: Double,
        brierDriftRatio: Double?,
        coverage: Double
    ): KillSwitchResult {
        val d = CalibrationDefaults
        val reasons = mutableListOf<String>()
        if (dataQuality < d.KILL_MIN_QUALITY) reasons += "DATA_QUALITY_BELOW_${d.KILL_MIN_QUALITY}"
        if (regime == Regime.REGIME_TRANSITION || regime == Regime.UNKNOWN) reasons += "REGIME_${regime.name}"
        if (conflictRatio > d.KILL_MAX_CONFLICT) reasons += "CONFLICT_ABOVE_${d.KILL_MAX_CONFLICT}"
        if (brierDriftRatio != null && brierDriftRatio > d.KILL_BRIER_DRIFT) reasons += "BRIER_DRIFT"
        if (coverage < d.KILL_MIN_COVERAGE) reasons += "COVERAGE_BELOW_${d.KILL_MIN_COVERAGE}"
        return KillSwitchResult(reasons.size >= d.KILL_MIN_TRIGGERS, reasons)
    }
}

/* ------------------------------------------------------------------ */
/* SPEC v2 §D5 — Causal layer                                          */
/* ------------------------------------------------------------------ */

/**
 * v1 described a "Counterfactual Engine" and claimed causal inference from
 * observational market data. No identification strategy exists for that
 * claim, so the layer is demoted to a declarative DAG of assumed
 * transmission channels plus deterministic sensitivity analysis — a
 * what-if on the model's own arithmetic, explicitly not a causal effect.
 */
object CausalGraph {
    data class Edge(val from: String, val to: String, val sign: Int, val channel: String)

    val edges: List<Edge> = listOf(
        Edge("F03_FED", "F01_REAL_RATE", +1, "Policy path → real rate expectations"),
        Edge("F03_FED", "F02_USD", +1, "Policy differential → dollar"),
        Edge("F05_INFLATION", "F01_REAL_RATE", -1, "Breakeven ↑ → real rate ↓"),
        Edge("F04_TREASURY_CURVE", "F01_REAL_RATE", +1, "Nominal curve → real curve"),
        Edge("F01_REAL_RATE", "GOLD", -1, "Opportunity cost of a zero-coupon asset"),
        Edge("F02_USD", "GOLD", -1, "Numéraire effect"),
        Edge("F07_GEOPOLITICAL_RISK", "GOLD", +1, "Safe-haven bid"),
        Edge("F08_FINANCIAL_STRESS", "F17_LIQUIDITY", +1, "Stress → funding squeeze"),
        Edge("F17_LIQUIDITY", "GOLD", -1, "Forced liquidation of liquid collateral"),
        Edge("F09_GOLD_FLOW", "GOLD", +1, "ETF creation absorbs float"),
        Edge("F10_CENTRAL_BANK_DEMAND", "GOLD", +1, "Price-insensitive official demand"),
        Edge("F11_FUTURES_POSITIONING", "GOLD", -1, "Crowding → exhaustion risk"),
        Edge("F21_OIL_ENERGY", "F05_INFLATION", +1, "Energy → headline inflation"),
        Edge("F22_GLOBAL_CB_POLICY", "F02_USD", -1, "Policy convergence weakens the dollar"),
        Edge("F18_CREDIT", "F08_FINANCIAL_STRESS", +1, "Credit spreads → systemic stress"),
        Edge("F16_CROSS_ASSET", "GOLD", +1, "Precious-metals complex co-movement")
    )

    fun parentsOf(node: String): List<Edge> = edges.filter { it.to == node }
    fun childrenOf(node: String): List<Edge> = edges.filter { it.from == node }

    /**
     * Sensitivity Analysis (renamed from "Counterfactual"): recompute the
     * weighted bias with one factor's score forced to `forcedScore`, and
     * report the arithmetic delta. No causal interpretation is attached.
     */
    fun sensitivity(
        weightedScores: List<Pair<FactorScore, Double>>,
        factorId: String,
        forcedScore: Double
    ): Double? {
        if (weightedScores.none { it.first.factorId == factorId }) return null
        val base = weightedScores.sumOf { (f, w) -> f.score * w }
        val alt = weightedScores.sumOf { (f, w) ->
            if (f.factorId == factorId) forcedScore * w else f.score * w
        }
        return alt - base
    }
}
