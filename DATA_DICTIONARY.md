# Gold Intelligence Engine — Data Dictionary

Source: user-supplied specification «شاخص های طلا.rtf», sections "Master List —
Gold Intelligence Engine" and "Data Source Architecture". This document is
the authoritative mapping between the specification's 36 Master List
variables, the engine's 22-factor `FactorCatalog` (`Specification.kt`), and
the `FeatureKeys` contract consumed by the rule-based engines.

No live data source, credential, or numeric value is defined here — this is
the schema only. Ingestion is deferred to the future server-side deployment.

## Required record fields (per variable)

Every raw variable stored by the ingestion layer must carry:

| Field | Meaning |
|---|---|
| `value` / `actual` | Latest observed value |
| `expected` | Consensus/forecast at release time (if applicable) |
| `previous` | Prior period's value |
| `revision` | Revised value, stored separately from `actual` (no look-ahead) |
| `timestamp` | Observation/market timestamp (UTC) |
| `releaseTimestamp` | Official publication timestamp (UTC) |
| `source` | Origin system/provider |
| `quality` | 0.0–1.0 data-quality score (freshness, reliability, missingness) |
| `latencySeconds` | Delay between event and availability to the engine |

These map directly to `Observation` in `Model.kt`.

## Source tiers (Source Hierarchy)

| Tier | Definition | Examples |
|---|---|---|
| A | Official / Primary | CFTC, U.S. Treasury |
| B | Official Repository / Highly Reliable Institutional | FRED, CME |
| C | Institutional | World Gold Council |
| D | Secondary / Derived / Aggregated | News aggregators, derived indices |

## Update-frequency classes

| Class | Cadence | Suitable for |
|---|---|---|
| A — High Frequency | Intraday | Gold, DXY, Yields, Fed Futures, VIX, Futures/OI |
| B — Medium Frequency | Daily/Weekly | ETF Flows, COT, Inflation prints, Economic Data |
| C — Low Frequency | Monthly/Quarterly | Central Bank Purchases, Mine Supply, Jewellery, China/India structural demand |

Mixing classes into one score without frequency-aware weighting causes the
Frequency Mismatch failure the specification explicitly warns against.

## Master List → Factor → Feature Key mapping

| # | Master List Variable | Factor (`FactorCatalog`) | Tier | Frequency | Direction | `FeatureKeys` |
|---|---|---|---|---|---|---|
| 1 | US 10Y Real Yield (TIPS) | F01_REAL_RATE | A | Daily/Intraday | ↓ | `REAL_YIELD`, `REAL_YIELD_ZSCORE`, `REAL_YIELD_TREND` |
| 2 | DXY | F02_USD | A | Intraday | ↓ | `DXY`, `DXY_ZSCORE`, `DXY_TREND`, `DXY_MOVE_SIGMA` |
| 3 | Fed Rate Expectations (SOFR/Fed Funds Futures) | F03_FED | A | Intraday | ↓ | `FED_EXPECTED_RATE_CHANGE`, `FED_SURPRISE` |
| 4 | US 10Y Treasury Yield | F04_TREASURY_CURVE | A | Intraday | ↓ | `US10Y_YIELD`, `US10Y_MOVE_SIGMA` |
| 5 | Geopolitical Risk | F07_GEOPOLITICAL_RISK | D | Real-time | ↑ | `GEOPOLITICAL_RISK_SCORE`, `GEOPOLITICAL_RISK_DELTA` |
| 6 | US 2Y Yield | F04_TREASURY_CURVE | A | Intraday | ↓ | `YIELD_CURVE_10Y_2Y` |
| 7 | 10Y–2Y Spread | F04_TREASURY_CURVE | B | Daily | Dynamic | `YIELD_CURVE_10Y_2Y` |
| 8 | 5Y Breakeven | F05_INFLATION | B | Daily | ↑* | `BREAKEVEN_CHANGE` |
| 9 | 10Y Breakeven | F05_INFLATION | B | Daily | ↑* | `BREAKEVEN_CHANGE` |
| 10 | CPI Surprise | F05_INFLATION | B | Monthly | Dynamic | `INFLATION_SURPRISE` |
| 11 | Core CPI Surprise | F05_INFLATION | B | Monthly | Dynamic | `INFLATION_SURPRISE` |
| 12 | Core PCE Surprise | F05_INFLATION | B | Monthly | Dynamic | `INFLATION_SURPRISE` |
| 13 | Fed Decision Surprise | F03_FED | B | Event | Dynamic | `FED_SURPRISE` |
| 14 | ETF Net Flows | F09_GOLD_FLOW | B | Daily | ↑ | `ETF_FLOW_ZSCORE` |
| 15 | ETF Holdings | F09_GOLD_FLOW | B | Daily | ↑ | `ETF_HOLDINGS_CHANGE` |
| 16 | Central Bank Net Purchases | F10_CENTRAL_BANK_DEMAND | C | Monthly | ↑ | `CENTRAL_BANK_NET_BUYING_3M` |
| 17 | COT Managed Money Net | F11_FUTURES_POSITIONING | A | Weekly | ↑ | `COT_NET_POSITION_ZSCORE` |
| 18 | COT Position Z-Score | F11_FUTURES_POSITIONING | A | Weekly | Dynamic | `COT_NET_POSITION_ZSCORE`, `COT_EXTREME_LONG`, `COT_EXTREME_SHORT` |
| 19 | COMEX Open Interest | F11_FUTURES_POSITIONING | A | Daily | Dynamic | `COT_NET_POSITION_ZSCORE` |
| 20 | Gold Volume | F13_MARKET_MOMENTUM | A | Intraday | Dynamic | `GOLD_MOMENTUM` |
| 21 | Gold Momentum | F13_MARKET_MOMENTUM | A | Intraday | ↑ | `GOLD_MOMENTUM`, `GOLD_RETURN` |
| 22 | Gold Trend | F13_MARKET_MOMENTUM | A | Intraday | ↑ | `GOLD_MOMENTUM` |
| 23 | Gold Realized Volatility | F15_OPTIONS_VOLATILITY | A | Intraday | Dynamic | `GOLD_REALIZED_VOL_ZSCORE`, `GOLD_MOVE_SIGMA` |
| 24 | VIX | F08_FINANCIAL_STRESS | A | Intraday | ↑ | `VIX`, `VIX_ZSCORE` |
| 25 | Global Financial Stress | F08_FINANCIAL_STRESS | B | Daily | ↑ | `FINANCIAL_STRESS_SCORE` |
| 26 | US Recession Probability | F06_ECONOMIC_SURPRISE | B | Daily/Monthly | ↑ | `RECESSION_PROBABILITY` |
| 27 | US GDP Surprise | F06_ECONOMIC_SURPRISE | B | Quarterly | Dynamic | `ECONOMIC_SURPRISE_INDEX` |
| 28 | US NFP Surprise | F06_ECONOMIC_SURPRISE | B | Monthly | Dynamic | `ECONOMIC_SURPRISE_INDEX` |
| 29 | US Unemployment Rate | F06_ECONOMIC_SURPRISE | B | Monthly | Dynamic | `ECONOMIC_SURPRISE_INDEX` |
| 30 | US ISM Manufacturing | F06_ECONOMIC_SURPRISE | B | Monthly | Dynamic | `ECONOMIC_SURPRISE_INDEX` |
| 31 | US ISM Services | F06_ECONOMIC_SURPRISE | B | Monthly | Dynamic | `ECONOMIC_SURPRISE_INDEX` |
| 32 | China Gold Demand | F19_CHINA | C | Monthly | ↑ | `CHINA_DEMAND_INDEX` |
| 33 | India Gold Demand | F20_INDIA | C | Monthly | ↑ | `INDIA_DEMAND_INDEX` |
| 34 | Gold Jewellery Demand | F12_PHYSICAL_DEMAND | C | Quarterly | ↑ | `PHYSICAL_DEMAND_INDEX` |
| 35 | Mine Production | F12_PHYSICAL_DEMAND | C | Quarterly | ↓ | `PHYSICAL_DEMAND_INDEX` |
| 36 | Gold Recycling | F12_PHYSICAL_DEMAND | C | Quarterly | ↓ | `PHYSICAL_DEMAND_INDEX` |

\* Inflation's effect is conditional on the Real Yield response, per the
specification's explicit warning (`InteractionEngine` implements this via
`INTERACTION_INFLATION_BULLISH_BONUS` / `INTERACTION_INFLATION_OVERRIDE_PENALTY`).

## Factors present in the engine but outside the 36-variable Master List

These were introduced later in the specification's Factor Architecture
(Factors 14–18, 21, 22) and are represented in `FactorCatalog` with defined
`FeatureKeys` but zero base weight in `GoldSpecification.baseFactorWeights`
until a source and calibration are established:

`F14_MARKET_MICROSTRUCTURE`, `F16_CROSS_ASSET`, `F17_LIQUIDITY`,
`F18_CREDIT`, `F21_OIL_ENERGY`, `F22_GLOBAL_CB_POLICY`.
