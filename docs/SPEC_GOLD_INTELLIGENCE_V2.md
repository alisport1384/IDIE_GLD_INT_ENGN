# Gold Intelligence Engine — Free Data Edition **v2.1 (Corrected)**

> نسخه اصلاح‌شده «شاخص های طلا — نسخه رایگان نهایی» پس از اعمال ۱۰۹ قلم اصلاحی گزارش محدودیت‌ها.
> تاریخ: 2026-10-01 · بازبینی v2.1: 2026-10-02 · مرجع اصلاحات: `REMEDIATION_PLAN.md` · مخزن: `IDIE_GLD_INT_ENGN`
>
> **معماری تحلیلی سند v1 بدون تغییر حفظ شده است.** تغییرات فقط در چهار محور است:
> (۱) لایه Data Acquisition، (۲) جدول وزن‌ها، (۳) افزودن Horizon Gating، (۴) افزودن قرارداد API و سطح نمایش اپ.
>
> **تغییرات v2.1:** (۹) §22 — افزودن چارت زنده با تحلیل روی محور قیمت، (۵) §14 — بسته‌شدن ۸ شکاف از ۱۰ شکاف با منابع رایگان و کلیدلس، (۶) §15 — حذف کامل قفل انتشار مبتنی بر لایسنس؛ نگهداری لایسنس فقط برای اِسناد، (۷) §18.1 — افزودن صفحهٔ هفتم، (۸) §21 — افزودن مشخصات گزارش‌گیر عملیاتی. (۱۰) §23 — افزودن منابع کیفیت: ذخایر ماهانهٔ IMF، تقویم با رقم واقعی، نرخ‌های سیاستی BIS، دفتر سفارش سوم؛ حل Gap 9. (۱۱) §21.1 — کلید ضبط گزارش‌گیر با پیش‌فرض خاموش.

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
| Gap 9 | **تقاضای بانک مرکزی (F10)** | شکاف | **✅ حل شد — §23** | `api.imf.org/external/sdmx/2.1/data/IRFCL/.IRFCLDT1_IRFCL56V_FTO..M` — ذخایر طلای ماهانهٔ کشورها بر حسب اونس تِروی خالص (خط ۵۶ الگوی IRFCL) |
| Gap 10 | تطبیق آنالوگ تاریخی | شکاف | **باقی** | مجموعه‌داده‌ی اپیزودهای برچسب‌خورده وجود ندارد |

**قاعده‌ی گزارش:** هر شکاف — حل‌شده یا باقی — در صفحه Diagnostics با وضعیت (`RESOLVED` / `UNRESOLVED`)، منبع زنده و مقدار جاری نمایش داده می‌شود. هیچ شکافی پنهان نمی‌شود و هیچ مقداری جایگزین‌سازی نمی‌شود.

از ۱۰ شکاف، ۹ مورد با منابع کلیدلس و رایگان بسته شده‌اند (جزئیات منابع تازه در §۲۳). تنها آنالوگ تاریخی به‌عنوان حل‌نشده اعلام می‌شود؛ جریان خرید/ابطال ETF نیز همچنان به‌صراحت پراکسی برچسب می‌خورد.

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
| 7 | **CHART** | چارت زندهٔ XAU/USD با تحلیل قفل‌شده به محور قیمت — §22 |
| 8 | **LOGS** | صفحهٔ مستقل گزارش‌گیر — §21 |

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

### 21.1 الزام و کلید ضبط ‹اصلاح v2.1›

هر شکست در دریافت، پارس، اعتبارسنجی یا محاسبه باید **نام شاخص** و **علت دقیق** را ثبت کند. هیچ شکستی نباید خاموش باشد و هیچ مقداری نباید جایگزین شود.

**کلید ضبط — پیش‌فرض خاموش.** ضبط‌کنندهٔ مشترک محصول (`DiagnosticLog.shared`) در حالت خاموش ساخته می‌شود و تا زمانی که کاربر آن را روشن نکند هیچ رکوردی نگه نمی‌دارد:

| قاعده | رفتار الزامی |
|---|---|
| حالت اولیه | خاموش؛ `isRecording() == false` |
| هنگام خاموشی | هیچ رکوردی ساخته و نگه‌داری نمی‌شود؛ تنها شمارندهٔ «ثبت‌نشده‌ها» (`suppressedCount`) افزایش می‌یابد |
| روشن‌کردن | ضبط از همان لحظه آغاز می‌شود؛ رکوردهای پیش از آن وجود ندارند و بازسازی نمی‌شوند. نخستین رکورد، خودِ رویداد `RECORDING_ON` است |
| خاموش‌کردن دوباره | رکوردهای گرفته‌شده تا زمان پاک‌کردن قابل خواندن می‌مانند |
| مسیرهای تغییر وضعیت | اپ: نخستین کنترل تب گزارش‌گیر (با ماندگاری بین اجراها) · سرور: `GET /v1/logs/on` و `/v1/logs/off` یا متغیر محیطی `GI_LOG=1` · کتابخانه: `DiagnosticLog(enabled = …)` |
| نمایش | ردیف نخست «خلاصه» در صفحهٔ گزارش‌گیر وضعیت ضبط و تعداد رکوردهای گرفته‌نشده را اعلام می‌کند |

ضبط‌کنندهٔ ساخته‌شده به‌صورت صریح (`DiagnosticLog()`) روشن است؛ این استثنا فقط برای آزمون‌ها و مصرف‌کنندهٔ کتابخانه‌ای است.

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

---

## 22. چارت زنده و نمایش تحلیل روی قیمت ‹بخش جدید v2.1›

### 22.1 فید

| مورد | منبع | توضیح |
|---|---|---|
| قیمت زنده XAU/USD | `scanner.tradingview.com/global/scan` | ستون‌های `close,bid,ask,open,high,low,change,change_abs,volume,update_mode,time` |
| کندل در حال شکل‌گیری | همان درخواست | ستون‌های بازه‌ای `open\|5` … `close\|240` و ستون روزانه |
| بروکر | `OANDA:XAUUSD` (پیش‌فرض) · `FX:XAUUSD` (FXCM) · `FOREXCOM:XAUUSD` | قابل تعویض در اپ؛ هر تعویض در گزارش‌گیر ثبت می‌شود |
| تاریخچهٔ اولیه | `api.kraken.com/0/public/OHLC?pair=PAXGUSD` | فقط برای پر کردن سری در اولین اجرا |

### 22.2 قواعد صحت داده ‹الزامی›

1. **پیش‌بار بازمقیاس می‌شود، نه جایگزین.** ضریب `brokerLast / seedLast` محاسبه، منتشر و در گزارش ثبت می‌شود. کندل پیش‌بار توخالی و کم‌رنگ رسم می‌شود و در شمارش `seededBars` جدا می‌ماند.
2. **کلاک کندل، زمان دریافت است.** ستون `time` اسکرینر یک مهر جلسه است و هرگز به‌عنوان زمان کندل استفاده نمی‌شود.
3. **bid/ask کش‌شده کنار گذاشته می‌شود.** اگر میانهٔ bid/ask بیش از ۲۵ واحد پایه با آخرین قیمت فاصله داشته باشد، حذف و با کد `BIDASK_STALE` ثبت می‌شود.
4. **هیچ کندلی ساخته نمی‌شود.** نبود داده ⇒ `SERIES_EMPTY` روی چارت، نه درون‌یابی.

### 22.3 لایه‌های قفل‌شده به محور قیمت

`SPOT` · `BID` · `ASK` · `EXPECTED_MOVE` (نقطهٔ §D4.7) · `BAND_HIGH`/`BAND_LOW` (P95/P5 به‌صورت ناحیه) · `BENCHMARK` (بنچمارک LBMA).

### 22.4 نمایش تغییرات

۹ فیلد در هر اجرا با اجرای قبلی مقایسه می‌شود: جهت · سوگیری · اطمینان · رژیم · وضعیت سیگنال · کیفیت داده · تعداد فاکتور فعال · قیمت · کلید قطع. هر تغییر روی چارت با جهت (▲/▼) و با کد `STATE_CHANGED` در گزارش‌گیر ثبت می‌شود.

### 22.5 تحلیل دوگانه

| نوع | شرط فعال‌شدن | خروجی |
|---|---|---|
| `HORIZON_SPLIT` | جهت غالب افق‌های 5m–1H با جهت غالب افق‌های 4H–1W مخالف باشد | دو شاخه با قوت = میانگین Confidence و فهرست افق‌های هم‌جهت |
| `FACTOR_CONFLICT` | هر دو اردوگاه فاکتوری با |score| ≥ ۲۵ وجود داشته باشند و سهم وزنی اردوگاه اقلیت ≥ ۳۰٪ باشد | دو شاخه با سهم وزنی و سه فاکتور قوی هر طرف |

هیچ شاخه‌ای حذف یا میانگین‌گیری نمی‌شود؛ هر خوانش دوگانه با کد `DUAL_READING` ثبت می‌شود.

### 22.6 اکسپشن‌ها

`FEED_UNAVAILABLE` · `SYMBOL_NOT_QUOTED` · `PRICE_ABSENT` · `QUOTE_ABSENT` · `SERIES_EMPTY` · `SERIES_SEED_ONLY` · `ANALYSIS_ABSENT` · `KILL_SWITCH` · `LOW_DATA_QUALITY` و سه خطای آخر سایر مراحل خط لوله. همه روی چارت و همه با پیشوند `CHART_EXCEPTION_` در گزارش‌گیر.

### 22.7 ثبت در گزارش‌گیر

مرحلهٔ جدید `LogStage.CHART` با کدهای: `FEED_OK` · `FEED_UNAVAILABLE` · `SYMBOL_NOT_QUOTED` · `PRICE_ABSENT` · `BAR_INCOMPLETE` · `FEED_NOT_STREAMING` · `BIDASK_STALE` · `SEED_OK` · `SEED_EMPTY` · `SEED_UNAVAILABLE` · `SEED_VENUE_ERROR` · `SERIES_SEEDED` · `REBASE_IMPOSSIBLE` · `BAR_SEALED` · `BAR_OUT_OF_ORDER` · `OVERLAY_BUILT` · `STATE_CHANGED` · `DUAL_READING` · `CHART_EXCEPTION_*` · `VENUE_SWITCHED` · `TIMEFRAME_SWITCHED`.

---

## 23. منابع کیفیت ‹بخش جدید v2.1›

هدف این بخش یک چیز است: تبدیل آخرین ورودی‌های پراکسی و غایب به **اندازه‌گیری**. هر منبع زیر رایگان، بدون کلید و بدون حساب کاربری است و در تاریخ ۲۰۲۶‑۱۰‑۰۲ به‌صورت زنده تأیید شده است.

### 23.1 فهرست منابع

| شناسه | داده | جایگزین چه چیزی شد | Tier | دوره | شرط درخواست |
|---|---|---|---|---|---|
| `IMF_RESERVES` | خط ۵۶ الگوی IRFCL — طلای پولی کشورها بر حسب **اونس تِروی خالص**، ماهانه | **F10** که پیش‌تر `NO_FREE_SOURCE` بود و از همهٔ افق‌ها حذف می‌شد | A | ماهانه، ≈۳ هفته پس از پایان ماه | ندارد (SDMX‑ML) |
| `CALENDAR` | `indicator, country, date, importance, actual, forecast, previous, period, unit, source` | ساعت تا رویداد بعدی (پیش‌تر N/A)، شاخص غافلگیری اقتصادی (پیش‌تر پراکسی سهام منهای اوراق)، غافلگیری تورمی (پیش‌تر تغییر breakeven) | B | پیوسته | هدر `Origin: https://www.tradingview.com` — بدون آن ۴۰۳ |
| `BIS` | `WS_CBPOL` نرخ‌های سیاستی US، منطقهٔ یورو، بریتانیا، ژاپن، سوئیس، کانادا | ورودی واگرایی سیاستی F22 (پیش‌تر پراکسی روند دلار) و مسیر مورد انتظار سیاست در F03 (پیش‌تر Δ2Y بیست‌روزه) | A | ماهانه | هدر `Accept: application/vnd.sdmx.data+json;version=1.0.0` — بدون آن ۴۰۶ |
| `COINBASE` | دفتر سفارش سطح ۲ برای PAXG/USD، ۱۰۰ سطح هر سمت | چیزی را جایگزین نمی‌کند؛ **سومین دفتر مستقل** در کنار Kraken و OKX است و قاعدهٔ کیفیت «نیم‌میانگین/نیم‌کمینه» را تقویت می‌کند | B | هر چرخه | ندارد |

### 23.2 مقادیر مشتق‌شده

| کلید | تعریف |
|---|---|
| `CB_GOLD_NET_3M_T` | تغییر خالص ذخایر گزارش‌شده طی سه ماه گزارش‌شدهٔ اخیر، تُن، جمع روی کشورهایی که هر دو سر بازه را گزارش کرده‌اند |
| `CB_GOLD_NET_3M_Z` | همان تغییر، استانداردشده نسبت به ۲۴ پنجرهٔ غلتان سه‌ماههٔ همین measure (در نبود تاریخچهٔ کافی: تقسیم بر ۱۵۰ تُن) |
| `CB_GOLD_BREADTH` | سهم کشورهای خریدار منهای سهم کشورهای فروشنده در آخرین ماه، ×۱۰۰ — خوانش «جریان رسمی» که نمی‌گذارد یک گزارشگر بزرگ به‌تنهایی فاکتور را بسازد |
| `SURPRISE_INDEX_MEASURED` | میانگین `(actual − forecast) / max(|forecast|, |previous|)` با برش ±۱، روی همهٔ انتشارهای ۳۰ روز اخیر، ×۱۰۰ (حداقل ۵ انتشار) |
| `INFLATION_SURPRISE_MEASURED` | همان آماره، محدود به انتشارهای CPI، PPI، PCE و شاخص‌های قیمتی |
| `POLICY_RATE_DIVERGENCE` | نرخ سیاستی US منهای میانگین پنج نرخ دیگر، به‌علاوهٔ تغییر ۱۲ماههٔ همین فاصله |
| `FED_EXPECTED_RATE_CHANGE` | اوراق سه‌ماهه منهای نرخ سیاستی منتشرشده: حرکتی که بازار قیمت‌گذاری کرده است |

### 23.3 فرمول F10 ‹اصلاح›

```
F10 = 0.7 · zToScore(CentralBank_NetBuying_3M, cap 2.0)
    + 0.3 · ratioToScore(CentralBank_Official_Flow_Breadth, scale 25)
```

ورودی الزامی تنها `CentralBank_NetBuying_3M` است؛ نبودِ گستره، فاکتور را حذف نمی‌کند. `min_horizon` همچنان `1W` است، چون انتشار ماهانه و با تأخیر است.

### 23.4 نقص‌های گزارش‌دهی ملی

برخی اظهارنامه‌های ملی خطای مقیاس دارند. قاعده: **حذف و اعلام، نه اصلاح حدسی**.

| شرط رد | آستانه |
|---|---|
| ذخیرهٔ یک کشور | > ۹۰۰۰ تُن |
| جهش یک‌ماههٔ یک کشور | > ۴۰۰ تُن |

هر کشور ردشده با کد `RESERVES_IMPLAUSIBLE` در گزارش‌گیر نام برده می‌شود. در ۲۰۲۶‑۱۰‑۰۲ این قاعده `AGO`، `BRA` و `G163` را کنار گذاشت و ۶۲ کشور گزارشگر باقی ماند.

### 23.5 ساعت رویداد

`CALENDAR_NEXT_EVENT_HOURS` نخست به **نزدیک‌ترین رویداد پرتأثیر** اشاره می‌کند. اگر پنجرهٔ تقویم هیچ رویداد پرتأثیری نداشته باشد، نزدیک‌ترین رویداد با هر درجهٔ اهمیت ثبت می‌شود و صراحتاً `isProxy` علامت می‌خورد. تقویم هفتگی پیشین تنها به‌عنوان مسیر پشتیبانِ «زمان‌بندی» باقی می‌ماند، چون رقم واقعی را منتشر نمی‌کند.

### 23.6 منابع بررسی‌شده و ردشده

`dataservices.imf.org` (سرویس قدیمی حذف شده) · دیتافلو `IFS` در API جدید IMF (۴۰۴) · کیوب `snbgolda` بانک ملی سوئیس (۴۰۴) · Binance (۴۵۱ در این منطقه) · Bybit (۴۰۳) · دسترسی مهمان Trading Economics (۴۱۰) · فایل `fund.ajax?fileType=csv` آی‌شیرز (HTML برمی‌گرداند) · `fsapi.gold.org/api/etf/...` (۴۰۴) · `GLD_US_archive_EN.csv` اسپایدر (PDF برمی‌گرداند) · کلید هفتگی طلای `ILM` بانک مرکزی اروپا (۴۰۴).

نتیجهٔ مستقیم: **جریان خرید/ابطال ETF (F09) پراکسی می‌ماند** و در هر صفحه با برچسب `PROXY` نمایش داده می‌شود.

### 23.7 شگفتی اجماع و پراکسی درون‌روزی بازده واقعی ‹افزودهٔ ۲۰۲۶‑۱۰‑۰۲›

| کلید | تعریف | منبع | برچسب |
|---|---|---|---|
| `CONSENSUS_SURPRISE` | میانگین شگفتیِ نرمال‌شدهٔ **سه انتشار پراهمیت اخیر** تقویم: `(actual − forecast) / max(|forecast|, |previous|, 1e-6)` در بازهٔ ‎[-1, +1]‎ | تقویم اقتصادی تریدینگ‌ویو (همان رکوردی که `actual` و `forecast` را با هم دارد) | `MEASURED` |
| `REAL_YIELD_INTRADAY_PROXY` | نرخ اسمی دە‌سالهٔ لحظه‌ای منهای آخرین نقطه‌سربه‌سر منتشرشده: `US10Y_INTRADAY − BREAKEVEN10Y` | `TVC:US10Y` از اسکنر + سری نقطه‌سربه‌سر خزانه‌داری | `PROXY` (سقف کیفیت ۰٫۶) |

قاعدهٔ پیشین «جفت‌کردن ردیف تقویم با انتشار BLS» تنها **مسیر پشتیبان** است؛ وقتی خودِ ردیف تقویم هر دو رقم را دارد، جفت‌کردن دو منبع لازم نیست و کد `NO_MATCHED_RELEASE` دیگر مسیر اصلی را از کار نمی‌اندازد.

منحنی واقعی خزانه‌داری فقط **پایان روز** منتشر می‌شود؛ بنابراین میان دو انتشار، تنها خوانش موجود از حرکت بازده واقعی همین ترکیب است و همیشه `PROXY` علامت می‌خورد — هرگز جای `REAL_YIELD` را نمی‌گیرد.

### 23.8 لنگرهای اعتماد ‹TLS›

`api.imf.org` زیر ریشهٔ **`Sectigo Public Server Authentication Root R46`** (ساخت ۲۰۲۱‑۰۳‑۲۲) امضا می‌شود. هر دستگاهی که مخزن اعتمادش پیش از آن تاریخ است — یعنی عملاً هر گوشی اندروید ۱۰ که به‌روزرسانی Conscrypt نگرفته — زنجیره را با `SSLHandshakeException: Trust anchor for certification path not found` رد می‌کند. نتیجهٔ مشاهده‌شده روی `SM-J810F` (API 29): فید ذخایر `TRANSPORT_ERROR` و عامل `F10` روی `MISSING_INPUTS`.

الزام:

- ریشهٔ R46 به‌صورت `res/raw/sectigo_server_root_r46.pem` همراه اپ منتشر می‌شود، با اثر انگشت SHA‑256:
  `7B:B6:47:A6:2A:EE:AC:88:BF:25:7A:A5:22:D0:1F:FE:A3:95:E0:AB:45:C7:3F:93:F6:56:54:EC:38:F2:5A:06`
- در `res/xml/network_security_config.xml` این ریشه **فقط** برای `api.imf.org` و `fiscaldata.treasury.gov` و **در کنار** مخزن سیستم اعتبار دارد؛ `base-config` دست‌نخورده می‌ماند (`system` و `cleartextTrafficPermitted="false"`).
- هیچ‌گاه `TrustManager` سفارشی، pinning یا `checkServerTrusted` خالی مجاز نیست.
- آزمون `TrustAnchorTest` در هر بیلد اثر انگشت، خودامضا بودن، تاریخ انقضا و سیم‌کشی manifest را بررسی می‌کند و در صورت تعویض گواهی شکست می‌خورد.

جایگزین بررسی و رد شد: `data.imf.org` و `www.imf.org` روی تمام مسیرهای SDMX پاسخ ۴۰۳ می‌دهند، DataMapper هیچ سری ذخایر طلا ندارد، و بازنشرهای کیلویی (Goldhub، fxmacrodata، ویکی‌پدیا) یا کلید می‌خواهند یا ماشین‌خوان نیستند. بنابراین **اصلاح لایهٔ انتقال تنها مسیر صادقانه است**.

## 24. آنالوگ تاریخی ‹بخش جدید ۲۰۲۶‑۱۰‑۰۲›

هیچ مجموعه‌دادهٔ رایگانِ **برچسب‌خورده‌ای** از «اپیزودهای تاریخی طلا» وجود ندارد؛ این بخش چنین چیزی نمی‌سازد و هیچ رویدادی را نام‌گذاری نمی‌کند. آنچه بدون برچسب شدنی است انجام می‌شود: تاریخچهٔ روزانه‌ای که خود اپ دانلود می‌کند (GLD از ۲۰۰۴‑۱۱‑۱۸، ۵٬۵۰۴ جلسه) با همان شرایطی توصیف می‌شود که موتور امروز می‌خواند، و نزدیک‌ترین جلسات به امروز همراه با **بازدهی که واقعاً پس از آن‌ها محقق شد** گزارش می‌شوند.

### 24.1 ستون‌های توصیف

روند طلا · نوسان طلا · روند سهام (`SPY`) · روند اوراق بلندمدت (`TLT`) · روند دلار (`UUP`، در صورت وجود تاریخچه) · ترس سهام (`VIX`) · روند نقره (`SLV`). هر ستون جداگانه استاندارد می‌شود (z)؛ ستونی که ساخته نشود فقط حذف می‌شود.

پنج سری‌ای که این ستون‌ها از آن‌ها ساخته می‌شوند (`GLD`, `SLV`, `SPY`, `TLT`, `VIX`) با عمق کامل نگه‌داری می‌شوند (`DEEP_HISTORY_BARS = 6000`) و بقیهٔ سری‌ها با پنجرهٔ ۶۰۰ جلسه‌ای پیشین؛ همان فایل در هر دو حالت دانلود می‌شود، پس تنها هزینهٔ عمق، حافظه است. هیچ ویژگی موجودی از پنجرهٔ باز استفاده نمی‌کند (همه `takeLast` دارند)، بنابراین مقادیر فاکتورها تغییر نمی‌کنند.

### 24.2 ثابت‌ها و قواعد رد

| ثابت | مقدار | معنا |
|---|---|---|
| `MOMENTUM_DAYS` | ۶۰ | پنجرهٔ روند |
| `VOL_DAYS` | ۲۰ | پنجرهٔ نوسان |
| `FORWARD_DAYS` | ۲۰ | افق بازده محقق‌شده |
| `MATCHES` | ۴ | حداکثر تطبیق گزارش‌شده |
| `MIN_SEPARATION_DAYS` | ۱۸۰ | تطبیق‌ها نباید یک اپیزود تکراری باشند |
| `MIN_HISTORY` | ۴۰۰ | کف تاریخچه |
| `MIN_DIMENSIONS` | ۴ | کف شرایط قابل‌استفاده |

فاصله = نُرم اقلیدسی استانداردشده تقسیم بر ریشهٔ تعداد شرایط. شباهت = `1 / (1 + distance / SIMILARITY_SCALE)`. نامزدها باید پنجرهٔ آیندهٔ کامل داشته باشند و بیرون از پنجرهٔ محاسبهٔ توصیف امروز بنشینند، تا نشت رو‌به‌جلو رخ ندهد.

### 24.3 قواعد گزارش

هر تطبیق یک **تاریخ واقعی** است؛ فاصله، تعداد شرایط و بازده محقق‌شدهٔ ۲۰ جلسهٔ بعد منتشر می‌شوند. جهت تنها از همان بازده محقق‌شده می‌آید (`DIRECTION_FLOOR_PCT`). تطبیق توصیفی است، نه پیش‌بینی: در متن «ابطال» هر ردیف صریحاً نوشته می‌شود. اگر تاریخچه یا شرایط کافی نباشد، لایه با کدهای `NO_UNIVERSE` / `NO_GOLD_HISTORY` / `HISTORY_TOO_SHORT` / `TOO_FEW_CONDITIONS` / `TODAY_INCOMPLETE` / `NO_CANDIDATE` **نبود خود را اعلام می‌کند** و هیچ ردیفی نمی‌سازد.

## 25. منابع اندازه‌گیری‌شدهٔ تازه ‹بخش جدید ۲۰۲۶‑۱۰‑۰۲›

چهار ورودی که از نخستین بررسی «پراکسی» مانده بودند، اندازه‌گیری شدند و سه دستهٔ اطلاعات تازه افزوده شد. بررسی کامل وب، با نتیجهٔ هر درخواست و دلیل هر رد، در `docs/INDICATOR_RESEARCH.md`.

### 25.1 FRED بدون کلید

`api.stlouisfed.org` کلید ثبت‌شده می‌خواهد؛ **خروجی نموداری** مسیر دیگری است و هیچ‌چیز نمی‌خواهد:

```
https://fred.stlouisfed.org/graph/fredgraph.csv?id=<SERIES>&cosd=<YYYY-MM-DD>
```

دو ستون CSV، کل تاریخچه، و مشاهدهٔ غایب به‌صورت `.` که **حذف می‌شود، نه پر**. قید سنجیده‌شده: این میزبان، `User-Agent` مرورگری را از محدودهٔ دیتاسنتر رد می‌کند؛ بنابراین فقط همین ارائه‌دهنده عامل پیش‌فرض پشته را بازنویسی می‌کند.

| سری | شناسه در جهان داده | می‌خوراند | جایگزین چه شد |
|---|---|---|---|
| `BAMLH0A0HYM2` | `HY_OAS` | F18 اعتبار | نسبت معکوس HYG/LQD (پراکسی) |
| `STLFSI4` | `FINANCIAL_STRESS_STLFSI` | F08 تنش مالی | ترکیب VIX + ETF + تأمین مالی (پراکسی) |
| `NFCI` | `FINANCIAL_CONDITIONS_NFCI` | F17 نقدشوندگی | — پایهٔ تازه |
| `WALCL` − `WTREGEN` − `RRPONTSYD` | `FED_NET_LIQUIDITY` | F17 نقدشوندگی | — پایهٔ تازه |
| `USEPUINDXD`, `WLEMUINDXD` | `POLICY_UNCERTAINTY_DAILY`, `NEWS_EQUITY_UNCERTAINTY` | F07 ریسک ژئوپلیتیک | صرف GVZ/VIX (پراکسی) |
| `T5YIFR` | `INFLATION_EXPECTATION_5Y5Y` | F05 تورم | — پایهٔ تازه |
| `DFII10`, `T10YIE`, `GVZCLS` | `*_DEEP` | عمق جمعیت آماری F01/F05/F15 | ~۲ سال ← ۲۳ سال |
| `DTWEXBGS` | `DOLLAR_BROAD_INDEX` | بررسی مستقل F02 | — |

نقدینگی خالص تنها رقم مشتق است و مشتق‌گیری فقط یک تفریق است: `WALCL` و `WTREGEN` به میلیون و `RRPONTSYD` به میلیارد منتشر می‌شوند، و دو سری روزانه **در تاریخ ترازنامهٔ هفتگی** خوانده می‌شوند، نه درون‌یابی‌شده.

### 25.2 زنجیرهٔ آپشن منتشرشده

`https://cdn.cboe.com/api/global/delayed_quotes/options/GLD.json` — ۸٬۱۹۴ قرارداد فهرست‌شده، هرکدام با نوسان ضمنی، موقعیت باز، حجم و یونانی‌ها، روی همان میزبانی که پیش‌تر در پشته بود.

| اندازه | تعریف |
|---|---|
| `GOLD_RISK_REVERSAL_25D` | نوسان ضمنی نزدیک‌ترین قرارداد به دلتای `+0.25` منهای نزدیک‌ترین به `−0.25`، روی نخستین سررسید ≥ ۱۴ روز. اگر هیچ بال در فاصلهٔ ۰٫۰۸ از ۲۵ دلتا نباشد، **رد می‌شود** |
| `GOLD_PUT_CALL_OI` / `_VOLUME` | موقعیت باز و حجم پوت تقسیم بر کال، کل زنجیره |
| `GOLD_IV_TERM_SLOPE` | نوسان ضمنی ATM سررسید ≥ ۷۵ روز منهای ≥ ۱۴ روز |

ریسک‌ریورسال تنها سنجهٔ **جهت‌دار** آپشنی پشته است و اکنون ۴۰٪ وزن F15 را می‌گیرد؛ نسبت پوت/کال ۲۰٪.

### 25.3 مسیر سیاستی که بازار بابتش پول می‌دهد

قرارداد آتی وجوه فدرال ۳۰ روزه روی **میانگین نرخ مؤثر ماه تحویل** تسویه می‌شود؛ پس `100 − قیمت` خودِ نرخ قیمت‌گذاری‌شده است — نه درخت احتمال، نه مدل. `CBOT:ZQ1!` به‌علاوهٔ سه قرارداد تاریخ‌دار روی همان اسکرینر. ورودی `FED_EXPECTED_RATE_CHANGE` اکنون `ضمنی ۱۲ماهه − نرخ سیاستی منتشرشده` است؛ اوراق سه‌ماهه مسیر پشتیبان و `Δ2Y(20d)` پشتیبانِ پشتیبان می‌ماند.

همان درخواست `TVC:MOVE` (نوسان ضمنی بازار اوراق)، بازده ده‌سالهٔ آلمان/ژاپن/بریتانیا/چین و `CRYPTO:BTCUSD` را هم می‌آورد.

### 25.4 شیب منحنی ترس سهام

`_VIX9D` و `_VIX3M` از همان CDN؛ `VIX_TERM_SLOPE = VIX3M − VIX9D`. مثبت یعنی بازار نقدی آرام است و ریسک را برای بعد قیمت می‌زند؛ منفی یعنی تنش همین حالا.

### 25.5 سامانه‌های تحلیل خبری

| سامانه | وضعیت |
|---|---|
| شاخص عدم‌قطعیت سیاست اقتصادی (Baker, Bloom & Davis) — شمارش مقالات روزنامه‌ها | **زنده**، روزانه، به‌صورت صدک پنج‌سالهٔ تاریخچهٔ خودش به F07 می‌رود |
| شاخص عدم‌قطعیت مرتبط با بازار سهام | **زنده**، روزانه |
| شاخص احساسات خبری روزانهٔ فدرال‌رزرو سانفرانسیسکو (۲۴ روزنامه) | در دسترس (`200`، ۴۱۸ کیلوبایت OOXML) اما نیازمند **مسیر پاسخ باینری در لایهٔ HTTP** — تغییر رابط عمومی، بدون تأیید انجام نشد |
| GDELT 2.0 DOC | **۴۲۹** از این محدودهٔ آدرس، در همهٔ حالت‌ها و پرس‌وجوها |
| NewsAPI / Finnhub / Marketaux / Cryptopanic / Alpha Vantage | همه کلید می‌خواهند |

### 25.6 اثر سنجیده‌شده

همان ماشین، دو اجرای پشت‌سرهم، بدون تغییر دیگر: کیفیت داده **۰٫۶۸۲ ← ۰٫۷۱۵**، شاخص دریافتی **۶۳ ← ۷۴**، سری زمانی **۲۶ ← ۴۰**، شاخص با برچسب `EXPIRED` **۲ ← ۰**، ورودی پراکسی روی F07/F08/F18 **۳ ← ۰**، فاکتور فعال ۲۲/۲۲، خطا ۰.

**وزن‌ها تغییر نکردند.** جدول وزن §۲ نرمتیو است و تغییر آن تصمیم جداگانه‌ای است.

---

## ۲۶. موتور استنتاج — کالیبراسیون گام‌روبه‌جلو و سنجه‌های ساختاری

§D2 جدول وزن‌ها را صراحتاً «پیشین‌های اولیه، تا بازتخمین با کالیبراسیون گام‌روبه‌جلو پس از وجود سابقه» نامیده بود و §۱۹ تا آن زمان هر احتمال عددی را نگه می‌داشت. سابقه‌ای در کار نبود، پس `calibrationQuality` تهی می‌ماند، ستون احتمال در هر شش افق `UNCALIBRATED_NO_SAMPLE` می‌خواند، و جملهٔ کالیبراسیون در فرمول اطمینان ثابتاً صفر بود.

سابقه در همان داده‌ای بود که برنامه هر چرخه دانلود می‌کند. تاریخچهٔ روزانهٔ Cboe تا ۲۰۰۴ عقب می‌رود — **۵٬۵۰۵ جلسه**. §۲۶ آن را بازپخش می‌کند.

این بخش **هیچ منبع تازه‌ای** اضافه نمی‌کند و **هیچ وزنی** را تغییر نمی‌دهد. تنها ماشین استنتاج را عوض می‌کند: جایی که پیش‌تر عددی فرض می‌شد، حالا اندازه‌گیری می‌شود؛ و جایی که اندازه‌گیری جواب ندهد، همین هم منتشر می‌شود.

### ۲۶.۱ بازپخش گام‌روبه‌جلوی لنگرانداخته

`ingestion/WalkForwardCalibration.kt`. موتور `engine` بدون ورودی/خروجی می‌ماند، پس رانندهٔ بازپخش — که به `MarketUniverse` نیاز دارد — دقیقاً مثل `SeriesAnalogueEngine` در لایهٔ `ingestion` می‌نشیند.

پنل بازپخش کل بیست‌ودو عامل **نیست**. بیشتر عوامل به انتشارهای کلانی وابسته‌اند که تاریخچهٔ روزانهٔ رایگان ندارند. آنچه واقعاً از قیمت بازسازی‌شدنی است:

| عامل | وزن نرمتیو | جایگزین قیمتی | جهت |
|---|---|---|---|
| F01 بازده واقعی | ۰٫۲۰۰ | `TIP` — بازده کل اوراق تورم‌تطبیق | TIP بالا ⇒ بازده واقعی پایین ⇒ صعودی |
| F02 دلار | ۰٫۱۵۰ | `UUP` | دلار بالا ⇒ نزولی |
| F08 تنش مالی | ۰٫۰۳۰ | `VIX` | ترس بالا ⇒ صعودی |
| F13 مومنتوم | ۰٫۰۲۰ | `GLD` | روند خودِ طلا |
| F16 بین‌دارایی | ۰٫۰۱۰ | `SPY` | ریسک‌پذیری ⇒ نزولی |
| F18 اعتبار | ۰٫۰۱۰ | `HYG` | تنش اعتباری ⇒ صعودی |
| F21 انرژی | ۰٫۰۲۰ | `USO` | تورم انرژی ⇒ صعودی |

مجموع: **۰٫۴۴۰ از وزن نرمتیو**، شامل دو عامل بزرگ جدول. این سهم با هر نتیجه منتشر می‌شود و **حد صادقانهٔ کار** است: نگاشت روی ترکیبی برازش می‌شود که با همان قاعده و روی همان مقیاس ±۱۰۰ ساخته شده، اما از زیرمجموعه‌ای از ورودی‌های زنده.

هر جلسه با پنجره‌های پسرو توصیف می‌شود (هیچ نگاه‌به‌جلویی)، سپس:

1. **لنگر**: نخستین برازش پس از ۱٬۲۵۰ جلسهٔ گرم‌کردن.
2. هر **۱۲۵ جلسه** نگاشت روی *هرچه تا آن تاریخ دانسته بوده* دوباره برازش می‌شود و روی **۱۲۵ جلسهٔ بعدی** — که هرگز ندیده — نمره می‌خورد.
3. هر سطر نمره‌خورده بنابراین واقعاً رو به جلوست، و هر برازش از کل تاریخ در دسترسِ تاریخ خودش استفاده می‌کند.

یک برش ثابت واحد روی بیست سال، نگاشت را در یک دوران پولی برازش می‌دهد و در دورانی دیگر نمره می‌زند؛ بدکالیبراسیون حاصل، خاصیت برش است نه مدل. بازپخش لنگرانداخته آن سوگیری را حذف می‌کند.

### ۲۶.۲ نگاشت یکنوا: رگرسیون ایزوتونیک

لینک لجستیک `1/(1+e^{−bias/25})` را هیچ‌کس برازش نداده بود؛ عدد ۲۵ یک انتخاب بود. جای آن **رگرسیون ایزوتونیک** با الگوریتم ادغام همسایه‌های ناقض (PAVA) می‌نشیند: بهترین تابع پله‌ای **یکنوا** در معنای کمترین مربعات.

یکنوایی قید درست است نه راحتی: امتیاز بالاتر هرگز نباید به احتمال پایین‌تر نگاشته شود، هرچه نوفهٔ نمونه بگوید. بیرون از دامنهٔ برازش‌شده، مقدار **قید می‌شود، نه برون‌یابی**. زیر ۱۲۰ نمونه، برازش رد می‌شود.

### ۲۶.۳ نمرهٔ برایر و تجزیهٔ مورفی

`BS = REL − RES + UNC` (Brier 1950؛ Murphy 1973)، با سطل‌بندی **صدکی** نه هم‌عرض — سطل هم‌عرض وقتی جرم احتمال در یک دم جمع شود فرومی‌پاشد، و این دقیقاً بلایی است که سر یک مدل جهتی می‌آید.

| جمله | معنا |
|---|---|
| `REL` اعتمادپذیری | بدکالیبراسیون. کمتر بهتر. این همان بخشی است که بازکالیبراسیون برمی‌دارد |
| `RES` تفکیک‌پذیری | توان گفتن چیزی غیر از «متوسط». بیشتر بهتر |
| `UNC` عدم‌قطعیت | `ō(1−ō)`؛ مال مسئله است نه مدل |
| `skill` | `1 − BS/UNC`. مثبت یعنی بهتر از نرخ پایه |

`calibrationQuality` حاصل‌ضرب دو سهم است: اندازهٔ نمونه نسبت به ۷۵۰ جلسه، و مهارت نسبت به مرجع ۰٫۰۵. **مهارتِ نامثبت ⇒ کیفیت صفر ⇒ احتمال منتشر نمی‌شود.**

وضعیت تازه‌ای به §۱۹ افزوده می‌شود:

| وضعیت | معنا |
|---|---|
| `UNCALIBRATED_NO_SAMPLE` | افق هرگز اندازه‌گیری نشد (میله‌های روزانه افق درون‌روزی را حل نمی‌کنند) |
| `UNCALIBRATED_NO_SKILL` | **اندازه‌گیری شد و مهارت بیرون‌نمونه نداشت** |
| `CALIBRATED_WALK_FORWARD` | نگاشت اندازه‌گیری‌شده اعمال شد؛ عدد منتشرشده فراوانی مشاهده‌شدهٔ همین امتیاز است، نه منحنی |

«اندازه‌گیری شد و بی‌ارزش بود» پاسخی متفاوت از «هرگز اندازه‌گیری نشد» است و این دو روی صفحه در هم نمی‌ریزند.

### ۲۶.۴ بازهٔ کانفورمال تقسیمی

پیش‌تر باند `P5–P95` ضریب ثابت `±1.645σ` بود — چندک توزیع نرمال، **فرض‌شده**. جای آن **پیش‌بینی کانفورمال تقسیمی** می‌نشیند (Vovk, Gammerman & Shafer 2005؛ Lei et al. 2018): نیم‌پهنا برابر با مانده‌ای است که در رتبهٔ

```
⌈(n+1)(1−α)⌉
```

از ماندهٔ مطلق مرتب‌شدهٔ داده‌های بیرون‌نمونه قرار می‌گیرد. `+1` تصحیح نمونهٔ متناهی است و باند را کمی **محافظه‌کار** می‌کند نه کوتاه. پوشش `≥ 1−α` بدون هیچ فرض توزیعی تضمین می‌شود — تنها تحت تعویض‌پذیری.

تعویض‌پذیری را سری قیمت روزانه نقض می‌کند. پس **پوشش محقق‌شده اندازه‌گیری و در کنار سطح اسمی منتشر می‌شود**، نه ادعا. مانده‌ها بر σ تقسیم می‌شوند تا نیم‌پهنا بی‌بعد باشد و مستقیماً جای ۱٫۶۴۵ بنشیند.

### ۲۶.۵ تعداد مؤثر شرط‌ها

جملهٔ `ModelAgreement` سهم اکثریت شواهد وزنی بود؛ دوازده عاملی که همه تابع دلارند، دوازده شاهد نیستند. **آنتروپی نمایی مقادیر ویژهٔ ماتریس همبستگی** (Meucci 2009، *Managing Diversification*):

```
pᵢ = λᵢ / Σλ ,  ENB = exp(−Σ pᵢ ln pᵢ) ∈ [1, n]
```

۱ یعنی همه‌چیز یک عامل پنهان است، `n` یعنی کاملاً پراکنده. نسبت `ENB/n` جمله را به سمت خنثی جمع می‌کند:

```
Agreement′ = 0.5 + (Agreement − 0.5) × ENB/n
```

مقادیر ویژه با چرخش ژاکوبی دوره‌ای محاسبه می‌شوند (`Jacobi`، ~۶۰ خط، بدون وابستگی تازه).

### ۲۶.۶ سن رژیم: تشخیص نقطه تغییر بیزی برخط

برچسب رژیم قاعده‌محور بود. جای آن **پسین طول اجرا** می‌نشیند (Adams & MacKay 2007):

```
P(rₜ|x₁:ₜ) ∝ Σ_{r_{t−1}} P(xₜ|r_{t−1}) · P(rₜ|r_{t−1}) · P(r_{t−1}|x₁:_{t−1})
```

پیش‌بین مزدوج نرمال–گامای‌معکوس ⇒ پیش‌بینی استیودنت با `df = 2α` و `scale = β(κ+1)/(ακ)`؛ مخاطرهٔ ثابت `h = 1/λ` با `λ = 250`؛ هرس به ۲۰۰ فرضیهٔ نخست. خروجی: طول اجرای بیشینهٔ پسین، `P(rₜ=0)`، و جرم روی رژیم جوان‌تر از ۱۵ جلسه. این سه، `RegimeStability` و جریمهٔ `Transition` را تغذیه می‌کنند.

### ۲۶.۷ اعتبار روند، صاف‌سازی، و شکنندگی

| سنجه | مرجع | کاربرد |
|---|---|---|
| نسبت واریانس `VR(q)` با آمارهٔ مقاوم به ناهمسانی | Lo & MacKinlay 1988 | `\|z\| < 1.96` ⇒ گام تصادفی رد نمی‌شود ⇒ **نقطهٔ تخمین حرکت مورد انتظار در ۰٫۵ ضرب می‌شود**؛ بازگشت‌به‌میانگین ⇒ ۰٫۲۵ |
| نمای هرست دامنهٔ بازمقیاس | R/S | گزارش کنار VR |
| فیلتر کالمن سطح محلی یک‌بعدی | Kalman 1960 | ترکیب صاف‌شده کنار خام؛ برخلاف میانگین متحرک نیم‌پنجره عقب نمی‌افتد |
| میانگین هرس‌شدهٔ متقارن | — | فاصلهٔ ترکیب وزنی از هستهٔ هرس‌شده؛ فاصلهٔ بیش از ۲۰ واحد ⇒ برچسب `FRAGILE` |

نیم‌پهنای باند با ضریب اعتبار **کوچک نمی‌شود**: وقتی تاپه گام تصادفی است، ادعا کوچک می‌شود، عدم‌قطعیت نه.

### ۲۶.۸ اثر سنجیده‌شده

| | پیش از §۲۶ | پس از §۲۶ |
|---|---|---|
| سابقهٔ کالیبراسیون | وجود نداشت | **۳٬۶۲۵ جلسهٔ نمره‌خوردهٔ رو به جلو** از ۲۹ برازش لنگرانداخته |
| وضعیت احتمال ۱D / ۱W | `UNCALIBRATED_NO_SAMPLE` | `UNCALIBRATED_NO_SKILL` — اندازه‌گیری شد |
| نمرهٔ برایر ۱D | — | ۰٫۲۵۰۳ (REL ۰٫۰۰۱۳ − RES ۰٫۰۰۰۵ + UNC ۰٫۲۴۹۴)، مهارت −۰٫۰۰۳۳، نرخ پایه ۵۲٫۴٪ |
| نمرهٔ برایر ۱W | — | ۰٫۲۵۰۷ (REL ۰٫۰۰۳۶ − RES ۰٫۰۰۱۰ + UNC ۰٫۲۴۸۴)، مهارت −۰٫۰۰۹۳، نرخ پایه ۵۴٫۰٪ |
| باند ۱D | `±1.645σ` فرض‌شده | **`±1.773σ` سنجیده‌شده**، پوشش محقق‌شده ۹۰٫۰٪ در ۳٬۶۲۵ جلسه |
| باند ۱W | `±1.645σ` فرض‌شده | **`±1.889σ` سنجیده‌شده**، پوشش محقق‌شده ۹۰٫۰٪ |
| شواهد مستقل | شمارش ساده | **ENB ۵٫۵۶ از ۷** (۷۹٫۴٪) |
| رژیم | برچسب قاعده‌محور | **۱۹۹ جلسه، `P(change)` ۰٫۴٪، `HIGH`** روی ۷۵۰ بازده |
| اعتبار روند | سنجیده نمی‌شد | **`VR(5)` ۰٫۹۰۸، `z` −۰٫۶۲، `H` ۰٫۶۰۰ ⇒ `RANDOM_WALK`، اعتبار ۵۰٪** |
| شکنندگی | سنجیده نمی‌شد | وزنی −۷۹٫۸ در برابر هرس‌شده −۳۱٫۲، فاصله ۴۸٫۷ ⇒ `FRAGILE` |
| سری زمانی | ۴۰ | ۴۲ (`TIP`, `UUP` با عمق کامل) |

مهم‌ترین نتیجه همین سطر دوم است: پنل قیمتی **مهارت جهتی بیرون‌نمونه روی طلا در یک روز و یک هفته ندارد**، و سامانه حالا این را می‌داند و می‌گوید — به‌جای آنکه مثل پیش، نداند.

کیفیت داده ۰٫۷۱۴، ۷۴/۷۴ شاخص، ۲۲/۲۲ فاکتور، ۰ خطا. **وزن‌ها تغییر نکردند.**

---

## ۲۷. پنل اندازه‌گیری‌شده، پیش‌بین دوم، و باند مشروط

§۲۶ سابقهٔ کالیبراسیون را از **هفت جایگزین قیمتی** ساخت — صندوق‌هایی که تقریباً مثل عاملی رفتار می‌کنند که جای آن نشسته‌اند — و ۰٫۴۴۰ از وزن نرمتیو را پوشش می‌داد. §۲۷ جایگزین‌ها را با **خودِ سری منتشرشده** عوض می‌کند و سه چیز به ماشین استنتاج می‌افزاید.

### ۲۷.۱ پنل: از جایگزین به اندازه‌گیری

| عامل | وزن | §۲۶ (جایگزین) | §۲۷ (اندازه‌گیری‌شده) | تبدیل |
|---|---|---|---|---|
| F01 بازده واقعی | ۰٫۲۰۰ | `TIP` بازده کل | **`DFII10`** بازده ده‌سالهٔ TIPS | تفاضل سطح |
| F02 دلار | ۰٫۱۵۰ | `UUP` | **`DTWEXBGS`** شاخص گستردهٔ دلار فدرال‌رزرو | بازده |
| F03 فدرال‌رزرو | ۰٫۱۰۰ | — | **`DGS2`** بازده دوساله به‌عنوان مسیر قیمت‌گذاری‌شده | تفاضل سطح |
| F04 منحنی خزانه | ۰٫۰۴۰ | — | **`T10Y2Y`** اسپرد ۱۰ق۲ | تفاضل سطح |
| F05 تورم | ۰٫۰۶۰ | — | **`T10YIE`** نرخ سربه‌سر ده‌ساله | تفاضل سطح |
| F07 ژئوپلیتیک | ۰٫۰۶۰ | — | **`USEPUINDXD`** عدم‌قطعیت سیاست اقتصادی | Z سالانه |
| F08 تنش مالی | ۰٫۰۳۰ | `VIX` Cboe | **`VIXCLS`** بستهٔ منتشرشده | بازده |
| F13 مومنتوم | ۰٫۰۲۰ | `GLD` | `GLD` | بازده |
| F15 نوسان/آپشن | ۰٫۰۱۰ | — | **`GVZCLS`** نوسان ضمنی طلا | بازده |
| F16 بین‌دارایی | ۰٫۰۱۰ | `SPY` | `SPY` | بازده |
| F18 اعتبار | ۰٫۰۱۰ | `HYG` | `HYG` | بازده |
| F21 نفت | ۰٫۰۲۰ | `USO` صندوق | **`DCOILWTICO`** نفت نقدی وست تگزاس | بازده |
| F22 سیاست جهانی | ۰٫۰۳۰ | — | **`DGS10`** بازده ده‌ساله | تفاضل سطح |

**۰٫۷۴۰ از وزن نرمتیو، و در اجرای زنده ۰٫۷۴۰ از آن اندازه‌گیری‌شده — هیچ جایگزینی به کار نرفت.** هر پایه یک `fallbackSeriesId` دارد که فقط وقتی سری منتشرشده در دسترس نباشد استفاده می‌شود، و این تقسیم هر چرخه منتشر می‌شود.

دو تصحیح فنی که این جابه‌جایی لازم کرد:

1. **تبدیل سطح در برابر بازده.** درصد تغییرِ یک *نرخ* بی‌معناست؛ نرخ‌ها و اسپردها با **تفاضل واحد درصدی** وارد می‌شوند، و شاخص‌های سطحی مثل EPU با **Z نسبت به پنجرهٔ خودشان**.
2. **هم‌ترازی تاریخی.** سری‌های کلان تقویم تعطیلات متفاوتی از بورس دارند، پس هم‌ترازی موقعیتی تاریخ‌ها را جابه‌جا می‌کند. اتصال اکنون **as-of** است: هر جلسه آخرین مشاهدهٔ منتشرشده **در همان روز یا پیش از آن** را می‌گیرد، با سقف کهنگی ۷ روز. مشاهدهٔ بعدی هرگز دیده نمی‌شود — این همان تعریف نگاه‌به‌جلو است.

### ۲۷.۲ پیش‌بین دوم: رگرسیون لجستیک ریج

نگاشت ایزوتونیک §۲۶ **یک عدد** را به احتمال می‌برد: ترکیب وزن‌دارِ نرمتیو. چنین نگاشتی نمی‌تواند کشف کند که دو عامل *با هم* معنا دارند، یا که یکی از آن‌ها تمام ترکیب را به دوش می‌کشد.

`RidgeLogistic` کل پنل استانداردشده را به‌صورت یک بردار می‌خواند و نگاشت جهتی را از داده تخمین می‌زند، زیر جریمهٔ تیخونوف روی شیب‌ها (عرض از مبدأ جریمه نمی‌شود)، با IRLS. مرجع: Hoerl & Kennard 1970؛ le Cessie & van Houwelingen 1992.

**این مدل جدول وزن را تغییر نمی‌دهد.** جدول §۲ نرمتیو است و دست‌نخورده می‌ماند؛ این یک پیش‌بین *جداگانه* است که احتمالش با احتمال ایزوتونیک ترکیب می‌شود، و فقط اگر مهارت بیرون‌نمونهٔ خودش مثبت باشد.

### ۲۷.۳ ترکیب پیش‌بین‌ها

Bates & Granger (1969) نشان دادند ترکیب معمولاً از اعضایش بهتر است؛ Timmermann (2006) مرور می‌کند چرا. اعضا در فضای **لگاریتم نسبت شانس** تجمیع می‌شوند — استخر عقیدهٔ لگاریتمی استاندارد — که نتیجه را احتمالِ سالم نگه می‌دارد.

دو قاعده جلوی بیش‌برازشِ خودِ ترکیب را می‌گیرد:

1. عضو تنها با **مهارت بیرون‌نمونهٔ مثبت** وارد می‌شود؛
2. وزن‌ها متناسب با همان مهارت‌اند و هر دوره بازتخمین نمی‌شوند.

با هیچ عضو پذیرفتنی، ترکیب خود را خالی گزارش می‌کند و هیچ احتمالی منتشر نمی‌شود.

### ۲۷.۴ باند کانفورمال موندریان

باند تجمیعی §۲۶ **حاشیه‌ای** معتبر است: به‌طور میانگین ۹۰٪ جلسات را می‌پوشاند — یعنی در بازار آرام **خیلی پهن** و در بازار خشن **خیلی باریک** است. Vovk و همکاران (۲۰۰۵، فصل ۴) چندک را بر یک رده‌بندی مشروط می‌کنند که از اطلاعات پیش از نتیجه ساخته شده باشد؛ اینجا رده‌بندی **دهکِ سه‌تاییِ نوسان پسرو** است، که در زمان پیش‌بینی دانسته است و بنابراین تضمین را درون هر سطل حفظ می‌کند.

هر سطل پوشش محقق‌شدهٔ خودش را منتشر می‌کند. سطلی با ماندهٔ کمتر از ۱۲۰ به باند تجمیعی برمی‌گردد، نه به چندکی که نمی‌تواند پشتیبانی کند.

### ۲۷.۵ ضریب اطلاعات هر پایه

همبستگی رتبه‌ای اسپیرمن میان امتیاز هر پایه و بازده آتی‌ای که قرار بود پیش‌بینی کند — همان ضریب اطلاعاتِ ادبیات کمی (Grinold & Kahn). رتبه‌ای و نه پیرسون، چون امتیازها کران‌دار و اشباع‌شونده‌اند.

همراه آن آمارهٔ `t = IC·√(n−2)/√(1−IC²)` منتشر می‌شود. با چند هزار جلسه، یک IC در حد چند صدم **از صفر قابل‌تمییز است** حتی اگر برای جابه‌جا کردن نمرهٔ برایر بسیار کوچک باشد؛ گفتن هر دو واقعیت دقیق‌تر از گرد کردن آن به «بی‌سیگنال» است.

### ۲۷.۶ اصالت داده — ممیزی

`IndicatorCatalog.Provenance` هر شاخص را در یکی از سه رده می‌گذارد و این رده روی صفحهٔ «شاخص‌ها» بالای همهٔ بخش‌ها منتشر می‌شود:

| رده | تعریف |
|---|---|
| `MEASURED` | خودِ سری منتشرشده، از ناشرش |
| `DERIVED` | حساب روی سری‌های اندازه‌گیری‌شده — اسپرد، نسبت، Z — با فرمول روی همان سطر |
| `PROXY` | جایگزین **اعلام‌شده** برای چیزی که هیچ منبع رایگانی منتشر نمی‌کند؛ هر کدام می‌گوید جای چه نشسته و چرا |

هیچ شاخصی تخمین زده، مدل‌سازی یا پر نمی‌شود.

### ۲۷.۷ منابع اندازه‌گیری‌شدهٔ تازه (همه بدون کلید، همه آزموده‌شده)

| منبع | چه آورد |
|---|---|
| FRED `fredgraph.csv` × ۲۴ شناسهٔ تازه | منحنی واقعی ۵/۳۰ ساله، سربه‌سر ۵ساله، ۲ساله و ۱۰سالهٔ اسمی، اسپرد ۱۰ق۲ و ۱۰ق۳م، VIX/VXN/OVX، نفت وست‌تگزاس و برنت و گاز، OAS درجه‌سرمایه‌گذاری/CCC/بازار نوظهور، شاخص دلار اقتصادهای پیشرفته، چهار نرخ ارز، ذخایر بانکی، نرخ مؤثر، ردیاب بیماری واگیر |
| CFTC `publicreporting.cftc.gov` گزارش **تفکیکی** | پوزیشن خالص **پول مدیریت‌شده** و **پوشش‌دهندگان تجاری** — تفکیکی که سطل قدیمی «غیرتجاری» پنهان می‌کرد |
| نیویورک‌فد `markets.newyorkfed.org` | پرتفوی **SOMA** (هفتگی از ۲۰۰۳) و **دم توزیع ریپو**: صدک ۹۹ام SOFR منهای میانهٔ وزنی |
| خزانه‌داری — منحنی **واقعی** کامل | ۵/۷/۱۰/۲۰/۳۰ ساله، نه فقط ده‌ساله |
| OECD SDMX | **شاخص پیشروی مرکب** ایالات متحده |

### ۲۷.۸ اثر سنجیده‌شده

| | پیش از §۲۷ | پس از §۲۷ |
|---|---|---|
| شاخص دریافتی | ۷۴ | **۹۷** |
| سری زمانی | ۴۲ | **۷۶** |
| کیفیت داده | ۰٫۷۱۴ | **۰٫۷۲۳** |
| وزن پوشش‌دادهٔ پنل | ۰٫۴۴۰ | **۰٫۷۴۰** |
| سهم اندازه‌گیری‌شدهٔ پنل | ۰٫۰۰۰ (همه جایگزین) | **۰٫۷۴۰ (همه اندازه‌گیری‌شده)** |
| جلسات نمره‌خوردهٔ رو به جلو | ۳٬۶۲۵ | **۳٬۲۵۰** (پنل کامل الزامی شد) |
| پیش‌بین‌های نمره‌خورده | ۱ | **۲** (ایزوتونیک، ریج) |
| باند ۱D | تجمیعی ±۱٫۷۷σ | **مشروط: آرام ±۲٫۰۵σ · عادی ±۱٫۸۱σ · پرتنش ±۱٫۵۵σ** (پوشش محقق ۹۰٫۱٪ در هر سه) |
| باند ۱W | تجمیعی ±۱٫۸۹σ | **آرام ±۲٫۳۴σ · عادی ±۱٫۷۷σ · پرتنش ±۱٫۳۹σ** |
| ضریب اطلاعات | سنجیده نمی‌شد | **۱D: ۱ از ۱۳ معنادار · ۱W: ۷ از ۱۳ معنادار** (قوی‌ترین F07 با `t` ۳٫۶۸) |
| پراکسی اعلام‌شده در شاخص‌ها | شمرده نمی‌شد | **۴ از ۹۷** (DXY، جریان ETF ×۲، پراکسی درون‌روزی بازده واقعی) |

**مهم‌ترین یافتهٔ تحلیلی:** در افق یک‌هفته‌ای، **هفت از سیزده پایهٔ پنل ضریب اطلاعات آماری معنادار دارند** (قوی‌ترین `IC` ۰٫۰۶۴ با `t` ۳٫۶۸ روی ۳٬۲۵۰ جلسهٔ بیرون‌نمونه). اطلاعات **واقعی است**؛ اندازه‌اش برای جابه‌جا کردن نمرهٔ برایر کافی نیست. مهارت بیرون‌نمونهٔ هر دو پیش‌بین همچنان مثبت نیست، پس احتمال منتشر نمی‌شود و وضعیت `UNCALIBRATED_NO_SKILL` می‌ماند — اما حالا سامانه دقیقاً می‌داند **کدام پایه چقدر اطلاعات دارد** و **باندش در هر رژیم نوسانی چقدر باید باشد**.

**وزن‌ها تغییر نکردند.** جدول وزن §۲ نرمتیو است و تغییر آن تصمیم جداگانه‌ای است.
