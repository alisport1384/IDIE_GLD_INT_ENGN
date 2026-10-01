package io.goldintelligence.engine

/**
 * Builds the Base / Bull / Bear / Tail scenario set defined verbatim in the
 * specification's "Scenario Engine" section. Probabilities and magnitudes
 * are intentionally left null: assigning numeric values to them requires a
 * calibrated ProbabilityModel, which this module does not fabricate.
 */
class RuleBasedScenarioOutlookEngine : ScenarioOutlookEngine {
    override fun buildScenarios(scores: List<FactorScore>, goldBias: Double?): List<Scenario> {
        val base = Scenario(
            name = "BASE",
            probability = null,
            trigger = "Fed Dovish + Real Yield ↓ → Gold ↑ (current dominant path)",
            expectedDirection = directionOf(goldBias),
            expectedMagnitude = null,
            invalidation = "The current dominant factor reverses direction."
        )

        val bull = Scenario(
            name = "BULL",
            probability = null,
            trigger = "Geopolitical Shock + ETF Inflow + Real Yield ↓↓",
            expectedDirection = Direction.BULLISH,
            expectedMagnitude = null,
            invalidation = "Geopolitical de-escalation OR ETF outflow reversal OR Real Yield turns up."
        )

        val bear = Scenario(
            name = "BEAR",
            probability = null,
            trigger = "CPI Surprise ↑↑ + Fed Hawkish + Real Yield ↑↑",
            expectedDirection = Direction.BEARISH,
            expectedMagnitude = null,
            invalidation = "Inflation surprise fades OR Fed pivots dovish OR Real Yield turns down."
        )

        val tail = Scenario(
            name = "TAIL",
            probability = null,
            trigger = "Liquidity Shock + USD Spike + Forced Liquidation",
            expectedDirection = Direction.BEARISH,
            expectedMagnitude = null,
            invalidation = "Liquidity conditions normalize."
        )

        return listOf(base, bull, bear, tail)
    }

    private fun directionOf(bias: Double?): Direction = when {
        bias == null -> Direction.NEUTRAL
        bias > CalibrationDefaults.BIAS_NEUTRAL_BAND -> Direction.BULLISH
        bias < -CalibrationDefaults.BIAS_NEUTRAL_BAND -> Direction.BEARISH
        else -> Direction.NEUTRAL
    }
}
