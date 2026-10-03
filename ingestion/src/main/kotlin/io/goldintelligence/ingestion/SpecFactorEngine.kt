package io.goldintelligence.ingestion

import io.goldintelligence.engine.CalibrationDefaults
import io.goldintelligence.engine.DataQuality
import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.LogStage
import io.goldintelligence.engine.FactorCatalog
import io.goldintelligence.engine.FactorScore
import io.goldintelligence.engine.FeatureKeys
import io.goldintelligence.engine.FeatureSet
import io.goldintelligence.engine.GoldSpecification
import io.goldintelligence.engine.Horizon
import io.goldintelligence.engine.FactorHorizons
import java.time.Instant

/**
 * Diagnostics for one factor, so the presentation layer can state exactly why
 * a factor is or is not contributing instead of showing an unexplained blank.
 */
data class FactorDiagnostic(
    val factorId: String,
    val available: Boolean,
    val score: Double?,
    val quality: Double,
    val asOf: Instant?,
    val usedFeatures: List<String>,
    val missingFeatures: List<String>,
    val isProxy: Boolean,
    val baseWeight: Double,
    val minHorizon: Horizon,
    val reason: String?
)

data class FactorResult(
    val scores: List<FactorScore>,
    val diagnostics: List<FactorDiagnostic>
)

/**
 * Maps the feature contract onto the 22 gold-oriented factor scores.
 *
 * Sign convention is the specification's: every score is already expressed on
 * the shared −100..+100 bearish..bullish *gold* scale, so a rising real yield
 * yields a negative F01, not a positive one.
 *
 * A factor whose required inputs are absent is omitted from [FactorResult.scores]
 * entirely. It is never emitted with score 0, which the weighting layer would
 * otherwise read as "neutral evidence" rather than "no evidence".
 */
class SpecFactorEngine(private val log: DiagnosticLog = DiagnosticLog.shared) {

    fun score(bundle: FeatureBundle, now: Instant = Instant.now()): FactorResult {
        val scores = mutableListOf<FactorScore>()
        val diags = mutableListOf<FactorDiagnostic>()

        fun emit(
            id: String,
            value: Double?,
            used: List<String>,
            required: List<String> = used,
            reason: String? = null
        ) {
            val present = required.filter { bundle[it] != null }
            val missing = required.filter { bundle[it] == null }
            val contributors = present.mapNotNull { bundle[it] }
            val quality = if (contributors.isEmpty()) 0.0 else DataQuality.aggregate(contributors.map { it.quality })
            val asOf = contributors.minByOrNull { it.asOf }?.asOf
            val isProxy = contributors.any { it.isProxy }
            val baseWeight = GoldSpecification.baseFactorWeights[id] ?: 0.0
            val minH = FactorHorizons.minHorizon[id] ?: Horizon.D1

            if (value != null && contributors.isNotEmpty()) {
                scores += FactorScore(
                    factorId = id,
                    score = value.coerceIn(-100.0, 100.0),
                    quality = quality,
                    asOf = asOf
                )
            }
            diags += FactorDiagnostic(
                factorId = id,
                available = value != null && contributors.isNotEmpty(),
                score = value?.coerceIn(-100.0, 100.0),
                quality = quality,
                asOf = asOf,
                usedFeatures = present,
                missingFeatures = missing,
                isProxy = isProxy,
                baseWeight = baseWeight,
                minHorizon = minH,
                reason = reason ?: if (missing.isNotEmpty()) "MISSING_INPUTS: ${missing.joinToString()}" else null
            )

            if (value != null && contributors.isNotEmpty()) {
                log.debug(
                    LogStage.FACTOR, "SpecFactorEngine", if (isProxy) "SCORED_PROXY" else "SCORED",
                    String.format(java.util.Locale.US, "%.2f (q %.2f)", value.coerceIn(-100.0, 100.0), quality),
                    key = id
                )
            } else {
                log.warn(
                    LogStage.FACTOR, "SpecFactorEngine",
                    when {
                        reason != null -> reason.substringBefore(':')
                        missing.isNotEmpty() -> "MISSING_INPUTS"
                        else -> "NOT_COMPUTABLE"
                    },
                    reason ?: "dropped: " +
                        (if (missing.isEmpty()) "formula produced no value" else "missing " + missing.joinToString()),
                    key = id,
                    detail = "baseWeight=$baseWeight minHorizon=${minH.code} used=${present.size}"
                )
            }
        }

        fun f(key: String): Double? = bundle.valueOf(key)

        /* F01 — Real yield. Rising real yield raises the opportunity cost of gold. */
        emit(
            "F01_REAL_RATE",
            blend(
                Stats.zToScore(f(FeatureKeys.REAL_YIELD_ZSCORE))?.let { -it } to 0.6,
                Stats.ratioToScore(f(FeatureKeys.REAL_YIELD_TREND), 0.02)?.let { -it } to 0.4
            ),
            listOf(FeatureKeys.REAL_YIELD_ZSCORE, FeatureKeys.REAL_YIELD_TREND, FeatureKeys.REAL_YIELD)
        )

        /* F02 — Dollar. Gold is dollar-denominated; a stronger dollar mechanically weighs on it. */
        emit(
            "F02_USD",
            blend(
                Stats.zToScore(f(FeatureKeys.DXY_ZSCORE))?.let { -it } to 0.6,
                Stats.ratioToScore(f(FeatureKeys.DXY_TREND), 0.01)?.let { -it } to 0.4
            ),
            listOf(FeatureKeys.DXY_ZSCORE, FeatureKeys.DXY_TREND, FeatureKeys.DXY)
        )

        /* F03 — Fed path. A repriced-higher policy path is bearish for a zero-coupon asset. */
        emit(
            "F03_FED",
            blend(
                Stats.ratioToScore(f(FeatureKeys.FED_EXPECTED_RATE_CHANGE), 0.40)?.let { -it } to 0.7,
                Stats.ratioToScore(f(FeatureKeys.FED_SURPRISE), 0.50)?.let { -it } to 0.3
            ),
            listOf(FeatureKeys.FED_EXPECTED_RATE_CHANGE, FeatureKeys.FED_SURPRISE)
        )

        /* F04 — Treasury curve. Steepening signals an easing path; a yield spike is bearish. */
        emit(
            "F04_TREASURY_CURVE",
            blend(
                Stats.ratioToScore(f(FeatureKeys.US10Y_MOVE_SIGMA), 3.0)?.let { -it } to 0.6,
                Stats.ratioToScore(f(FeatureKeys.YIELD_CURVE_10Y_2Y), 1.50) to 0.4
            ),
            listOf(FeatureKeys.US10Y_MOVE_SIGMA, FeatureKeys.YIELD_CURVE_10Y_2Y, FeatureKeys.US10Y_YIELD)
        )

        /* F05 — Inflation. Rising breakevens compress real yields. */
        /* SPEC v2.1 §25.1 — the five-year, five-year forward rate is what the
         * market expects once the current cycle has washed out; above target
         * it is a standing bid under gold. */
        emit(
            "F05_INFLATION",
            blend(
                Stats.ratioToScore(f(FeatureKeys.BREAKEVEN_CHANGE), 0.20) to 0.45,
                Stats.zToScore(f(FeatureKeys.INFLATION_SURPRISE), 2.0) to 0.30,
                f(FeatureKeys.INFLATION_EXPECTATION_5Y5Y)
                    ?.let { Stats.ratioToScore(it - INFLATION_TARGET, 0.60) } to 0.25
            ),
            listOf(
                FeatureKeys.BREAKEVEN_CHANGE, FeatureKeys.INFLATION_SURPRISE,
                FeatureKeys.INFLATION_EXPECTATION_5Y5Y
            )
        )

        /* F06 — Growth surprise. Stronger growth supports real rates and risk assets. */
        emit(
            "F06_ECONOMIC_SURPRISE",
            Stats.ratioToScore(f(FeatureKeys.ECONOMIC_SURPRISE_INDEX), 8.0)?.let { -it },
            listOf(FeatureKeys.ECONOMIC_SURPRISE_INDEX)
        )

        /* F07 — Geopolitical risk. Safe-haven bid. */
        emit(
            "F07_GEOPOLITICAL_RISK",
            blend(
                f(FeatureKeys.GEOPOLITICAL_RISK_SCORE)?.let { Stats.ratioToScore(it - 50.0, 25.0) } to 0.7,
                Stats.ratioToScore(f(FeatureKeys.GEOPOLITICAL_RISK_DELTA), 20.0) to 0.3
            ),
            listOf(FeatureKeys.GEOPOLITICAL_RISK_SCORE, FeatureKeys.GEOPOLITICAL_RISK_DELTA)
        )

        /* F08 — Financial stress. Bullish as a haven, but turns bearish once funding seizes
           and gold is sold as liquid collateral (the 2008/2020 pattern). */
        val funding = f(FeatureKeys.DOLLAR_FUNDING_STRESS)
        val stressRaw = Stats.zToScore(f(FeatureKeys.FINANCIAL_STRESS_SCORE), 2.0)
        emit(
            "F08_FINANCIAL_STRESS",
            stressRaw?.let { s -> if (funding != null && funding >= CalibrationDefaults.LIQUIDITY_STRESS_THRESHOLD) -0.5 * s else s },
            listOf(FeatureKeys.FINANCIAL_STRESS_SCORE),
            reason = if (funding != null && funding >= CalibrationDefaults.LIQUIDITY_STRESS_THRESHOLD)
                "LIQUIDATION_REGIME_SIGN_FLIP" else null
        )

        /* F09 — ETF flow. */
        emit(
            "F09_GOLD_FLOW",
            blend(
                Stats.zToScore(f(FeatureKeys.ETF_FLOW_ZSCORE), 2.0) to 0.7,
                Stats.ratioToScore(f(FeatureKeys.ETF_HOLDINGS_CHANGE), 4.0) to 0.3
            ),
            listOf(FeatureKeys.ETF_FLOW_ZSCORE, FeatureKeys.ETF_HOLDINGS_CHANGE)
        )

        /* F10 — Central-bank demand (SPEC v2.1 §23).
         * Measured from the monthly reserves template: how much gold the
         * official sector added over the last three reported months, and how
         * broadly it was spread across reporting countries. Breadth guards
         * against a single large reporter carrying the whole signal.
         */
        emit(
            "F10_CENTRAL_BANK_DEMAND",
            blend(
                Stats.zToScore(f(FeatureKeys.CENTRAL_BANK_NET_BUYING_3M), 2.0) to 0.7,
                Stats.ratioToScore(f(FeatureKeys.CENTRAL_BANK_PROXY_FLOW), 25.0) to 0.3
            ),
            listOf(FeatureKeys.CENTRAL_BANK_NET_BUYING_3M, FeatureKeys.CENTRAL_BANK_PROXY_FLOW),
            listOf(FeatureKeys.CENTRAL_BANK_NET_BUYING_3M)
        )

        /* F11 — Positioning. Crowding is contrarian. */
        emit(
            "F11_FUTURES_POSITIONING",
            Stats.zToScore(f(FeatureKeys.COT_NET_POSITION_ZSCORE), 2.0)?.let { -it },
            listOf(FeatureKeys.COT_NET_POSITION_ZSCORE)
        )

        /* F12 — Physical demand.
         * SPEC v2.1: measured from the Shanghai and Mumbai premia. A premium
         * of roughly one percent over the international price is a strong
         * physical bid, so the scale is tight. */
        emit(
            "F12_PHYSICAL_DEMAND",
            Stats.ratioToScore(f(FeatureKeys.PHYSICAL_DEMAND_INDEX), 1.5),
            listOf(FeatureKeys.PHYSICAL_DEMAND_INDEX),
            listOf(FeatureKeys.PHYSICAL_DEMAND_INDEX)
        )

        /* F13 — Momentum. */
        emit(
            "F13_MARKET_MOMENTUM",
            Stats.ratioToScore(f(FeatureKeys.GOLD_MOMENTUM), 4.0),
            listOf(FeatureKeys.GOLD_MOMENTUM, FeatureKeys.GOLD_RETURN)
        )

        /* F14 — Microstructure. */
        emit(
            "F14_MARKET_MICROSTRUCTURE",
            f(FeatureKeys.MICROSTRUCTURE_IMBALANCE)?.times(60.0),
            listOf(FeatureKeys.MICROSTRUCTURE_IMBALANCE)
        )

        /* F15 — Options / volatility. An implied-over-realized premium prices hedging demand. */
        /* SPEC v2.1 §25.2 — the chain itself now carries a direction.
         * A positive 25-delta risk reversal means the market pays more for
         * upside than for the mirror downside, and a put/call open interest
         * ratio above one means the book is hedged for a fall. Both are
         * counted from published contracts, so they lead the blend. */
        emit(
            "F15_OPTIONS_VOLATILITY",
            blend(
                Stats.ratioToScore(f(FeatureKeys.GOLD_RISK_REVERSAL), 3.0) to 0.40,
                f(FeatureKeys.GOLD_PUT_CALL_OI)?.let { Stats.ratioToScore(it - 1.0, 0.45) } to 0.20,
                Stats.ratioToScore(f(FeatureKeys.GOLD_IV_SKEW), 8.0) to 0.25,
                Stats.zToScore(f(FeatureKeys.GOLD_REALIZED_VOL_ZSCORE), 2.0)?.let { -it } to 0.15
            ),
            listOf(
                FeatureKeys.GOLD_RISK_REVERSAL, FeatureKeys.GOLD_PUT_CALL_OI,
                FeatureKeys.GOLD_IV_SKEW, FeatureKeys.GOLD_REALIZED_VOL_ZSCORE
            )
        )

        /* F16 — Cross-asset. Silver outperforming gold marks a risk-seeking metals complex. */
        emit(
            "F16_CROSS_ASSET",
            Stats.zToScore(f(FeatureKeys.GOLD_SILVER_RATIO_ZSCORE), 2.0)?.let { -it },
            listOf(FeatureKeys.GOLD_SILVER_RATIO_ZSCORE, FeatureKeys.GOLD_SILVER_RATIO)
        )

        /* F17 — Dollar liquidity. Funding stress forces liquidation of liquid assets. */
        /* SPEC v2.1 §25.1 — funding stress is only one leg. Tight financial
         * conditions and a shrinking pool of net liquidity drain the bid for
         * every asset, gold included; loosening does the opposite. */
        emit(
            "F17_LIQUIDITY",
            blend(
                Stats.zToScore(funding, 2.0)?.let { -it } to 0.40,
                Stats.ratioToScore(f(FeatureKeys.FINANCIAL_CONDITIONS), 0.60)?.let { -it } to 0.35,
                Stats.ratioToScore(f(FeatureKeys.FED_NET_LIQUIDITY_CHANGE), 3.0) to 0.25
            ),
            listOf(
                FeatureKeys.DOLLAR_FUNDING_STRESS, FeatureKeys.FINANCIAL_CONDITIONS,
                FeatureKeys.FED_NET_LIQUIDITY_CHANGE
            )
        )

        /* F18 — Credit. Widening spreads are a haven signal. */
        emit(
            "F18_CREDIT",
            Stats.zToScore(f(FeatureKeys.CREDIT_SPREAD_HY), 2.0),
            listOf(FeatureKeys.CREDIT_SPREAD_HY)
        )

        /* F19 / F20 — China and India. */
        emit("F19_CHINA", Stats.ratioToScore(f(FeatureKeys.CHINA_DEMAND_INDEX), 3.0), listOf(FeatureKeys.CHINA_DEMAND_INDEX))
        emit("F20_INDIA", Stats.ratioToScore(f(FeatureKeys.INDIA_DEMAND_INDEX), 4.0), listOf(FeatureKeys.INDIA_DEMAND_INDEX))

        /* F21 — Oil. Energy feeds headline inflation. */
        emit("F21_OIL_ENERGY", Stats.ratioToScore(f(FeatureKeys.OIL_MOMENTUM), 15.0), listOf(FeatureKeys.OIL_MOMENTUM))

        /* F22 — Global policy divergence. */
        emit(
            "F22_GLOBAL_CB_POLICY",
            Stats.ratioToScore(f(FeatureKeys.GLOBAL_CB_POLICY_DIVERGENCE), 5.0)?.let { -it },
            listOf(FeatureKeys.GLOBAL_CB_POLICY_DIVERGENCE)
        )

        val order = FactorCatalog.factors.map { it.id }
        return FactorResult(
            scores = scores.sortedBy { order.indexOf(it.factorId) },
            diagnostics = diags.sortedBy { order.indexOf(it.factorId) }
        )
    }

    fun toFeatureSet(bundle: FeatureBundle): FeatureSet = FeatureSet(bundle.numeric())

    private companion object {
        /** The inflation rate the Federal Reserve states as its objective. */
        const val INFLATION_TARGET = 2.0
    }

    /** Weighted blend that silently drops absent terms and renormalizes the rest. */
    private fun blend(vararg terms: Pair<Double?, Double>): Double? {
        val present = terms.filter { it.first != null }
        if (present.isEmpty()) return null
        val wTotal = present.sumOf { it.second }
        if (wTotal <= 0.0) return null
        return present.sumOf { (v, w) -> v!! * w } / wTotal
    }
}
