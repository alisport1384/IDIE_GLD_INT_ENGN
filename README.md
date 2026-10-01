# Gold Intelligence Engine

Multi-horizon gold regime and direction engine, implemented against
[`docs/SPEC_GOLD_INTELLIGENCE_V2.md`](docs/SPEC_GOLD_INTELLIGENCE_V2.md).

The application runs on **live, free, keyless public data**. There is no sample
mode in the shipped build, no synthetic series, and no zero-filling: when an
input does not exist, the feature is omitted, the factor that depends on it is
dropped, and the omission is reported on the Diagnostics screen.

---

## Modules

| Module | Type | Contents |
|---|---|---|
| `engine` | Kotlin/JVM library | Platform-independent analytical core: factor table, horizon gating, quality model, dominance, time decay, cross-market confirmation, confidence, expected move, kill switch, causal layer, regime detection, scenarios, invalidation. No I/O. |
| `ingestion` | Kotlin/JVM library | 19-field data contract, quality gates G01–G11, market universe, feature engineering, factor scoring, indicator catalogue. Depends on `engine`. |
| `client` | Kotlin/JVM library | HTTP/JSON layer with no third-party dependencies, provider clients, free-data aggregator, screen model and its codec. Depends on `ingestion`. |
| `server` | Kotlin/JVM application | REST + SSE service over the same screen model. Depends on `client`. |
| `app` | Android application | Android host. Renders the screen model; contains no analytical logic. Depends on `client`. |

Dependency direction is strictly one-way:
`app → client → ingestion → engine`, and `server → client`.

---

## Data sources

All sources below are reachable without an API key, an account, or a paid
subscription. See [`NOTICE`](NOTICE) for licence and attribution terms.

| Need | Provider | Endpoint |
|---|---|---|
| Spot gold / silver | gold-api.com | `api.gold-api.com/price/XAU`, `/XAG` |
| Delayed quotes (GLD, SLV, TIP, HYG, LQD, UUP, USO, SPY, TLT, GDX, VIX, GVZ, SKEW, OVX) | Cboe Global Markets | `cdn.cboe.com/api/global/delayed_quotes/quotes/{SYM}.json` |
| Daily history | Cboe Global Markets | `cdn.cboe.com/api/global/delayed_quotes/charts/historical/{SYM}.json` |
| Nominal and real Treasury curve | U.S. Treasury | `home.treasury.gov/resource-center/data-chart-center/interest-rates/daily-treasury-rates.csv/{year}/all` |
| Breakeven inflation | derived | nominal − real, same publication |
| Positioning | CFTC | `publicreporting.cftc.gov/resource/6dca-aqww.json` (contract `088691`) |
| FX / synthetic DXY | ECB via Frankfurter | `api.frankfurter.app` |
| Funding (SOFR, EFFR, history) | Federal Reserve Bank of New York | `markets.newyorkfed.org/api/rates/...` |

Yahoo Finance is implemented as an optional provider for the COMEX futures
curve. It is the only licence-restricted source in the stack: values obtained
from it are used for derived quantities only and are never rendered raw or
returned raw by the API. The provider is wrapped in a circuit breaker and the
application degrades cleanly when it is unavailable.

---

## Build

Requirements: JDK 17 or newer. The Android build additionally requires an
Android SDK with API 36 and `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) set.
Gradle itself is provided by the wrapper and needs no local installation.

```bash
# unit tests (engine + ingestion + client)
./gradlew test

# installable debug APK  -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebug

# signed release APK     -> app/build/outputs/apk/release/app-release.apk
./gradlew :app:assembleRelease

# REST/SSE server on port 8080
./gradlew :server:run --args="8080"
```

### Signing

`keystore/gold-intelligence.keystore` is a **development** key, committed
deliberately so that a clean clone produces an installable release build.
Store password, key password and alias are all `goldintelligence`.

**Replace it before any public distribution.** Override it without touching the
build file:

```bash
./gradlew :app:assembleRelease \
  -PGI_KEYSTORE=/path/to/your.keystore \
  -PGI_KEYSTORE_PASSWORD=... \
  -PGI_KEY_ALIAS=... \
  -PGI_KEY_PASSWORD=...
```

If no keystore is present, the release build still succeeds and produces an
unsigned APK.

---

## Server API

| Method | Path | Returns |
|---|---|---|
| `GET` | `/v1/health` | Provider health, aggregate data quality, active factor count |
| `GET` | `/v1/report` | Full state: spot, direction, regime, factors, horizons, features |
| `GET` | `/v1/screens` | The six-screen UI model consumed by the Android app |
| `GET` | `/v1/factors` | Factor scores with weights and availability diagnostics |
| `GET` | `/v1/indicators` | Indicator catalogue with current values and provenance |
| `GET` | `/v1/stream` | Server-sent events, one message per refresh |

The data set is refreshed on a fixed interval and every response carries the
timestamp of the snapshot it was produced from.

---

## Application screens

1. **State** — spot, direction, regime, stability, signal state, cross-market confirmation, data quality.
2. **Factors** — all 22 factors with score, prior weight, effective weight and, where a factor is unavailable, the reason.
3. **Indicators** — every catalogued indicator with value, unit, tier, quality, staleness and source.
4. **Horizons** — 5m, 15m, 1H, 4H, 1D, 1W with direction, probability status, confidence, coverage and kill-switch state.
5. **Events & News** — scheduled events and news records when a source is configured; otherwise an explicit statement that no free source exists.
6. **Diagnostics** — provider health, quality tally, information dominance and the list of known gaps.

The Android app can run in two modes. In **direct** mode it performs ingestion
on the device using the `client` module. In **remote** mode it consumes
`/v1/screens` from the server. Both modes render the identical screen model.

---

## Deliberate limitations

These are properties of the free data layer, not defects, and each one is
surfaced in the application rather than hidden:

- **No calibrated probability until a live scoring record exists.** The engine reports `UNCALIBRATED_NO_SAMPLE` and publishes direction and confidence only. A probability is never invented from a heuristic.
- **5m and 15m are directional only.** Factor coverage at those horizons is 0.35 and 0.36; a probability would not be meaningful.
- **F10 (central bank demand)** and **F12 (physical demand)** have no free source and are permanently reported as unavailable. They are not approximated.
- **Open interest is weekly**, from the CFTC Commitments of Traders report. No free daily series exists.
- **Order book depth is top-of-book only**, from the displayed quote. Full depth requires a paid L2 feed.
- **LBMA benchmark prices, Bloomberg consensus, and SGE/MCX physical premiums** are not reachable without a paid licence and are listed as known gaps.
- **Historical analogue matching is not implemented** because the historical dataset it requires does not exist in this stack.

---

## Licence and disclaimer

See [`NOTICE`](NOTICE) for per-source attribution. This software produces a
statistical description of market conditions. It is not investment advice.
