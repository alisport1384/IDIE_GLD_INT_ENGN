package io.goldintelligence.engine

object GoldSpecification {
    val baseFactorWeights: Map<String, Double> = linkedMapOf(
        "F01_REAL_RATE" to 0.22,
        "F02_USD" to 0.16,
        "F03_FED" to 0.14,
        "F04_TREASURY_CURVE" to 0.0,
        "F05_INFLATION" to 0.08,
        "F06_ECONOMIC_SURPRISE" to 0.0,
        "F07_GEOPOLITICAL_RISK" to 0.12,
        "F08_FINANCIAL_STRESS" to 0.0,
        "F09_GOLD_FLOW" to 0.10,
        "F10_CENTRAL_BANK_DEMAND" to 0.0,
        "F11_FUTURES_POSITIONING" to 0.06,
        "F12_PHYSICAL_DEMAND" to 0.02,
        "F13_MARKET_MOMENTUM" to 0.05,
        "F14_MARKET_MICROSTRUCTURE" to 0.0,
        "F15_OPTIONS_VOLATILITY" to 0.0,
        "F16_CROSS_ASSET" to 0.0,
        "F17_LIQUIDITY" to 0.0,
        "F18_CREDIT" to 0.0,
        "F19_CHINA" to 0.0,
        "F20_INDIA" to 0.0,
        "F21_OIL_ENERGY" to 0.0,
        "F22_GLOBAL_CB_POLICY" to 0.0
    )

    val requiredHorizons = listOf("5m", "15m", "1H", "4H", "1D", "1W")

    val requiredLayers = listOf(
        "DATA", "FEATURES", "FACTORS", "CAUSALITY", "EVENTS_NEWS",
        "MICROSTRUCTURE", "REGIME", "INFORMATION_DOMINANCE",
        "DYNAMIC_WEIGHTS", "INTERACTIONS", "DIVERGENCE",
        "HISTORICAL_ANALOGUES", "ENSEMBLE", "PROBABILITY",
        "CALIBRATION", "CONFIDENCE", "SCENARIOS", "INVALIDATION"
    )
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
}

/**
 * Regime-conditional multipliers applied to each causal group's aggregate
 * weight (Layer 4 "Regime Engine" / Dynamic Weighting formula:
 * Effective Weight = Base Weight × Regime Weight × Dominance × Quality × Decay).
 *
 * These multipliers are Initial Priors, exactly as the specification frames
 * base weights: "این وزن‌ها را نباید به‌عنوان حقیقت تاریخی نهایی در نظر گرفت؛
 * اینها Initial Prior هستند تا سیستم بتواند بعداً با داده واقعی آنها را
 * کالیبره کند." They must be recalibrated once live/backtest data exists
 * (spec items: Walk-Forward Learning, Model Drift Detection).
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
 * from the specification's Shock Detection example ("DXY 5σ Move + 10Y 4σ
 * Move + Gold 5σ Move + VIX Spike"). The VIX spike z-score and the minimum
 * simultaneous-trigger count are provisional Initial Priors pending
 * recalibration.
 */
object ShockThresholds {
    const val DXY_SIGMA = 5.0
    const val US10Y_SIGMA = 4.0
    const val GOLD_SIGMA = 5.0
    const val VIX_SPIKE_ZSCORE = 3.0
    const val MIN_TRIGGERED_FOR_SHOCK = 2
}

/**
 * All remaining numeric calibration parameters used by the rule-based
 * engines (Regime Detector, Divergence, Contradiction, Interaction,
 * Scenario, News Intelligence). Centralized here so the entire calibration
 * surface of the engine is a single, auditable, versioned contract, per the
 * specification's own directive that such parameters are priors requiring
 * later Walk-Forward calibration, never silently hard-coded per-file values.
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

    // Information dominance
    const val DOMINANCE_BOOST_MAX = 0.5

    // Time decay
    const val TIME_DECAY_HALF_LIFE_HOURS = 72.0

    // News intelligence (Layer 21/22 of the specification)
    const val NEWS_SEVERITY_WEIGHT = 0.4
    const val NEWS_NOVELTY_WEIGHT = 0.2
    const val NEWS_CREDIBILITY_WEIGHT = 0.4
    const val NEWS_HIGH_IMPACT_THRESHOLD = 70.0
    const val NEWS_MEDIUM_IMPACT_THRESHOLD = 40.0
}
