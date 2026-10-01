package io.goldintelligence.engine

/**
 * SPEC v2 §D2 — Corrected base factor weights.
 *
 * In v1 nine of the twenty-two factors carried weight 0.0 while still being
 * declared as part of the Master List, so one third of the declared evidence
 * surface could never influence the output and the table did not sum to 1.
 * The corrected table below assigns a non-zero prior to every declared factor
 * and sums to exactly 1.000.
 *
 * Group subtotals: MONETARY .52 · RISK .12 · FLOW .10 · INFLATION .08 ·
 * POSITIONING .06 · GROWTH .05 · STRUCTURE .05 · PHYSICAL .02.
 *
 * These remain Initial Priors, to be re-estimated by Walk-Forward calibration
 * once a live/backtest record exists.
 */
object GoldSpecification {
    val baseFactorWeights: Map<String, Double> = linkedMapOf(
        "F01_REAL_RATE" to 0.200,
        "F02_USD" to 0.150,
        "F03_FED" to 0.100,
        "F04_TREASURY_CURVE" to 0.040,
        "F05_INFLATION" to 0.060,
        "F06_ECONOMIC_SURPRISE" to 0.050,
        "F07_GEOPOLITICAL_RISK" to 0.060,
        "F08_FINANCIAL_STRESS" to 0.030,
        "F09_GOLD_FLOW" to 0.060,
        "F10_CENTRAL_BANK_DEMAND" to 0.040,
        "F11_FUTURES_POSITIONING" to 0.060,
        "F12_PHYSICAL_DEMAND" to 0.008,
        "F13_MARKET_MOMENTUM" to 0.020,
        "F14_MARKET_MICROSTRUCTURE" to 0.010,
        "F15_OPTIONS_VOLATILITY" to 0.010,
        "F16_CROSS_ASSET" to 0.010,
        "F17_LIQUIDITY" to 0.020,
        "F18_CREDIT" to 0.010,
        "F19_CHINA" to 0.006,
        "F20_INDIA" to 0.006,
        "F21_OIL_ENERGY" to 0.020,
        "F22_GLOBAL_CB_POLICY" to 0.030
    )

    val requiredHorizons = listOf("5m", "15m", "1H", "4H", "1D", "1W")

    val requiredLayers = listOf(
        "DATA", "FEATURES", "FACTORS", "CAUSALITY", "EVENTS_NEWS",
        "MICROSTRUCTURE", "REGIME", "INFORMATION_DOMINANCE",
        "DYNAMIC_WEIGHTS", "INTERACTIONS", "DIVERGENCE",
        "HISTORICAL_ANALOGUES", "ENSEMBLE", "PROBABILITY",
        "CALIBRATION", "CONFIDENCE", "SCENARIOS", "INVALIDATION"
    )

    /** SPEC v2 §D2 invariant: the prior table must stay normalized. */
    fun weightsAreNormalized(tolerance: Double = 1e-9): Boolean =
        kotlin.math.abs(baseFactorWeights.values.sum() - 1.0) <= tolerance
}

/**
 * Metadata for the 22-factor Master List (Factor Architecture section of the
 * specification). `group` is the causal cluster used for redundancy control
 * (Information Redundancy / Causal Layer) and regime-based weight adjustment.
 */
object FactorCatalog {
    data class FactorMeta(
        val id: String,
        val nameFa: String,
        val nameEn: String,
        val group: String
    )

    val factors: List<FactorMeta> = listOf(
        FactorMeta("F01_REAL_RATE", "بازده واقعی", "Real Yield", "MONETARY"),
        FactorMeta("F02_USD", "دلار آمریکا", "USD / DXY", "MONETARY"),
        FactorMeta("F03_FED", "انتظارات فدرال رزرو", "Fed Expectations", "MONETARY"),
        FactorMeta("F04_TREASURY_CURVE", "منحنی بازده خزانه", "Treasury Curve", "MONETARY"),
        FactorMeta("F05_INFLATION", "تورم", "Inflation", "INFLATION"),
        FactorMeta("F06_ECONOMIC_SURPRISE", "شگفتی اقتصادی", "Economic Surprise", "GROWTH"),
        FactorMeta("F07_GEOPOLITICAL_RISK", "ریسک ژئوپلیتیک", "Geopolitical Risk", "RISK"),
        FactorMeta("F08_FINANCIAL_STRESS", "استرس مالی جهانی", "Financial Stress", "RISK"),
        FactorMeta("F09_GOLD_FLOW", "جریان ETF طلا", "Gold ETF Flow", "FLOW"),
        FactorMeta("F10_CENTRAL_BANK_DEMAND", "خرید بانک‌های مرکزی", "Central Bank Demand", "FLOW"),
        FactorMeta("F11_FUTURES_POSITIONING", "پوزیشن‌گیری فیوچرز / COT", "Futures Positioning", "POSITIONING"),
        FactorMeta("F12_PHYSICAL_DEMAND", "تقاضای فیزیکی", "Physical Demand", "PHYSICAL"),
        FactorMeta("F13_MARKET_MOMENTUM", "مومنتوم بازار طلا", "Market Momentum", "STRUCTURE"),
        FactorMeta("F14_MARKET_MICROSTRUCTURE", "ریزساختار بازار", "Market Microstructure", "STRUCTURE"),
        FactorMeta("F15_OPTIONS_VOLATILITY", "نوسان / آپشن", "Options / Volatility", "STRUCTURE"),
        FactorMeta("F16_CROSS_ASSET", "روابط بین‌دارایی", "Cross-Asset Relationships", "STRUCTURE"),
        FactorMeta("F17_LIQUIDITY", "نقدشوندگی دلار", "Liquidity", "RISK"),
        FactorMeta("F18_CREDIT", "شرایط اعتباری", "Credit Conditions", "RISK"),
        FactorMeta("F19_CHINA", "چین", "China", "PHYSICAL"),
        FactorMeta("F20_INDIA", "هند", "India", "PHYSICAL"),
        FactorMeta("F21_OIL_ENERGY", "نفت / انرژی", "Oil / Energy", "INFLATION"),
        FactorMeta("F22_GLOBAL_CB_POLICY", "واگرایی سیاست بانک‌های مرکزی جهانی", "Global Central-Bank Policy Divergence", "MONETARY")
    )

    val byId: Map<String, FactorMeta> = factors.associateBy { it.id }

    val groupWeights: Map<String, Double> =
        factors.groupBy { it.group }
            .mapValues { (_, members) ->
                members.sumOf { GoldSpecification.baseFactorWeights[it.id] ?: 0.0 }
            }
}

/**
 * SPEC v2 §D1 — Regime families.
 *
 * v1 declared eleven regimes but supplied weight multipliers that could not be
 * estimated separately for all of them from the available sample. For
 * weighting purposes the eleven states collapse into six families; the
 * eleven-state `Regime` enum is retained unchanged for reporting.
 */
enum class RegimeFamily { MONETARY, INFLATION, RISK, LIQUIDITY, GROWTH, TRANSITION }

object RegimeFamilies {
    fun of(regime: Regime): RegimeFamily = when (regime) {
        Regime.MONETARY_EASING, Regime.MONETARY_TIGHTENING -> RegimeFamily.MONETARY
        Regime.INFLATION -> RegimeFamily.INFLATION
        Regime.GEOPOLITICAL_CRISIS, Regime.RISK_OFF, Regime.RISK_ON -> RegimeFamily.RISK
        Regime.LIQUIDITY_STRESS -> RegimeFamily.LIQUIDITY
        Regime.GROWTH_SLOWDOWN -> RegimeFamily.GROWTH
        Regime.REGIME_TRANSITION, Regime.NORMAL, Regime.UNKNOWN -> RegimeFamily.TRANSITION
    }
}

/**
 * Regime-conditional multipliers applied to each causal group's aggregate
 * weight (Layer 4 "Regime Engine" / Dynamic Weighting formula:
 * Effective Weight = Base Weight × Regime Weight × Dominance × Quality × Decay).
 *
 * These multipliers are Initial Priors. They must be recalibrated once
 * live/backtest data exists (Walk-Forward Learning, Model Drift Detection).
 */
object RegimeAdjustments {
    private val table: Map<Regime, Map<String, Double>> = mapOf(
        Regime.MONETARY_EASING to mapOf(
            "MONETARY" to 1.35, "RISK" to 0.9
        ),
        Regime.MONETARY_TIGHTENING to mapOf(
            "MONETARY" to 1.35, "RISK" to 0.9
        ),
        Regime.INFLATION to mapOf(
            "MONETARY" to 1.1, "INFLATION" to 1.5, "RISK" to 0.9, "GROWTH" to 1.1
        ),
        Regime.GEOPOLITICAL_CRISIS to mapOf(
            "MONETARY" to 0.9, "RISK" to 1.6, "FLOW" to 1.1, "GROWTH" to 0.9
        ),
        Regime.LIQUIDITY_STRESS to mapOf(
            "MONETARY" to 1.2, "INFLATION" to 0.8, "RISK" to 1.5,
            "FLOW" to 0.8, "POSITIONING" to 0.8, "PHYSICAL" to 0.7, "STRUCTURE" to 0.9
        ),
        Regime.RISK_ON to mapOf(
            "RISK" to 0.8, "FLOW" to 1.1, "POSITIONING" to 1.1, "STRUCTURE" to 1.1, "GROWTH" to 1.1
        ),
        Regime.RISK_OFF to mapOf(
            "INFLATION" to 0.9, "RISK" to 1.4, "FLOW" to 1.1, "POSITIONING" to 0.9,
            "STRUCTURE" to 0.9, "GROWTH" to 0.9
        ),
        Regime.GROWTH_SLOWDOWN to mapOf(
            "MONETARY" to 1.1, "INFLATION" to 0.9, "RISK" to 1.1, "GROWTH" to 1.3
        ),
        Regime.REGIME_TRANSITION to mapOf(
            "MONETARY" to 0.85, "INFLATION" to 0.85, "RISK" to 0.85, "FLOW" to 0.85,
            "POSITIONING" to 0.85, "PHYSICAL" to 0.85, "STRUCTURE" to 0.85, "GROWTH" to 0.85
        ),
        Regime.NORMAL to emptyMap(),
        Regime.UNKNOWN to emptyMap()
    )

    fun multiplierFor(factorId: String, regime: Regime): Double {
        val group = FactorCatalog.byId[factorId]?.group ?: return 1.0
        return table[regime]?.get(group) ?: 1.0
    }
}

/**
 * Shock-detection thresholds. DXY/US10Y/Gold sigma levels are taken verbatim
 * from the specification's Shock Detection example. The VIX spike z-score and
 * the minimum simultaneous-trigger count are provisional Initial Priors.
 */
object ShockThresholds {
    const val DXY_SIGMA = 5.0
    const val US10Y_SIGMA = 4.0
    const val GOLD_SIGMA = 5.0
    const val VIX_SPIKE_ZSCORE = 3.0
    const val MIN_TRIGGERED_FOR_SHOCK = 2
}

/**
 * All remaining numeric calibration parameters used by the rule-based engines.
 * Centralized here so the entire calibration surface of the engine is a
 * single, auditable, versioned contract.
 */
object CalibrationDefaults {
    // Regime detection
    const val GEOPOLITICAL_CRISIS_THRESHOLD = 75.0
    const val LIQUIDITY_STRESS_THRESHOLD = 2.0
    const val INFLATION_SURPRISE_THRESHOLD = 0.15
    const val FED_EXPECTATION_SHIFT_THRESHOLD = 0.10
    const val GROWTH_SLOWDOWN_THRESHOLD = 0.35
    const val RISK_OFF_VIX_ZSCORE = 1.5
    const val RISK_ON_VIX_ZSCORE = 1.0
    const val RISK_OFF_STRESS_THRESHOLD = 1.5

    // Divergence / positioning
    const val DIVERGENCE_MAGNITUDE_THRESHOLD = 20.0
    const val COT_EXTREME_ZSCORE = 2.0

    // Contradiction
    const val CONFLICT_HIGH_RATIO = 0.40
    const val CONFLICT_MEDIUM_RATIO = 0.20

    // Interaction clusters
    const val INTERACTION_MONETARY_CLUSTER_BONUS = 8.0
    const val INTERACTION_INFLATION_BULLISH_BONUS = 6.0
    const val INTERACTION_INFLATION_OVERRIDE_PENALTY = 6.0
    const val INTERACTION_TREND_CONFIRMATION_BONUS = 4.0

    // Gold Bias interpretation
    const val BIAS_NEUTRAL_BAND = 10.0

    // Information dominance (SPEC v2 §D4.3 — bounds of the normalized R² map)
    const val DOMINANCE_BOOST_MAX = 0.5
    const val DOMINANCE_MIN = 0.75
    const val DOMINANCE_MAX = 1.50
    const val DOMINANCE_WINDOW_BARS = 60

    /**
     * SPEC v2 §D4.4 — Time decay.
     * `decay = exp(-age_hours / tau)` with `tau = half_life / ln 2`.
     * Half-life 48 h ⇒ tau ≈ 69.3 h. v1 used the half-life directly as tau,
     * which made the actual half-life 1/ln2 ≈ 1.44× longer than declared.
     */
    const val TIME_DECAY_HALF_LIFE_HOURS = 48.0
    val TIME_DECAY_TAU_HOURS: Double = TIME_DECAY_HALF_LIFE_HOURS / kotlin.math.ln(2.0)

    // SPEC v2 §D4.1 — Data quality scoring
    const val QUALITY_STALENESS_FLOOR = 0.25
    const val QUALITY_STALENESS_GRACE = 1.5
    const val QUALITY_STALENESS_EXPIRY = 3.0
    const val QUALITY_PROXY_CEILING = 0.60

    // SPEC v2 §D4.6 — Confidence composition
    const val CONF_W_DATA_QUALITY = 0.30
    const val CONF_W_CMC = 0.20
    const val CONF_W_MODEL_AGREEMENT = 0.20
    const val CONF_W_REGIME_STABILITY = 0.15
    const val CONF_W_CALIBRATION = 0.15
    const val CONF_P_CONFLICT = 0.25
    const val CONF_P_TRANSITION = 0.20

    // SPEC v2 §D4.8 — Kill-switch thresholds
    const val KILL_MIN_QUALITY = 0.60
    const val KILL_MAX_CONFLICT = 0.40
    const val KILL_BRIER_DRIFT = 1.25
    const val KILL_MIN_COVERAGE = 0.40
    const val KILL_MIN_TRIGGERS = 2

    // SPEC v2 §19 — Statistical validation regime
    const val MIN_CALIBRATION_SAMPLES = 500
    const val MIN_EVENT_SAMPLES = 30

    // News intelligence (Layer 21/22 of the specification)
    const val NEWS_SEVERITY_WEIGHT = 0.4
    const val NEWS_NOVELTY_WEIGHT = 0.2
    const val NEWS_CREDIBILITY_WEIGHT = 0.4
    const val NEWS_HIGH_IMPACT_THRESHOLD = 70.0
    const val NEWS_MEDIUM_IMPACT_THRESHOLD = 40.0
}
