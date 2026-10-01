package io.goldintelligence.app

import io.goldintelligence.engine.Direction
import io.goldintelligence.engine.FactorScore
import io.goldintelligence.engine.FeatureKeys
import io.goldintelligence.engine.FeatureSet
import io.goldintelligence.engine.InputSnapshot
import io.goldintelligence.engine.NewsRecord
import java.time.Instant

/**
 * Illustrative sample snapshot used only to demonstrate the engine's full
 * output surface in the UI. These are NOT live market values — no live data
 * source is connected yet (data ingestion is planned as a future server-side
 * deployment). Replace SampleData.snapshot() with a real InputSnapshot once
 * a FactorEngine/data feed exists.
 */
object SampleData {
    fun snapshot(now: Instant = Instant.now()): InputSnapshot {
        val factorScores = listOf(
            FactorScore("F01_REAL_RATE", -55.0, asOf = now),
            FactorScore("F02_USD", -30.0, asOf = now),
            FactorScore("F03_FED", -40.0, asOf = now),
            FactorScore("F05_INFLATION", 25.0, asOf = now),
            FactorScore("F07_GEOPOLITICAL_RISK", 60.0, asOf = now),
            FactorScore("F09_GOLD_FLOW", 35.0, asOf = now),
            FactorScore("F11_FUTURES_POSITIONING", -20.0, asOf = now),
            FactorScore("F13_MARKET_MOMENTUM", 45.0, asOf = now)
        )

        val features = FeatureSet(
            mapOf(
                FeatureKeys.REAL_YIELD_TREND to -0.15,
                FeatureKeys.DXY_TREND to -0.08,
                FeatureKeys.FED_EXPECTED_RATE_CHANGE to -0.15,
                FeatureKeys.GEOPOLITICAL_RISK_SCORE to 55.0,
                FeatureKeys.VIX_ZSCORE to 0.8,
                FeatureKeys.FINANCIAL_STRESS_SCORE to 0.5,
                FeatureKeys.DXY_MOVE_SIGMA to 1.2,
                FeatureKeys.GOLD_MOVE_SIGMA to 1.5,
                FeatureKeys.US10Y_MOVE_SIGMA to 1.0,
                FeatureKeys.COT_NET_POSITION_ZSCORE to 1.5,
                FeatureKeys.COT_EXTREME_LONG to 0.0,
                FeatureKeys.DOLLAR_FUNDING_STRESS to 0.6
            )
        )

        val news = listOf(
            NewsRecord(
                eventType = "FOMC",
                country = "US",
                severity = 60.0,
                novelty = 50.0,
                credibility = 90.0,
                goldRelevance = 80.0,
                direction = Direction.BULLISH,
                timestamp = now
            )
        )

        return InputSnapshot(
            observations = emptyMap(),
            features = features,
            factorScores = factorScores,
            news = news
        )
    }
}
