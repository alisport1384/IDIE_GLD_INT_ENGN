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
| Dollar index, Treasury yields, VIX, COMEX futures, **daily open interest**, dated forward curve | TradingView public scanner | `scanner.tradingview.com/global/scan` |
| **L2 order book depth**, spread series, daily gold bars | Kraken (PAXG/USD) | `api.kraken.com/0/public/Depth`, `/Spread`, `/OHLC` |
| **L2 order book depth**, daily bars | OKX (XAUT/USDT) | `okx.com/api/v5/market/books`, `/history-candles` |
| **OTC quotes by size tier** | Swissquote | `forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/XAU/USD` |
| **LBMA benchmark series** | World Gold Council | `fsapi.gold.org/api/goldprice/v11/chart/price/USD/max/false` |
| **Economic calendar with consensus** | ForexFactory / FairEconomy | `nfs.faireconomy.media/ff_calendar_thisweek.json` |
| CPI, unemployment (actual prints) | U.S. Bureau of Labor Statistics | `api.bls.gov/publicAPI/v1/timeseries/data/{series}` |
| **Shanghai physical premium** | SGE Au(T+D) via Sina / Eastmoney | `hq.sinajs.cn/list=gds_AUTD` |
| **India physical premium** | MCX gold front month via scanner | `scanner.tradingview.com/global/scan` |

Yahoo Finance is implemented as an optional provider for the dated COMEX
futures curve. It is wrapped in a circuit breaker and the application degrades
cleanly when it is unavailable; the TradingView scanner supplies the same
curve independently. No source in the stack is publication-restricted: every
value is rendered with its provenance, licence class and quality tier
attached.

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
| `GET` | `/v1/screens` | The seven-screen UI model consumed by the Android app |
| `GET` | `/v1/factors` | Factor scores with weights and availability diagnostics |
| `GET` | `/v1/indicators` | Indicator catalogue with current values and provenance |
| `GET` | `/v1/stream` | Server-sent events, one message per refresh |
| `GET` | `/v1/logs` | Logger screen model: summary, per-stage tally, failures, per-indicator status, trace |
| `GET` | `/v1/logs.json` | Structured log; `?level=`, `?stage=`, `?key=`, `?q=`, `?limit=` narrow the result |
| `GET` | `/v1/logs.md` | The complete log as a Markdown file download |
| `GET` | `/v1/logs.txt` | The complete log as a plain-text file download |

The data set is refreshed on a fixed interval and every response carries the
timestamp of the snapshot it was produced from.

---

## Application screens

1. **State** — spot, direction, regime, stability, signal state, cross-market confirmation, data quality.
2. **Factors** — all 22 factors with score, prior weight, effective weight and, where a factor is unavailable, the reason.
3. **Indicators** — every catalogued indicator with value, unit, tier, quality, staleness and source.
4. **Horizons** — 5m, 15m, 1H, 4H, 1D, 1W with direction, probability status, confidence, coverage and kill-switch state.
5. **Events & News** — scheduled events and news records when a source is configured; otherwise an explicit statement that no free source exists.
6. **Diagnostics** — provider health, quality tally, information dominance, market depth, the COMEX forward curve, and the resolution state of every former data gap with the live source that closed it.
7. **Logger** — a separate tab, fed only by the diagnostic log: error and warning tally, per-stage counts, the failure list, a per-indicator status line naming which indicator failed and why, and the full trace. It can be filtered by level and by text, copied to the clipboard, and saved as `.md` or `.txt` through the system file picker. No market value is rendered on this screen and no log line is rendered on the other six.

The Android app can run in two modes. In **direct** mode it performs ingestion
on the device using the `client` module. In **remote** mode it consumes
`/v1/screens` from the server. Both modes render the identical screen model.

---

## Deliberate limitations

These are properties of the free data layer, not defects, and each one is
surfaced in the application rather than hidden:

- **No calibrated probability until a live scoring record exists.** The engine reports `UNCALIBRATED_NO_SAMPLE` and publishes direction and confidence only. A probability is never invented from a heuristic.
- **5m and 15m are directional only.** Factor coverage at those horizons is 0.35 and 0.36; a probability would not be meaningful.
- **F10 (central bank demand)** has no free source — the IMF IFS and WGC reserve datasets are not reachable without a key — and is reported as unavailable rather than approximated. It is the only factor in that state.
- **Open interest is daily** (TradingView scanner, `open_interest` column) and is cross-checked against the weekly CFTC Commitments of Traders report.
- **Order book depth is real L2**, aggregated from two independent gold-backed venues (Kraken PAXG/USD and OKX XAUT/USDT) plus Swissquote OTC quotes by size tier. It is a proxy for the COMEX book, is labelled as such on screen, and is not a CME L2 feed.
- **Consensus forecasts** come from the ForexFactory calendar, not from Bloomberg. The consensus value is published; the realised surprise is computed only when an actual print is independently available (BLS).
- **The LBMA benchmark series is monthly** (World Gold Council). The daily AM/PM fixes require an LBMA licence, so the deviation of spot from benchmark is reported at monthly resolution.
- **The India premium carries a structural duty and GST wedge** of roughly nine percentage points, which is subtracted before the premium is scored.
- **Historical analogue matching is not implemented** because the historical dataset it requires does not exist in this stack.
- Every one of the above is reported on the Diagnostics screen with its live source, and every ingestion step is traceable on the Logger screen.

---

## Attribution and disclaimer

See [`NOTICE`](NOTICE) for per-source attribution. This software produces a
statistical description of market conditions. It is not investment advice.
