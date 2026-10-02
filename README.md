# Gold Intelligence Engine

**IDIE_GLD_INT_ENGN** — a multi-horizon gold regime, direction and confidence engine with a
live XAU/USD chart, implemented end to end against
[`docs/SPEC_GOLD_INTELLIGENCE_V2.md`](docs/SPEC_GOLD_INTELLIGENCE_V2.md) (**SPEC_GOLD_INTELLIGENCE_V2.1**).

Version **1.0.0** · `versionCode 2` · Kotlin 2.2.21 · AGP 8.13.1 · Gradle 9.5.0 · JDK 17 · minSdk 29 / targetSdk 36
Build history and the reproduction procedure: [`docs/PRODUCTION_RECORD.md`](docs/PRODUCTION_RECORD.md).

---

## خلاصهٔ فارسی

این مخزن یک موتور تحلیل طلا است که روی **دادهٔ زندهٔ رایگان و بدون کلید** کار می‌کند. هیچ دادهٔ نمونه، هیچ سری ساختگی و هیچ پرکردن با صفر در آن نیست: اگر ورودی‌ای وجود نداشته باشد، ویژگی حذف، فاکتور وابسته غیرفعال، و علت آن در صفحهٔ تشخیص و در گزارش‌گیر اعلام می‌شود.

- **۲۲ فاکتور** با وزن پایه و وزن مؤثر، **۶ افق** (۵m تا ۱W)، **۶۳ اندیکاتور** کاتالوگ‌شده.
- **۸ صفحه**: وضعیت، فاکتورها، اندیکاتورها، افق‌ها، رویدادها، تشخیص، **چارت زنده**، **گزارش‌گیر**.
- **چارت زنده**: XAU/USD از OANDA (پیش‌فرض) یا FXCM یا FOREX.com، با تحلیل نهایی قفل‌شده به محور قیمت، خلاصهٔ تغییرات، تحلیل دوگانه و اکسپشن‌ها — به‌علاوهٔ حالت تمام‌صفحه.
- **قاعدهٔ سطح خطا در گزارش‌گیر:** `ERROR` فقط برای خطای مدیریت‌نشده است. خطایی که مسیر جایگزین دارد یا روی صفحه نمایش داده می‌شود، `WARN` ثبت می‌شود.
- خروجی‌ها: اپ اندروید، سرور REST/SSE، و کتابخانه‌های JVM.

---

## 1. Architecture

```
app (Android)  ─┐
                ├─→  client  ─→  ingestion  ─→  engine
server (JVM)   ─┘
```

Dependency direction is strictly one-way and enforced by the Gradle module graph:
`app → client → ingestion → engine` and `server → client`.
`engine` performs no I/O; `app` contains no analytical logic.

| Module | Type | Responsibility |
|---|---|---|
| `engine` | Kotlin/JVM library | Factor table, horizon gating, quality model, dominance, time decay, cross-market confirmation, confidence, expected move, kill switch, regime detection, scenarios, invalidation, diagnostic log. |
| `ingestion` | Kotlin/JVM library | 19-field data contract, quality gates G01–G11, market universe, feature engineering, factor scoring, 63-entry indicator catalogue. |
| `client` | Kotlin/JVM library | Dependency-free HTTP/JSON layer, 17 provider clients, free-data aggregator, chart feed and bar store, screen model + codec. |
| `server` | Kotlin/JVM application | REST + SSE over the identical screen model, 2-minute refresh, `0.0.0.0:8080`. |
| `app` | Android application | Eight-tab renderer plus the chart canvas. No analytics, no provider calls of its own. |

### Source tree

```
engine/src/main/kotlin/io/goldintelligence/engine/
  Specification.kt      factor catalogue, base weights, spec constants
  Horizon.kt            Horizon, HorizonMode, FactorHorizons.minHorizon
  Model.kt              Direction, Regime, Stability, FactorScore, DynamicWeight, reports
  SpecEngines.kt        quality, decay, dominance, cross-market confirmation, confidence
  MultiHorizonEngine.kt per-horizon evaluation, kill switch, scenarios, SPEC_VERSION
  CoreEngines.kt        expected move, conflict, signal state, uncertainty
  FeatureKeys.kt        canonical feature identifiers
  DiagnosticLog.kt      ring-buffer logger: levels, stages, filters, exports

ingestion/src/main/kotlin/io/goldintelligence/ingestion/
  DataContract.kt       19-field observation contract
  QualityGates.kt       G01–G11, tier factors, staleness policy
  SeriesModel.kt        TimeSeries, resampling, returns, volatility
  MarketUniverse.kt     canonical series/scalar keys
  SpecFeatureEngineer.kt  features from the universe
  SpecFactorEngine.kt   F01–F22 scoring + FactorDiagnostic
  IndicatorCatalog.kt   63 indicators with units and sources
  Sources.kt            source descriptors

client/src/main/kotlin/io/goldintelligence/client/
  Json.kt               minimal JSON reader/writer, no third-party dependency
  Http.kt               timeouts, retries, throttle, per-provider circuit breaker, log hooks
  Providers.kt          17 provider specs + per-provider clients, optionalIds
  FreeDataAggregator.kt one refresh across every provider, caches and TTLs
  ChartModels.kt        Candle, ChartSeries, PriceLevel, AnalysisDelta, DualAnalysis, ChartPayload
  ChartFeed.kt          BrokerFeedProvider (quote + forming bar), Kraken seed loader
  LiveChartStore.kt     per-timeframe ring buffer, bar sealing, one-time seed rebase
  ChartOverlayBuilder.kt  levels, deltas, dual readings, exceptions, Memo
  ScreenModel.kt        Row/Badge/Section/Screen/ScreenModel + codec
  ScreenModelBuilder.kt the eight screens, every display rule
  GoldIntelligenceClient.kt  DIRECT and REMOTE modes, AnalysisResult

server/src/main/kotlin/io/goldintelligence/server/Main.kt
app/src/main/java/io/goldintelligence/app/
  MainActivity.kt             eight tabs, auto-refresh, logger UI, chart tab
  ChartView.kt                price canvas: candles, grid, level gutter, pan
  ChartFullscreenActivity.kt  landscape immersive chart + ChartHandoff
```

---

## 2. Analytical model (SPEC v2.1)

**Factor weights (Σ = 1.000)**

| F01 | F02 | F03 | F04 | F05 | F06 | F07 | F08 | F09 | F10 | F11 |
|---|---|---|---|---|---|---|---|---|---|---|
| .200 | .150 | .100 | .040 | .060 | .050 | .060 | .030 | .060 | .040 | .060 |

| F12 | F13 | F14 | F15 | F16 | F17 | F18 | F19 | F20 | F21 | F22 |
|---|---|---|---|---|---|---|---|---|---|---|
| .008 | .020 | .010 | .010 | .010 | .020 | .010 | .006 | .006 | .020 | .030 |

**Horizon gating** — a factor contributes only at or above its `min_horizon`:
5m → F02, F03, F04, F13, F14, F16, F21 · 15m → F15 · 1H → F07 · 1D → F01, F05, F06, F08, F09, F17, F18, F22 · 1W → F10, F11, F12, F19, F20.
Coverage per horizon: 0.35 / 0.36 / 0.42 / 0.42 / 0.88 / 1.00. 5m and 15m are `DIRECTIONAL_ONLY`.

**Core formulas**

```
Confidence = clamp01( .30·DQ + .20·CMC + .20·Agreement + .15·RegimeStability
                    + .15·Calibration − .25·Conflict − .20·Transition ) × Coverage
CMC        = agreement across 8 cross-market witnesses
ExpectedMove = k(h) · σ · (2P − 1),  k = .80 .85 .90 .95 1.00 1.05
Decay        τ = 48 / ln2 ≈ 69.3 h
Dominance    = rolling R², clamped to [0.75, 1.50]
KillSwitch   engages on ≥2 of { DQ<.60, regime TRANSITION/UNKNOWN, conflict>.40,
                                Brier drift>1.25, coverage<.40 }
```

**Quality model** — 19-field data contract; gates G01–G11; tier factor A 1.00 / B 0.90 / C 0.75 / D 0.55 / PROXY 0.60; proxy ceiling 0.60; staleness floor 0.25 with grace 1.5× and expiry 3.0×; `DEGRADED` halves the score; aggregate = `.5·mean + .5·min`; **no zero-fill**.

---

## 3. Data sources

Every source is reachable without an API key, an account or a paid plan. Licence and attribution terms: [`NOTICE`](NOTICE).

| Need | Provider | Endpoint |
|---|---|---|
| Spot gold / silver | gold-api.com | `api.gold-api.com/price/XAU`, `/XAG` |
| Live chart quote + forming bar (XAU/USD) | TradingView public screener | `scanner.tradingview.com/global/scan`, interval columns `open\|60`, `high\|60`, … |
| Chart seed history | Kraken | `api.kraken.com/0/public/OHLC?pair=PAXGUSD&interval=…` |
| Delayed quotes (GLD, SLV, TIP, HYG, LQD, UUP, USO, SPY, TLT, GDX, VIX, GVZ, SKEW, OVX) | Cboe | `cdn.cboe.com/api/global/delayed_quotes/quotes/{SYM}.json` |
| Daily history | Cboe | `cdn.cboe.com/api/global/delayed_quotes/charts/historical/{SYM}.json` |
| Nominal + real Treasury curve | U.S. Treasury | `home.treasury.gov/.../daily-treasury-rates.csv/{year}/all` |
| Positioning | CFTC | `publicreporting.cftc.gov/resource/6dca-aqww.json` (contract `088691`) |
| FX | ECB via Frankfurter | `api.frankfurter.app` |
| Funding (SOFR, EFFR) | New York Fed | `markets.newyorkfed.org/api/rates/...` |
| DXY, UST yields, VIX, COMEX futures, daily OI, dated curve, MCX | TradingView scanner | `scanner.tradingview.com/global/scan` |
| L2 depth, spread, daily bars | Kraken PAXG/USD | `/0/public/Depth`, `/Spread`, `/OHLC` |
| L2 depth, daily bars | OKX XAUT/USDT | `okx.com/api/v5/market/books`, `/history-candles` |
| OTC quotes by size tier | Swissquote | `forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/XAU/USD` |
| LBMA benchmark series | World Gold Council | `fsapi.gold.org/api/goldprice/v11/chart/price/USD/max/false` |
| Economic calendar with consensus | ForexFactory / FairEconomy | `nfs.faireconomy.media/ff_calendar_thisweek.json` |
| CPI, unemployment prints | U.S. BLS | `api.bls.gov/publicAPI/v1/timeseries/data/{series}` |
| Shanghai premium | SGE Au(T+D) via Sina, Eastmoney backup | `hq.sinajs.cn/list=gds_AUTD` |

**Optional providers** (`Providers.optionalIds`): `YAHOO`, `EASTMONEY_SGE`. Their refusal is absorbed by a declared fallback and is therefore logged at `WARN`, never `ERROR`.

Each provider has its **own** id because the circuit breaker is keyed per id: a backup sharing the primary's id would be skipped while the primary's breaker is open.

---

## 4. Live chart (§22)

| Element | Behaviour |
|---|---|
| Venue | `OANDA:XAUUSD` default, `FX:XAUUSD` (FXCM), `FOREXCOM:XAUUSD`; switching is logged `VENUE_SWITCHED` |
| Timeframe | 5m · 15m · 1H · 4H · 1D; switching is logged `TIMEFRAME_SWITCHED` |
| Bars | The screener prints the **forming** bar per timeframe; `LiveChartStore` seals it into a ring buffer of 240 bars per timeframe |
| Seeding | First launch seeds from Kraken PAXG/USD and multiplies by `brokerLast / seedLast`; seeded bars are drawn **hollow**, counted separately and the factor is published |
| Bar clock | Bars are bucketed by **ingest time**; the screener's `time` column is a session stamp and is never used as the bar clock |
| Bid/ask | Dropped and logged `BIDASK_STALE` when the mid is more than 25 bp from the last price (the screener caches them independently) |
| Levels | `SPOT`, `BID`, `ASK`, `EXPECTED_MOVE`, `BAND_HIGH`/`BAND_LOW` (P95/P5 as a shaded corridor), `BENCHMARK` (LBMA) |
| Readability | The canvas carries price only; every narrative row is a native view outside the plot. Level tags live in a right-hand gutter and are pushed apart on collision. Default window 70 bars, adjustable 30–240, drag to pan |
| Full screen | Tap the chart or press ⛶ — landscape, immersive, bar-count control, optional analysis card (off by default), logged `FULLSCREEN_OPENED` / `FULLSCREEN_CLOSED` |
| Change summary | Nine fields compared with the previous refresh: direction, bias, confidence, regime, signal state, data quality, active factor count, price, kill switch — each change logged `STATE_CHANGED` |
| Dual analysis | `HORIZON_SPLIT` when 5m–1H opposes 4H–1W; `FACTOR_CONFLICT` when both camps hold \|score\| ≥ 25 and the minority carries ≥ 30 % of active weight. Both branches are published in full — never averaged away. Logged `DUAL_READING` |
| Exceptions | `FEED_UNAVAILABLE`, `SYMBOL_NOT_QUOTED`, `PRICE_ABSENT`, `QUOTE_ABSENT`, `SERIES_EMPTY`, `SERIES_SEED_ONLY`, `ANALYSIS_ABSENT`, `KILL_SWITCH`, `LOW_DATA_QUALITY`, plus the last three pipeline errors |

---

## 5. Logger (§21)

A separate tab fed only by `DiagnosticLog`, so it still works when the pipeline has failed.

- Levels `TRACE · DEBUG · INFO · WARN · ERROR`; stages `STARTUP · NETWORK · PARSE · QUALITY · FEATURE · FACTOR · REGIME · HORIZON · RENDER · EXPORT · CHART`.
- Per-level and per-stage tallies, failure list, per-indicator status, dropped-record counter, text filter, clipboard copy, `.md` / `.txt` export through the system picker.
- **Severity contract:** `ERROR` means a failure nobody handled. A refusal with a declared fallback, a displayed chart exception, or an engaged kill switch is `WARN`. Mirrored entries are recorded once at `DEBUG` as `EXCEPTION_MIRRORED` so nothing is double-counted.
- No market value is rendered on this screen, and no log line is rendered on the other seven.

---

## 6. Server API

| Method | Path | Returns |
|---|---|---|
| `GET` | `/v1/health` | Provider health, aggregate data quality, active factor count |
| `GET` | `/v1/screens` | All eight screens |
| `GET` | `/v1/state` `/v1/factors` `/v1/indicators` `/v1/horizons` `/v1/events` `/v1/diagnostics` | Individual screens |
| `GET` | `/v1/chart` | Chart screen as rows |
| `GET` | `/v1/chart.json` | Candles, levels, deltas, dual readings and exceptions as drawn |
| `GET` | `/v1/logs` `/v1/logs.json?level=&stage=&key=&q=&limit=` `/v1/logs.md` `/v1/logs.txt` | Logger |
| `GET` | `/v1/stream` | Server-sent events, one message per refresh |

---

## 7. Build and run

Requirements: **JDK 17**; for the APK also an Android SDK with API 36 and `build-tools 35.0.0`, with `ANDROID_HOME` set. Gradle comes from the wrapper.

```bash
chmod +x gradlew
./gradlew test                  # 82 unit tests
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease  # app/build/outputs/apk/release/app-release.apk
./gradlew :server:installDist   # server/build/install/server/bin/server
```

On a memory-constrained machine always add `--no-daemon`.

**Signing** — `keystore/gold-intelligence.keystore` is a development key committed on purpose so a clean clone produces an installable release build; store password, key password and alias are all `goldintelligence`. Replace it before public distribution:

```bash
./gradlew :app:assembleRelease \
  -PGI_KEYSTORE=/path/to/your.keystore -PGI_KEYSTORE_PASSWORD=… \
  -PGI_KEY_ALIAS=… -PGI_KEY_PASSWORD=…
```

Without a keystore the release build still succeeds and produces an unsigned APK.

**CI** — `.github/workflows/android.yml`, job `verify`: JDK 17 temurin, `gradle/actions/setup-gradle@v4`, runs `test`, `:app:assembleDebug`, `:server:installDist`, uploads the APK and the test reports.

---

## 8. Project contract

Rules that hold for every change in this repository:

1. **Module direction is fixed.** `app → client → ingestion → engine`, `server → client`. The engine never performs I/O; the app never computes analytics.
2. **One display authority.** Every rendering decision (badges, gating, staleness, probability suppression, masking) is taken in `ScreenModelBuilder`, so the phone and the server cannot disagree.
3. **No fabricated data.** No sample mode, no synthetic series, no zero-fill, no interpolation across a gap. A missing input removes the feature and disables the factor, with the reason published.
4. **Provenance travels with the value.** Source text lives in `Row.noteEn` / `Row.noteFa`; there is no separate source field.
5. **Each provider owns its id**, because the circuit breaker is keyed per id.
6. **`ERROR` is reserved for unhandled failures.** See the severity contract in §5.
7. **No third-party runtime dependency** in `client`, `ingestion` or `engine`: JSON and HTTP are implemented in-tree.
8. **Kotlin JVM toolchain 17** everywhere; no `java { sourceCompatibility }` block alongside `jvmToolchain`.
9. **Tests accompany behaviour.** 82 tests across engine, ingestion and client; a new rule ships with the test that pins it.

---

## 9. Declared limitations

Properties of the free data layer, surfaced in the product rather than hidden:

- **No calibrated probability until a live scoring record exists** — the engine publishes `UNCALIBRATED_NO_SAMPLE`, direction and confidence; a probability is never invented.
- **5m and 15m are directional only** (coverage 0.35 / 0.36).
- **F10 (central bank demand) has no free source** and stays unavailable — the only factor in that state. Its weight is redistributed.
- **Historical analogue matching is not implemented**; the dataset it needs does not exist in this stack.
- **Open interest is daily** (scanner `open_interest`), cross-checked against the weekly CFTC report.
- **Order-book depth is real L2 from gold-backed venues** (Kraken PAXG/USD, OKX XAUT/USDT) plus Swissquote OTC tiers — a labelled proxy for the COMEX book, not a CME feed.
- **Consensus comes from the ForexFactory calendar**; the surprise is computed only when an actual print is independently available (BLS).
- **The LBMA benchmark series is monthly** (WGC); the daily AM/PM fixes require a licence.
- **The India premium carries a ~9 pp duty and GST wedge**, subtracted before scoring.
- **Chart seed bars are rebased, not borrowed silently** (hollow, counted, factor published).
- **The venue's bid/ask is dropped when it disagrees with the last price** by more than 25 bp.
- **Some providers rate-limit this network** (Yahoo returns 429); the breaker holds them for 15 minutes and the declared fallback is used.

---

## 10. Attribution and disclaimer

Per-source attribution: [`NOTICE`](NOTICE). Data dictionary: [`DATA_DICTIONARY.md`](DATA_DICTIONARY.md). Source-of-spec notes: [`SPEC_SOURCE.md`](SPEC_SOURCE.md).

This software produces a statistical description of market conditions. **It is not investment advice.**
