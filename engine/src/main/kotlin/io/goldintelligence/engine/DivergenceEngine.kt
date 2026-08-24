package io.goldintelligence.engine

import kotlin.math.abs
import kotlin.math.sign

/**
 * Detects Macro, Flow and Positioning divergences (Layer "Divergence
 * Engine": divergence is treated as information, not noise). News
 * Divergence (Narrative vs Reality) is handled separately by
 * NewsIntelligenceEngine, since it requires a NewsRecord, not FactorScores.
 */
class RuleBasedDivergenceAnalysisEngine : DivergenceAnalysisEngine {
    override fun analyze(scores: List<FactorScore>, features: FeatureSet): List<DivergenceRecord> {
        if (scores.isEmpty()) return emptyList()
        val d = CalibrationDefaults
        val byId = scores.associateBy { it.factorId }
        val records = mutableListOf<DivergenceRecord>()

        val macroIds = listOf("F01_REAL_RATE", "F02_USD", "F03_FED")
        val macroScores = macroIds.mapNotNull { byId[it]?.score }
        val macroAvg = if (macroScores.isNotEmpty()) macroScores.average() else null
        val momentum = byId["F13_MARKET_MOMENTUM"]?.score

        if (macroAvg != null && momentum != null &&
            abs(macroAvg) >= d.DIVERGENCE_MAGNITUDE_THRESHOLD &&
            abs(momentum) >= d.DIVERGENCE_MAGNITUDE_THRESHOLD &&
            sign(macroAvg) != sign(momentum)
        ) {
            records += DivergenceRecord(
                DivergenceType.MACRO,
                "Macro factors (Real Yield/DXY/Fed) diverge from Gold price momentum."
            )
        }

        val flow = byId["F09_GOLD_FLOW"]?.score
        if (flow != null && momentum != null &&
            abs(flow) >= d.DIVERGENCE_MAGNITUDE_THRESHOLD &&
            abs(momentum) >= d.DIVERGENCE_MAGNITUDE_THRESHOLD &&
            sign(flow) != sign(momentum)
        ) {
            records += DivergenceRecord(
                DivergenceType.FLOW,
                "ETF flow direction diverges from Gold price momentum."
            )
        }

        val cotZ = features.values[FeatureKeys.COT_NET_POSITION_ZSCORE]
        val cotExtremeLong = features.values[FeatureKeys.COT_EXTREME_LONG]
        if (momentum != null && momentum > 0.0 &&
            cotExtremeLong != null && cotExtremeLong >= 1.0 &&
            cotZ != null && cotZ >= d.COT_EXTREME_ZSCORE
        ) {
            records += DivergenceRecord(
                DivergenceType.POSITIONING,
                "Gold price rising while COT positioning sits at a crowded long extreme (exhaustion risk)."
            )
        }

        return records
    }
}
