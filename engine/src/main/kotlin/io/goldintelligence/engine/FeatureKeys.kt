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

    /* ---------------- SPEC v2.1 — keys added when the paid gaps closed ---
     * Each of these was previously reported as OUT_OF_STACK, UNREACHABLE or
     * WEEKLY_ONLY and now carries a measured value.
     */

    // Depth of book — real L2 for allocated gold, no longer a top-of-book proxy.
    const val BOOK_IMBALANCE = "Book_Imbalance"
    const val BOOK_DEPTH_TOTAL = "Book_Depth_Total"
    const val BOOK_SPREAD_BP = "Book_Spread_Bp"
    const val OTC_SPREAD_BP = "OTC_Spread_Bp"

    // Daily open interest, replacing the weekly-only positioning print.
    const val OPEN_INTEREST = "Open_Interest"
    const val OPEN_INTEREST_CHANGE = "OpenInterest_Change"
    const val OPEN_INTEREST_ZSCORE = "OpenInterest_ZScore"

    // Regional physical premia.
    const val CHINA_PREMIUM = "China_Premium"
    const val INDIA_PREMIUM = "India_Premium"

    // Benchmark and cross-source agreement.
    const val LBMA_BENCHMARK = "LBMA_Benchmark"
    const val LBMA_DEVIATION = "LBMA_Deviation"
    const val SPOT_SOURCE_DISPERSION = "Spot_Source_Dispersion"

    // Calendar and consensus.
    const val CALENDAR_HIGH_IMPACT_24H = "Calendar_HighImpact_24h"
    const val CALENDAR_HOURS_TO_EVENT = "Calendar_Hours_To_Event"
    const val CONSENSUS_SURPRISE = "Consensus_Surprise"
    const val CPI_YOY = "CPI_YoY"
    const val UNEMPLOYMENT_RATE = "Unemployment_Rate"

    // SPEC v2.1 §25 — measured macro, options and news-derived inputs.
    /** Market-implied policy rate twelve months out, from fed funds futures. */
    const val FED_IMPLIED_PATH_12M = "FedPath_Implied_12M"
    /** Chicago Fed National Financial Conditions Index: negative is loose. */
    const val FINANCIAL_CONDITIONS = "Financial_Conditions"
    /** Federal Reserve net liquidity, in billions of dollars. */
    const val FED_NET_LIQUIDITY = "Fed_Net_Liquidity"
    /** Thirteen-week change in net liquidity, in per cent. */
    const val FED_NET_LIQUIDITY_CHANGE = "Fed_Net_Liquidity_Change"
    /** Five-year, five-year forward inflation expectation. */
    const val INFLATION_EXPECTATION_5Y5Y = "Inflation_Expectation_5Y5Y"
    /** Implied volatility of the US bond market (MOVE). */
    const val BOND_VOLATILITY = "Bond_Volatility"
    /** Equity fear curve: three-month VIX minus nine-day VIX. */
    const val VIX_TERM_SLOPE = "VIX_Term_Slope"
    /** Put open interest over call open interest on the gold ETF chain. */
    const val GOLD_PUT_CALL_OI = "Gold_PutCall_OI"
    /** Twenty-five delta risk reversal, in volatility points. */
    const val GOLD_RISK_REVERSAL = "Gold_Risk_Reversal"
    /** Newspaper-derived daily economic policy uncertainty. */
    const val POLICY_UNCERTAINTY = "Policy_Uncertainty"
    /** Newspaper-derived daily equity-market uncertainty. */
    const val NEWS_UNCERTAINTY = "News_Uncertainty"

    // SPEC v2.1 §27 — measured inputs that replaced a stand-in or added a read.
    /** WTI spot, published daily by the EIA through FRED. */
    const val WTI_SPOT = "WTI_Spot"
    /** Twenty-session change in WTI spot, in per cent. */
    const val OIL_MOMENTUM_SPOT = "Oil_Momentum_Spot"
    /** Brent minus WTI, in dollars: the physical-dislocation read. */
    const val BRENT_WTI_SPREAD = "Brent_WTI_Spread"
    /** Crude implied volatility (OVX). */
    const val OIL_VOLATILITY = "Oil_Volatility"
    /** Real-yield curve slope: 30-year minus 5-year TIPS yield. */
    const val REAL_CURVE_SLOPE = "Real_Curve_Slope"
    /** Five-year real yield. */
    const val REAL_YIELD_5Y = "Real_Yield_5Y"
    /** Five-year breakeven inflation rate. */
    const val BREAKEVEN_5Y = "Breakeven_5Y"
    /** Breakeven curve slope: ten-year minus five-year. */
    const val BREAKEVEN_SLOPE = "Breakeven_Slope"
    /** Ten-year minus three-month Treasury spread. */
    const val YIELD_CURVE_10Y_3M = "Yield_Curve_10Y_3M"
    /** Managed-money net position from the CFTC disaggregated report. */
    const val COT_MANAGED_MONEY_Z = "COT_ManagedMoney_Z"
    /** Commercial-hedger net position from the same report. */
    const val COT_COMMERCIAL_Z = "COT_Commercial_Z"
    /** Investment-grade option-adjusted spread. */
    const val CREDIT_SPREAD_IG = "Credit_Spread_IG"
    /** CCC and lower option-adjusted spread: the tail of the credit market. */
    const val CREDIT_SPREAD_CCC = "Credit_Spread_CCC"
    /** Emerging-market corporate option-adjusted spread. */
    const val CREDIT_SPREAD_EM = "Credit_Spread_EM"
    /** Thirteen-week change in the Fed's SOMA portfolio, in per cent. */
    const val SOMA_CHANGE = "SOMA_Change"
    /** SOFR's 99th percentile over its volume-weighted median, in basis points. */
    const val REPO_TAIL_SPREAD = "Repo_Tail_Spread"
    /** Bank reserve balances at the Fed, in billions. */
    const val RESERVE_BALANCES = "Reserve_Balances"
    /** OECD composite leading indicator for the United States. */
    const val OECD_LEADING_INDICATOR = "OECD_Leading_Indicator"
    /** Six-month change in that indicator, in index points. */
    const val OECD_LEADING_CHANGE = "OECD_Leading_Change"
    /** Nasdaq implied volatility (VXN) over the VIX: the tech-risk tilt. */
    const val VOL_DISPERSION_VXN_VIX = "Vol_Dispersion_VXN_VIX"
    /** Newspaper-derived infectious-disease equity-market volatility tracker. */
    const val INFECTIOUS_DISEASE_EMV = "Infectious_Disease_EMV"
    /** Dollar against the advanced-foreign-economies basket. */
    const val DOLLAR_AFE = "Dollar_AFE"
    /** Yuan per dollar: the managed-currency read behind F19. */
    const val USDCNY = "USDCNY"
}
