package io.goldintelligence.engine

/**
 * Canonical feature-key contract (Data Dictionary keys) referenced by the
 * rule-based analytical engines in this module. Populating these keys from
 * raw market/economic data is the responsibility of a FeatureEngineer
 * implementation supplied by the data-ingestion layer (planned server-side
 * deployment). This object defines the contract only; it fabricates no
 * values, sources, or formulas.
 */
object FeatureKeys {
    // Factor 1 — Real Rate (SPEC v2 §3.6: intraday proxy, US cash hours only)
    const val REAL_YIELD_INTRADAY_PROXY = "RealYield_Intraday_Proxy"

    const val REAL_YIELD = "RealYield"
    const val REAL_YIELD_ZSCORE = "RealYield_ZScore"
    const val REAL_YIELD_TREND = "RealYield_Trend"

    // Factor 2 — USD
    const val DXY = "DXY"
    const val DXY_ZSCORE = "DXY_ZScore"
    const val DXY_TREND = "DXY_Trend"
    const val DXY_MOVE_SIGMA = "DXY_Move_Sigma"

    // Factor 3 — Fed Expectations
    const val FED_EXPECTED_RATE_CHANGE = "FedExpectations_Change"
    const val FED_SURPRISE = "FOMC_Surprise"

    // Factor 4 — Treasury Curve
    const val US10Y_YIELD = "US10Y_Yield"
    const val US10Y_MOVE_SIGMA = "US10Y_Move_Sigma"
    const val YIELD_CURVE_10Y_2Y = "Spread_10Y_2Y"

    // Factor 5 — Inflation
    const val INFLATION_SURPRISE = "Inflation_Surprise"
    const val BREAKEVEN_CHANGE = "Breakeven_Change"

    // Factor 6 — Economic Surprise / Growth
    const val ECONOMIC_SURPRISE_INDEX = "EconomicSurprise_Index"

    // Factor 7 — Geopolitical Risk
    const val GEOPOLITICAL_RISK_SCORE = "GeopoliticalRisk_Score"
    const val GEOPOLITICAL_RISK_DELTA = "GeopoliticalRisk_Delta"

    // Factor 8 — Financial Stress
    const val FINANCIAL_STRESS_SCORE = "FinancialStress_Score"
    const val VIX = "VIX"
    const val VIX_ZSCORE = "VIX_ZScore"

    // Factor 9 — Gold ETF Flow
    const val ETF_FLOW_ZSCORE = "ETFFlow_ZScore"
    const val ETF_HOLDINGS_CHANGE = "ETFHoldings_Change"

    // Factor 10 — Central Bank Demand
    const val CENTRAL_BANK_NET_BUYING_3M = "CentralBank_NetBuying_3M"

    // Factor 11 — Futures Positioning / COT
    const val COT_NET_POSITION_ZSCORE = "COT_NetPosition_ZScore"
    const val COT_EXTREME_LONG = "COT_ExtremeLong"
    const val COT_EXTREME_SHORT = "COT_ExtremeShort"

    // Factor 10 — Central Bank Demand (continued)
    const val CENTRAL_BANK_PROXY_FLOW = "CentralBank_Proxy_Flow"

    // Factor 12 — Physical Demand
    const val PHYSICAL_DEMAND_INDEX = "PhysicalDemand_Index"

    // Factor 13 — Market Momentum
    const val GOLD_MOMENTUM = "Gold_Momentum"
    const val GOLD_RETURN = "Gold_Return"
    const val GOLD_MOVE_SIGMA = "Gold_Move_Sigma"

    // Factor 14 — Market Microstructure
    const val MICROSTRUCTURE_IMBALANCE = "Microstructure_Imbalance"

    // SPEC v2 §1.5 — COMEX forward curve
    const val FUTURES_BASIS = "Futures_Basis"
    const val TERM_STRUCTURE_SLOPE = "TermStructure_Slope"

    // Factor 15 — Options / Volatility
    const val GOLD_IV_SKEW = "Gold_IV_Skew"
    const val GOLD_REALIZED_VOL_ZSCORE = "Gold_RealizedVol_ZScore"

    // Factor 16 — Cross-Asset Relationships
    const val GOLD_SILVER_RATIO = "Gold_Silver_Ratio"
    const val GOLD_SILVER_RATIO_ZSCORE = "Gold_Silver_Ratio_ZScore"

    // Factor 17 — Liquidity
    const val DOLLAR_FUNDING_STRESS = "DollarFunding_Stress"

    // Factor 18 — Credit Conditions
    const val CREDIT_SPREAD_HY = "Credit_HY_Spread"

    // Factor 19 / 20 — China / India
    const val CHINA_DEMAND_INDEX = "China_Demand_Index"
    const val INDIA_DEMAND_INDEX = "India_Demand_Index"

    // Factor 21 — Oil / Energy
    const val OIL_MOMENTUM = "Oil_Momentum"

    // Factor 22 — Global Central-Bank Policy Divergence
    const val GLOBAL_CB_POLICY_DIVERGENCE = "GlobalCB_Policy_Divergence"

    // Macro / growth state
    const val RECESSION_PROBABILITY = "US_Recession_Probability"
}
