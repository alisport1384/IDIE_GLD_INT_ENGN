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
| 16 | Central Bank Net Purchases | F10_CENTRAL_BANK_DEMAND | A | Monthly | ↑ | `CENTRAL_BANK_NET_BUYING_3M`, `CENTRAL_BANK_PROXY_FLOW` |
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

## Live keys added with SPEC v2.1 §23

These are universe ids, not Master List variables; they carry the measured
replacements for the last proxy inputs.

| Universe id | Unit | Provider | Feature it feeds |
|---|---|---|---|
| `CB_GOLD_NET_3M_T` | tonnes | `IMF_RESERVES` | `CENTRAL_BANK_NET_BUYING_3M` (fallback scale 150 t) |
| `CB_GOLD_NET_3M_Z` | z | `IMF_RESERVES` | `CENTRAL_BANK_NET_BUYING_3M` (preferred) |
| `CB_GOLD_BREADTH` | buyers−sellers, % | `IMF_RESERVES` | `CENTRAL_BANK_PROXY_FLOW` |
| `CB_GOLD_REPORTERS` | countries | `IMF_RESERVES` | provenance note only |
| `SURPRISE_INDEX_MEASURED` | index, ±100 | `CALENDAR` | `ECONOMIC_SURPRISE_INDEX` |
| `INFLATION_SURPRISE_MEASURED` | index, ±100 | `CALENDAR` | `INFLATION_SURPRISE` |
| `CALENDAR_NEXT_EVENT_HOURS` | hours | `CALENDAR` | `CALENDAR_HOURS_TO_EVENT` |
| `POLICY_RATE_US` | % | `BIS` | `FED_EXPECTED_RATE_CHANGE` |
| `POLICY_RATE_DIVERGENCE` | pp | `BIS` | `GLOBAL_CB_POLICY_DIVERGENCE` |
| `POLICY_DIVERGENCE_CHANGE_12M` | pp | `BIS` | `GLOBAL_CB_POLICY_DIVERGENCE` |

## Live keys added 2026‑10‑02 (SPEC §23.7, §24)

| Universe id | Unit | Provider | Feature it feeds |
|---|---|---|---|
| `US10Y_INTRADAY` | % | `TRADINGVIEW` (`TVC:US10Y`) | `REAL_YIELD_INTRADAY_PROXY` |
| `US02Y_INTRADAY` | % | `TRADINGVIEW` (`TVC:US02Y`) | provenance / cross-check only |
| `CONSENSUS_SURPRISE` | normalised, ±1 | `CALENDAR` (BLS pairing as fallback) | `CONSENSUS_SURPRISE` |

`REAL_YIELD_INTRADAY_PROXY` = `US10Y_INTRADAY − BREAKEVEN10Y` (last published), always
flagged `PROXY`, quality ceiling 0.6; it never substitutes for `REAL_YIELD`.

Historical analogues are not universe keys: `SeriesAnalogueEngine` reads the
daily histories already loaded (`GLD`, `SPY`, `TLT`, `UUP`, `VIX`, `SLV`) and
publishes its matches as `state.scenarios`, each a real date with its distance
and the gold return realised over the following 20 sessions.

## Live keys added 2026‑10‑02 (SPEC §25)

Measured sources. Full survey, including rejected endpoints: `docs/INDICATOR_RESEARCH.md`.

| Universe id | Unit | Provider | Feature it feeds |
|---|---|---|---|
| `HY_OAS` | pp | `FRED` (`BAMLH0A0HYM2`) | `CREDIT_SPREAD_HY` (measured; ETF ratio is the fallback) |
| `FINANCIAL_CONDITIONS_NFCI` | index, 0 = average | `FRED` (`NFCI`) | `FINANCIAL_CONDITIONS`, `FINANCIAL_STRESS_SCORE` |
| `FINANCIAL_STRESS_STLFSI` | index, 0 = average | `FRED` (`STLFSI4`) | `FINANCIAL_STRESS_SCORE` |
| `FED_BALANCE_SHEET` | USD m | `FRED` (`WALCL`) | `FED_NET_LIQUIDITY` |
| `TREASURY_GENERAL_ACCOUNT` | USD m | `FRED` (`WTREGEN`) | `FED_NET_LIQUIDITY` |
| `REVERSE_REPO` | USD bn | `FRED` (`RRPONTSYD`) | `FED_NET_LIQUIDITY` |
| `FED_NET_LIQUIDITY` | USD bn | derived | `FED_NET_LIQUIDITY`, `FED_NET_LIQUIDITY_CHANGE` |
| `POLICY_UNCERTAINTY_DAILY` | index | `FRED` (`USEPUINDXD`) | `POLICY_UNCERTAINTY`, `GEOPOLITICAL_RISK_SCORE/DELTA` |
| `NEWS_EQUITY_UNCERTAINTY` | index | `FRED` (`WLEMUINDXD`) | `NEWS_UNCERTAINTY`, `GEOPOLITICAL_RISK_SCORE` |
| `INFLATION_EXPECTATION_5Y5Y` | % | `FRED` (`T5YIFR`) | `INFLATION_EXPECTATION_5Y5Y` |
| `REAL10Y_DEEP` | % | `FRED` (`DFII10`) | `REAL_YIELD_ZSCORE` population |
| `BREAKEVEN10Y_DEEP` | % | `FRED` (`T10YIE`) | breakeven population |
| `GVZ_DEEP` | index | `FRED` (`GVZCLS`) | gold IV population |
| `DOLLAR_BROAD_INDEX` | index | `FRED` (`DTWEXBGS`) | independent check on `DXY` |
| `FED_FUNDS_IMPLIED_FRONT` | % | `TRADINGVIEW` (`CBOT:ZQ1!`) | provenance |
| `FED_FUNDS_IMPLIED_12M` | % | `TRADINGVIEW` (dated `ZQ`) | `FED_IMPLIED_PATH_12M`, `FED_EXPECTED_RATE_CHANGE` |
| `BOND_VOL_MOVE` | index | `TRADINGVIEW` (`TVC:MOVE`) | `BOND_VOLATILITY` |
| `VIX_9D`, `VIX_3M` | index | `CBOE` | `VIX_TERM_SLOPE` |
| `DE10Y`, `JP10Y`, `GB10Y`, `CN10Y` | % | `TRADINGVIEW` | context rows |
| `BTC_SPOT` | USD | `TRADINGVIEW` | context row |
| `GOLD_RISK_REVERSAL_25D` | vol pts | `CBOE` option chain | `GOLD_RISK_REVERSAL` |
| `GOLD_PUT_CALL_OI` | ratio | `CBOE` option chain | `GOLD_PUT_CALL_OI` |
| `GOLD_PUT_CALL_VOLUME` | ratio | `CBOE` option chain | provenance |
| `GOLD_IV_TERM_SLOPE` | vol pts | `CBOE` option chain | provenance |
| `GOLD_OPTION_OPEN_INTEREST` | contracts | `CBOE` option chain | provenance |

`FED_NET_LIQUIDITY` = `(WALCL − WTREGEN) / 1000 − RRPONTSYD`, in billions, with
the two daily series read **as of** the weekly balance-sheet date.

`GOLD_RISK_REVERSAL_25D` is refused when no listed contract sits within 0.08 of
25 delta — it is never interpolated from a distant wing.


## Inference-layer outputs (SPEC v2.1 §26)

Derived in the engine from values already in the report. None is ingested, none
is a source, and each is absent rather than defaulted when its sample is short.

| Field | Unit | Produced by | Consumed by |
|---|---|---|---|
| `inference.calibratedOn` | factor ids | `WalkForwardCalibration` | published basis of the mapping |
| `inference.sampleSize` | sessions | anchored walk-forward replay | published with the record |
| `horizons[].calibration.brier` | score | `BrierDecomposition` | `calibrationQuality`, kill-switch `brierDriftRatio` |
| `…reliability` / `…resolution` / `…uncertainty` | score | Murphy 1973 decomposition | published; `BS = REL − RES + UNC` |
| `…skill` | ratio | `1 − BS/UNC` | gate on publishing a probability |
| `…baseRate` | ratio | realised up-rate of the scored fold | published |
| `horizons[].rawProbability` | probability | logistic of `goldBias` | published beside `probability` |
| `horizons[].probability` | probability | isotonic map of `goldBias` | the published figure |
| `horizons[].conformal.halfWidthSigma` | σ | split conformal quantile | `expectedMove.p5` / `.p95` |
| `…targetCoverage` / `…realisedCoverage` | ratio | nominal vs **measured** on held-out sessions | published together |
| `expectedMove.intervalSource` | enum | `GAUSSIAN` \| `CONFORMAL` | states whether the band is assumed or measured |
| `inference.effectiveNumberOfBets` | count | `EffectiveBreadth` (Meucci 2009) | shrinks `ModelAgreement` |
| `inference.breadthRatio` | ratio | `ENB / n` | the shrinkage factor itself |
| `inference.runLength.map` | sessions | `ChangepointDetector` (Adams & MacKay 2007) | `RegimeStability` |
| `…changeProbability` | probability | posterior `P(rₜ=0)` | `Transition` penalty at ≥ 0.20 |
| `…youngRegimeProbability` | probability | mass below 15 sessions | `RegimeStability` |
| `inference.trend.varianceRatio` / `.zStatistic` | ratio / z | Lo & MacKinlay 1988, heteroskedasticity-robust | `momentumCredibility` |
| `inference.trend.hurst` | exponent | rescaled range | published beside VR |
| `inference.trend.momentumCredibility` | ratio | 1.0 trending / 0.5 random walk / 0.25 reverting | scales `expectedMove.point` |
| `inference.filteredComposite.level` | ±100 | local-level Kalman filter | published beside the raw composite |
| `inference.robustness.fragility` | ±100 | weighted composite vs trimmed mean of its legs | `FRAGILE` flag at ≥ 20 |

The replay panel reproduces **0.440** of the normative factor weight. The share
is published with every result; it is never presented as a calibration of the
full table.


## Measured series added with SPEC v2.1 §27

Every row is the published series itself. Source in parentheses; all keyless.

| Universe key | Unit | Source | Feeds |
|---|---|---|---|
| `REAL5Y_DEEP`, `REAL30Y_DEEP` | % | `FRED` (`DFII5`, `DFII30`) | `Real_Yield_5Y`, `Real_Curve_Slope` |
| `BREAKEVEN5Y_DEEP` | % | `FRED` (`T5YIE`) | `Breakeven_5Y`, `Breakeven_Slope` |
| `US10Y_DEEP`, `US02Y_DEEP` | % | `FRED` (`DGS10`, `DGS2`) | §26 replay legs F22, F03 |
| `CURVE_10Y2Y_DEEP`, `CURVE_10Y3M_DEEP` | pp | `FRED` (`T10Y2Y`, `T10Y3M`) | replay leg F04, `Yield_Curve_10Y_3M` |
| `VIX_DEEP` | index | `FRED` (`VIXCLS`) | replay leg F08 |
| `NASDAQ_VOL_VXN` | index | `FRED` (`VXNCLS`) | `Vol_Dispersion_VXN_VIX` |
| `OIL_VOL_OVX` | index | `FRED` (`OVXCLS`) | `Oil_Volatility` |
| `WTI_SPOT`, `BRENT_SPOT`, `NATGAS_SPOT` | USD | `FRED` (`DCOILWTICO`, `DCOILBRENTEU`, `DHHNGSP`) | replay leg F21, `Brent_WTI_Spread` |
| `IG_OAS`, `CCC_OAS`, `EM_OAS` | pp | `FRED` (`BAMLC0A0CM`, `BAMLH0A3HYC`, `BAMLEMCBPIOAS`) | `Credit_Spread_IG` / `_CCC` / `_EM` |
| `DOLLAR_AFE_INDEX` | index | `FRED` (`DTWEXAFEGS`) | `Dollar_AFE` |
| `EURUSD`, `USDJPY`, `USDCNY`, `GBPUSD` | FX | `FRED` (`DEXUSEU`, `DEXJPUS`, `DEXCHUS`, `DEXUSUK`) | context, F19 |
| `RESERVE_BALANCES` | USD mn | `FRED` (`WRESBAL`) | `Reserve_Balances` |
| `FED_FUNDS_EFFECTIVE` | % | `FRED` (`DFF`) | provenance |
| `INFECTIOUS_DISEASE_EMV` | index | `FRED` (`INFECTDISEMVTRACKD`) | `Infectious_Disease_EMV` |
| `REAL_CURVE_5Y` … `REAL_CURVE_30Y` | % | `TREASURY` real curve CSV | `Real_Yield_5Y` fallback, `Real_Curve_Slope` |
| `COT_MANAGED_MONEY_NET`, `COT_COMMERCIAL_NET` | contracts | `CFTC` disaggregated (`72hh-3qpy`, code `088691`) | `COT_ManagedMoney_Z`, `COT_Commercial_Z` |
| `SOMA_TOTAL` | USD bn | `NY_FED` (`/api/soma/summary.json`) | `SOMA_Change` |
| `SOFR_P99_SPREAD` | bp | `NY_FED` (`/api/rates/secured/sofr/...`) | `Repo_Tail_Spread` |
| `OECD_CLI_US` | index | `OECD` SDMX | `OECD_Leading_Indicator`, `_Change` |

## Inference-layer outputs added with §27

| Field | Produced by | Consumed by |
|---|---|---|
| `inference.measuredOn` | legs replayed on the published series rather than a stand-in | published with the basis row |
| `inference.coveredWeight` / `.measuredWeight` | normative weight the panel reproduces, and the measured share of it | published every cycle |
| `inference.combination[h].members[]` | each forecaster's out-of-sample Brier, skill, weight and whether it was admitted | the published probability |
| `inference.liveRidgeProbability` | the panel model's reading of today | pooled with the isotonic reading |
| `inference.conditionalBands[h][]` | Mondrian buckets, each with its own half-width and **realised** coverage | `expectedMove.p5` / `.p95` |
| `inference.legInformation[h][]` | per-leg rank correlation, hit rate, `t`, significance | the diagnostics surface |

The replay panel reproduces **0.740** of the normative factor weight, all of it from measured
series on the live stack. The share is published with every result and is never presented as a
calibration of the full table.
