package io.goldintelligence.engine

import kotlin.math.abs
import kotlin.math.min

/**
 * Contradiction Engine: instead of immediately averaging opposing factors,
 * classify how much of the weighted evidence disagrees with the majority
 * direction, per the specification's example (Real Yield/DXY/Fed Bearish
 * vs Geopolitics/ETF/Momentum Bullish → Conflict = High, not just a blended
 * BUY 57%).
 */
class RuleBasedContradictionAnalysisEngine : ContradictionAnalysisEngine {
    override fun analyze(weightedScores: List<Pair<FactorScore, Double>>): Pair<ConflictLevel, List<String>> {
        if (weightedScores.isEmpty()) return ConflictLevel.UNKNOWN to emptyList()

        val bullish = weightedScores.filter { it.first.score > 0.0 }
        val bearish = weightedScores.filter { it.first.score < 0.0 }

        val bullishMagnitude = bullish.sumOf { abs(it.first.score) * it.second }
        val bearishMagnitude = bearish.sumOf { abs(it.first.score) * it.second }
        val total = bullishMagnitude + bearishMagnitude

        if (total <= 0.0) return ConflictLevel.LOW to emptyList()

        val minority = min(bullishMagnitude, bearishMagnitude)
        val ratio = minority / total
        val d = CalibrationDefaults

        val level = when {
            ratio >= d.CONFLICT_HIGH_RATIO -> ConflictLevel.HIGH
            ratio >= d.CONFLICT_MEDIUM_RATIO -> ConflictLevel.MEDIUM
            else -> ConflictLevel.LOW
        }

        val notes = if (level != ConflictLevel.LOW) {
            val majorityIsBullish = bullishMagnitude >= bearishMagnitude
            val minoritySide = if (majorityIsBullish) bearish else bullish
            val minorityFactors = minoritySide
                .sortedByDescending { abs(it.first.score) * it.second }
                .take(3)
                .map { it.first.factorId }
            listOf(
                "Majority direction: ${if (majorityIsBullish) "Bullish" else "Bearish"}; " +
                    "opposing factors: ${minorityFactors.joinToString().ifEmpty { "none" }}"
            )
        } else emptyList()

        return level to notes
    }
}
