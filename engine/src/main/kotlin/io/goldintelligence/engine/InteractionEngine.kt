package io.goldintelligence.engine

/**
 * Encodes the specification's explicit interaction clusters.
 *
 * Sign convention (per the specification's Factor Scoring section):
 * FactorScore.score is already mapped onto the shared -100..+100
 * Bearish..Bullish gold-bias scale — e.g. Real Yield's own raw direction is
 * "↓" (falling real yield is bullish for gold), so a *positive* F01 score
 * means "real yield is behaving bullishly", not "real yield rose". Each
 * cluster below is therefore agreement/conflict between already gold-
 * oriented scores, not a re-interpretation of raw indicator direction:
 *
 *  - F01, F02, F03 all positive (Real Yield↓ + DXY↓ + Fed Dovish, each
 *    already bullish-scored) → reinforced bullish cluster.
 *  - F01, F02, F03 all negative (Real Yield↑ + DXY↑ + Fed Hawkish) →
 *    reinforced bearish cluster.
 *  - F05 positive + F01 positive (Inflation↑ read as bullish AND Real
 *    Yield↓ agrees) → strongest bullish combination (Regime C).
 *  - F05 positive + F01 negative (Inflation's naive bullish read conflicts
 *    with Real Yield actually behaving bearishly) → the inflation effect
 *    must NOT be taken as automatically bullish; it is dampened here.
 *  - F07 positive + F13 positive (Geopolitical Risk↑ + Momentum↑ agree) →
 *    Trend Confirmation bonus.
 */
class ClusterFactorInteractionEngine : FactorInteractionEngine {
    override fun adjust(scores: List<FactorScore>): Double {
        if (scores.isEmpty()) return 0.0
        val byId = scores.associateBy { it.factorId }
        val d = CalibrationDefaults
        var adjustment = 0.0

        val realYield = byId["F01_REAL_RATE"]?.score
        val usd = byId["F02_USD"]?.score
        val fed = byId["F03_FED"]?.score
        val inflation = byId["F05_INFLATION"]?.score
        val geo = byId["F07_GEOPOLITICAL_RISK"]?.score
        val momentum = byId["F13_MARKET_MOMENTUM"]?.score

        if (realYield != null && usd != null && fed != null) {
            if (realYield > 0.0 && usd > 0.0 && fed > 0.0) {
                adjustment += d.INTERACTION_MONETARY_CLUSTER_BONUS
            } else if (realYield < 0.0 && usd < 0.0 && fed < 0.0) {
                adjustment -= d.INTERACTION_MONETARY_CLUSTER_BONUS
            }
        }

        if (inflation != null && realYield != null) {
            if (inflation > 0.0 && realYield > 0.0) {
                adjustment += d.INTERACTION_INFLATION_BULLISH_BONUS
            } else if (inflation > 0.0 && realYield < 0.0) {
                adjustment -= d.INTERACTION_INFLATION_OVERRIDE_PENALTY
            }
        }

        if (geo != null && momentum != null && geo > 0.0 && momentum > 0.0) {
            adjustment += d.INTERACTION_TREND_CONFIRMATION_BONUS
        }

        return adjustment
    }
}
