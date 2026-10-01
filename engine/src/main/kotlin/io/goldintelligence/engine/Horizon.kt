package io.goldintelligence.engine

/**
 * SPEC v2 §D1 — Horizon gating.
 *
 * v1 published a single factor table for all six horizons. Most factors
 * cannot refresh anywhere near a 5-minute cadence on the free data stack, so
 * using them at 5m silently recycles a stale daily or weekly value as if it
 * were new information. Each factor therefore declares a `minHorizon`: the
 * shortest horizon on which it is allowed to contribute at all.
 */
enum class Horizon(val code: String, val minutes: Long, val rank: Int) {
    M5("5m", 5, 0),
    M15("15m", 15, 1),
    H1("1H", 60, 2),
    H4("4H", 240, 3),
    D1("1D", 1_440, 4),
    W1("1W", 10_080, 5);

    companion object {
        fun of(code: String): Horizon? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
        val ordered: List<Horizon> = entries.sortedBy { it.rank }
    }
}

/** Numeric-probability policy per horizon (SPEC v2 §D1 / §19). */
enum class HorizonMode {
    /** Calibrated probability may be published. */
    CALIBRATED,

    /** Direction only; probability is withheld as structurally uncalibrated. */
    DIRECTIONAL_ONLY
}

object FactorHorizons {
    /** Shortest horizon on which each factor may contribute. */
    val minHorizon: Map<String, Horizon> = linkedMapOf(
        "F01_REAL_RATE" to Horizon.D1,
        "F02_USD" to Horizon.M5,
        "F03_FED" to Horizon.M5,
        "F04_TREASURY_CURVE" to Horizon.M5,
        "F05_INFLATION" to Horizon.D1,
        "F06_ECONOMIC_SURPRISE" to Horizon.D1,
        "F07_GEOPOLITICAL_RISK" to Horizon.H1,
        "F08_FINANCIAL_STRESS" to Horizon.D1,
        "F09_GOLD_FLOW" to Horizon.D1,
        "F10_CENTRAL_BANK_DEMAND" to Horizon.W1,
        "F11_FUTURES_POSITIONING" to Horizon.W1,
        "F12_PHYSICAL_DEMAND" to Horizon.W1,
        "F13_MARKET_MOMENTUM" to Horizon.M5,
        "F14_MARKET_MICROSTRUCTURE" to Horizon.M5,
        "F15_OPTIONS_VOLATILITY" to Horizon.M15,
        "F16_CROSS_ASSET" to Horizon.M5,
        "F17_LIQUIDITY" to Horizon.D1,
        "F18_CREDIT" to Horizon.D1,
        "F19_CHINA" to Horizon.W1,
        "F20_INDIA" to Horizon.W1,
        "F21_OIL_ENERGY" to Horizon.M5,
        "F22_GLOBAL_CB_POLICY" to Horizon.D1
    )

    fun isActive(factorId: String, horizon: Horizon): Boolean {
        val min = minHorizon[factorId] ?: return true
        return horizon.rank >= min.rank
    }

    fun activeAt(horizon: Horizon): List<String> =
        GoldSpecification.baseFactorWeights.keys.filter { isActive(it, horizon) }
}

/**
 * Coverage = the share of total prior weight that is actually admissible at a
 * horizon. The remainder is *not* redistributed into Confidence: weights are
 * renormalized so the score stays on the same scale, but Confidence is then
 * multiplied by Coverage, so a thin-evidence horizon can never look as certain
 * as a full-evidence one.
 */
object HorizonGate {
    data class Gated(
        val horizon: Horizon,
        val coverage: Double,
        val activeFactorIds: List<String>,
        val gatedFactorIds: List<String>,
        val renormalizedBaseWeights: Map<String, Double>,
        val mode: HorizonMode
    )

    fun coverage(horizon: Horizon, availableFactorIds: Collection<String> = GoldSpecification.baseFactorWeights.keys): Double {
        val all = GoldSpecification.baseFactorWeights
        val total = all.values.sum()
        if (total <= 0.0) return 0.0
        val active = availableFactorIds
            .filter { FactorHorizons.isActive(it, horizon) }
            .sumOf { all[it] ?: 0.0 }
        return (active / total).coerceIn(0.0, 1.0)
    }

    fun modeFor(horizon: Horizon): HorizonMode =
        if (horizon.rank <= Horizon.M15.rank) HorizonMode.DIRECTIONAL_ONLY else HorizonMode.CALIBRATED

    fun gate(horizon: Horizon, availableFactorIds: Collection<String>): Gated {
        val all = GoldSpecification.baseFactorWeights
        val active = availableFactorIds.filter { FactorHorizons.isActive(it, horizon) && all.containsKey(it) }
        val gated = availableFactorIds.filter { !FactorHorizons.isActive(it, horizon) && all.containsKey(it) }
        val activeMass = active.sumOf { all[it] ?: 0.0 }
        val renormalized = if (activeMass <= 0.0) {
            emptyMap()
        } else {
            active.associateWith { (all[it] ?: 0.0) / activeMass }
        }
        return Gated(
            horizon = horizon,
            coverage = coverage(horizon, availableFactorIds),
            activeFactorIds = active,
            gatedFactorIds = gated,
            renormalizedBaseWeights = renormalized,
            mode = modeFor(horizon)
        )
    }
}
