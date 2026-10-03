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
- **گزارش‌گیر به‌صورت پیش‌فرض خاموش است** و تا وقتی کاربر آن را روشن نکند هیچ رکوردی نگه نمی‌دارد؛ روشن‌کردن آن ضبط را از همان لحظه آغاز می‌کند.
- **قاعدهٔ سطح خطا در گزارش‌گیر:** `ERROR` فقط برای خطای مدیریت‌نشده است. خطایی که مسیر جایگزین دارد یا روی صفحه نمایش داده می‌شود، `WARN` ثبت می‌شود.
- **منابع تازهٔ §۲۳:** ذخایر طلای ماهانهٔ کشورها از صندوق بین‌المللی پول (حل‌کنندهٔ فاکتور F10)، تقویم اقتصادی با رقم واقعی در کنار اجماع (ساعت تا رویداد بعدی و شاخص غافلگیری واقعی)، نرخ‌های سیاستی بانک‌های مرکزی از BIS، و دفتر سفارش سوم از کوین‌بیس.
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
| `ingestion` | Kotlin/JVM library | 19-field data contract, quality gates G01–G11, market universe, feature engineering, factor scoring, 74-entry indicator catalogue, historical-analogue matcher. |
| `client` | Kotlin/JVM library | Dependency-free HTTP/JSON/CSV layer, 18 provider clients, free-data aggregator, option-chain reader, chart feed and bar store, screen model + codec. |
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
  AdvancedInference.kt  §26 inference layer: isotonic, Brier/Murphy, split conformal,
                        effective number of bets + Jacobi, BOCPD, variance ratio +
                        Hurst, local-level Kalman, robust aggregate (pure, no I/O)
  FeatureKeys.kt        canonical feature identifiers
  DiagnosticLog.kt      ring-buffer logger: levels, stages, filters, exports

ingestion/src/main/kotlin/io/goldintelligence/ingestion/
  DataContract.kt       19-field observation contract
  QualityGates.kt       G01–G11, tier factors, staleness policy
  SeriesModel.kt        TimeSeries, resampling, returns, volatility
  MarketUniverse.kt     canonical series/scalar keys
  SpecFeatureEngineer.kt  features from the universe
  SpecFactorEngine.kt   F01–F22 scoring + FactorDiagnostic
  HistoricalAnalogues.kt  nearest-neighbour matcher over the loaded history
  WalkForwardCalibration.kt  §26 anchored replay of the loaded history → InferenceBundle
  IndicatorCatalog.kt   74 indicators with units and sources
  Sources.kt            source descriptors

client/src/main/kotlin/io/goldintelligence/client/
  Json.kt               minimal JSON reader/writer, no third-party dependency
  Http.kt               timeouts, retries, throttle, per-provider circuit breaker, log hooks
  Providers.kt          18 provider specs + per-provider clients, option chain, optionalIds
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

### 2.1 Inference layer (SPEC v2.1 §26)

§D2 recorded the weights as *initial priors, to be re-estimated by walk-forward calibration once a record exists*, and §19 withheld every numeric probability until one did. The record is built by replaying the 5,505 daily sessions the app already downloads. **No new source, no changed weight** — only the inference machinery.

| Component | Method | What it replaced |
|---|---|---|
| Walk-forward calibration (`ingestion/WalkForwardCalibration.kt`) | Anchored origin: refit on everything known at each origin, score the next 125 sessions forward, 1,250-session warm-up | `calibrationQuality = null` on every cycle |
| Score → probability map | Isotonic regression, pool-adjacent-violators | Unfitted logistic `1/(1+e^{−bias/25})` |
| Calibration score | Brier + Murphy decomposition `BS = REL − RES + UNC`, quantile bins, Brier skill | Constant `0.0` in the confidence formula |
| Expected-move band | **Split conformal** half-width at rank `⌈(n+1)(1−α)⌉`, in σ, with **measured** coverage published beside the nominal level | Assumed Gaussian `±1.645σ` |
| Model agreement | **Effective number of bets** `ENB = exp(−Σ pᵢ ln pᵢ)` on correlation eigenvalues; `Agreement′ = 0.5 + (Agreement−0.5)·ENB/n` | Raw majority share of weighted evidence |
| Regime stability / transition | **Bayesian online changepoint detection**, Normal-Inverse-Gamma → Student-t predictive, hazard `1/250`, top-200 pruning | Rule-based regime label |
| Momentum credibility | **Lo–MacKinlay variance ratio** (heteroskedasticity-robust) + rescaled-range Hurst; `abs(z) < 1.96` ⇒ point estimate ×0.5 | Momentum asserted at full size on any tape |
| Cleanliness | 1-D local-level **Kalman** filter on the replayed composite; symmetric trimmed mean vs the weighted composite ⇒ `FRAGILE` flag | — |

References: Brier 1950; Murphy 1973; Kalman 1960; Lo & MacKinlay 1988; Adams & MacKay 2007 (arXiv:0710.3742); Meucci 2009 *Managing Diversification*; Vovk, Gammerman & Shafer 2005; Lei et al. 2018.

### 2.2 Measured panel, second forecaster, conditional band (SPEC v2.1 §27)

§26 replayed **seven price stand-ins**. §27 replays **the published series themselves**.

| Factor | Weight | Series the replay now reads | Transform |
|---|---|---|---|
| F01 real rate | .200 | `DFII10` — 10-year TIPS yield | level difference |
| F02 dollar | .150 | `DTWEXBGS` — Fed broad dollar index | return |
| F03 Fed | .100 | `DGS2` — 2-year yield as the priced path | level difference |
| F04 curve | .040 | `T10Y2Y` | level difference |
| F05 inflation | .060 | `T10YIE` — 10-year breakeven | level difference |
| F07 geopolitical | .060 | `USEPUINDXD` — policy uncertainty | z against its own year |
| F08 stress | .030 | `VIXCLS` | return |
| F13 momentum | .020 | `GLD` | return |
| F15 options/vol | .010 | `GVZCLS` — gold implied volatility | return |
| F16 cross-asset | .010 | `SPY` | return |
| F18 credit | .010 | `HYG` | return |
| F21 oil | .020 | `DCOILWTICO` — WTI spot | return |
| F22 global policy | .030 | `DGS10` | level difference |

**0.740 of the normative weight, and on the live stack 0.740 of it measured — no stand-in was used.** Each leg keeps a declared fallback for the cycle where its publisher is unreachable, and the split is published every cycle.

Two corrections the switch required: a **level difference** rather than a percentage change for rates and spreads (the percentage change of a yield is meaningless), and an **as-of join** rather than positional alignment (the macro calendar is not the exchange calendar; each session takes the last observation published on or before its own date, capped at seven days, never a later one).

Three additions to the inference machinery:

| Addition | Method | What it replaced |
|---|---|---|
| Second forecaster | **Ridge logistic** on the standardised panel, IRLS with a Tikhonov penalty on the slopes (Hoerl & Kennard 1970) | one isotonic map of one number — which cannot see that two factors matter jointly |
| Published probability | **Log-odds combination** of the members that earned positive out-of-sample skill, weights proportional to that skill (Bates & Granger 1969; Timmermann 2006) | the isotonic member alone |
| Expected-move band | **Mondrian conformal** conditioned on the trailing-volatility tercile (Vovk et al. 2005 ch. 4), each bucket publishing its own realised coverage | one pooled band — right on average, too wide in a calm tape and too narrow in a violent one |
| Factor diagnostics | **Information coefficient** per leg: Spearman rank correlation with the forward return, with `t = IC·√(n−2)/√(1−IC²)` | nothing — the engine reported how much weight a factor carried, never how much information |

The ridge model **does not re-weight the published composite**. The §2 table is normative and untouched; the ridge is a separate forecaster whose probability is pooled, and only on positive measured skill.

**Measured on the live stack** — 13 legs, 3,250 sessions scored forward, 26 anchored refits:

| | 1D | 1W |
|---|---|---|
| Brier | 0.2509 | 0.2499 |
| Brier skill — isotonic member | −0.0062 | −0.0077 |
| Brier skill — ridge member | −0.0027 | −0.0123 |
| members admitted to the combination | 0 | 0 |
| conformal band — CALM / NORMAL / STRESSED | ±2.05σ / ±1.81σ / ±1.55σ | ±2.34σ / ±1.77σ / ±1.39σ |
| realised coverage, every bucket | 90.1 % | 90.1 % |
| legs with a significant information coefficient | **1 of 13** | **7 of 13** |

ENB 7.13 of 13 (54.8 %) · regime age 199 sessions · `VR(5)` 0.908 ⇒ `RANDOM_WALK`.

**The analytical finding.** At the weekly horizon **seven of thirteen legs carry a statistically significant information coefficient** — the strongest being policy uncertainty at `IC` +0.064, `t` 3.68 over 3,250 held-out sessions, then the two-year yield at `t` 3.53. The information is **real**; it is far too small to move a Brier score. Neither forecaster earns positive out-of-sample skill, so the probability stays withheld at `UNCALIBRATED_NO_SKILL` — but the engine now knows precisely which leg carries how much, and how wide its band should be in each volatility regime.

---

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
| Economic calendar with consensus **and released actual** | TradingView economic calendar | `economic-calendar.tradingview.com/events?from=&to=&countries=US,EU,CN,GB,JP` |
| Economic calendar (schedule only, fallback) | ForexFactory / FairEconomy | `nfs.faireconomy.media/ff_calendar_thisweek.json` |
| Official-sector gold holdings, monthly, per country | IMF — International Reserves (IRFCL) | `api.imf.org/external/sdmx/2.1/data/IRFCL/.IRFCLDT1_IRFCL56V_FTO..M?startPeriod=YYYY-MM` |
| Central-bank policy rates | Bank for International Settlements | `stats.bis.org/api/v1/data/WS_CBPOL/M.US+XM+GB+JP+CH+CA/all?lastNObservations=18` |
| Third L2 gold book | Coinbase Exchange PAXG/USD | `api.exchange.coinbase.com/products/PAXG-USD/book?level=2` |
| CPI, unemployment prints | U.S. BLS | `api.bls.gov/publicAPI/v1/timeseries/data/{series}` |
| Shanghai premium | SGE Au(T+D) via Sina, Eastmoney backup | `hq.sinajs.cn/list=gds_AUTD` |

**Optional providers** (`Providers.optionalIds`): `YAHOO`, `EASTMONEY_SGE`, `COINBASE`. Their refusal is absorbed by a declared fallback and is therefore logged at `WARN`, never `ERROR`.

### 3.1 Sources added in SPEC v2.1 §23, and what each one replaced

| Provider id | Series read | Replaces | Tier | Cadence | Request requirement |
|---|---|---|---|---|---|
| `IMF_RESERVES` | IRFCL line 56 — "gold (including gold deposits and, if appropriate, gold swapped)", **fine troy ounces**, per country, monthly | **F10 central-bank demand**, which previously reported `NO_FREE_SOURCE` and was dropped from every horizon | A | monthly, ≈3 weeks after month end | none (SDMX-ML; `Accept: application/xml`) |
| `CALENDAR` | `indicator, country, date, importance, actual, forecast, previous, period, unit, source` | the **hours-to-next-event** clock (previously N/A), the **economic surprise index** (previously an equity-minus-bond proxy) and the **inflation surprise** (previously a breakeven move) | B | continuous | `Origin: https://www.tradingview.com` — without it the endpoint answers 403 |
| `BIS` | `WS_CBPOL` policy rates for US, euro area, UK, Japan, Switzerland, Canada | the **global policy divergence** input of F22 (previously a dollar-trend proxy) and the **expected policy path** input of F03 (previously Δ2Y over 20 sessions) | A | monthly | `Accept: application/vnd.sdmx.data+json;version=1.0.0` — without it the endpoint answers 406 |
| `COINBASE` | PAXG/USD level-2 book, 100 levels per side | nothing — it is a **third independent book** beside Kraken and OKX, which is what the half-mean/half-minimum quality rule rewards | B | per refresh | none |

Derived values, all computed from the above and never modelled:

- `CB_GOLD_NET_3M_T` — net change in reported holdings over the last three reported months, tonnes, summed over every country that reported both end points.
- `CB_GOLD_NET_3M_Z` — that change standardised against the last 24 rolling three-month windows of the same measure.
- `CB_GOLD_BREADTH` — share of reporting countries that added gold last month minus the share that sold, ×100. This is the "official flow" reading: it keeps one large reporter from carrying the factor alone.
- `SURPRISE_INDEX_MEASURED` — mean of `(actual − forecast) / max(|forecast|, |previous|)`, clipped to ±1, over every release of the last 30 days, ×100.
- `INFLATION_SURPRISE_MEASURED` — the same statistic restricted to CPI, PPI, PCE and price-index releases.
- `POLICY_RATE_DIVERGENCE` — US policy rate minus the mean of the other five, plus the twelve-month change in that gap.
- `FED_EXPECTED_RATE_CHANGE` — three-month bill minus the published policy rate: the move the market has priced.

**Reporting defects handled, not patched.** Some national submissions carry a scale error. A country series is **dropped and named in the log** (`RESERVES_IMPLAUSIBLE`) when a holding exceeds 9,000 t or a single month moves more than 400 t; the intended scale is never guessed. On 2026‑10‑02 this rejected `AGO`, `BRA` and `G163`, leaving 62 reporting countries.

**Evaluated and rejected** (recorded so they are not retried): `dataservices.imf.org` legacy SDMX (withdrawn), IMF dataflow `IFS` (404 — no longer published), SNB cube `snbgolda` (404), Binance (451 from this region), Bybit (403), Trading Economics guest access (410 — discontinued), iShares IAU `fund.ajax?fileType=csv` (returns HTML), `fsapi.gold.org/api/etf/...` ETF flows (404), SPDR `GLD_US_archive_EN.csv` (returns a PDF), ECB `ILM` weekly gold key (404). Consequently **ETF creations/redemptions remain a proxy** (signed dollar volume) and are labelled as such on every screen.

Each provider has its **own** id because the circuit breaker is keyed per id: a backup sharing the primary's id would be skipped while the primary's breaker is open.

### 3.2 Additions of 2026‑10‑02 (SPEC §23.7, §23.8, §24)

| Need | What is read | How it is derived | Label |
|---|---|---|---|
| `CONSENSUS_SURPRISE` | the last three high-importance rows of the TradingView calendar, each carrying `actual` and `forecast` | mean of `(actual − forecast) / max(\|forecast\|, \|previous\|, 1e-6)`, clipped to ±1 | measured (`CALENDAR`) |
| `REAL_YIELD_INTRADAY_PROXY` | `TVC:US10Y` from the scanner, and the last published 10-year breakeven | quoted nominal ten-year **minus** last breakeven | `PROXY`, quality ceiling 0.6 |
| Historical analogue | the daily histories the app already downloads, kept at full depth for this purpose (`GLD` back to 2004‑11‑18, 5,505 sessions, plus `SPY`, `TLT`, `VIX`, `SLV`; `UUP` when its history is present) | nearest-neighbour match on standardised conditions; each match is a real date published with its distance and the gold return realised over the next 20 sessions | descriptive, never predictive |

`CONSENSUS_SURPRISE` previously tried to pair a ForexFactory row with a BLS print and logged `NO_MATCHED_RELEASE` whenever the two did not line up. Since one calendar row already carries both numbers, no pairing is needed; the BLS route is kept only as the fallback.

**Android trust anchor.** `api.imf.org` is issued under *Sectigo Public Server Authentication Root R46*, created 2021‑03‑22 and therefore absent from the Android 10 trust store. On an API 29 handset the reserves feed failed with `SSLHandshakeException: Trust anchor for certification path not found` and F10 reported `MISSING_INPUTS`, while the same build worked on desktop. The root is now shipped at `app/src/main/res/raw/sectigo_server_root_r46.pem` (SHA‑256 `7B:B6:47:A6:…:38:F2:5A:06`) and trusted **in addition to** the system store, **only** for `api.imf.org` and `fiscaldata.treasury.gov`, through `app/src/main/res/xml/network_security_config.xml`. No custom `TrustManager`, no pinning, cleartext still refused. `TrustAnchorTest` fails the build if the certificate, its fingerprint, its validity or the manifest wiring changes.

No IMF mirror on an older root exists: `data.imf.org` and `www.imf.org` answer 403 on every SDMX path, the DataMapper API carries no gold-reserve indicator, and the keyless re-publications of the same table are not machine-readable. Fixing the transport is the only honest route.

### 3.3 Measured sources added in SPEC v2.1 §25 (2026‑10‑02)

Four inputs that had been proxies since the first survey are now measured, and
three kinds of information the stack never had are now published. Full survey,
including every endpoint that refused and why: [`docs/INDICATOR_RESEARCH.md`](docs/INDICATOR_RESEARCH.md).

**FRED, without an API key.** `api.stlouisfed.org` requires a registered key —
which is why the credit spread, the financial-conditions indices and the Fed
balance sheet were previously ruled out. The chart export is a different route
and requires nothing:
`https://fred.stlouisfed.org/graph/fredgraph.csv?id=<SERIES>&cosd=<YYYY-MM-DD>`.
It returns the full history as two CSV columns and writes a missing observation
as `.`, which is dropped, never carried forward. The host refuses a browser
User-Agent from datacentre ranges, so this provider — and only this provider —
overrides the stack's default agent.

| FRED id | Reads | Feeds | Was |
|---|---|---|---|
| `BAMLH0A0HYM2` | ICE BofA US High Yield OAS, daily | **F18** credit | inverted HYG/LQD ETF ratio (`PROXY`) |
| `STLFSI4` | St. Louis Fed Financial Stress Index, weekly | **F08** stress | VIX + ETF + funding composite (`PROXY`) |
| `NFCI` | Chicago Fed National Financial Conditions, weekly | **F17** liquidity | — (new leg) |
| `WALCL`, `WTREGEN`, `RRPONTSYD` | Fed net liquidity = assets − TGA − reverse repo | **F17** liquidity | — (new leg) |
| `USEPUINDXD`, `WLEMUINDXD` | newspaper-derived daily uncertainty indices | **F07** geopolitical | GVZ/VIX premium z (`PROXY`) |
| `T5YIFR` | 5y5y forward inflation expectation, daily | **F05** inflation | — (new leg) |
| `DFII10`, `T10YIE`, `GVZCLS` | 23 years of daily real yield, breakeven and gold IV | deeper z-score populations for **F01**, **F05**, **F15** | ~2 years |
| `DTWEXBGS` | broad trade-weighted dollar, daily | independent check on **F02** | — |

**The published GLD option chain.**
`https://cdn.cboe.com/api/global/delayed_quotes/options/GLD.json` — 8,194 listed
contracts, each with implied volatility, open interest, volume and greeks, on
the Cboe host already in use. From it, counted and interpolated, never modelled:
the **25-delta risk reversal** (IV of the +25Δ call minus the −25Δ put on the
nearest expiry beyond two weeks, refused when neither wing is within 0.08 of 25
delta), the **put/call open-interest and volume ratios**, the **IV term slope**
and total listed open interest. The risk reversal is the only directional
options measure in the stack and now carries 40 % of **F15**.

**The policy path the market is paying for.** A 30-day fed funds future settles
on the average effective rate of its delivery month, so `100 − price` *is* the
priced rate — no probability tree. `CBOT:ZQ1!` plus three dated contracts on the
screener already in use give the strip; **F03**'s expected-rate-change input is
now `implied 12M − published policy rate` instead of `3-month bill − policy
rate`, with the bill kept as the fallback. The same request adds `TVC:MOVE`
(bond-market implied volatility), the German, Japanese, British and Chinese
ten-year yields, and `CRYPTO:BTCUSD` as the competing store of value. Cboe
`_VIX9D` and `_VIX3M` give the slope of the equity fear curve.

**Measured effect**, same machine, consecutive runs, nothing else changed:
data quality **0.682 → 0.715**, ingested indicators **63 → 74**, series loaded
**26 → 40**, indicators badged `EXPIRED` **2 → 0**, proxy inputs on F07/F08/F18
**3 → 0**, factors 22/22, errors 0. Factor weights were **not** changed: the
specification's weight table is normative.

### 3.4 News-analysis systems

| System | Status |
|---|---|
| **Economic Policy Uncertainty** (Baker, Bloom & Davis) — counts newspaper articles that carry *economy*, *policy* and *uncertainty* together | **live**, daily, `USEPUINDXD`, mapped to F07 as a five-year percentile of its own history |
| **Equity-Market-Related Economic Uncertainty** — the same method for market uncertainty | **live**, daily, `WLEMUINDXD` |
| **SF Fed Daily News Sentiment Index** — sentiment scored from the text of 24 US newspapers | reachable (`200`, 418 kB OOXML) but needs a **binary response path in the HTTP client**, which is a public-interface change and is not made without approval |
| **GDELT 2.0 DOC API** (tone and volume timelines) | **429 from this address range** on every mode and query tried; not a rate problem, a range block |
| NewsAPI, Finnhub, Marketaux, Cryptopanic, Alpha Vantage news | all require a registered key — out of scope by the project's own rule |

---

### 3.5 Measured sources added in SPEC v2.1 §27

Every endpoint below was called from this machine before it was wired in; none takes a key.

| Source | Endpoint | What it added |
|---|---|---|
| FRED chart export | `fred.stlouisfed.org/graph/fredgraph.csv?id=<ID>&cosd=<DATE>` × 24 new ids | `DFII5` `DFII30` `T5YIE` `DGS10` `DGS2` `T10Y2Y` `T10Y3M` `VIXCLS` `VXNCLS` `OVXCLS` `DCOILWTICO` `DCOILBRENTEU` `DHHNGSP` `BAMLC0A0CM` `BAMLH0A3HYC` `BAMLEMCBPIOAS` `DTWEXAFEGS` `DEXUSEU` `DEXJPUS` `DEXCHUS` `DEXUSUK` `WRESBAL` `DFF` `INFECTDISEMVTRACKD` |
| CFTC Public Reporting | `publicreporting.cftc.gov/resource/72hh-3qpy.json` (disaggregated) | **managed-money** and **commercial-hedger** net positions — the split the legacy "non-commercial" bucket hides. Gold contract code `088691`, weekly, back to 1986 |
| New York Fed markets | `markets.newyorkfed.org/api/soma/summary.json` | the desk's **SOMA portfolio**, weekly from 2003 |
| New York Fed markets | `markets.newyorkfed.org/api/rates/secured/sofr/last/<n>.json` | the **repo tail**: SOFR's published 99th percentile less its volume-weighted median |
| US Treasury | the real-curve CSV already in the stack | the **whole published real curve** — 5/7/10/20/30 year — not only the ten-year |
| OECD SDMX | `sdmx.oecd.org/public/rest/data/OECD.SDD.STES,DSD_STES@DF_CLI,/USA.M.LI...AA...H?format=csvfile` | the **composite leading indicator** for the United States |

Depth was also increased on series already in the stack, because the §26 replay reads them: `GVZCLS` to its 2008-06-03 start, `DTWEXBGS` to 2006-01-02, `USEPUINDXD` and `WLEMUINDXD` and `T5YIFR` to 2003, and `TIP` and `UUP` kept at full Cboe depth.

**Rejected after testing.** `prices.lbma.org.uk` (403, Cloudflare) · `data.nasdaq.com/api/v3/datasets/LBMA/GOLD` (403) · `spdrgoldshares.com` holdings JSON (404) · `www.sge.com.cn/graph/Dailyhq` (200 but every field zero) · `SKEWCLS` on FRED (404) · `cmegroup.com` metals report (connection refused). ICE BofA OAS series on FRED are capped at about three years of redistributable history, so the deep credit leg stays the ETF ratio.

### 3.6 Data provenance audit

Every indicator the app publishes carries a standing classification, rendered at the top of the Indicators screen and beside each row:

| Class | Meaning | Count |
|---|---|---|
| `MEASURED` | the published series itself, from its publisher | **81 of 97** |
| `DERIVED` | arithmetic on measured series only — a spread, a ratio, a z-score — with the formula on the row | **12 of 97** |
| `STAND-IN` | a declared substitute for something no free source publishes, each naming what it replaces and why | **4 of 97** |

The four stand-ins are the reconstructed dollar index (ICE's DXY is not freely licensed), the two ETF-flow readings (no machine-readable holdings file exists) and the intraday real-yield read (the Treasury real curve is end-of-day only). No indicator is estimated, modelled or filled in.

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

**The recorder is off by default and records nothing until it is switched on** (§21.1). A user who never opens the logger is never recorded; the counter of records *not* taken is kept so the switch's effect is visible. Turning it on starts capture from that moment — records from before the switch do not exist and are never reconstructed.

| Surface | How to switch recording |
|---|---|
| App | Logger tab → the first control: *Recording is OFF — tap to switch on*. The choice is remembered between sessions (`SharedPreferences: gold_intelligence/logging_enabled`) |
| Server | `GET /v1/logs/on`, `GET /v1/logs/off`; or start the process with `GI_LOG=1` to capture the very first refresh |
| Library | `DiagnosticLog.shared.setRecording(true)`; `DiagnosticLog(enabled = …)` for an independent recorder |

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
| `GET` | `/v1/logs/on` `/v1/logs/off` | Switch recording on or off (off at start-up) |
| `GET` | `/v1/stream` | Server-sent events, one message per refresh |

---

## 7. Build and run

Full from-zero build document, including toolchain installation, every header a
provider needs, the release checklist and the deliverable pipeline:
**[`BUILD.md`](BUILD.md)**. The short form:

Requirements: **JDK 17** (not 21 — the modules declare `jvmToolchain(17)`); for the APK also an Android SDK with API 36 and `build-tools 35.0.0`, with `ANDROID_HOME` set. Gradle comes from the wrapper.

```bash
chmod +x gradlew
./gradlew --no-daemon test                  # 110 unit tests
./gradlew --no-daemon :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew --no-daemon :app:assembleRelease  # app/build/outputs/apk/release/app-release.apk
./gradlew --no-daemon :server:installDist   # server/build/install/server/bin/server
```

Running the server on a desktop JVM needs
`JAVA_OPTS="-Dsun.net.http.allowRestrictedHeaders=true"`: without it the JVM
silently drops the `Origin` header and the economic calendar answers 403.
Android has no such restriction.

On a memory-constrained machine always add `--no-daemon`.

**Signing** — `keystore/gold-intelligence.keystore` is a development key committed on purpose so a clean clone produces an installable release build; store password, key password and alias are all `goldintelligence`. Replace it before public distribution:

```bash
./gradlew :app:assembleRelease \
  -PGI_KEYSTORE=/path/to/your.keystore -PGI_KEYSTORE_PASSWORD=… \
  -PGI_KEY_ALIAS=… -PGI_KEY_PASSWORD=…
```

Without a keystore the release build still succeeds and produces an unsigned APK.

**CI** — `.github/workflows/build.yml`, job `verify`: JDK 17 temurin, `gradle/actions/setup-gradle@v4`, runs `test`, `:app:assembleDebug`, `:server:installDist`, uploads the APK and the test reports.

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
9. **Tests accompany behaviour.** 88 tests across engine, ingestion and client; a new rule ships with the test that pins it.
10. **The logger never records unless the user asked for it.** The shared recorder is constructed off; only an explicit switch turns it on.

---

## 9. Declared limitations

Properties of the free data layer, surfaced in the product rather than hidden:

- **The calibration panel is measured but partial.** The §27 replay reproduces 0.740 of the normative weight from the published series themselves; the flow, positioning, physical and central-bank factors have no free daily history and are not in it. The covered share and the measured share are published with every result, and the mapping is never presented as a calibration of the whole table.
- **Neither forecaster earns positive out-of-sample skill at 1D or 1W**, so no member enters the combination and the probability stays withheld at `UNCALIBRATED_NO_SKILL`. The per-leg information coefficients show the information is real and too small; both facts are published rather than one of them.
- **The ridge model does not re-weight the published composite.** It is a separate forecaster. The §2 weight table is normative and unchanged.
- **The conditional band's guarantee is per bucket, not global.** A bucket with fewer than 120 residuals falls back to the pooled band rather than reporting a quantile it cannot support, and every bucket publishes its own realised coverage.
- **Macro series are joined as-of, never interpolated.** A session takes the last observation published on or before its own date; beyond seven days stale it is left empty rather than carried forward.
- **ICE BofA credit spreads on FRED carry about three years of redistributable history**, so they inform the live reading but not the deep replay.
- **The calibration sample is price-derived, not the full factor set.** The §26 replay reproduces 0.440 of the normative weight from seven price stand-ins; the macro, flow, positioning and physical factors have no free daily history and are not in it. The covered share is published with every result and the mapping is never presented as a calibration of the whole table.
- **Measured skill is not positive at 1D or 1W**, so the probability stays withheld and the status reads `UNCALIBRATED_NO_SKILL`. A probability is never invented; the engine states which of "never measured" and "measured and found to have none" applies.
- **Split conformal assumes exchangeability, which a daily price series violates.** The guarantee is therefore not claimed: the realised coverage is measured on the held-out sessions and published beside the nominal level, and under-coverage is readable as distribution shift.
- **Isotonic calibration clamps outside its fitted range** rather than extrapolating; a bias beyond anything in the sample receives the endpoint probability, not a continued trend.
- **The changepoint posterior is pruned** to the 200 most probable run lengths and reads gold's own daily returns only; it dates the regime, it does not name it.
- **The effective number of bets is computed on the price panel**, which is the only set with aligned daily history, and is applied as a shrinkage on agreement rather than as a count of factors.
- **5m and 15m are directional only** (coverage 0.35 / 0.36).
- **F10 (central bank demand) is measured** from the IMF reserves template and carries that publication's lag: holdings are reported monthly, roughly three weeks after month end, so the factor is gated to the 1W horizon and never claims to be current.
- **National scale errors in the reserves template are dropped, not corrected** — the affected country is named in the log and excluded from the aggregate for that cycle.
- **ETF creations and redemptions remain a proxy** (signed dollar volume on GLD). The sponsor's daily holdings file is not published in a machine-readable form; every free route to it was tried and is listed in §3.1.
- **Historical analogues are matched, not labelled.** No free dataset of labelled episodes exists, so no match is given a name: each one is a real date, published with its distance from today and the gold return realised over the 20 sessions that followed. With fewer than 400 sessions or 4 usable conditions the layer reports its absence instead of a weak answer.
- **The intraday real-yield reading is a proxy** — the Treasury's real curve is end-of-day only, so between publications the quoted nominal ten-year minus the last breakeven is the only available read; it never replaces `REAL_YIELD`.
- **Android 10 needs the bundled Sectigo root** to reach the IMF reserves feed; without it that single feed fails and F10 falls back to `MISSING_INPUTS`. Nothing else in the stack depends on it.
- **Open interest is daily** (scanner `open_interest`), cross-checked against the weekly CFTC report.
- **Order-book depth is real L2 from gold-backed venues** (Kraken PAXG/USD, OKX XAUT/USDT, Coinbase PAXG/USD) plus Swissquote OTC tiers — a labelled proxy for the COMEX book, not a CME feed.
- **Consensus and actual come from the same calendar row**; the surprise index needs at least five released prints in the window before it is published, and the BLS prints remain the independent cross-check.
- **The event clock reports the next *high-impact* release**; if the window holds none, the next release of any weight is published and labelled as the substitute it is.
- **The LBMA benchmark series is monthly** (WGC); the daily AM/PM fixes require a licence.
- **The India premium carries a ~9 pp duty and GST wedge**, subtracted before scoring.
- **Chart seed bars are rebased, not borrowed silently** (hollow, counted, factor published).
- **The venue's bid/ask is dropped when it disagrees with the last price** by more than 25 bp.
- **Some providers rate-limit this network** (Yahoo returns 429); the breaker holds them for 15 minutes and the declared fallback is used.
- **The option-chain measures are delayed**, like every Cboe quote in the stack, and the risk reversal is refused outright when no listed contract sits within 0.08 of 25 delta rather than being interpolated from a distant wing.
- **Net liquidity follows the Wednesday balance sheet**: the Treasury cash balance and the reverse-repo figure are read *as of* that date, never interpolated forward, so the series is weekly however often the app refreshes.
- **The newspaper-derived uncertainty indices are counts of articles, not of events**; they measure how much the press is writing about uncertainty, which is what F07 reads them as.
- **FRED refuses a browser User-Agent from datacentre ranges**; the provider sends this project's own agent string. If that string is ever rejected the provider fails closed and the previous proxies take over.

---

## 10. Attribution and disclaimer

Per-source attribution: [`NOTICE`](NOTICE). Data dictionary: [`DATA_DICTIONARY.md`](DATA_DICTIONARY.md). Build from zero: [`BUILD.md`](BUILD.md). Indicator survey: [`docs/INDICATOR_RESEARCH.md`](docs/INDICATOR_RESEARCH.md). Method survey: [`docs/INFERENCE_METHODS.md`](docs/INFERENCE_METHODS.md). Source-of-spec notes: [`SPEC_SOURCE.md`](SPEC_SOURCE.md).

This software produces a statistical description of market conditions. **It is not investment advice.**
