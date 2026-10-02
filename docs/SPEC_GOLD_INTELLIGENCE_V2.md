# Gold Intelligence Engine — Free Data Edition **v2.1 (Corrected)**

> نسخه اصلاح‌شده «شاخص های طلا — نسخه رایگان نهایی» پس از اعمال ۱۰۹ قلم اصلاحی گزارش محدودیت‌ها.
> تاریخ: 2026-10-01 · بازبینی v2.1: 2026-10-02 · مرجع اصلاحات: `REMEDIATION_PLAN.md` · مخزن: `IDIE_GLD_INT_ENGN`
>
> **معماری تحلیلی سند v1 بدون تغییر حفظ شده است.** تغییرات فقط در چهار محور است:
> (۱) لایه Data Acquisition، (۲) جدول وزن‌ها، (۳) افزودن Horizon Gating، (۴) افزودن قرارداد API و سطح نمایش اپ.
>
> **تغییرات v2.1:** (۵) §14 — بسته‌شدن ۸ شکاف از ۱۰ شکاف با منابع رایگان و کلیدلس، (۶) §15 — حذف کامل قفل انتشار مبتنی بر لایسنس؛ نگهداری لایسنس فقط برای اِسناد، (۷) §18.1 — افزودن صفحهٔ هفتم، (۸) §21 — افزودن مشخصات گزارش‌گیر عملیاتی.

---

## 0. تغییرات نسبت به v1

| # | تغییر | دلیل |
|---|---|---|
| 1 | CME به‌طور کامل از Data Contract حذف شد | همه مسیرها (Daily Bulletin / CmeWS / ftp) پاسخ **403** می‌دهند |
| 2 | منحنی فوروارد COMEX **اضافه شد** (§1.5) | نمادهای ماه‌دار `.CMX` روی Yahoo رایگان و درون‌روزی‌اند |
| 3 | جدول وزن ۲۲ فاکتور بازنویسی شد؛ مجموع = `1.000` | در v1 عملاً ۱۳ فاکتور وزن صفر و مجموع `0.95` بود |
| 4 | **Horizon-Aware Factor Gating** اضافه شد (§D1) | Real Yield و ۱۴ فاکتور دیگر منبع درون‌روزی ندارند |
| 5 | Polling Baseline بازنویسی شد | نرخ Twelve Data در v1 (۱۴۴۰/روز) از سقف ۸۰۰/روز عبور می‌کرد |
| 6 | GDELT از الزامی به اختیاری تنزل یافت | **429** مستمر از IP نوع Datacenter |
| 7 | Data Contract از ۱۹ فیلد الزامی تشکیل شد | v1 فیلدهای `source_tier`, `license_class`, `vintage`, `warmup` را الزام نکرده بود |
| 8 | فرمول‌های Confidence، Cross-Market Confirmation، Expected Move، Information Dominance و Kill-Switch تعریف عددی گرفتند | در v1 فقط مفهومی بودند و در کد `null` یا دایره‌وار پیاده شده بودند |
| 9 | §17 قرارداد REST/WebSocket و §18 سطح نمایش شاخص‌ها در اپ اضافه شد | حلقه `STATE → API → APP` در v1 بسته نبود |
| 10 | `range=max` از همه نمونه‌ها حذف شد | به‌صورت خاموش گرانولاریتی را تغییر می‌دهد (۲۶۸ در برابر ۶٬۶۳۰ کندل) |

---

# بخش I — معماری تحلیلی (بدون تغییر از v1)

## I.1 سلسله‌مراتب

```text
                    GOLD
                      │
        ┌─────────────┴─────────────┐
   MACRO FORCE                 MARKET FORCE
        │                           │
 ┌──────┼──────┐              ┌─────┼─────┐
Real   USD    Fed           Flow  Position Price
Yield         Expect.              ing     Action
 └──────┴──────┘                   │
        └──────────┬────────────────┘
                   │
              RISK REGIME
                   │
             FINAL REGIME
         BULL · NEUTRAL · BEAR
```

## I.2 خط لوله کامل

```text
DATA → FEATURES → FACTORS → CAUSALITY → EVENTS/NEWS → MICROSTRUCTURE
→ REGIME → INFORMATION DOMINANCE → DYNAMIC WEIGHTS → INTERACTIONS
→ DIVERGENCE → HISTORICAL ANALOGUES → ENSEMBLE → PROBABILITY
→ CALIBRATION → CONFIDENCE → SCENARIOS → INVALIDATION
→ GOLD INTELLIGENCE STATE
```

## I.3 سه سؤال مستقل موتور

```text
1. چرا طلا باید حرکت کند؟     → Fundamental / Causal
2. آیا بازار تأیید می‌کند؟      → Flow / Positioning / Price
3. آیا شرایط اعتماد هست؟       → Regime / Liquidity / Shock / Contradiction
```

---

# بخش II — اصلاحات هسته‌ای

## D1. Horizon-Aware Factor Gating ‹جدید — حیاتی›

هر فاکتور یک `min_horizon` دارد. در افقی کوتاه‌تر از آن، وزن فاکتور **صفر** می‌شود و جرم وزنی حذف‌شده مستقیماً از Confidence کسر می‌گردد.

```text
W_removed(h)            = Σ base_weight(f)  برای هر f که min_horizon(f) > h
Renormalized Weights    = base_weight(f) / (1 − W_removed(h))     برای f های فعال
Coverage(h)             = 1 − W_removed(h)
Confidence(h)           = Confidence_raw(h) × Coverage(h)
```

> این قاعده جایگزین رفتار v1 می‌شود که در آن نبودِ داده به‌صورت خاموش بین بقیه فاکتورها توزیع می‌شد و Confidence تغییر نمی‌کرد.

### D1.1 جدول `min_horizon` و پوشش

| افق | فاکتورهای فعال | Coverage | با `F01` به‌صورت PROXY |
|---|---:|---:|---:|
| `5m` | 7 | **0.350** | 0.550 |
| `15m` | 8 | **0.360** | 0.560 |
| `1H` | 9 | **0.420** | 0.620 |
| `4H` | 9 | **0.420** | 0.620 |
| `1D` | 17 | **0.880** | 0.880 |
| `1W` | 22 | **1.000** | 1.000 |

**نتیجه الزام‌آور:** در `5m` استک رایگان تنها **۳۵٪** جرم شواهد مدل را پشتیبانی می‌کند. بنابراین:

```text
5m , 15m   →  DIRECTIONAL_ONLY , calibration_status = UNCALIBRATED , احتمال عددی نمایش داده نمی‌شود
1H , 4H    →  احتمال فقط در صورت n ≥ 500
1D , 1W    →  افق‌های مرجع V1
```

## D2. جدول وزن پایه ‹اصلاح‌شده›

| Factor | عنوان | گروه علّی | `min_horizon` | **Base Weight** |
|---|---|---|---|---:|
| F01 | Real Yield | MONETARY | `1D` (+PROXY درون‌روزی) | **0.200** |
| F02 | USD / DXY | MONETARY | `5m` | **0.150** |
| F03 | Fed Expectations | MONETARY | `5m` | **0.100** |
| F04 | Treasury Curve | MONETARY | `5m` | **0.040** |
| F22 | Global CB Policy | MONETARY | `1D` | **0.030** |
| F05 | Inflation | INFLATION | `1D` | **0.060** |
| F21 | Oil / Energy | INFLATION | `5m` | **0.020** |
| F07 | Geopolitical Risk | RISK | `1H` | **0.060** |
| F08 | Financial Stress | RISK | `1D` | **0.030** |
| F17 | Liquidity | RISK | `1D` | **0.020** |
| F18 | Credit Conditions | RISK | `1D` | **0.010** |
| F09 | Gold ETF Flow | FLOW | `1D` | **0.060** |
| F10 | Central Bank Demand | FLOW | `1W` | **0.040** |
| F11 | Futures Positioning | POSITIONING | `1W` | **0.060** |
| F06 | Economic Surprise | GROWTH | `1D` | **0.050** |
| F13 | Market Momentum | STRUCTURE | `5m` | **0.020** |
| F14 | Market Microstructure | STRUCTURE | `5m` | **0.010** |
| F15 | Options / Volatility | STRUCTURE | `15m` | **0.010** |
| F16 | Cross-Asset | STRUCTURE | `5m` | **0.010** |
| F12 | Physical Demand | PHYSICAL | `1W` | **0.008** |
| F19 | China | PHYSICAL | `1W` | **0.006** |
| F20 | India | PHYSICAL | `1W` | **0.006** |
| | | | **جمع** | **1.000** |

تجمیع گروهی، منطبق بر جدول ۱۰ گروهی v1:

| گروه | وزن v1 | وزن v2 | ✓ |
|---|---:|---:|---|
| Monetary (Real Rate + USD + Fed) | 0.52 | 0.52 | ✅ |
| Risk | 0.12 | 0.12 | ✅ |
| Gold Flow | 0.10 | 0.10 | ✅ |
| Inflation | 0.08 | 0.08 | ✅ |
| Positioning | 0.06 | 0.06 | ✅ |
| Growth | 0.05 | 0.05 | ✅ |
| Market Structure | 0.05 | 0.05 | ✅ |
| Physical Demand | 0.02 | 0.02 | ✅ |

> این وزن‌ها `source = PRIOR` هستند و باید با Walk-Forward کالیبره شوند. هیچ وزنی مجاز نیست صفر باشد.

## D3. Data Contract v2 — ۱۹ فیلد الزامی

```text
factor_id            variable_id          raw_value            unit
event_time           release_time         ingestion_time       availability_time
source               source_tier          license_class        frequency
latency              data_quality         vintage              revision
coverage_status      warmup_required      data_since
```

قواعد:

| قاعده | مقدار مجاز |
|---|---|
| `source_tier` | `A` رسمی/اولیه · `B` نهادی · `C` ثانویه · `D` مشتق · `PROXY` |
| `license_class` | `PUBLIC_DOMAIN` · `ATTRIBUTION` · `FREE_TIER_NO_REDIST` · `UNOFFICIAL_NO_REDIST` · `RESEARCH_ONLY` |
| `coverage_status` | `COVERED` · `PARTIAL` · `PROXY` · `NOT_AVAILABLE` |
| `vintage_capability` | `NATIVE` (FRED/ALFRED) · `SNAPSHOT_ONLY` (بقیه ⇒ آرشیو روزانه اجباری) |
| `ip_sensitivity` | `NONE` · `UA_REQUIRED` · `DC_BLOCKED` |
| Backtest | فقط `availability_time ≤ backtest_time` |
| داده غایب | هرگز `0`؛ همیشه Gate + کسر Coverage |
| `PROXY` | هرگز با منبع مستقیم یکسان فرض نشود؛ `proxy_of` اجباری |
| API عمومی | هر فیلد با `license_class ≠ PUBLIC_DOMAIN / ATTRIBUTION` فقط به‌صورت `DERIVED` خارج می‌شود |

## D4. فرمول‌های عددی ‹جدید›

### D4.1 Data Quality

```text
data_quality = clamp01(
    0.40 · freshness          ( = exp(−latency / expected_frequency) )
  + 0.25 · source_reliability ( A=1.00 · B=0.90 · C=0.75 · D=0.60 · PROXY=0.55 )
  + 0.20 · completeness       ( نسبت فیلدهای غیرتهی قرارداد )
  + 0.15 · (1 − revision_risk)
)
```

### D4.2 Dynamic Weight

```text
Effective Weight = Base Weight
                 × Regime Multiplier
                 × Information Dominance
                 × Data Quality
                 × Time Decay
→ Normalize روی فاکتورهای فعالِ همان افق
```

### D4.3 Information Dominance ‹بازتعریف — حذف حلقه بازخورد›

```text
Dominance(f, t) = R²_rolling( Δ Factor_f  →  Δ Gold , window = 60 بازه )
                  نرمال‌شده روی [0.75 .. 1.50]
```

> در v1 از `abs(score)` ساخته می‌شد که خودتقویتی ایجاد می‌کرد. تعریف جدید مستقل از امتیاز جاری است.

### D4.4 Time Decay

```text
Decay(t) = exp( − age_hours / TIME_DECAY_TAU_HOURS )
TIME_DECAY_TAU_HOURS = half_life_hours / ln 2
پیش‌فرض: half_life = 48h  ⇒  τ ≈ 69.3h
```

### D4.5 Cross-Market Confirmation ‹جدید›

```text
بازارهای شاهد (۸ مورد):
  DXY · ^TNX · RealYield(یا PROXY) · Silver · Oil · VIX · ETF Flow · Futures Basis

agree    = تعداد بازارهایی که علامتشان با جهت طلا سازگار است
disagree = تعداد مخالف
CMC = clamp01( (agree − disagree) / n_available  × 0.5 + 0.5 )
```

### D4.6 Confidence ‹جایگزین فرمول ناقص v1›

```text
Confidence_raw = clamp01(
    0.30 · DataQuality_avg
  + 0.20 · CMC
  + 0.20 · ModelAgreement
  + 0.15 · RegimeStability
  + 0.15 · CalibrationQuality
  − 0.25 · ConflictRatio
  − 0.20 · TransitionFlag
)

Confidence(h) = Confidence_raw × Coverage(h)      ← از D1
```

### D4.7 Expected Move ‹جدید›

```text
σ(h)          = realized volatility طلا در افق h
k(h)          = median( GVZ_implied / realized )  در پنجره ۲۵۰ روز
ExpectedMove  = k(h) · σ(h) · (2·P_direction − 1)
Distribution  = چندک‌های تجربی بازده افق h مشروط به Regime  → P5 P25 P50 P75 P95
```

شرط انتشار: فقط اگر `calibration_status = CALIBRATED`.

### D4.8 Kill-Switch ‹جدید›

```text
STATE = UNCERTAIN  اگر حداقل دو شرط هم‌زمان برقرار شوند:
  DataQuality_avg      < 0.60
  Regime               = REGIME_TRANSITION یا UNKNOWN
  ConflictRatio        > 0.40
  ModelDrift (Brier 60d) > Brier_baseline × 1.25
  Coverage(h)          < 0.40
```

## D5. Causal Layer ‹تنزل آگاهانه›

گراف علّی به **DAG اعلامی** تبدیل می‌شود و فقط دو کاربرد دارد:
۱) جلوگیری از Double Counting · ۲) مسیریابی `Event → Factor`.
هیچ ادعای علّیت آماری نمی‌کند. «Counterfactual Engine» به **Sensitivity Analysis** تغییر نام داد.

```text
Inflation → Fed Expectations → Yields → Real Yield → Gold
Geopolitical Shock ├→ Safe Haven → Gold
                   ├→ Oil → Inflation Expectations
                   └→ Liquidity Stress → USD
Fed ├→ Yield  ├→ DXY  └→ Real Yield → Gold
Gold → Inflation Expectations          ‹مسیر بازخوردی — علامت‌گذاری‌شده، از تخمین تک‌معادله‌ای خارج›
```

---

# بخش III — لایه داده رایگان ‹اصلاح‌شده›

## Free Stack — مرجع اجرایی v2

| لایه | منبع مرجع | Tier | `ip_sensitivity` | وضعیت |
|---|---|---|---|---|
| COMEX Gold OHLCV (پیوسته) | Yahoo `GC=F` | C | `UA_REQUIRED` | COVERED |
| **COMEX منحنی فوروارد** | Yahoo `GCZ26.CMX`, `GCG27.CMX`, `GCJ27.CMX`, `GCM27.CMX`, `GCZ27.CMX` | C | `UA_REQUIRED` | **COVERED ‹جدید›** |
| COMEX Settlement / Volume | Yahoo per-contract close و volume | C | `UA_REQUIRED` | PARTIAL |
| COMEX Open Interest | **CFTC فقط — هفتگی** | A | `NONE` | PARTIAL |
| ~~CME Daily Bulletin / DataMine~~ | **حذف شد — 403** | — | `DC_BLOCKED` | NOT_AVAILABLE |
| XAU/USD Spot | `gold-api.com` + Twelve Data + Alpha Vantage | B/C | `NONE` | COVERED |
| XAU/USD Tick | Dukascopy (کلاینت `dukascopy-node`) | C | `NONE` | COVERED (RESEARCH_ONLY) |
| Treasury Nominal | U.S. Treasury CSV + FRED | A | `NONE` | COVERED |
| Treasury Real | U.S. Treasury Real Yield Curve + FRED `DFII10` | A | `NONE` | COVERED (روزانه) |
| **Real Yield درون‌روزی** | `^TNX` + `TIP` → `PROXY` | PROXY | `UA_REQUIRED` | **PROXY ‹جدید›** |
| Treasury Auctions | TreasuryDirect API | A | `NONE` | COVERED |
| Term Premium | NY Fed ACM XLS | A | `NONE` | COVERED |
| Macro / Vintage | FRED + ALFRED | A | `NONE` | COVERED |
| Fed Expectations | `ZQ=F` / `SR1=F` / `SR3=F` + EFFR | C/A | `UA_REQUIRED` | COVERED |
| Fed Events | Fed RSS + FOMC Calendar + H.4.1/H.15 | A | `NONE` | COVERED |
| COT | CFTC Socrata `72hh-3qpy` / `6dca-aqww` | A | `NONE` | COVERED |
| News | Google News RSS + Benzinga + MarketWatch + CNBC + Mining.com + Fed RSS + Finnhub | C/D | `NONE` | COVERED |
| ~~GDELT~~ | **اختیاری — Circuit Breaker** | D | `DC_BLOCKED` | OPTIONAL |
| Geopolitical Risk Index | GPR XLS ماهانه + روزانه | B | `NONE` | COVERED (با تأخیر) |
| Economic Releases | BLS + BEA + FRED/ALFRED | A | `NONE` | COVERED |
| Consensus | Cleveland Nowcast + SPF + GDPNow + Philly | PROXY | `NONE` | PROXY |
| Gold ETF | WGC Goldhub XLSX + SPDR GLD API | B | `NONE` | COVERED |
| Central Bank Gold | WGC XLSX + IMF `api.imf.org` SDMX 2.1/3.0 | B/A | `NONE` | COVERED |
| Cross-Market | Yahoo `DX-Y.NYB` `SI=F` `BZ=F` `CL=F` `^GSPC` `^VIX` + Coinbase | C | `UA_REQUIRED` | COVERED |
| Options / Volatility | Cboe `GVZ_History.csv` + FRED `GVZCLS` + Cboe `GLD.json` | B/PROXY | `NONE` | COVERED |
| Credit / Stress | FRED `BAMLH0A0HYM2` `BAMLC0A0CM` `NFCI` `ANFCI` `STLFSI4` | A | `NONE` | COVERED |
| Recession Probability | NY Fed `allmonth.xls` + FRED `RECPROUSM156N` | A | `NONE` | COVERED |
| Global CB Policy | BIS `WS_CBPOL` + FRED | A | `NONE` | COVERED |
| EFFR / SOFR | NY Fed Markets API | A | `NONE` | COVERED |
| **FX چین/هند** | Yahoo `CNY=X` `CNH=X` `INR=X` | C | `UA_REQUIRED` | **PARTIAL ‹جدید›** |
| NLP داخلی | MinHash + spaCy + FinBERT + HDBSCAN | D | — | نیازمند پیاده‌سازی |

---

## 1. COMEX Gold Futures

### 1.1 OHLCV پیوسته

```bash
curl -A "$BROWSER_UA" "https://query1.finance.yahoo.com/v8/finance/chart/GC=F?period1=0&period2=$(date +%s)&interval=1d"
curl -A "$BROWSER_UA" "https://query1.finance.yahoo.com/v8/finance/chart/GC=F?range=8d&interval=1m"
```

> ❗ **`range=max` ممنوع است.** با `range=max&interval=1d` فقط ۲۶۸ کندل برمی‌گردد در حالی که با `period1/period2` همان نماد **۶٬۶۳۰ کندل از 2000-08-30** می‌دهد. پاسخ در هر دو حالت `HTTP 200` است؛ Quality Gate باید `meta.dataGranularity` را با `interval` درخواستی مقایسه کند.

| Interval | سقف واقعی | کندل اندازه‌گیری‌شده |
|---|---|---:|
| `1m` | **۸ روز در هر درخواست** (بیشتر ⇒ `HTTP 422`) | 10,810 |
| `5m` | ۶۰ روز | 17,141 |
| `15m` | ۶۰ روز | 5,715 |
| `1h` | ۷۳۰ روز | 17,396 |
| `1d` | از 2000-08-30 | 6,630 |

`1m` آرشیو تاریخی ندارد ⇒ Collector باید از امروز انباشت کند.

### 1.2 Settlement / Volume / Open Interest ‹بازنویسی‌شده›

```text
CME Daily Bulletin   → 403
CME CmeWS            → 403
CME ftp/bulletin     → 403
CME DataMine         → unreachable
⇒ CME به‌طور کامل از Data Contract خارج شد
```

| قلم | منبع v2 | فرکانس |
|---|---|---|
| Settlement | close قرارداد ماه‌دار `.CMX` | روزانه |
| Volume | `quote.volume` همان نماد | درون‌روزی |
| **Open Interest** | **CFTC — هفتگی** (`open_interest_all`) | **WEEKLY** |

> اصلاح معنایی: در v1 فرکانس Open Interest «Daily» اعلام شده بود. این اشتباه است و به `WEEKLY` تصحیح شد.

### 1.3 Tick / Bid / Ask

```bash
npx dukascopy-node -i xauusd -from 2020-01-01 -to 2026-10-01 -t tick -f csv
npx dukascopy-node -i xauusd -from 2020-01-01 -to 2026-10-01 -t m1   -f csv
```

`license_class = RESEARCH_ONLY` ⇒ فقط Backtest و استخراج Feature؛ هرگز در خروجی عمومی زنده.
مسیر مستقیم `.bi5` از قرارداد حذف شد (پاسخ **503**).

### 1.4 آنچه رایگان نیست

`MBO` / `MBP-10` لحظه‌ای CME. Depth واقعی از V1 حذف است. پراکسی‌های مجاز:

```text
Spread · Realized Volatility · Amihud Illiquidity · Roll Spread Estimator
Volume Clock · Kyle's Lambda تقریبی · Order Flow Proxy از تیک
```

### 1.5 منحنی فوروارد COMEX ‹بخش جدید — شکاف v1 را می‌بندد›

نمادهای ماه‌دار روی Yahoo با پسوند `.CMX` در دسترس‌اند (راستی‌آزمایی ۲۰۲۶-۱۰-۰۱):

| نماد | قیمت | تیک ۱ دقیقه‌ای (۵ روز) | تاریخچه روزانه |
|---|---:|---:|---|
| `GCZ26.CMX` | 4193.0 | ۵٬۴۲۹ کندل، با حجم | از 2020-12-30 (۱٬۴۵۰ کندل) |
| `GCG27.CMX` | 4229.2 | ✅ | ✅ |
| `GCJ27.CMX` | 4270.2 | ✅ | ✅ |
| `GCM27.CMX` | 4303.5 | ✅ | ✅ |
| `GCZ27.CMX` | 4433.5 | ✅ | ✅ |

> نماد بدون پسوند (`GCZ26`) پاسخ `Not Found` می‌دهد؛ پسوند `.CMX` الزامی است.

Featureهای فعال‌شده:

```text
Futures Basis            = F_front − Spot
Annualized Basis
Calendar Spread          = F_next − F_front
Term Structure Slope     = (F_12M − F_front) / F_front
Curve Shape              ∈ { CONTANGO , BACKWARDATION }
Per-contract Volume Profile
```

**سیاست Roll ‹الزامی›:** سری پیوسته برای Featureهای نوسان/مومنتوم با **Ratio-Adjustment** از همین نمادهای ماه‌دار ساخته می‌شود. `GC=F` خام فقط برای نمایش مجاز است و هیچ Feature آماری از آن ساخته نمی‌شود — در غیر این صورت جهش Roll به‌عنوان Shock واقعی تشخیص داده می‌شود.

---

## 2. Spot Gold

| نقش | منبع | نکته |
|---|---|---|
| Primary | `https://api.gold-api.com/price/XAU` | بدون کلید · **`has_bid_ask = false`** |
| Secondary | Twelve Data `XAU/USD` | سقف ۸۰۰/روز و ۸/دقیقه |
| Validation | Alpha Vantage `CURRENCY_EXCHANGE_RATE` یا `GOLD_SILVER_SPOT` | ۲۵/روز · کلید `demo` کار نمی‌کند |

```text
gold-api ────┐
Twelve Data ─┼→ Outlier Filter (MAD) → Reference Mid → Engine
Alpha Vantage┘
انحراف > 0.3% → Data Quality Alert
```

LBMA Historical از قرارداد خارج است؛ مرجع تاریخی بلندمدت = فایل میانگین‌های WGC.
Spread **فقط** از Dukascopy استخراج می‌شود، نه از منبع Primary.

---

## 3. Treasury / Yield Curve

### 3.1–3.2 Nominal و Real

```text
daily_treasury_yield_curve
daily_treasury_real_yield_curve
daily_treasury_real_long_term
```

> ❗ هدر واقعی فایل Real سال ۲۰۲۶: `Date, 5 YR, 7 YR, 10 YR, 20 YR, 30 YR`
> **کوتاه‌ترین سررسید واقعی ۵ ساله است.** هر ارجاع به «Real Yield ۲ ساله» از سند حذف شد.

### 3.3 Yield Curve

```text
سطوح: 2Y · 5Y · 10Y · 30Y        اسپردها: 10Y−2Y · 10Y−3M · 30Y−10Y
```

### 3.4 Treasury Auctions

```text
/TA_WS/securities/announced   /TA_WS/securities/auctioned   /TA_WS/securities/search
فیلدها: cusip · auctionDate · securityTerm · highYield · bidToCoverRatio
        · indirectBidderAccepted · somaAccepted
```

> `Tail` و `Dealer Allocation` در پاسخ TreasuryDirect تأیید نشده‌اند ⇒ `coverage_status = PARTIAL`.

### 3.5 Term Premium

```text
https://www.newyorkfed.org/medialibrary/media/research/data_indicators/ACMTermPremium.xls
```

`10Y Yield` و `Term Premium` همیشه دو Variable جدا می‌مانند.

### 3.6 Real Yield درون‌روزی — PROXY ‹بخش جدید›

Real Yield منبع رایگان درون‌روزی **ندارد**. تعریف PROXY:

| قلم | مقدار |
|---|---|
| ورودی‌ها | `^TNX` (۱٬۸۳۷ کندل ۱ دقیقه‌ای در ۵ روز) و `TIP` (۱٬۷۶۳ کندل) |
| پنجره اعتبار | **فقط ساعات نقدی آمریکا** (`09:30–16:00 ET`) |
| فرم | `RealYield_Intraday_Proxy = ^TNX − Breakeven_lastDaily` با تصحیح جهت از بازده `TIP` |
| `source_tier` | `PROXY` · `proxy_of = DFII10` |
| `data_quality` | سقف **0.60** |
| خارج از پنجره | F01 **Gate می‌شود** و `Coverage(h)` کاهش می‌یابد |

> هیچ‌گاه به‌جای `DFII10` در Backtest استفاده نمی‌شود؛ فقط ورودی زنده افق‌های کوتاه.

---

## 4. FRED / ALFRED

کلید رایگان الزامی است (بدون کلید ⇒ `HTTP 400`).

```text
DGS2 DGS5 DGS10 DGS30 DGS3MO T10Y2Y T10Y3M
DFII5 DFII10 DFII30 T5YIE T10YIE T5YIFR
EFFR DFF SOFR SOFR30DAYAVG DFEDTARU DFEDTARL
WALCL RRPONTSYD
BAMLH0A0HYM2 BAMLC0A0CM NFCI ANFCI STLFSI4 RECPROUSM156N
GVZCLS VIXCLS SP500 DTWEXBGS ECBDFR
```

قاعده Backtest: `availability_time ≤ backtest_time` · `vintage_capability = NATIVE`.

---

## 5. Fed Expectations

```text
implied_rate = 100 − futures_price        منابع: ZQ=F · SR1=F · SR3=F · EFFR رسمی NY Fed
خروجی: Expected_Rate_Change · Expected_Cuts · Expected_Hikes
       · Probability_NoChange · Expected_Rate_Path
```

Validation دوره‌ای در برابر خروجی عمومی FedWatch. API رسمی FedWatch نیازمند entitlement است و در قرارداد نیست.

---

## 6. Fed Events

```text
feeds/press_all.xml · press_monetary.xml · speeches.xml · testimony.xml
monetarypolicy/fomccalendars.htm · releases/h41 · releases/h15 · datadownload
```

RSS تاریخچه ندارد ⇒ `warmup_required = 180d` و ثبت اجباری `ingestion_time`.

برای Event Engine: `Expected · Actual · Surprise · Immediate Reaction · Secondary Reaction · Historical Reaction · Reaction Efficiency`.

---

## 7. COT / CFTC

| Dataset | شناسه | شروع | تعداد |
|---|---|---|---:|
| Disaggregated (مرجع) | `72hh-3qpy` | 2006-06-13 | **1,059 هفته** |
| Legacy (فقط Z-Score بلندمدت) | `6dca-aqww` | 1986-01-15 | **1,935 هفته** |

```text
market_and_exchange_names = "GOLD - COMMODITY EXCHANGE INC."
فیلدها: m_money_positions_long_all · m_money_positions_short_all · m_money_positions_spread
        open_interest_all · change_in_open_interest_all · prod_merc_* · swap_* · other_rept_* · nonrept_*
Position Date = سه‌شنبه     Publication = جمعه 15:30 ET
availability_time = report_date + publication_delay        ‹اجباری در Backtest›
Position_ZScore: پنجره ۱۵۶ هفته
```

---

## 8. News

### 8.1 جمع‌آوری ‹بازنویسی‌شده›

| منبع | نقش v2 |
|---|---|
| Google News RSS · Benzinga · MarketWatch · CNBC · Mining.com · Fed RSS | **مرجع Real-time** |
| Finnhub Free | مکمل (کلید لازم) |
| **GDELT DOC 2.0** | **اختیاری** — `HTTP 429` مستمر از IP نوع Datacenter. پشت Circuit Breaker: `on_429 → disable 24h`، هرگز وابستگی سخت |

قواعد: `≥60s` فاصله · فقط `عنوان + چکیده + لینک` ذخیره می‌شود · Body هرگز.

### 8.2 NLP داخلی

```text
News → Dedup(MinHash) → NER(spaCy) → Sentiment(FinBERT) → Event Classification
     → Clustering(HDBSCAN) → Severity → Novelty → Credibility → Gold Relevance
     → Geopolitical / Monetary / Economic Shock
```

### 8.3 GPR Index

```text
ماهانه: https://www.matteoiacoviello.com/gpr_files/data_gpr_export.xls
روزانه: https://www.matteoiacoviello.com/gpr_files/data_gpr_daily_recent.xls
```

Tier B · `revision_aware = true` · **فقط Benchmark و Calibration، نه Real-time**.

### 8.4 Narrative vs Reality ‹نرمتیو شد›

```text
Expected_Impact   از News Intelligence
Actual_Reaction   = بازده طلا در پنجره رویداد
Reaction_Efficiency = Actual_Reaction / Expected_Impact
Narrative_Divergence = |sign(Expected) − sign(Actual)| × |Expected_Impact|
⇒ خروجی NEWS Divergence در Divergence Engine
```

---

## 9. Economic Releases

```text
BLS API · BEA API · FRED · ALFRED · Cleveland Fed Nowcast · SPF · Atlanta GDPNow · Philly Fed
ذخیره: Expected · Actual · Previous · First Released · Revision · Vintage · Release Timestamp
Surprise            = Actual − Expected_Proxy
Normalized Surprise = Surprise / RMSE_Philly
consensus_kind      = PROXY        ‹اجباری›
```

**ISM**: منبع رایگان مستقیم ندارد. جایگزین صریح با برچسب `PROXY_ISM`: Philadelphia Fed MBOS، Empire State، Dallas Fed. هرگز با عنوان «ISM» منتشر نشود.

---

## 10. Gold ETF / Structural

```text
WGC Goldhub XLSX  (ETF Flows · Official Holdings · Reserve Changes · Demand by Country
                   · Trading Volumes · Open Interest · Futures Curves · Historical Averages)
SPDR GLD: https://api.spdrgoldshares.com/api/v1/historical-archive?product=gld&exchange=NYSE&lang=en
```

قواعد: Parse پویای صفحه والد (نام فایل تاریخ‌دار Hard-Code نشود) · `vintage_capability = SNAPSHOT_ONLY` ⇒ هر دانلود با `vintage_date` بایگانی و **بازنویسی ممنوع** · IAU فقط Validation ثانویه.

### 10.1 IMF

```text
https://api.imf.org/external/sdmx/2.1/      https://api.imf.org/external/sdmx/3.0/
```

`dataservices.imf.org` از ۵ نوامبر ۲۰۲۵ بازنشسته است.
Rate limit ≈ ۲ req/ثانیه.
❗ Quality Gate اجباری: **`HTTP 200` با dataset خالی ⇒ REJECT + ALERT** (خطای خاموش کلید اشتباه).

---

## 11. Cross-Market

| دارایی | منبع |
|---|---|
| DXY | Yahoo `DX-Y.NYB` |
| USD Broad ‹Feature مستقل، نه اعتبارسنج DXY› | FRED `DTWEXBGS` |
| Silver | Yahoo `SI=F` |
| Brent / WTI | Yahoo `BZ=F` / `CL=F` + EIA (کلید لازم) |
| S&P 500 | Yahoo `^GSPC` / FRED `SP500` |
| VIX | Yahoo `^VIX` / FRED `VIXCLS` |
| Bitcoin | Coinbase / CoinGecko (Binance در UK/EU مسدود) |
| **CNY / CNH / INR** | Yahoo `CNY=X` · `CNH=X` · `INR=X` ‹جدید› |
| Gold/Silver Ratio · CNH−CNY Spread | محاسبه داخلی |

> اصلاح v1: `DTWEXBGS` سبد متفاوتی از DXY دارد و **نمی‌تواند اعتبارسنج آن باشد**؛ به Feature مستقل تبدیل شد.

منابع توصیه‌نشده: `Stooq` · `Nasdaq Data Link/CHRIS` · `api.metals.live` · `Yahoo XAUUSD=X` · `LBMA JSON`.

---

## 11A. Options / Volatility

```text
تاریخچه IV طلا: https://cdn.cboe.com/api/global/us_indices/daily_prices/GVZ_History.csv
                 → 4,283 رکورد از 09/18/2009          Tier B · مستقیم
زنجیره کامل:    https://cdn.cboe.com/api/global/delayed_quotes/options/GLD.json
                 → 8,140 قرارداد · 3.41 MiB · تأخیر ~15 دقیقه · PROXY
لحظه‌ای:        Yahoo ^GVZ · FRED GVZCLS
```

مشتقات: `ATM IV · Put/Call (Volume و OI) · 25Δ Risk Reversal · IV Skew/Smile · IV Term Structure · OI Distribution`.

| قاعده | مقدار |
|---|---|
| `proxy_of` | `COMEX_GC_OPTIONS` (ETF Options ≠ Futures Options) |
| **`warmup_required`** | **۲۵۰ روز معاملاتی** — زنجیره هیچ تاریخچه‌ای ندارد؛ تا انباشت، Featureهای Skew/RR/Term وارد امتیاز نمی‌شوند |
| ذخیره‌سازی | فقط قراردادهای با `volume > 0 یا OI > 0` + فشرده‌سازی zstd |
| Yahoo `v7/finance/options` | ممنوع (نیازمند Cookie+Crumb) |
| CME QuikStrike | API ندارد — خارج از قرارداد |

---

## 11B. Credit / Stress / Recession / Global Policy

```text
BAMLH0A0HYM2 · BAMLC0A0CM · BAMLH0A0HYM2EY · NFCI · ANFCI · STLFSI4
TED منسوخ ⇒ اسپردهای مبتنی بر SOFR

Recession: https://www.newyorkfed.org/medialibrary/media/research/capital_markets/allmonth.xls
           FRED RECPROUSM156N

Global Policy: https://stats.bis.org/api/v2/data/dataflow/BIS/WS_CBPOL/1.0/D.{AREA}?format=csv
               + FRED ECBDFR و معادل‌های BOJ/BOE
```

CDX/Markit رایگان نیست؛ اسپردهای FRED جایگزین مستقیم (نه Proxy) برای F18.

---

## 12. معماری Ingestion

```text
FREE DATA PROVIDERS
  → DATA INGESTION SERVER (JVM, 24/7)
      · UA Policy · Retry/Backoff · Rate-Limit Guard · Circuit Breaker
      · Central Cache · Raw Payload Archive (90d)
  → NORMALIZATION → RAW DATA STORE → CROSS-VALIDATION / QUALITY
  → FEATURE ENGINEERING → GOLD ENGINE → GOLD INTELLIGENCE STATE
  → REST / WebSocket API → ANDROID APP
```

### 12.1 Polling Baseline ‹بازنویسی‌شده — سازگار با سقف‌های واقعی›

| منبع | v1 | **v2** | بودجه روزانه | دلیل اصلاح |
|---|---|---|---:|---|
| gold-api | 10s | **15s** | 5,760 | سقف اعلام‌نشده؛ کاهش ریسک |
| Yahoo `GC=F` | 60s | **60s فقط در ساعات بازار** | ~1,400 | کنترل بودجه |
| Yahoo منحنی فوروارد (۵ نماد) | — | **300s** | 1,440 | جدید |
| Yahoo `ZQ/SR1/SR3` | 5m | **5m** | 864 | — |
| Yahoo Cross-Market (۷ نماد) | 60s | **120s** | 5,040 | کاهش از ۱۰٬۰۸۰ |
| **Twelve Data** | **60s ❌** | **120s** | **720** | **سقف ۸۰۰/روز؛ v1 ناقض بود** |
| Alpha Vantage | 2×/day | 2×/day | 2 | — |
| Fed RSS | 30s | 30s | 2,880 | — |
| News RSS (۵ فید) | — | 120s | 3,600 | — |
| GDELT | ≥60s | **اختیاری ≥60s + CB** | ≤1,440 | 429 |
| Cboe GVZ CSV | روزانه | روزانه | 1 | — |
| Cboe GLD Options | 15m | 15m (ساعات بازار) | ~26 | — |
| COT | weekly | weekly | — | — |
| Treasury / FRED | روزانه | روزانه | — | — |
| WGC XLSX | دوره‌ای | دوره‌ای + بایگانی vintage | — | — |
| NY Fed EFFR/SOFR | روزانه | روزانه | — | — |
| BIS | روزانه | روزانه | — | — |
| GPR XLS | هفتگی | هفتگی | — | — |

**بودجه کل Yahoo: ≈ ۸٬۷۴۰ req/روز** (در برابر ≈۱۲٬۳۸۴ در v1).

### 12.2 Backtest Integrity

```text
event_time · release_time · ingestion_time · availability_time · first_release · revision · vintage
Point-in-Time + First Released + ALFRED Vintage + Walk-Forward + Out-of-Sample + No Look-Ahead
joint_history_floor = دیرترین data_since میان Featureهای فعال   ‹باید در خروجی گزارش شود›
```

### 12.3 Key Security و UA Policy

```text
کلیدها: FRED · Twelve Data · Finnhub · Alpha Vantage · EIA · BLS · BEA
محل: فقط Secret Store سرور. هرگز در APK، هرگز در مخزن.
معماری: Android → Your Server → Free Providers
UA: هر درخواست Yahoo باید User-Agent مرورگر واقعی و Accept-Language داشته باشد.
    بدون آن پاسخ HTTP 429 است.
```

### 12.4 Quality Gates ‹۸ گیت v1 + ۳ گیت جدید›

```text
[1]  بدون timestamp                      → REJECT
[2]  بدون source                          → REJECT
[3]  واحد نامعتبر                         → REJECT
[4]  واگرایی بین‌منبعی > 0.3%              → ALERT
[5]  Missing ≠ 0                          → Gate + کسر Coverage
[6]  Revision-aware                       → اجباری
[7]  Rate-limit aware                     → اجباری
[8]  License-aware                        → اجباری
[9]  HTTP 200 با dataset خالی             → REJECT + ALERT     ‹جدید — IMF/SDMX›
[10] meta.dataGranularity ≠ interval      → REJECT             ‹جدید — Yahoo›
[11] timestamp > now + clock_skew_tol     → REJECT             ‹جدید›
```

---

## 13. Coverage Matrix v2

| Factor | منبع رایگان | نوع | وزن | `min_horizon` | وضعیت |
|---|---|---|---:|---|---|
| F01 Real Yield | Treasury Real + FRED `DFII10` · PROXY درون‌روزی | مستقیم + PROXY | 0.200 | `1D` | COVERED / PROXY |
| F02 USD | Yahoo `DX-Y.NYB` | مستقیم | 0.150 | `5m` | COVERED |
| F03 Fed Expectations | `ZQ/SR1/SR3` + EFFR | مشتق | 0.100 | `5m` | COVERED |
| F04 Treasury Curve | Treasury + FRED + `^TNX` | مستقیم | 0.040 | `5m` | COVERED |
| F05 Inflation | BLS/BEA/FRED + Breakevens | مستقیم | 0.060 | `1D` | COVERED |
| F06 Economic Surprise | BLS/BEA + Nowcast/SPF/GDPNow/Philly | PROXY | 0.050 | `1D` | PARTIAL |
| F07 Geopolitical Risk | RSS + NLP + GPR | مشتق | 0.060 | `1H` | COVERED |
| F08 Financial Stress | FRED `NFCI`/`STLFSI4` | مستقیم | 0.030 | `1D` | COVERED |
| F09 Gold ETF Flow | WGC + SPDR | مستقیم | 0.060 | `1D` | COVERED |
| F10 Central Bank Demand | WGC + IMF | مستقیم | 0.040 | `1W` | COVERED |
| F11 Futures Positioning | CFTC | مستقیم | 0.060 | `1W` | COVERED |
| F12 Physical Demand | WGC + USGS | مستقیم | 0.008 | `1W` | COVERED |
| F13 Market Momentum | `.CMX` روی سری Ratio-Adjusted | مشتق | 0.020 | `5m` | COVERED |
| F14 Microstructure | Dukascopy + حجم `.CMX` | PROXY | 0.010 | `5m` | PARTIAL |
| F15 Options / Volatility | GVZ + زنجیره GLD | مستقیم + PROXY | 0.010 | `15m` | COVERED (warmup 250d) |
| F16 Cross-Asset | Yahoo + FRED + Coinbase | مشتق | 0.010 | `5m` | COVERED |
| F17 Liquidity | FRED `WALCL`/`RRPONTSYD`/SOFR | مستقیم | 0.020 | `1D` | COVERED |
| F18 Credit | FRED OAS + NFCI | مستقیم | 0.010 | `1D` | COVERED |
| F19 China | WGC + `CNY=X`/`CNH=X` + اسپرد CNH−CNY | مستقیم + PARTIAL | 0.006 | `1W` | PARTIAL |
| F20 India | WGC + `INR=X` | مستقیم + PARTIAL | 0.006 | `1W` | PARTIAL |
| F21 Oil / Energy | Yahoo `BZ=F`/`CL=F` + EIA | مستقیم | 0.020 | `5m` | COVERED |
| F22 Global CB Policy | BIS `WS_CBPOL` + FRED | مستقیم | 0.030 | `1D` | COVERED |

---

## 14. شکاف‌های واقعی ‹به‌روزرسانی v2.1 — پس از کشف منابع رایگان جایگزین›

| # | شکاف | وضعیت v2 | وضعیت v2.1 | منبع زنده‌ی جایگزین |
|---|---|---|---|---|
| Gap 1 | عمق دفتر سفارش (L2) | شکاف | **✅ حل شد — PROXY معتبر** | Kraken `PAXG/USD` Depth(100) + OKX `XAUT-USDT` books(100) — هر توکن = ۱ اونس تحویل‌پذیر؛ Swissquote BBO به تفکیک Size Tier |
| Gap 2 | Consensus تحلیلگران | شکاف | **✅ حل شد** | `nfs.faireconomy.media/ff_calendar_thisweek.json` (Forecast نظرسنجی‌شده) + Actual از BLS |
| Gap 3 | بنچمارک LBMA | شکاف | **✅ حل شد — ماهانه** | `fsapi.gold.org` سری بنچمارک مبتنی بر LBMA منتشرشده توسط WGC |
| Gap 4 | Open Interest روزانه COMEX | شکاف جدید | **✅ حل شد** | `scanner.tradingview.com/global/scan` ستون `open_interest` برای `COMEX:GC1!` و قراردادهای تاریخ‌دار |
| Gap 5 | Premium فیزیکی چین و هند | شکاف جدید | **✅ حل شد** | SGE Au(T+D) از Sina/Eastmoney · `SHFE:AU1!` · `MCX:GOLD1!` — تبدیل با نرخ مرجع ECB |
| Gap 6 | GDELT بلادرنگ از محیط سروری | شکاف جدید | **باقی — اختیاری** | 429 مستمر؛ GPR از پریمیوم GVZ/VIX |
| ~~Gap 7~~ | ~~منحنی فوروارد COMEX~~ | ✅ حل شد | **✅ حل شد — مستقل از Yahoo** | قراردادهای تاریخ‌دار `GCV2026…GCZ2027` از همان scanner |
| Gap 8 | DXY واقعی (به‌جای DXY ترکیبی) | PROXY | **✅ حل شد** | `TVC:DXY` — مقدار منتشرشده‌ی ICE |
| Gap 9 | **تقاضای بانک مرکزی (F10)** | شکاف | **باقی — تنها شکاف حل‌نشده** | آمار فصلی فقط به‌صورت سند؛ IMF IFS و SDMX مسدود/تایم‌اوت |
| Gap 10 | تطبیق آنالوگ تاریخی | شکاف | **باقی** | مجموعه‌داده‌ی اپیزودهای برچسب‌خورده وجود ندارد |

**قاعده‌ی گزارش:** هر شکاف — حل‌شده یا باقی — در صفحه Diagnostics با وضعیت (`RESOLVED` / `UNRESOLVED`)، منبع زنده و مقدار جاری نمایش داده می‌شود. هیچ شکافی پنهان نمی‌شود و هیچ مقداری جایگزین‌سازی نمی‌شود.

از ۱۰ شکاف، ۸ مورد با منابع کلیدلس و رایگان بسته شده‌اند؛ F10 و آنالوگ تاریخی صراحتاً به‌عنوان حل‌نشده اعلام می‌شوند.

---

## 15. Attribution ‹بازنویسی v2.1 — محدودیت انتشار حذف شد›

| منبع | `license_class` | قاعده |
|---|---|---|
| Treasury · Fed · CFTC · BLS · NY Fed | `PUBLIC_DOMAIN` | ذکر منبع توصیه‌شده |
| WGC / Goldhub · Cboe · ECB/Frankfurter · gold-api · TradingView · Kraken · OKX · Swissquote · SGE(Sina/Eastmoney) · ForexFactory | `ATTRIBUTION` | Attribution اجباری در فایل `NOTICE` و در پانویس اپ |
| Yahoo Finance | `ATTRIBUTION` | منبع اختیاری و پشتیبان؛ Circuit Breaker فعال |
| ~~CME~~ | — | **حذف شد** |

**قاعده v2.1:** دسته‌بندی لایسنس صرفاً برای **اِسناد و ردیابی منشأ** نگهداری می‌شود. هیچ فیلدی بر مبنای `license_class` از نمایش یا از خروجی API حذف نمی‌شود؛ `raw_publishable` همواره `true` است. هر مقدار همراه با منبع، کلاس لایسنس، زمان دریافت و Tier کیفیت نمایش داده می‌شود.

---

## 16. چک‌لیست اجرایی v2

### کلیدها
```text
[ ] FRED  [ ] Twelve Data  [ ] Finnhub  [ ] Alpha Vantage
[ ] EIA ‹اضافه‌شده›  [ ] BLS  [ ] BEA  [ ] CFTC Socrata token (اختیاری)
```

### Collectorها
```text
[ ] Yahoo GC=F (period1/period2 — بدون range=max)
[ ] Yahoo منحنی فوروارد GCZ26/GCG27/GCJ27/GCM27/GCZ27 .CMX  ‹جدید›
[ ] Yahoo ZQ=F / SR1=F / SR3=F
[ ] Yahoo DX-Y.NYB / SI=F / BZ=F / CL=F / ^GSPC / ^VIX / ^TNX / TIP / BTC-USD
[ ] Yahoo CNY=X / CNH=X / INR=X  ‹جدید›
[ ] Spot 3-source + MAD filter
[ ] Treasury daily (nominal + real)
[ ] FRED / ALFRED vintage collector
[ ] CFTC Gold (Disaggregated + Legacy)
[ ] Fed RSS + News RSS (۵ فید)
[ ] Cboe GVZ History CSV
[ ] Cboe GLD Options (ذخیره دلتا‌ای + zstd)
[ ] GPR XLS (ماهانه + روزانه)
[ ] NY Fed EFFR / SOFR / ACM / Recession
[ ] BIS WS_CBPOL
[ ] FRED Credit/Stress
[ ] WGC XLSX dynamic parser + vintage archive
[ ] SPDR GLD
[ ] IMF SDMX 2.1/3.0
[ ] Dukascopy backfill (dukascopy-node)
[ ] GDELT ‹اختیاری، پشت Circuit Breaker›
```

### دروازه‌ها و زیرساخت
```text
[ ] ۱۱ Quality Gate (§12.4)
[ ] UA Policy برای Yahoo
[ ] Circuit Breaker + Exponential Backoff
[ ] Central Cache
[ ] Raw Payload Archive (90d)
[ ] Point-in-Time / Vintage Store
[ ] NTP + ثبت clock_skew
```

---

## 17. قرارداد API ‹بخش جدید›

```text
GET  /v1/state?horizon={5m|15m|1H|4H|1D|1W}      → HorizonState
GET  /v1/state/all                                → Map<Horizon, HorizonState>
GET  /v1/factors                                  → 22 × FactorView
GET  /v1/indicators                               → N × IndicatorView
GET  /v1/events?from=&to=                         → EventView[]
GET  /v1/news?since=                              → NewsView[]
GET  /v1/diagnostics                              → SourceHealth[] + budget + staleness
WS   /v1/stream                                   → push در هر بازمحاسبه
```

### مدل‌های خروجی

```text
HorizonState {
  horizon · direction · probability? · calibration_status
  confidence · coverage · regime · regime_stability
  dominant_factor · primary_drivers[] · contradictions[] · conflict
  cross_market_confirmation · news_state · liquidity · shock
  expected_move? · distribution{p5,p25,p50,p75,p95}?
  scenarios[] · invalidation · signal_state · as_of
}

FactorView {
  factor_id · name_fa · name_en · group
  score(−100..+100) · base_weight · effective_weight · gated(bool)
  quality · source_tier · coverage_status · min_horizon
  contribution · event_time · latency · staleness
}

IndicatorView {
  variable_id · name_fa · name_en · factor_id
  raw_value · unit · change · z_score
  source · source_tier · license_class
  event_time · release_time · latency · frequency
  quality · coverage_status · staleness · data_since
}
```

**قاعده الزامی:** هیچ `IndicatorView` با `license_class ∈ {UNOFFICIAL_NO_REDIST, FREE_TIER_NO_REDIST, RESEARCH_ONLY}` مقدار `raw_value` منتشر نمی‌کند؛ فقط `z_score` و `change`.

---

## 18. سطح نمایش شاخص‌ها در اپ ‹بخش جدید — الزام محصول›

وضعیت فعلی اپ: یک `Activity`، ۱۷ خط متن، تغذیه از `SampleData`، **بدون هیچ شاخصی**، **بدون مجوز `INTERNET`**.

الزام v2: **تمام ۲۲ فاکتور و تمام شاخص‌های زیرین باید در اپ قابل مشاهده باشند.**

### 18.1 ساختار هفت‌صفحه‌ای ‹v2.1›

| # | صفحه | محتوا |
|---|---|---|
| 1 | **STATE** | جهت · احتمال (یا `UNCALIBRATED`) · Confidence · Coverage · Regime + پایداری · Dominant Factor · Conflict · Liquidity · Shock · Signal State · Kill-Switch |
| 2 | **FACTORS** | **۲۲ ردیف** — `name_fa` · امتیاز −۱۰۰..+۱۰۰ (نوار رنگی) · وزن پایه · وزن مؤثر · Gated · Quality · Tier · سهم در Bias · زمان آخرین به‌روزرسانی |
| 3 | **INDICATORS** | **تمام شاخص‌ها** گروه‌بندی‌شده بر اساس فاکتور — مقدار · Δ · z-score · واحد · منبع · Tier · `event_time` · Latency · Quality · Staleness |
| 4 | **HORIZONS** | **۶ ردیف** — افق · جهت · احتمال/`UNCALIBRATED` · Coverage · Confidence · Expected Move · P5…P95 |
| 5 | **EVENTS & NEWS** | تقویم پیش‌رو · Expected/Actual/Surprise · واکنش بازار · Reaction Efficiency · Narrative Divergence |
| 6 | **DIAGNOSTICS** | سلامت هر منبع · بودجه Rate-Limit مصرف‌شده · کهنگی · `joint_history_floor` · Attribution منابع · **عمق بازار (L2 + Size Tier)** · **منحنی فوروارد COMEX** · **وضعیت تک‌تک شکاف‌های §14 با منبع زنده** |
| 7 | **LOGS** | صفحهٔ مستقل گزارش‌گیر — §21 |

### 18.2 فهرست شاخص‌های الزامی صفحه ۳

| گروه | شاخص‌ها |
|---|---|
| F01 Real Yield | `DFII10` · `DFII5` · Δ · Trend · Z-Score · `RealYield_Intraday_Proxy` |
| F02 USD | DXY · Δ · Trend · Z-Score · `USD_Broad (DTWEXBGS)` |
| F03 Fed | Implied Rate از `ZQ/SR1/SR3` · Expected Cuts/Hikes · P(NoChange) · Rate Path · FOMC Surprise |
| F04 Curve | `DGS2` `DGS5` `DGS10` `DGS30` `DGS3MO` · `T10Y2Y` `T10Y3M` · `^TNX` · Term Premium (ACM) |
| F05 Inflation | CPI · Core CPI · PCE · Core PCE · `T5YIE` `T10YIE` `T5YIFR` · Surprise هر کدام |
| F06 Growth | GDP · NFP · Unemployment · `PROXY_ISM` · Recession Probability · Economic Surprise Index |
| F07 Geopolitical | GPR Level · GPRD · Z-Score · News Shock Score · Severity/Novelty/Credibility |
| F08 Stress | VIX · `VIXCLS` · `NFCI` · `ANFCI` · `STLFSI4` · Equity Drawdown |
| F09 ETF | GLD Holdings · GLD Flow · WGC Total ETF Flow · Z-Score |
| F10 Central Bank | Net Purchases · 3M Net Buying · Official Holdings (کشور) |
| F11 Positioning | Managed Money Long/Short/Net · Open Interest ‹هفتگی› · Position Z-Score · Extreme Flags |
| F12 Physical | Jewellery · Mine Production · Recycling · Total Demand |
| F13 Momentum | Gold Return · Momentum · Trend · Realized Volatility · Volume |
| F14 Microstructure | Spread · Amihud · Roll Estimator · Order-Flow Proxy · Volume Clock |
| F15 Options | GVZ · ATM IV · Put/Call (Vol و OI) · 25Δ RR · IV Skew · IV Term Structure |
| F16 Cross-Asset | Gold/Silver · Gold/Oil · Gold/SPX · Corr(Gold,DXY) · Corr(Gold,RealYield) · Corr(Gold,VIX) |
| F17 Liquidity | SOFR · EFFR · `WALCL` · `RRPONTSYD` · Dollar Funding Stress |
| F18 Credit | HY OAS · IG OAS · HY Effective Yield |
| F19 China | WGC China Demand · `CNY=X` · `CNH=X` · **اسپرد CNH−CNY** |
| F20 India | WGC India Demand · `INR=X` |
| F21 Oil | Brent · WTI · Oil Momentum · Oil Volatility |
| F22 Global CB | Fed · ECB · BOJ · BOE · SNB · PBOC Policy Rate · Fed−ECB · Fed−BOJ |
| Market Structure | Futures Basis · Annualized Basis · Calendar Spread · Term Structure Slope · Curve Shape |

### 18.3 قواعد نمایش الزامی

```text
[ ] هر مقدار PROXY با نشان «PROXY» و رنگ متمایز
[ ] هر فاکتور Gated با نشان «GATED @ {horizon}» و امتیاز خاکستری
[ ] Staleness در هر ردیف:  fresh ( < 1× frequency ) · stale ( 1–3× ) · expired ( > 3× )
[ ] هیچ احتمال عددی در 5m/15m نمایش داده نشود — فقط «UNCALIBRATED»
[ ] در حالت Kill-Switch کل صفحه ۱ به «STATE = UNCERTAIN» تغییر کند
[ ] هیچ raw_value با license محدود نمایش داده نشود
[ ] صفحه About با Attribution کامل منابع
[ ] دوزبانه fa/en با استفاده از FactorCatalog.nameFa
```

### 18.4 الزامات فنی اپ

```text
[ ] <uses-permission android:name="android.permission.INTERNET" />
[ ] android:usesCleartextTraffic="false"
[ ] ماژول :client برای قرارداد §17
[ ] Repository → ViewModel → StateFlow ، ارزیابی روی Dispatchers.Default
[ ] SampleData فقط در src/debug ، حذف از APK ریلیز
[ ] buildTypes.release: minify + proguard + signingConfig از متغیر محیطی
[ ] Theme.Material3 + values-night + values-fa
[ ] ابعاد dp + contentDescription
```

---

## 19. رژیم اعتبارسنجی آماری ‹نرمتیو شد›

| قاعده | مقدار |
|---|---|
| حداقل نمونه مدل رویدادی | `n ≥ 30` وگرنه `INSUFFICIENT_INFORMATION` |
| حداقل نمونه برای Calibration | `n ≥ 500` در هر افق وگرنه `UNCALIBRATED` |
| خانواده‌های Regime برای وزن‌دهی | **۶** (`MONETARY`, `INFLATION`, `RISK`, `LIQUIDITY`, `GROWTH`, `NORMAL`) — ۱۱ برچسب فقط برای نمایش |
| Regime با `n < 100` | در `NORMAL` ادغام می‌شود |
| Walk-Forward | `Train 2018–2023 → Test 2024` · `2019–2024 → 2025` · `2020–2025 → 2026` |
| معیارها | Directional Accuracy · Precision · Recall · **Brier** · **Log Loss** · Calibration Error · Expected Value · Max Drawdown |
| ❌ معیار ناکافی | Win Rate به‌تنهایی |
| Lead-Lag | تا `Lag 20` روی `1h` · تا `Lag 5` روی `5m` |
| Drift Detection | فقط روی Featureهای با `data_since ≤ now − 3y` |
| Historical Analogue | **`NOT_AVAILABLE`** تا ساخت پنل Point-in-Time |
| هر پارامتر کالیبراسیون | `source ∈ {SPEC_VERBATIM, PRIOR, FITTED}` — `PRIOR`ها در خروجی علامت می‌خورند |

---

## 20. وضعیت نهایی Compliance

```text
Macro Core                 = FREE
Treasury Nominal / Real    = FREE (روزانه)
Real Yield درون‌روزی        = PROXY (^TNX + TIP، فقط ساعات نقدی آمریکا)
Fed Expectations           = FREE via local calculation
Fed Events                 = FREE
COT / Positioning          = FREE (هفتگی)
Spot Gold                  = FREE multi-source
COMEX Forward Curve        = FREE  ‹جدید›
COMEX Open Interest        = WEEKLY only  ‹تنزل یافته›
ETF / Central Bank         = FREE
Cross-Market               = FREE
News Collection            = FREE (GDELT اختیاری)
Economic Releases          = FREE / Proxy برای Consensus
Vintage Backtest           = FREE برای FRED ، SNAPSHOT_ONLY برای بقیه
Term Premium               = FREE
Treasury Auctions          = FREE (بدون Tail)
Options / Volatility       = FREE (GVZ مستقیم + GLD به‌عنوان PROXY، warmup 250d)
Credit Conditions          = FREE via FRED
Geopolitical Risk          = FREE (GPR + RSS/NLP)
Recession Probability      = FREE
Global CB Policy Rates     = FREE
IMF Reserves               = FREE

CME Live Order Book        = NOT FREE
CME Daily Bulletin/DataMine= BLOCKED (403)
True Bloomberg/Reuters Consensus = NOT FREE
Historical LBMA Benchmark  = NOT PUBLICLY FREE
SGE / Shanghai Premium     = NO VERIFIED FREE SOURCE
India Physical Premium     = NO VERIFIED FREE SOURCE
GDELT از IP نوع Datacenter  = BLOCKED (429)
```

---

## پیوست — اعداد راستی‌آزمایی‌شده (۲۰۲۶-۱۰-۰۱)

| قلم | مقدار |
|---|---|
| `GC=F` تاریخچه روزانه | ۶٬۶۳۰ کندل، از 2000-08-30 |
| `GC=F` سقف `1m` | ۸ روز در هر درخواست |
| `GC=F` سقف `5m`/`15m` | ۶۰ روز |
| `GC=F` سقف `1h` | ۷۳۰ روز |
| `GCZ26.CMX` | ۴۱۹۳.۰ · ۵٬۴۲۹ کندل ۱ دقیقه‌ای/۵ روز · تاریخچه از 2020-12-30 |
| منحنی فوروارد | Dec26 ۴۱۹۳.۰ → Dec27 ۴۴۳۳.۵ (Contango) |
| `GVZ_History.csv` | ۴٬۲۸۳ رکورد، از 09/18/2009 |
| `GLD.json` | ۸٬۱۴۰ قرارداد · ۳.۴۱ MiB |
| CFTC Disaggregated | ۱٬۰۵۹ هفته، از 2006-06-13 |
| CFTC Legacy | ۱٬۹۳۵ هفته، از 1986-01-15 |
| Treasury Real هدر | `Date, 5 YR, 7 YR, 10 YR, 20 YR, 30 YR` |
| `^TNX` / `TIP` ۱ دقیقه‌ای | ۱٬۸۳۷ / ۱٬۷۶۳ کندل در ۵ روز |
| `CNY=X` / `CNH=X` / `INR=X` | ۶.۶۹۸۷ / ۶.۷۱۸۳ / ۹۶.۳۰۵ |
| CME (همه مسیرها) | **403** |
| GDELT (۳ تلاش) | **429** |
| Yahoo بدون UA مرورگر | **429** |
| FRED بدون کلید | **400** |

---

*پایان سند v2.*

---

## 21. گزارش‌گیر عملیاتی ‹بخش جدید v2.1 — الزام محصول›

### 21.1 الزام

هر شکست در دریافت، پارس، اعتبارسنجی یا محاسبه باید **نام شاخص** و **علت دقیق** را ثبت کند. هیچ شکستی نباید خاموش باشد و هیچ مقداری نباید جایگزین شود.

### 21.2 ساختار رکورد

| فیلد | توضیح |
|---|---|
| `sequence` | شمارنده یکنوا |
| `timestamp` | UTC با دقت میلی‌ثانیه |
| `level` | `TRACE` · `DEBUG` · `INFO` · `WARN` · `ERROR` |
| `stage` | `NETWORK` · `PARSE` · `QUALITY` · `FEATURE` · `FACTOR` · `ENGINE` · `RENDER` |
| `component` | شناسهٔ Provider یا موتور |
| `code` | کد ماشین‌خوان: `HTTP_403` · `JSON_MALFORMED` · `GATE_G05_NOT_EXPIRED` · `VALUE_ABSENT` · `NO_FREE_SOURCE` · `DEGRADED` · `INGESTED` · `BACKUP_USED` |
| `message` | شرح انسانی |
| `key` | **شناسهٔ شاخص یا فاکتوری که رکورد به آن مربوط است** |
| `url` · `httpStatus` · `latencyMillis` · `detail` | زمینهٔ تشخیصی |

حلقهٔ حافظه با ظرفیت ثابت؛ تعداد رکوردهای حذف‌شده (`dropped`) گزارش می‌شود تا هیچ از دست رفتنی پنهان نماند.

### 21.3 سطح نمایش

صفحهٔ **LOGS** یک تب مستقل است و شامل پنج بخش است: `Summary` · `By Stage` · `Failures` · `Per-Indicator Status` · `Full Trace`.

**قاعدهٔ تفکیک — الزامی:** صفحهٔ LOGS هیچ مقدار بازاری نمایش نمی‌دهد و شش صفحهٔ تحلیلی هیچ خط لاگی نمایش نمی‌دهند. این قاعده با تست خودکار تضمین شده است.

### 21.4 خروجی‌گیری

| مقصد | مکانیزم |
|---|---|
| کلیپ‌بورد | `ClipboardManager` — متن کامل |
| فایل `.md` | `ACTION_CREATE_DOCUMENT` با `type=text/markdown`؛ در نبود Document Provider، نوشتن در `getExternalFilesDir` |
| فایل `.txt` | همان مسیر با `type=text/plain` |
| سرور | `GET /v1/logs.md` · `GET /v1/logs.txt` با `Content-Disposition: attachment` · `GET /v1/logs.json?level=&stage=&key=&q=&limit=` |

سرآیند هر خروجی شامل نسخهٔ Spec، مدل دستگاه/منبع، زمان آخرین به‌روزرسانی، فیلتر فعال و شمارش سطوح است.

### 21.5 فیلتر

فیلتر متنی روی `key` / `code` / `component` / `message` و انتخاب حداقل سطح (`ERROR` / `WARN` / `INFO` / `DEBUG`) در اپ و در `/v1/logs.json` یکسان عمل می‌کند.
