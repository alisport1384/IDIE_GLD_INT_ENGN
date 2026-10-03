# Indicator & News-System Survey — 2026-10-02

Every row below was requested from this machine on 2026-10-02 and is recorded
with what actually came back. Nothing in this file is quoted from memory, from
documentation, or from a third-party list: an endpoint appears here only if it
answered, and the sample it returned is printed. Endpoints that refused are in
§6 with the exact refusal, so they are not tried again.

Scope rule applied throughout: **keyless, accountless, free**. A source that
needs a key, a login, a paid tier or a scraped session is rejected on sight,
however good the data is.

---

## 1. What was added to the engine

These are live in the build. Each one replaced a proxy or added information the
stack did not have.

### 1.1 FRED — the whole catalogue, without an API key

**Finding that unlocked this section:** `api.stlouisfed.org` requires a
registered key, which is why the ICE BofA credit spread, the financial
conditions indices and the Fed balance sheet were previously ruled out and
replaced with ETF-ratio proxies. The **chart export** is a different route and
requires nothing:

```
https://fred.stlouisfed.org/graph/fredgraph.csv?id=<SERIES_ID>&cosd=<YYYY-MM-DD>
```

It returns the full published history as two CSV columns, with a missing
observation written `.`:

```
observation_date,DFII10
2003-01-02,2.43
…
2026-10-01,2.88
```

One constraint, measured, not assumed: the host **refuses a browser
User-Agent** from this address space — `Mozilla/5.0 …` returns a reset
connection (curl exit without status), while `curl/8.5.0`, `okhttp/4.12.0`,
`Dalvik/2.1.0 (Linux; U; Android 10; SM-J810F …)`, `Java/17` and this project's
own agent string all return `200`. The client therefore overrides the stack's
default agent for this provider only.

| FRED id | Universe id | What it measures | Published | Verified sample (2026-10-02) | Replaces |
|---|---|---|---|---|---|
| `BAMLH0A0HYM2` | `HY_OAS` | ICE BofA US High Yield option-adjusted spread | daily, 1-day lag | `2026-10-01, 3.24` pp | **F18**'s inverted HYG/LQD ETF-ratio proxy |
| `NFCI` | `FINANCIAL_CONDITIONS_NFCI` | Chicago Fed National Financial Conditions Index | weekly (Wed) | `2026-09-25, -0.548` | new leg of **F17** |
| `STLFSI4` | `FINANCIAL_STRESS_STLFSI` | St. Louis Fed Financial Stress Index | weekly (Wed) | `2026-09-25, -0.8074` | **F08**'s VIX/ETF/funding composite proxy |
| `WALCL` | `FED_BALANCE_SHEET` | Fed total assets, $M | weekly (Wed) | `2026-09-30, 6,743,031` | — |
| `WTREGEN` | `TREASURY_GENERAL_ACCOUNT` | Treasury General Account, $M | weekly | `2026-09-30, 948,674` | — |
| `RRPONTSYD` | `REVERSE_REPO` | Overnight reverse repo, $bn | daily | `2026-10-02, 1.501` | — |
| *(derived)* | `FED_NET_LIQUIDITY` | `WALCL − WTREGEN − RRP`, $bn | weekly | `5,782.8` bn | new leg of **F17** |
| `USEPUINDXD` | `POLICY_UNCERTAINTY_DAILY` | Baker–Bloom–Davis daily Economic Policy Uncertainty | daily | `2026-10-01, 160.60` | **F07**'s GVZ/VIX option-price proxy |
| `WLEMUINDXD` | `NEWS_EQUITY_UNCERTAINTY` | Equity-market-related economic uncertainty | daily | `2026-10-01, 27.74` | second leg of **F07** |
| `T5YIFR` | `INFLATION_EXPECTATION_5Y5Y` | 5-year, 5-year forward inflation expectation | daily | `2026-10-01, 2.36` % | new leg of **F05** |
| `DFII10` | `REAL10Y_DEEP` | 10-year TIPS yield, from 2003-01-02 | daily | `2026-10-01, 2.88` %, 5,900+ rows | deepens the **F01** z-score population from 2 years to 23 |
| `T10YIE` | `BREAKEVEN10Y_DEEP` | 10-year breakeven | daily | `2026-10-01, 2.36` % | deepens **F05** |
| `GVZCLS` | `GVZ_DEEP` | Cboe Gold ETF Volatility Index history | daily | `2026-10-01, 23.32` | deepens **F15** |
| `DTWEXBGS` | `DOLLAR_BROAD_INDEX` | Broad trade-weighted dollar index | daily | `2026-09-25, 120.33` | independent check on **F02** |

Net liquidity is the only derived figure and the derivation is a subtraction:
`WALCL` and `WTREGEN` are in millions, `RRPONTSYD` in billions, and the two
daily series are read **as of** the weekly balance-sheet date — never
interpolated forward.

### 1.2 Cboe — the full published option chain

```
https://cdn.cboe.com/api/global/delayed_quotes/options/GLD.json
```

Verified: `200`, **3.6 MB**, `8,194` listed contracts, each carrying
`iv, open_interest, volume, delta, gamma, vega, theta, bid, ask`, plus the
underlying quote (`current_price 379.75`, `iv30 20.631`). Same host, same
provider id and same circuit breaker as the quote and history feeds already in
use, so it adds no new dependency.

| Universe id | Definition | Verified sample |
|---|---|---|
| `GOLD_RISK_REVERSAL_25D` | IV of the contract nearest +0.25 delta minus IV of the contract nearest −0.25 delta, on the nearest expiry at least 14 days out; refused when neither wing is within 0.08 of 25 delta | `−0.72` vol pts |
| `GOLD_PUT_CALL_OI` | put open interest ÷ call open interest, whole chain | `0.538` |
| `GOLD_PUT_CALL_VOLUME` | the same on the session's traded volume | published |
| `GOLD_IV_TERM_SLOPE` | ATM IV of the first expiry ≥75 days minus ATM IV of the first ≥14 days | published |
| `GOLD_OPTION_OPEN_INTEREST` | total listed open interest | published |

The risk reversal is the only *directional* options measure in the stack: a
positive reading means the market is paying more for upside than for the mirror
downside. It now carries 40 % of **F15**, which until this build scored only on
IV-minus-realised-volatility.

### 1.3 TradingView screener — the priced policy path, bond volatility, global curves

Same POST endpoint and provider already in use; the only change is the symbol
list. All probed live:

| Ticker | Universe id | Verified sample | Why it matters |
|---|---|---|---|
| `CBOT:ZQ1!` | `FED_FUNDS_IMPLIED_FRONT` | `96.065` → 3.935 % | A 30-day fed funds future settles on the **average effective rate of its delivery month**, so `100 − price` *is* the rate the market is paying for. No probability tree, no model. |
| `CBOT:ZQZ2026` / `ZQH2027` / `ZQM2027` | `FED_FUNDS_IMPLIED_12M` | `95.92 / 95.68 / 95.39` → 4.08 / 4.32 / **4.61 %** | the policy path twelve months out |
| `CME:SR31!`, `CME:SR3Z2026` | — | `95.765` | three-month SOFR strip, cross-check |
| `TVC:MOVE` | `BOND_VOL_MOVE` | `107.2968` | ICE BofA bond-market option volatility — the bond-market analogue of the VIX, and historically a stronger gold driver than equity volatility |
| `TVC:DE10Y` | `DE10Y` | `3.4611 %` | |
| `TVC:JP10Y` | `JP10Y` | `3.088 %` | |
| `TVC:GB10Y` | `GB10Y` | `5.3792 %` | |
| `TVC:CN10Y` | `CN10Y` | `1.683 %` | sovereign curves gold is priced against outside the dollar |
| `CRYPTO:BTCUSD` | `BTC_SPOT` | `84,420.4` | the competing store of value |

`FED_EXPECTED_RATE_CHANGE` — the 0.100-weight input of **F03** — is now
`implied 12M − published policy rate` (`4.595 − 3.625 = +0.97 pp` on the
verification run) instead of `3-month bill − policy rate`. The bill remains the
fallback, and Δ2Y(20d) remains the fallback behind that.

Also probed and present on the screener but **not wired to a factor** (recorded
only, to avoid changing factor behaviour without cause):
`ECONOMICS:USIRYY` (3.4 %), `ECONOMICS:USCBBS` (6.743e12),
`ECONOMICS:USM2` (2.334e13), `ECONOMICS:USINTR` (4 — upper bound, differs from
the BIS convention), `ECONOMICS:USCCR`, `NYMEX:CL1!`, `CBOT:ZB1!`.

### 1.4 Cboe — the shape of the equity fear curve

`_VIX9D.json` and `_VIX3M.json`, both `200`, 504 B each. `VIX_TERM_SLOPE` =
3-month VIX − 9-day VIX = `+5.95` vol points on the verification run. Positive
is a calm spot market pricing later risk; negative is acute stress now.

---

## 2. Measured effect of the additions

Same machine, consecutive runs, nothing else changed.

| | before (2026-10-02T18:54Z) | after (2026-10-02T20:52Z) |
|---|---|---|
| Aggregate data quality | 0.682 | **0.715** |
| Ingested indicators | 63 / 63 | **74 / 74** |
| Time series loaded | 26 | **40** |
| Active factors | 22 / 22 | 22 / 22 |
| Indicators badged `EXPIRED` | 2 | **0** |
| Inputs still carrying `PROXY` on F07 / F08 / F18 | 3 | **0** |
| Errors in the operational log | 0 | 0 |

The quality rise is mechanical, not cosmetic: the quality contract caps a proxy
at 0.60 and awards a Tier-A published series 1.00, so converting four inputs
from proxy to measured raises the aggregate on its own.

---

## 3. News-analysis systems

The request was specifically for systems that analyse *news*. Three classes
exist in the free tier; one is usable from here.

### 3.1 Usable — newspaper-text indices (implemented)

Both are built by counting and classifying newspaper articles, published daily
with no key, and both are now live:

- **Economic Policy Uncertainty (Baker, Bloom & Davis)** — counts articles in a
  standing newspaper panel that contain terms for *economy*, *policy* and
  *uncertainty* together, normalised by article volume. `USEPUINDXD`, daily,
  back to 1985. Verified `2026-10-01 = 160.60`.
- **Equity-Market-Related Economic Uncertainty** — the same method restricted to
  market-related uncertainty. `WLEMUINDXD`, daily. Verified `2026-10-01 = 27.74`.

They are mapped to **F07** as the five-year percentile of their own history, so
the factor reads *"today's news-measured uncertainty against the last five
years"* rather than an option-price stand-in. The previous GVZ/VIX premium
proxy is kept as the fallback for cycles where the indices do not answer.

### 3.2 Usable but requires a change this build did not make

- **Daily News Sentiment Index — Federal Reserve Bank of San Francisco**
  `https://www.frbsf.org/wp-content/uploads/news_sentiment_data.xlsx` → `200`,
  418 kB, OOXML. This is the strongest free news-analysis product available: a
  daily sentiment score built from the text of 24 major US newspapers
  (Buckman, Shapiro, Sudhof, Wilson). The payload is a ZIP container whose data
  sheet is 3.3 MB of XML, so reading it needs a **binary response path in the
  HTTP client**, which today returns decoded text only. That is a change to a
  public interface in the project contract and is therefore **not made without
  approval**. Everything else needed (ZIP and XML handling) is already in the
  JDK and in the stack.

### 3.3 Not usable from here

- **GDELT 2.0 DOC API** — the canonical free global news-analytics service
  (`mode=TimelineTone`, `TimelineVolInfo`, `ArtList`). Re-probed four times on
  2026-10-02 with different queries and timespans: every call returned **HTTP
  429** with *"Please limit requests to one every 5 seconds…"* — the limit is
  applied to this address range, not to the call rate. Already recorded as a
  dead end in an earlier phase; re-confirmed, not retried further.
- **Commercial news APIs** (NewsAPI, Finnhub, Marketaux, Cryptopanic,
  Alpha Vantage news sentiment) — all require a registered key.
- **Google Trends** — no documented endpoint; the unofficial one is
  session-gated and rate-limited.

---

## 4. Found, verified, and deliberately not wired

These answered and are genuinely useful, but wiring them would change factor
behaviour without a defect to justify it. They are recorded here so the decision
is a decision, not an omission.

| Source | Endpoint | Verified | What it would add |
|---|---|---|---|
| Wikipedia pageviews | `wikimedia.org/api/rest_v1/metrics/pageviews/per-article/en.wikipedia/all-access/user/Gold/daily/…` | `200`, JSON, 8.7 kB | retail attention, a contrarian crowding read |
| FRED `VIXCLS`, `T10Y2Y`, `DTB3`, `SP500`, `DCOILWTICO`, `DEXCHUS`, `ANFCI`, `WRESBAL`, `UMCSENT`, `EXPINF10YR`, `INFECTDISEMVTRACKD` | same CSV route | all `200`, fresh to 2026-10-01 | duplicates of series the stack already quotes live, or lower-frequency context |
| Cboe `_VIX6M`, option chains for `SLV`, `SPY` | same CDN | `200` | a fuller volatility surface and a cross-asset skew comparison |

---

## 5. Factor-by-factor effect

| Factor | Weight | Input before | Input now | Status |
|---|---|---|---|---|
| F01 Real rate | 0.200 | z-score over ~2 years of Treasury CSV | z-score over 23 years (`DFII10`) | deeper population |
| F03 Fed | 0.100 | 3-month bill − policy rate | **fed funds futures 12M − policy rate** | measured |
| F05 Inflation | 0.060 | breakeven change + inflation surprise | **+ 5y5y forward expectation vs the 2 % objective** | third leg |
| F07 Geopolitical | 0.060 | GVZ/VIX premium z (PROXY) | **newspaper-derived uncertainty percentile** | measured |
| F08 Financial stress | 0.030 | VIX + ETF ratio + funding composite (PROXY) | **STLFSI4 + NFCI as published** | measured |
| F15 Options / vol | 0.010 | IV−RV and realised-vol z | **+ 25Δ risk reversal (0.40) and put/call OI (0.20)** | measured, directional |
| F17 Liquidity | 0.020 | SOFR−EFFR z only | **+ NFCI (0.35) and 13-week net liquidity change (0.25)** | three legs |
| F18 Credit | 0.010 | inverted HY/IG ETF ratio z (PROXY) | **ICE BofA HY OAS z over ten years** | measured |

Weights were **not** changed. The specification's weight table is normative and
a change to it is a separate decision.

---

## 6. Probed and refused — do not retry

| Target | Result |
|---|---|
| `api.stlouisfed.org/fred/series/observations` | `400 api_key required` — superseded by the chart-export route above |
| FRED `GOLDPMGBD228NLBM`, `GOLDAMGBD228NLBM` (LBMA fix) | `404` — withdrawn from FRED; the WGC monthly series remains the benchmark source |
| FRED multi-id CSV (`?id=A,B,C`) | returns a **ZIP** when the series have different frequencies — one id per request |
| FRED `USEPUNEWSINDXD`, `GPRD`, `GPR`, `TEU`, `DRIVE` | `404` — not FRED series |
| Geopolitical Risk Index (Caldara & Iacoviello) `matteoiacoviello.com/gpr_files/data_gpr_daily_recent.xls` | `200` but **legacy OLE2 `.xls`, 3.2 MB** — needs a BIFF reader; no CSV or JSON is published |
| NY Fed ACM term premium `ACMTermPremium.xls` | `200` but legacy `.xls`, **10.2 MB** — same problem |
| Cleveland Fed inflation expectations CSV | `404` (site reorganised; no stable machine-readable path found) |
| Atlanta Fed GDPNow `…/GDPTrackingModelDataAndForecasts.xlsx` | `200` but the body is an **HTML error page** |
| GDELT DOC 2.0 (all modes) | `429` from this address range |
| Browser User-Agent against FRED | connection reset — use a non-browser agent |

Dead ends recorded in earlier phases remain dead ends and were not re-probed:
Yahoo `429` from this address, CME Group `403`, Dukascopy `503`, Stooq
proof-of-work, Binance `451`, Bybit `403`, Trading Economics `410`, iShares
`fund.ajax` returning HTML, SPDR `GLD_US_archive_EN.csv` returning a PDF,
`fsapi.gold.org/api/etf/...` `404`, ECB `ILM` weekly gold key `404`,
`data.imf.org` / `www.imf.org` `403` on every SDMX path.

---

## 7. Attribution

Added to `NOTICE` with this build:

> Federal Reserve Bank of St. Louis (FRED) — series published by FRED, Federal
> Reserve Bank of St. Louis. `USEPUINDXD` and `WLEMUINDXD` are the Economic
> Policy Uncertainty indices of Baker, Bloom & Davis; `NFCI` is published by the
> Federal Reserve Bank of Chicago; `STLFSI4` by the Federal Reserve Bank of
> St. Louis; `BAMLH0A0HYM2` is the ICE BofA US High Yield Index OAS, used with
> permission of ICE Data Indices, LLC.

Option-chain data carries the existing Cboe notice. Fed funds futures, the MOVE
index and the sovereign yields carry the existing TradingView notice.
