# Gold Intelligence Engine

Android sample project for the Gold Intelligence & Regime Detection Engine specification.

## Modules

- `engine`: platform-independent analytical core.
- `app`: Android host and inspection UI.

The core follows the specification pipeline:

`DATA → FEATURES → FACTORS → CAUSALITY → EVENTS/NEWS → MICROSTRUCTURE → REGIME → DOMINANCE → DYNAMIC WEIGHTS → INTERACTIONS → DIVERGENCE → HISTORICAL ANALOGUES → ENSEMBLE → PROBABILITY → CALIBRATION → CONFIDENCE → SCENARIOS → INVALIDATION → GOLD INTELLIGENCE STATE`

The implementation deliberately does not fabricate missing formulas, live data sources, or historical datasets that are not defined by the supplied specification. The engine therefore exposes strict contracts for those components and reports `INSUFFICIENT_INFORMATION` when the required evidence is unavailable.

### Implemented analytical layers

Given `FactorScore` inputs (from a `FactorEngine`/data-ingestion layer, deployed later on the server), the engine now executes, deterministically:

- **Regime Engine** (`RegimeDetector.kt`) — threshold-based classification into the specification's regime set.
- **Shock Engine** (`ShockEngine.kt`) — the specification's explicit multi-sigma shock rule (DXY 5σ / US10Y 4σ / Gold 5σ / VIX spike).
- **Dynamic Weighting** (`CoreEngines.kt`) — `Base Weight × Regime Weight × Dominance × Quality × Time Decay`, normalized.
- **Interaction Engine** (`InteractionEngine.kt`) — monetary/inflation/trend clusters from the specification.
- **Contradiction Engine** (`ContradictionEngine.kt`) — conflict level instead of a blind average.
- **Divergence Engine** (`DivergenceEngine.kt`) — Macro / Flow / Positioning divergence detection.
- **Attribution Engine** (`AttributionEngine.kt`) — per-factor contribution to the final Gold Bias.
- **Scenario Engine** (`ScenarioEngine.kt`) — Base / Bull / Bear / Tail outlook.
- **Invalidation Engine** (`InvalidationRuleEngine.kt`) — explicit thesis-invalidation condition.
- **Event / News Intelligence** (`EventNews.kt`) — surprise scoring and severity/novelty/credibility → impact scoring.

All numeric thresholds and cluster weights used by these engines live in
`Specification.kt` (`CalibrationDefaults`, `RegimeAdjustments`,
`ShockThresholds`) and are explicitly labeled as provisional Initial Priors,
per the specification's own directive that base parameters require later
Walk-Forward recalibration against live data.

A `HeuristicProbabilityModel` is wired as the default `ProbabilityModel` so Probability/Confidence are not permanently absent. It is an explicitly non-calibrated, transparent placeholder (equal base-weight average of the supplied factors through a logistic transform) — it is **not** the specification's calibrated "P(Gold Up | Current State)" (Probability Calibration layer, spec items 16–17) and must be replaced by a properly trained/back-tested model before any output is used for a real decision. `NoProbabilityModel` remains available for callers that want a hard `null` instead.

`RuleBasedLiquidityEngine` assesses Liquidity from Dollar Funding Stress (spec item 7); it reports `UNKNOWN` when that feature is absent rather than guessing.

The `HistoricalAnalogueEngine` contract remains unimplemented (`NoHistoricalAnalogueEngine`) because it requires a historical dataset that does not yet exist; supplying one is a later stage of this project.

See `DATA_DICTIONARY.md` for the full Master List → Factor → Feature Key mapping.

## Build

GitHub Actions builds the debug APK with JDK 21.

`./gradlew assembleDebug`
