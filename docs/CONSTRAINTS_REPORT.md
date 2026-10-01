# گزارش محدودیت‌های پیاده‌سازی — Gold Intelligence Engine (Free Data Edition)

| قلم | مقدار |
|---|---|
| سند مرجع | «شاخص های طلا — نسخه رایگان نهایی.md» (Free Data Edition / Verified 2026-10-01) |
| مخزن مرجع | `github.com/alisport1384/IDIE_GLD_INT_ENGN` — commit `3732e27`، شاخه `main`، ۸ کامیت، ۴۳ فایل، ۲۱۳KB |
| تاریخ گزارش | 2026-10-01 |
| دامنه گزارش | فقط «محدودیت‌ها» برای پیاده‌سازی سند + ساخت موتور تحلیل اصلی. بدون طراحی راه‌حل، بدون roadmap، بدون تغییر دامنه. |
| روش | ۱) تحلیل کامل سند ۲) تحلیل کامل کد مخزن ۳) راستی‌آزمایی زنده endpointها از همین محیط (لاگ در پیوست ۱) |
| محل اجرای تست‌ها | IP نوع Datacenter، خروجی UK/EU |

## 0. راهنمای برچسب‌ها

| برچسب | معنی |
|---|---|
| `VERIFIED` | امروز توسط درخواست زنده یا خواندن مستقیم کد اثبات شد |
| `DOC` | ادعای خود سند؛ در این گزارش بازآزمایی نشد یا قابل بازآزمایی نبود |
| `DERIVED` | محاسبه حسابی روی اعداد VERIFIED |
| `INFERRED` | استنتاج با شواهد قوی، نه حقیقت اثبات‌شده |
| `UNKNOWN` | نامعلوم؛ نیازمند اندازه‌گیری (پیوست ۲) |

| شدت | معنی |
|---|---|
| 🔴 BLOCKER | قابلیت مربوطه در استک رایگان اصلاً ساختنی نیست |
| 🟠 CRITICAL | ساختنی است ولی خروجی از نظر آماری/معنایی بی‌اعتبار می‌شود اگر نادیده گرفته شود |
| 🟡 HIGH | کیفیت یا دقت را به‌طور محسوس پایین می‌آورد |
| 🟢 MEDIUM | محدودیت واقعی ولی قابل جذب در طراحی |

---

## 1. خلاصه عددی

| دسته | تعداد محدودیت | BLOCKER | CRITICAL | HIGH | MEDIUM |
|---|---:|---:|---:|---:|---:|
| A — شکاف داده‌ای مطلق (غیررایگان / بدون منبع) | 10 | 4 | 3 | 2 | 1 |
| B — انسداد دسترسی کشف‌شده در راستی‌آزمایی | 7 | 1 | 3 | 2 | 1 |
| C — Rate Limit / Quota | 7 | 0 | 2 | 3 | 2 |
| D — سقف عمق تاریخی (Backtest) | 10 | 1 | 4 | 4 | 1 |
| E — دریفت معنایی / Proxy | 10 | 0 | 4 | 4 | 2 |
| F — Point-in-Time / Vintage | 6 | 0 | 3 | 2 | 1 |
| G — آماری و مدل‌سازی | 12 | 2 | 6 | 3 | 1 |
| H — وضعیت فعلی مخزن | 24 | 3 | 8 | 9 | 4 |
| I — زیرساخت و عملیات | 9 | 1 | 3 | 3 | 2 |
| J — حقوقی و لایسنس | 9 | 0 | 2 | 4 | 3 |
| **جمع** | **94** | **12** | **38** | **36** | **18** |

ادعای سند: «فقط سه مورد خارج از دسترس رایگان باقی می‌ماند». نتیجه راستی‌آزمایی: آن سه مورد درست‌اند، اما **حداقل ۹ شکاف دیگر** (A4–A10، B1، B2) وجود دارد که سند یا آن‌ها را در فهرست سه‌گانه نیاورده یا دسترسی‌شان از محیط سروری امروز عملاً مسدود است.

---

## 2. دسته A — شکاف‌های داده‌ای مطلق

| ID | شکاف | وضعیت | فاکتور درگیر | اثر دقیق روی موتور | شدت | برچسب |
|---|---|---|---|---|---|---|
| A1 | CME Live Order Book — `MBO` / `MBP-10` | هیچ مسیر رایگان قانونی | F14 Microstructure | Depth، Imbalance، Aggressive Buy/Sell Volume، Kyle's Lambda واقعی، Liquidation Detection حذف می‌شوند. آیتم‌های ۴۱ و ۳۳ سند (Microstructure State / Liquidity-Adjusted Signal) فقط با Proxy قابل تقریب‌اند | 🔴 | DOC + VERIFIED (B1) |
| A2 | Consensus واقعی Bloomberg/Reuters | غیررایگان | F06 Economic Surprise | `Surprise = Actual − Consensus` با Nowcast جایگزین می‌شود؛ Nowcast مدل آماری است نه انتظار بازار. در دوره‌هایی که مدل و بازار واگرا هستند **علامت Surprise می‌تواند معکوس شود** | 🟠 | DOC |
| A3 | LBMA Historical Benchmark | توزیع لایسنس‌دار via ICE | مرجع قیمت تاریخی | Benchmark رسمی برای کالیبراسیون Reference Mid وجود ندارد؛ فقط اعتبارسنجی سه‌منبعی و میانگین‌های WGC | 🟢 | DOC |
| A4 | CME QuikStrike — IV آپشن‌های GC | فقط مشاهده وب، بدون API | F15 Options | IV واقعی آپشن فیوچرز طلا در دسترس نیست؛ کل F15 روی آپشن ETF (GLD) بنا می‌شود | 🟠 | DOC |
| A5 | CDX / Markit | غیررایگان | F18 Credit | معیار مستقیم ریسک اعتباری بازار مشتقه نیست؛ فقط OAS و شاخص‌های شرایط مالی | 🟡 | DOC |
| A6 | ISM Manufacturing / Services رسمی | غیررایگان | F06، Master List #30/#31 | دو متغیر از ۳۶ متغیر Master List منبع مستقیم ندارند؛ فقط نماگرهای منطقه‌ای Fed به‌عنوان Proxy | 🟡 | DOC |
| A7 | Shanghai Gold Premium / SGE | **سند هیچ منبع رایگانی برایش معرفی نکرده** | F19 China، آیتم‌های ۵۲ و ۵۴ | آیتم ۵۲ (China Layer) و ۵۴ (Gold Geographic Premium) بدون داده می‌مانند. تشخیص «تقاضای فیزیکی چین در لحظه» غیرممکن است؛ F19 به داده فصلی WGC تقلیل می‌یابد | 🔴 | VERIFIED (غیاب در سند) |
| A8 | India Local Premium / Import Demand ماهانه | **سند منبع رایگانی معرفی نکرده** | F20 India، آیتم ۵۳ | INR، Local Price، Physical Premium، Wedding Seasonality بدون منبع. F20 به داده فصلی WGC تقلیل می‌یابد | 🟠 | VERIFIED (غیاب در سند) |
| A9 | کل منحنی فورواردِ روزانه COMEX (Front/Next/3M/6M/12M) | **سند منبع رایگان روزانه معرفی نکرده**؛ فقط فایل دوره‌ای WGC | آیتم‌های ۴۲ و ۴۳ (Futures Basis، Term Structure، Contango/Backwardation، Calendar Spread) | این دو Feature در عمل حذف می‌شوند؛ Yahoo فقط `GC=F` (قرارداد پیوسته front) می‌دهد | 🔴 | VERIFIED |
| A10 | Real Yield درون‌روزی (TIPS) | هیچ منبع رایگان Intraday | **F01 — بزرگ‌ترین وزن مدل (۲۲٪)** | Treasury CSV و FRED `DFII10` فقط روزانه/EOD هستند. در Horizonهای `5m`/`15m`/`1H`/`4H` ورودی F01 **ثابت و بیات** است. ادعای Master List مبنی بر «Daily/Intraday» برای ردیف ۱ در استک رایگان قابل تحقق نیست | 🔴 | VERIFIED |

---

## 3. دسته B — انسدادهای دسترسی کشف‌شده در راستی‌آزمایی امروز

| ID | مورد | نتیجه تست زنده | انحراف از سند | اثر | شدت |
|---|---|---|---|---|---|
| B1 | CME Group | `daily-bulletin.html` → **403**؛ `CmeWS/.../Settlements` → **403**؛ `/ftp/bulletin/` → **403**؛ `datamine.cmegroup.com` → **اتصال برقرار نشد** | سند فقط `CmeWS` را مسدود می‌داند و «Daily Bulletin / DataMine» را مسیر رسمیِ باز معرفی می‌کند. در عمل **کل دامنه** به درخواست اسکریپتی ۴۰۳ می‌دهد | Settlement رسمی، Volume رسمی و **Open Interest روزانه COMEX** از دست می‌رود. OI فقط هفتگی از CFTC باقی می‌ماند → Master List #19 از «Daily» به «Weekly» سقوط می‌کند | 🔴 |
| B2 | GDELT DOC 2.0 | **429** در اولین درخواست و تکرار پس از ۱۰ ثانیه؛ بدنه پاسخ: «Please limit requests to one every 5 seconds…» | سند آن را «تست‌شده ✅» و فقط محدود به ۱ req/5s می‌داند | از IP نوع Datacenter، حتی با رعایت فاصله ۵ ثانیه‌ای، سرویس پاسخ نمی‌دهد. مرجع Real-time ریسک ژئوپلیتیک (F07) در محیط سروری عملاً در دسترس نیست | 🟠 |
| B3 | Yahoo Finance Chart API | با User-Agent پیش‌فرض curl → **429**؛ با UA مرورگر → **200** | سند به این شرط اشاره نکرده | کل استک Yahoo (GC=F، ZQ/SR1/SR3، DXY، SI، BZ، CL، ^GSPC، ^VIX، ^GVZ، BTC) به جعل UA وابسته است — هم شکننده، هم از نظر ToS پرریسک (J1) | 🟠 |
| B4 | سرویس‌های کلیددار | FRED بدون کلید → **400**؛ Twelve Data → **401**؛ Finnhub → **401**؛ EIA → **403** | سند کلیدها را فهرست کرده ولی EIA را در چک‌لیست کلیدها نیاورده | بدون ۵ کلید (FRED، Twelve Data، Finnhub، Alpha Vantage، EIA) + دسترسی BLS/BEA هیچ‌کدام از لایه‌های Macro/Credit/Oil راه نمی‌افتند | 🟡 |
| B5 | Yahoo `range=max&interval=1d` | **۲۶۸ کندل** برمی‌گرداند؛ همان نماد با `period1=0&period2=now&interval=1d` → **۶۶۳۰ کندل** از 2000-08-30 | سند نمونه `range=max&interval=1d` را توصیه کرده | تله Silent Degradation: پاسخ HTTP 200 است و خطا نمی‌دهد ولی گرانولاریتی بی‌سروصدا عوض می‌شود. استفاده از نمونه سند، ۹۶٪ تاریخچه روزانه را از دست می‌دهد | 🟠 |
| B6 | IMF Data Portal | `api.imf.org/external/sdmx/2.1/` → **200** | سند خودش هشدار داده: کلید اشتباه ⇒ dataset خالی با HTTP 200 | هر Quality Gate مبتنی بر «HTTP 200 = سالم» برای IMF نادرست است؛ باید «پاسخ خالی» را خطا بشمارد | 🟡 |
| B7 | Dukascopy | صفحه سرویس → **200**؛ مسیر مستقیم `datafeed.../XAUUSD/.../10h_ticks.bi5` → **503** | سند فقط `dukascopy-node` را نمونه داده | Backfill تیک XAU/USD فقط از طریق کلاینت اختصاصی قابل انجام است؛ دسترسی مستقیم HTTP تضمین‌شده نیست | 🟢 |

---

## 4. دسته C — محدودیت Rate Limit، Quota و Polling

| ID | منبع | سقف واقعی | Polling پیشنهادی سند | مصرف نتیجه‌شده | وضعیت | شدت |
|---|---|---|---|---:|---|---|
| C1 | Twelve Data Free | ۸۰۰ credit/روز و ۸ req/دقیقه | `60s` | **۱۴۴۰ req/روز** | ❌ **تناقض داخلی سند** — بیش از ۱.۸ برابر سقف. حداقل فاصله مجاز = `86400 / 800` = **۱۰۸ ثانیه** | 🟠 |
| C2 | Alpha Vantage | ۲۵ req/روز؛ کلید `demo` برای endpointهای طلا کار نمی‌کند | `2×/day` | ۲ | ✅ سازگار، ولی فقط برای Validation؛ نمی‌تواند هیچ نقش fallback زنده بازی کند | 🟡 |
| C3 | GDELT | ۱ req / ۵ ثانیه (+ مسدودسازی مشاهده‌شده B2) | `≥60s` | ۱۴۴۰ | ⚠️ از نظر عددی سازگار، از نظر عملی مسدود | 🟠 |
| C4 | gold-api.com | **سقف منتشرشده‌ای ندارد** | `10s` | **۸۶۴۰ req/روز** | ⚠️ ریسک غیرقابل اندازه‌گیری: منبع Primary اسپات، بدون SLA، بدون quota اعلام‌شده، بدون کلید ⇒ می‌تواند بدون اطلاع قطع شود | 🟡 |
| C5 | Yahoo (مجموع ۱۱ نماد) | سقف رسمی ندارد؛ 429 مشاهده شد | `GC=F:60s` + `ZQ/SR1/SR3:5m` + `Cross-Market:60s` | **≈ ۱۲٬۳۸۴ req/روز از یک IP** | ⚠️ احتمال بالای 429/بلاک؛ هیچ مسیر رسمی برای افزایش سهمیه وجود ندارد | 🟡 |
| C6 | IMF Data Portal | ~۱۰ req / ۵ ثانیه | دوره‌ای | کم | ✅ | 🟢 |
| C7 | Cboe GLD Options JSON | سقف اعلام‌نشده؛ **۳.۴۱ MiB در هر snapshot، ۸۱۴۰ قرارداد** | `15m` در ساعات بازار | ≈۲۶ snapshot/روز ⇒ **≈۸۸.۷ MiB/روز ⇒ ≈۳۱.۶ GiB/سال** (خام) | ⚠️ محدودیت پهنای‌باند و ذخیره‌سازی، نه Rate Limit | 🟢 |

---

## 5. دسته D — سقف عمق تاریخی (محدودکننده اصلی Backtest و Calibration)

### D.1 سقف اندازه‌گیری‌شده تاریخچه قیمت (`GC=F`، Yahoo، ۲۰۲۶-۱۰-۰۱)

| Interval | حداکثر بازه قابل دریافت | تعداد کندل اندازه‌گیری‌شده | محدودیت دقیق |
|---|---|---:|---|
| `1m` | **۸ روز در هر درخواست** | ۱۰٬۸۱۰ (۸ روز) | `range=1mo` → HTTP **422**: «Only 8 days worth of 1m granularity data are allowed to be fetched per request» — **آرشیو تاریخی ۱ دقیقه‌ای وجود ندارد** |
| `5m` | ۶۰ روز | ۱۷٬۱۴۱ | `range=730d` → **422** |
| `15m` | ۶۰ روز | ۵٬۷۱۵ | `range=730d` → **422** |
| `1h` | ۷۳۰ روز | ۱۷٬۳۹۶ | سقف سخت ۲ سال |
| `1d` | از 2000-08-30 | **۶٬۶۳۰** | فقط با `period1/period2`؛ با `range=max` فقط ۲۶۸ (رجوع به B5) |

### D.2 سقف تاریخچه سایر منابع

| منبع | شروع تاریخچه | حجم نمونه | پیامد |
|---|---|---:|---|
| Cboe `GVZ_History.csv` | **1992→خیر؛ 2009-09-18** | ۴٬۲۸۲ رکورد روزانه | هیچ داده IV طلا قبل از سپتامبر ۲۰۰۹ وجود ندارد |
| Cboe GLD Options Chain | **بدون تاریخچه — فقط Snapshot لحظه‌ای** | ۰ | IV Skew، 25Δ Risk Reversal، IV Term Structure، Put/Call Ratio در روز صفر **هیچ تاریخچه‌ای برای Backtest ندارند**؛ باید از امروز انباشته شوند |
| CFTC Disaggregated `72hh-3qpy` (GOLD) | 2006-06-13 | **۱٬۰۵۹ گزارش هفتگی** | کل پنجره Managed Money ≈ ۲۰.۳ سال |
| CFTC Legacy `6dca-aqww` (GOLD) | 1986-01-15 | ۱٬۹۳۵ گزارش هفتگی | عمیق‌تر ولی بدون تفکیک Managed Money |
| Fed RSS / News RSS | **بدون تاریخچه** | ۰ | Event Engine و News Intelligence از لحظه راه‌اندازی Collector شروع می‌شوند؛ هیچ Backfill رویدادی ممکن نیست |
| GPR روزانه | طولانی ولی **انتشار با تأخیر** | — | برای Real-time قابل اتکا نیست؛ فقط Benchmark/Calibration |

### D.3 محدودیت مشتق — سقف ترکیبی (Binding Constraint)

| ID | محدودیت | محاسبه | شدت |
|---|---|---|---|
| D-J1 | پنجره مشترک همه فاکتورها = کوتاه‌ترین عضو | هر مدلی که هم‌زمان به GVZ و COT تفکیکی نیاز دارد، سقفش ≈ **۸۵۵ مشاهده هفتگی** (از ۲۰۰۹-۰۹) است، نه ۲۶ سال | 🟠 |
| D-J2 | Horizonهای `5m` و `15m` که در `GoldSpecification.requiredHorizons` الزامی شده‌اند | فقط **۶۰ روز** تاریخچه فیوچرز دارند ⇒ Backtest معنادار، Walk-Forward و Out-of-Sample برای این دو Horizon در روز صفر **غیرممکن** است | 🔴 |
| D-J3 | Horizon `1W` | از ۶٬۶۳۰ کندل روزانه ⇒ ≈۱٬۳۶۰ مشاهده هفتگی مستقل؛ با تفکیک ۱۱ Regime ⇒ به‌طور میانگین ۱۲۴ نمونه/Regime و برای Regimeهای بحرانی بسیار کمتر | 🟠 |
| D-J4 | «Data Clock از امروز شروع می‌شود» | زنجیره آپشن، RSS، تیک ۱ دقیقه‌ای، Order Flow Proxy، Event Reaction Score و Reaction Efficiency همگی تاریخچه صفر دارند | 🟠 |

---

## 6. دسته E — دریفت معنایی و Proxy

| ID | جایگزینی | تفاوت ماهوی | اثر روی موتور | شدت |
|---|---|---|---|---|
| E1 | آپشن GLD به‌جای آپشن COMEX GC | ETF Options ≠ Futures Options؛ اندازه قرارداد، ساعت معامله، Settlement و پایه متفاوت | F15 با برچسب `PROXY`؛ Gamma Exposure و OI Distribution به بازار دیگری اشاره می‌کنند | 🟠 |
| E2 | تیک XAU/USD Dukascopy به‌جای ریزساختار COMEX | اسپات OTC ≠ فیوچرز متمرکز؛ نقدشوندگی، ساعت و Spread متفاوت | F14 صرفاً Proxy؛ Amihud، Roll و Volume Clock محاسبه‌شده به بازار COMEX قابل تعمیم نیستند | 🟠 |
| E3 | Nowcast/SPF/GDPNow به‌جای Consensus | مدل آماری ≠ انتظار بازار | **علامت Surprise ممکن است برعکس شود**؛ Trigger→Response Matrix (آیتم ۶) روی ورودی غلط کالیبره می‌شود | 🟠 |
| E4 | FRED `DTWEXBGS` به‌عنوان اعتبارسنج `DX-Y.NYB` | سبد ارزی و وزن‌ها کاملاً متفاوت است (Broad Trade-Weighted ≠ DXY شش‌ارزی) | Cross-Validation بین دو شاخص **متفاوت** انجام می‌شود؛ هر اختلافی لزوماً خطای داده نیست | 🟠 |
| E5 | gold-api به‌عنوان Primary اسپات | پاسخ فقط `price` دارد — **بدون bid/ask و بدون volume** (تأیید شد) | هیچ Spread Feature از منبع Primary استخراج نمی‌شود؛ آیتم ۴۱ سند از این مسیر تأمین نمی‌شود | 🟡 |
| E6 | Treasury Real Yield Curve | هدر فایل ۲۰۲۶ تأیید شد: `Date, 5 YR, 7 YR, 10 YR, 20 YR, 30 YR` — **کوتاه‌ترین سررسید ۵ ساله است** | «Real Yield ۲ ساله» و منحنی واقعی کوتاه‌مدت در دسترس نیست؛ Factor 1 سند که `5Y Real Yield` را می‌خواهد تأمین می‌شود ولی هر چیز کوتاه‌تر نه | 🟡 |
| E7 | `GC=F` قرارداد پیوسته | Yahoo قرارداد front را Roll می‌کند بدون Adjustment اعلام‌شده | در تاریخ‌های Roll، جهش مصنوعی قیمت وارد Momentum، Realized Volatility و `Gold_Move_Sigma` می‌شود ⇒ **Shock Engine می‌تواند Roll را به‌عنوان Shock واقعی تشخیص دهد** | 🟠 |
| E8 | Volume از Yahoo به‌جای Volume رسمی CME | تجمیع شخص ثالث | Master List #20 (Gold Volume) غیررسمی می‌ماند؛ Cross-Validate با CME (مسدود، B1) ممکن نیست | 🟡 |
| E9 | COMEX OI روزانه → CFTC هفتگی | فرکانس از روزانه به هفتگی با تأخیر انتشار | «تأیید حرکت با OI» که سند در Regime E می‌خواهد، درون‌روزی غیرممکن است | 🟡 |
| E10 | `^TNX` درون‌روزی در کنار Treasury CSV روزانه | دو کنوانسیون اندازه‌گیری و دو زمان‌بندی متفاوت | اختلاط این دو در یک Feature، ناپیوستگی در سری زمانی ایجاد می‌کند | 🟢 |

---

## 7. دسته F — Point-in-Time، Vintage و Look-Ahead

| ID | محدودیت | جزئیات | شدت |
|---|---|---|---|
| F1 | Vintage فقط برای FRED/ALFRED وجود دارد | Treasury CSV، WGC XLSX، SPDR، Cboe، GPR، BIS، CFTC هیچ API نسخه‌بندی ندارند. «First Released Value» برای این منابع **به‌صورت گذشته‌نگر قابل بازسازی نیست** | 🟠 |
| F2 | فایل‌های WGC بازنویسی می‌شوند | ارقام Demand و Reserves در انتشارهای بعدی Restate می‌شوند و نسخه قبلی حذف می‌شود | هر Backtest روی F10/F12/F19/F20 با داده Revised اجرا می‌شود ⇒ **Look-Ahead Bias ساختاری** | 🟠 |
| F3 | تأخیر انتشار COT | Position Date = سه‌شنبه، انتشار = جمعه ۱۵:۳۰ ET | اگر در Backtest مدل‌سازی نشود، ۳ روز اطلاعات آینده نشت می‌کند | 🟡 |
| F4 | RSS بدون تاریخچه | Fed RSS و News RSS فقط آیتم‌های جاری را می‌دهند | Event Engine تا زمان انباشت کافی، نمونه ندارد؛ «Historical Reaction» و «Reaction Efficiency» تا ماه‌ها قابل محاسبه نیستند | 🟠 |
| F5 | GPR بازنگری می‌شود | سری روزانه با تأخیر و بازنگری منتشر می‌شود | مقدار امروزِ GPR با مقداری که بعداً ثبت می‌شود یکی نیست | 🟡 |
| F6 | مدل داده موتور فیلد `ingestion_time` ندارد | `Observation` در `Model.kt` فقط `timestamp`، `releaseTimestamp` و `latencySeconds` دارد | قاعده صریح سند `ingestion_time <= backtest_time` **درون موتور قابل اعمال نیست**؛ فقط در `TimeSynchronizer` ماژول ingestion به‌صورت جزئی پوشش داده شده | 🟢 |

---

## 8. دسته G — محدودیت‌های آماری و مدل‌سازی موتور

| ID | محدودیت | عدد / دلیل | شدت |
|---|---|---|---|
| G1 | کمبود نمونه رویدادی | FOMC ۸ بار/سال، CPI ۱۲، NFP ۱۲، PCE ۱۲، GDP(advance) ۴. حتی با ۲۰ سال تاریخچه: FOMC ≈۱۶۰، CPI ≈۲۴۰. با تفکیک ۱۱ Regime ⇒ به‌طور میانگین ≈۱۵ FOMC در هر Regime و در Regimeهای نادر نزدیک صفر | 🟠 |
| G2 | انفجار پارامتر | ۲۲ فاکتور × ۱۱ Regime × ۶ Horizon = **۱٬۴۵۲ ترکیب وزن** در برابر حداکثر ۶٬۶۳۰ مشاهده روزانه ⇒ نسبت نمونه به پارامتر برای تخمین پایدار کافی نیست | 🟠 |
| G3 | Calibration (Platt / Isotonic) | برای اینکه «۷۰٪ واقعاً ۷۰٪» باشد، به چند صد نمونه در **هر Horizon و هر Regime** نیاز است. در `5m`/`15m` با ۶۰ روز تاریخچه این شرط برقرار نیست ⇒ خروجی Probability تا مدت‌ها غیرقابل‌کالیبره می‌ماند | 🟠 |
| G4 | Historical Analogue Engine | نیازمند پنل Point-in-Time چندفاکتوری است که هنوز وجود ندارد (D-J4، F1) | 🔴 |
| G5 | Causal Graph و Counterfactual | شناسایی علّی از داده مشاهده‌ای صرف ممکن نیست؛ آیتم‌های ۶۲ و ۶۳ سند بدون مدل ساختاری یا شناسایی‌کننده، **قابل اثبات نیستند**؛ خروجی Counterfactual به یک تحلیل حساسیت تنزل می‌یابد | 🔴 |
| G6 | عدم تطابق فرکانس در Horizonهای کوتاه | در `5m`: F01 روزانه (A10)، F05 ماهانه، F10/F12/F19/F20 فصلی ⇒ **۸ فاکتور از ۲۲ فاقد محتوای اطلاعاتی‌اند** ولی وزن می‌گیرند. هشدار صریح خود سند درباره Frequency Mismatch در استک رایگان تشدید می‌شود | 🟠 |
| G7 | نبود Ground Truth برای Regime | ۱۱ Regime برچسب واقعی ندارند؛ هیچ معیار بیرونی برای سنجش «Regime Accuracy» که سند در Model Validation خواسته وجود ندارد | 🟠 |
| G8 | Structural Break / Model Drift Detection | نیازمند پنجره طولانی و پایدار؛ در Featureهای بدون تاریخچه (آپشن، News، Microstructure) اصلاً قابل اجرا نیست | 🟡 |
| G9 | Lead-Lag Engine | تخمین Lag 0/1/3/5/10/20 در تایم‌فریم درون‌روزی محدود به پنجره ۶۰ روزه است ⇒ تخمین Lag در Regimeهای مختلف غیرقابل اتکا | 🟡 |
| G10 | Reflexivity | سند خودش می‌گوید گاهی `Gold → Factor`؛ این یعنی درون‌زایی (Endogeneity) و تخمین‌های تک‌معادله‌ای اریب می‌شوند | 🟡 |
| G11 | تضاد Normalization با Missing Data Policy | `NormalizingDynamicWeightEngine` وزن‌ها را بر مجموع تقسیم می‌کند؛ وقتی فاکتوری غایب است وزنش **به‌صورت خودکار بین بقیه توزیع می‌شود** و خروجی مثل حالت کامل به‌نظر می‌رسد — بدون آنکه Confidence کاهش یابد. سند صراحتاً «Weight Penalty» خواسته، نه توزیع مجدد بی‌صدا | 🟠 |
| G12 | Overfitting | ترکیب داده کم (D) + پارامتر زیاد (G2) + انتخاب آستانه دستی (`CalibrationDefaults`) محیط کلاسیک Overfitting است | 🟢 |

---

## 9. دسته H — وضعیت فعلی مخزن `IDIE_GLD_INT_ENGN` (commit `3732e27`)

### H.1 وضعیت ۱۸ لایه الزامی سند

| # | لایه (`GoldSpecification.requiredLayers`) | وضعیت واقعی در کد | فایل |
|---|---|---|---|
| 1 | DATA | ⚠️ فقط مدل داده؛ **بدون Store، بدون Raw Payload Archive، بدون Vintage Store** | `Model.kt`, `RawModel.kt` |
| 2 | FEATURES | ❌ `IdentityFeatureEngineer` فقط ورودی را بازمی‌گرداند — **هیچ Feature Engineering وجود ندارد** | `CoreEngines.kt:12` |
| 3 | FACTORS | ❌ `SuppliedFactorEngine.score()` همیشه `emptyList()` — **هیچ فاکتوری محاسبه نمی‌شود** | `CoreEngines.kt:16` |
| 4 | CAUSALITY | ❌ ماژول گراف علّی وجود ندارد | — |
| 5 | EVENTS / NEWS | ⚠️ فقط تابع امتیازدهی؛ `NoOpNewsScoringAdapter` همیشه `emptyList()` | `EventNews.kt`, `SnapshotAdapter.kt:19` |
| 6 | MICROSTRUCTURE | ❌ فقط یک کلید (`MICROSTRUCTURE_IMBALANCE`)، وزن F14 = ۰ | `FeatureKeys.kt` |
| 7 | REGIME | ✅ پیاده‌سازی شده (آستانه‌محور، priors) | `RegimeDetector.kt` |
| 8 | INFORMATION DOMINANCE | ⚠️ **Proxy دایره‌وار** — از `abs(score)` ساخته می‌شود؛ یعنی فاکتوری که بزرگ‌ترین امتیاز را دارد وزن بیشتری می‌گیرد و امتیازش را بزرگ‌تر می‌کند. این «اهمیت مورد توجه بازار» نیست | `ScoringPipeline.kt:85` |
| 9 | DYNAMIC WEIGHTS | ✅ فرمول سند پیاده شده | `CoreEngines.kt:25` |
| 10 | INTERACTIONS | ⚠️ فقط ۳ خوشه (Monetary، Inflation، Trend) | `InteractionEngine.kt` |
| 11 | DIVERGENCE | ⚠️ **۳ از ۶ نوع** (MACRO، FLOW، POSITIONING). NEWS، PRICE و CROSS_MARKET در enum تعریف شده‌اند ولی هرگز تولید نمی‌شوند | `DivergenceEngine.kt` |
| 12 | HISTORICAL ANALOGUES | ❌ `NoHistoricalAnalogueEngine` → `emptyList()` | `CoreEngines.kt:68` |
| 13 | ENSEMBLE | ❌ وجود ندارد؛ هیچ Macro/Flow/Market/News/Regime Model مستقلی نیست ⇒ `Model Disagreement` قابل محاسبه نیست | — |
| 14 | PROBABILITY | ⚠️ `HeuristicProbabilityModel` — خود README آن را «placeholder غیرکالیبره» می‌نامد | `HeuristicProbabilityModel.kt` |
| 15 | CALIBRATION | ❌ `NoOpCalibrator` ورودی را بدون تغییر برمی‌گرداند — **لایه Calibration عملاً وجود ندارد** | `CoreEngines.kt:50` |
| 16 | CONFIDENCE | ⚠️ فقط `میانگین Quality − ۰.۱×تعداد تناقض`. چهار جزء دیگر فرمول سند (Model Agreement، Regime Stability، Cross-Market Confirmation، Uncertainty) غایب‌اند | `CoreEngines.kt:54` |
| 17 | SCENARIOS | ⚠️ چهار سناریو با متن ثابت؛ `probability = null` و `expectedMagnitude = null` در هر چهار مورد | `ScenarioEngine.kt` |
| 18 | INVALIDATION | ⚠️ یک جمله تولیدشده از نام فاکتور غالب؛ **بدون هیچ آستانه عددی** | `InvalidationRuleEngine.kt` |

**جمع:** ۲ لایه کامل، ۱۰ لایه ناقص، ۶ لایه غایب.

### H.2 یافته‌های کد-محور (همگی `VERIFIED` با خواندن مستقیم)

| ID | یافته | محل | اثر | شدت |
|---|---|---|---|---|
| H-01 | **۱۳ فاکتور از ۲۲ وزن پایه صفر دارند** (F04، F06، F08، F10، F14، F15، F16، F17، F18، F19، F20، F21، F22) | `Specification.kt:5-27` | این فاکتورها حتی اگر امتیاز بگیرند، سهمشان در Gold Bias دقیقاً صفر است. ۵۹٪ معماری فاکتوری غیرفعال است | 🔴 |
| H-02 | **مجموع وزن‌های پایه = ۰.۹۵ است، نه ۱.۰۰** | همان | ۵٪ وزن گروه Growth سند (F06) جا افتاده؛ چون Normalize می‌شود خطا سکوت می‌کند | 🟠 |
| H-03 | **F10 Central Bank Demand وزن صفر گرفته** در حالی که سند آن را داخل Gold Flow ۱۰٪ گنجانده بود | همان | «یکی از مهم‌ترین متغیرهای ساختاری بازار طلا» طبق سند، در مدل بی‌اثر است | 🟠 |
| H-04 | `requiredHorizons` و `requiredLayers` تعریف شده‌اند ولی **هیچ‌جا استفاده یا اعمال نمی‌شوند** | `Specification.kt:29,31` | هیچ تضمینی برای پوشش ۱۸ لایه و ۶ Horizon وجود ندارد | 🟡 |
| H-05 | **خروجی تک‌افقی است** — `GoldIntelligenceState` هیچ فیلد Horizon ندارد | `Model.kt:112` | الزام Time-Horizon Output سند (`5m/15m/1H/4H/1D/1W`) اصلاً در مدل خروجی وجود ندارد | 🟠 |
| H-06 | `expectedMove = null` به‌صورت hard-coded در هر دو مسیر خروجی | `GoldIntelligenceEngine.kt:122,158` | Expected Move / Range / Monte Carlo (آیتم‌های ۴ و ۶۵) غایب | 🟡 |
| H-07 | `crossMarketConfirmation = null` به‌صورت hard-coded | `GoldIntelligenceEngine.kt:118,154` | Cross-Market Confirmation Score (آیتم ۲۶) غایب، در حالی که در فرمول Confidence سند جزء لازم است | 🟡 |
| H-08 | `StrictDataValidator` فقط **۲ از ۸ Quality Gate** سند را دارد | `CoreEngines.kt:3` | Invalid Unit، Cross-Source Divergence، Missing≠Zero، Revision-aware، Rate-limit aware، License-aware هیچ‌کدام وجود ندارند | 🟠 |
| H-09 | شرط `it.timestamp != null` روی یک `Instant` **غیرnullable** | `CoreEngines.kt:7` | همیشه true؛ گیت «بدون timestamp → reject» عملاً بی‌اثر است | 🟡 |
| H-10 | `TIME_DECAY_HALF_LIFE_HOURS = 72.0` در فرمول `exp(-age/72)` استفاده شده | `Specification.kt`, `ScoringPipeline.kt:97` | این ثابت زمانی (e-folding) است نه نیمه‌عمر؛ در ۷۲ ساعت مقدار ۰.۳۶۸ می‌شود نه ۰.۵ ⇒ نرخ فراموشی ≈۱.۴۴ برابر چیزی است که نام متغیر وعده می‌دهد | 🟢 |
| H-11 | Information Dominance دایره‌وار (رجوع به H.1 ردیف ۸) | `ScoringPipeline.kt:85` | خودتقویتی وزن؛ ریسک تشدید سیگنال غلط | 🟠 |
| H-12 | `Observation` فاقد ۷ فیلد الزامی Data Contract سند است: `source_tier`، `frequency`، `vintage`، `availability_time`، `license_class`، `ingestion_time`، `coverage_status`/`PROXY` | `Model.kt:21` | قواعد Free-Stack سند (Tier Priority، برچسب PROXY، License-aware gate، UNVERIFIED exclusion) **قابل اجرا نیستند** | 🟠 |
| H-13 | `factor_id`/`variable_id`/`data_quality` فقط جزئی پوشش دارند | `Model.kt` | ردیابی منبع تا فاکتور ناقص است | 🟢 |
| H-14 | **ماژول `ingestion` قرارداد Databento (پولی) را فرض کرده** | `Sources.kt:7-9`, `README.md` | تضاد مستقیم با Free Data Edition؛ Market Core فعلی مخزن روی منبع پولی بنا شده | 🟠 |
| H-15 | README می‌گوید «Gold Spot → dedicated Spot reference feed» و «Macro → Treasury/FRED/CME» | `README.md` | معماری سه‌منبعی اسپات (gold-api + Twelve Data + Alpha Vantage + فیلتر MAD) سند در قرارداد مخزن منعکس نشده | 🟡 |
| H-16 | `SnapshotAdapter` مقدار `quality = 1.0` را برای همه مشاهدات ثابت می‌گذارد | `SnapshotAdapter.kt:44,56,69` | Data Quality Engine سند (آیتم ۱۴) عملاً خنثی است؛ Effective Weight همیشه ضریب کیفیت ۱ می‌گیرد | 🟠 |
| H-17 | هیچ لایه Persistence، Scheduler، Retry/Backoff، Rate-Limit Guard یا Cache وجود ندارد | کل مخزن | کل بلوک «DATA INGESTION SERVER» سند پیاده نشده | 🟠 |
| H-18 | هیچ REST/WebSocket API وجود ندارد | کل مخزن | حلقه `GOLD INTELLIGENCE STATE → REST/WS → ANDROID APP` بسته نیست | 🟡 |
| H-19 | `engine` و `ingestion` هر دو `com.android.library` هستند | `engine/build.gradle.kts`, `ingestion/build.gradle.kts` | **تضاد معماری با سند**: سند صریحاً می‌گوید جمع‌آوری و موتور روی سرور اجرا شود (`Android → Your Server → Free Providers`). ماژول Android Library روی JVM سروری اجرا نمی‌شود | 🔴 |
| H-20 | تنها داده موجود در اپ، `SampleData` ثابت است | `SampleData.kt` | هیچ مسیر داده واقعی از سر تا ته وجود ندارد | 🟡 |
| H-21 | `SurpriseEventEngine` ساخته شده ولی **در `GoldIntelligenceEngine` تزریق نشده**؛ `input.events` در هیچ مسیری مصرف نمی‌شود | `GoldIntelligenceEngine.kt`, `Model.kt:55` | Event Engine عملاً از خط لوله خارج است | 🟡 |
| H-22 | `newsState` فقط از پرتأثیرترین خبر برچسب می‌گیرد؛ Narrative vs Reality، Reaction Efficiency و News Divergence پیاده نشده‌اند | `GoldIntelligenceEngine.kt` | آیتم‌های ۲۲ تا ۲۴ سند غایب | 🟡 |
| H-23 | هیچ Kill-Switch مستقل (آیتم ۳۷) وجود ندارد | — | `SignalState` فقط از Confidence و Shock مشتق می‌شود؛ شرط ترکیبی Data Quality↓ + Transition + Contradiction↑ + Drift↑ وجود ندارد | 🟡 |
| H-24 | هیچ تست Backtest، Walk-Forward یا Calibration در مخزن نیست | `src/test` | ۶ فایل تست، همگی unit روی قواعد؛ هیچ اعتبارسنجی آماری | 🟠 |

### H.3 محدودیت‌های Build و CI

| ID | یافته | شدت |
|---|---|---|
| H-25 | **`gradle/wrapper/gradle-wrapper.jar` وجود ندارد**؛ `gradlew` یک اسکریپت دست‌نویس است که اگر `gradle` سیستمی نباشد Gradle را از اینترنت دانلود می‌کند. ضمناً **بیت اجرا ندارد** (`-rw-r--r--`) ⇒ دستور مستند README یعنی `./gradlew assembleDebug` در یک clone تازه **اجرا نمی‌شود** | 🟠 |
| H-26 | CI فقط `gradle assembleDebug` را اجرا می‌کند — **`gradle test` اجرا نمی‌شود** ⇒ ۶ فایل تست موجود هیچ‌گاه در CI ارزیابی نمی‌شوند | 🟠 |
| H-27 | مخزن **فاقد فایل `LICENSE`** است، در حالی که عمومی است و قرار است داده‌هایی با شرایط Attribution/Redistribution را پردازش کند | 🟡 |
| H-28 | مخزن **فاقد `.gitignore`** است ⇒ ریسک کامیت شدن `local.properties`، کلیدهای API و خروجی‌های build | 🟠 |
| H-29 | ساخت نیازمند دانلود AGP 8.13.1، Kotlin 2.2.21، Gradle 9.5.0 و Android SDK 36 است؛ در محیط بدون شبکه قابل build نیست | 🟢 |

---

## 10. دسته I — زیرساخت و عملیات

| ID | محدودیت | جزئیات | شدت |
|---|---|---|---|
| I1 | نیاز به سرور ۲۴/۷ | Polling با فاصله ۱۰ تا ۶۰ ثانیه، RSS هر ۳۰ ثانیه و انباشت ۱ دقیقه‌ای فقط با پروسه دائمی ممکن است؛ اپ اندروید نمی‌تواند Collector باشد | 🟠 |
| I2 | ناسازگاری Runtime | `engine` و `ingestion` ماژول Android Library‌اند (H-19) ⇒ اجرای سروری بدون تغییر نوع ماژول ممکن نیست | 🔴 |
| I3 | ذخیره‌سازی زنجیره آپشن | ≈۳۱.۶ GiB/سال خام فقط برای `GLD.json` (C7) | 🟡 |
| I4 | الزام Raw Payload Archive | سند آرشیو payload خام را اجباری کرده ⇒ ضریب ذخیره‌سازی مضاعف روی همه منابع | 🟡 |
| I5 | اعتبار IP | Yahoo (429 بدون UA مرورگر)، GDELT (429 مستمر)، CME (403) همگی به IP نوع Datacenter حساس‌اند — این دقیقاً محیطی است که سرور Production در آن اجرا می‌شود | 🟠 |
| I6 | مدیریت کلید | ۵ کلید + ۲ دسترسی؛ سند درست می‌گوید نباید در APK باشند ⇒ الزام Secret Store سمت سرور | 🟡 |
| I7 | نبود Cache مرکزی | بدون آن، چند Instance یا چند Horizon، سهمیه رایگان مشترک را چند برابر مصرف می‌کنند (مستقیماً C1 را نقض می‌کند) | 🟡 |
| I8 | محدودیت منطقه‌ای | Binance در UK/EU؛ سند جایگزین Coinbase/Kraken داده (Coinbase امروز ۲۰۰ داد) | 🟢 |
| I9 | دقت ساعت | الزام Timestamp Integrity سند به NTP پایدار و ثبت UTC در همه نقاط نیاز دارد؛ هیچ تضمینی در کد فعلی نیست | 🟢 |

---

## 11. دسته J — حقوقی و لایسنس

| ID | منبع | محدودیت | اثر | شدت |
|---|---|---|---|---|
| J1 | Yahoo Finance | API غیررسمی، بدون ToS مجاز برای استفاده تجاری/بازتوزیع؛ نیازمند جعل UA (B3) | ستون فقرات قیمتی استک (۱۱ نماد) روی پایه‌ای بنا شده که می‌تواند هر زمان بسته یا منع شود | 🟠 |
| J2 | GDELT | بازتوزیع متادیتا مجاز، متن کامل خیر | ذخیره Body مقالات ممنوع ⇒ NLP فقط روی عنوان/چکیده | 🟡 |
| J3 | News RSS | فقط عنوان + چکیده + لینک | کیفیت FinBERT/NER روی متن کوتاه پایین‌تر از حالت متن کامل | 🟡 |
| J4 | WGC / Goldhub | رایگان با شرط Attribution | الزام نمایش Attribution در اپ | 🟢 |
| J5 | Dukascopy | مناسب پژوهش/Backtest طبق شرایط سرویس | استفاده در محصول زنده عمومی خارج از این چارچوب است | 🟡 |
| J6 | CME | ضد-Scraping، اکنون با 403 سخت اعمال می‌شود (B1) | هر تلاش برای دور زدن، هم فنی و هم حقوقی مردود است | 🟠 |
| J7 | gold-api / Twelve Data / Finnhub | Free Tier؛ بازتوزیع Raw Data مجاز فرض نشود | **خروجی REST/WebSocket عمومی اپ نباید قیمت خام این منابع را منتشر کند** — فقط مقادیر مشتق/تجمیعی | 🟡 |
| J8 | مخزن عمومی بدون LICENSE | H-27 | وضعیت حقوقی کد و داده‌های مشتق نامشخص | 🟡 |
| J9 | سازگاری لایه API سند | معماری سند `GOLD INTELLIGENCE STATE → REST/WebSocket → ANDROID APP` است | این لایه باید فقط State مشتق‌شده را بدهد، نه سری‌های خام — محدودیتی که در طراحی API باید از ابتدا قفل شود | 🟢 |

---

## 12. ماتریس اثر نهایی روی ۲۲ فاکتور

| فاکتور | ادعای سند | سطح واقعاً قابل دستیابی | محدودیت تعیین‌کننده | وزن در کد |
|---|---|---|---|---:|
| F01 Real Yield | COVERED | ⚠️ **روزانه فقط** | A10 — بدون منبع Intraday | 0.22 |
| F02 USD | COVERED | ✅ Intraday | B3 (وابستگی به Yahoo) | 0.16 |
| F03 Fed Expectations | COVERED | ✅ Intraday (محاسبه داخلی) | B3 + نیاز به Validation با FedWatch | 0.14 |
| F04 Treasury Curve | COVERED | ⚠️ Nominal درون‌روزی با `^TNX`، Real روزانه، منحنی واقعی روزانه | A10، E6، E10 | **0.00** |
| F05 Inflation | COVERED | ✅ (Breakeven روزانه، CPI/PCE ماهانه) | A2 برای Surprise | 0.08 |
| F06 Economic Surprise | PARTIAL | ⚠️ Proxy | A2، A6 | **0.00** |
| F07 Geopolitical Risk | COVERED | ⚠️ Real-time مسدود؛ فقط GPR با تأخیر | **B2** | 0.12 |
| F08 Financial Stress | COVERED | ✅ روزانه | کلید FRED | **0.00** |
| F09 Gold ETF Flow | COVERED | ✅ روزانه | F2 (بدون Vintage) | 0.10 |
| F10 Central Bank Demand | COVERED | ✅ ماهانه/فصلی | F2، **وزن صفر در کد** | **0.00** |
| F11 Futures Positioning | COVERED | ⚠️ هفتگی؛ OI روزانه از دست رفته | **B1**، E9 | 0.06 |
| F12 Physical Demand | COVERED | ⚠️ فصلی | F2 | 0.02 |
| F13 Market Momentum | COVERED | ✅ Intraday | E7 (Roll) | 0.05 |
| F14 Microstructure | PARTIAL | ❌ **فقط Proxy اسپات** | **A1 + B1** | **0.00** |
| F15 Options / Volatility | COVERED | ⚠️ GVZ مستقیم؛ زنجیره PROXY و **بدون تاریخچه** | A4، E1، D.2 | **0.00** |
| F16 Cross-Asset | COVERED | ✅ Intraday | B3 | **0.00** |
| F17 Liquidity | COVERED | ⚠️ روزانه | — | **0.00** |
| F18 Credit | COVERED | ✅ روزانه | A5 | **0.00** |
| F19 China | COVERED | ❌ فصلی؛ Premium/PBOC/SGE بدون منبع | **A7** | **0.00** |
| F20 India | COVERED | ❌ فصلی؛ Premium/INR بدون منبع | **A8** | **0.00** |
| F21 Oil / Energy | COVERED | ✅ Intraday | کلید EIA (B4) | **0.00** |
| F22 Global CB Policy | COVERED | ✅ روزانه | — | **0.00** |

**نتیجه ماتریس:** از ۲۲ فاکتور، ۸ فاکتور واقعاً «COVERED با کیفیت کامل»، ۹ فاکتور تنزل‌یافته (فرکانس یا Proxy)، ۵ فاکتور عملاً بدون داده کاربردی. مستقلاً، ۱۳ فاکتور در کد وزن صفر دارند.

---

## 13. سقف واقعی قابل دستیابی برای خروجی موتور

| فیلد خروجی `GOLD INTELLIGENCE STATE` | قابل تولید؟ | محدودیت مسدودکننده |
|---|---|---|
| Direction | ✅ | — |
| Gold Bias (−100..+100) | ✅ | H-01 (فقط از ۹ فاکتور) |
| Probability | ⚠️ غیرکالیبره | G3، H.1-14 |
| Calibrated Probability | ❌ | **G3 + H.1-15** |
| Confidence | ⚠️ ناقص | H.1-16، H-07 |
| Regime | ✅ | آستانه‌ها prior‌اند |
| Regime Stability | ⚠️ دودویی | G7 |
| Dominant Factor | ⚠️ دایره‌وار | H-11 |
| Attribution | ✅ | — |
| Contradictions / Conflict | ✅ | — |
| Divergences | ⚠️ ۳ از ۶ | H.1-11 |
| Cross-Market Confirmation | ❌ | H-07 |
| News State | ⚠️ برچسب ساده | H-22، B2 |
| Liquidity | ⚠️ روزانه | A10-خانواده |
| Shock | ⚠️ ریسک False Positive روی Roll | E7 |
| **Expected Move / Range / Distribution** | ❌ | H-06، G3 |
| **Time-Horizon Output (۶ افق)** | ❌ | **H-05 + D-J2** |
| Scenarios با احتمال عددی | ❌ | H.1-17، G3 |
| Historical Analogue Outcome | ❌ | **G4** |
| Invalidation با آستانه عددی | ❌ | H.1-18 |
| Model Ensemble / Disagreement | ❌ | H.1-13 |
| Counterfactual Contribution | ❌ | G5 |
| Signal State | ✅ | — |

---

## 14. محدودیت‌های مسدودکننده در برابر قابل‌مدیریت

### ۱۴.۱ مسدودکننده (BLOCKER) — با استک رایگان فعلی ساختنی نیستند

| ID | مورد |
|---|---|
| A1 | Depth واقعی CME |
| A7 | Shanghai Premium / China Layer لحظه‌ای |
| A9 | Futures Basis و Term Structure روزانه |
| A10 | Real Yield درون‌روزی (پرچم قرمز اصلی: بزرگ‌ترین وزن مدل) |
| B1 | Settlement / Volume / OI روزانه رسمی COMEX |
| D-J2 | Backtest افق‌های `5m` و `15m` |
| G4 | Historical Analogue Engine |
| G5 | Causal / Counterfactual اثبات‌پذیر |
| H-01 | ۱۳ فاکتور با وزن صفر |
| H-19 / I2 | اجرای موتور روی سرور با ماژول Android Library |

### ۱۴.۲ قابل‌مدیریت ولی نیازمند تصمیم صریح

| ID | موضوع تصمیم |
|---|---|
| C1 | کاهش Polling تِوِلو دیتا از ۶۰s به ≥۱۰۸s (تناقض سند) |
| B5 | جایگزینی `range=max` با `period1/period2` |
| E7 | سیاست Roll Adjustment برای `GC=F` |
| G11 | جایگزینی Normalization با Weight Penalty در داده غایب |
| H-02/H-03 | اصلاح جدول وزن‌ها و جایگاه F06/F10 |
| H-12 | توسعه `Observation` به Data Contract کامل سند |
| H-25/H-26/H-28 | wrapper jar، اجرای تست در CI، `.gitignore` |
| J7/J9 | قفل کردن API عمومی روی خروجی مشتق |

---

## پیوست ۱ — لاگ راستی‌آزمایی زنده (۲۰۲۶-۱۰-۰۱، از IP نوع Datacenter)

| Endpoint | کد | اندازه / نتیجه |
|---|---|---|
| `api.gold-api.com/price/XAU` | 200 | `price=4169.299805`, `updatedAt=2026-10-01T16:27:20Z`، بدون bid/ask |
| Yahoo `GC=F` (UA پیش‌فرض curl) | **429** | — |
| Yahoo `GC=F` (UA مرورگر) query1 / query2 | 200 / 200 | `firstTradeDate=967608000` (2000-08-30) |
| Yahoo `GC=F` `1m` range=1d/5d/7d/8d | 200 | 737 / 5,403 / 9,371 / 10,810 کندل |
| Yahoo `GC=F` `1m` range=1mo / 3mo | **422** | «Only 8 days worth of 1m granularity data…» |
| Yahoo `GC=F` `5m` 60d / 730d | 200 / **422** | 17,141 کندل |
| Yahoo `GC=F` `15m` 60d / 730d | 200 / **422** | 5,715 کندل |
| Yahoo `GC=F` `1h` 60d / 730d | 200 / 200 | 1,430 / 17,396 کندل |
| Yahoo `GC=F` `1d` range=max | 200 | **فقط ۲۶۸ کندل** |
| Yahoo `GC=F` `1d` period1=0 | 200 | **6,630 کندل**, 2000-08-30 → 2026-10-01 |
| Yahoo `^TNX` / `DX-Y.NYB` / `^VIX` / `^GVZ` `1m` 5d | 200 | 1,837 / 5,147 / 3,722 / 1,726 کندل |
| Yahoo `ZQ=F` / `SR1=F` / `SR3=F` `1m` 5d | 200 | 5,398 / 5,387 / 5,393 کندل |
| Yahoo `SI=F` / `BZ=F` / `^GSPC` `1m` 5d | 200 | 5,408 / 5,408 / 1,741 کندل |
| Treasury Real Yield CSV 2026 | 200 | 6,810 bytes؛ هدر: `Date, 5 YR, 7 YR, 10 YR, 20 YR, 30 YR` |
| TreasuryDirect `/TA_WS/securities/announced` | 200 | — |
| Cboe `GVZ_History.csv` | 307→200 | 89,871 bytes، 4,283 سطر، اولین رکورد 09/18/2009 |
| Cboe `delayed_quotes/options/GLD.json` | 200 | **3,576,852 bytes**، **8,140 قرارداد**، شامل `iv/open_interest/volume/delta/gamma/vega/theta/rho` |
| CFTC `72hh-3qpy` GOLD | 200 | `count=1059`، اولین 2006-06-13 |
| CFTC `6dca-aqww` GOLD | 200 | `count=1935`، اولین 1986-01-15 |
| FRED بدون کلید | **400** | «Variable api_key is not set» |
| SPDR GLD `historical-archive` | 200 | 540,715 bytes، XLSX |
| GPR daily XLS | 200 | 3,269,632 bytes |
| NY Fed ACM Term Premium XLS | 200 | 10,152,960 bytes |
| NY Fed EFFR API | 200 | بدون کلید |
| NY Fed `allmonth.xls` | 200 | — |
| BIS `WS_CBPOL` `D.US` CSV | 200 | 6,906,512 bytes |
| IMF `api.imf.org/external/sdmx/2.1/dataflow` | 200 | — |
| WGC Goldhub ETF page | 200 | — |
| Fed RSS `press_all.xml` | 200 | — |
| BLS API / BEA API | 200 / 200 | — |
| Cleveland Fed / Philly SPF / Atlanta GDPNow / USGS | 200 | — |
| Coinbase ticker | 200 | — |
| GDELT DOC 2.0 (دو بار، با فاصله ۱۰ ثانیه) | **429 / 429** | «Please limit requests to one every 5 seconds…» |
| **CME `daily-bulletin.html`** | **403** | — |
| **CME `CmeWS/.../Settlements`** | **403** | — |
| **CME `/ftp/bulletin/`** | **403** | — |
| **CME DataMine** | **اتصال برقرار نشد** | — |
| Twelve Data بدون کلید | **401** | — |
| Finnhub بدون کلید | **401** | — |
| EIA بدون کلید | **403** | — |
| Alpha Vantage `CURRENCY_EXCHANGE_RATE` (demo) | 200 | — |
| Dukascopy سایت / `datafeed .bi5` | 200 / **503** | مسیر مستقیم تضمین‌شده نیست |

---

## پیوست ۲ — اقلام `UNKNOWN` که قبل از تثبیت Data Contract باید اندازه‌گیری شوند

| # | قلم نامعلوم | چرا مهم است |
|---|---|---|
| 1 | Quota واقعی `gold-api.com` | Primary اسپات است و هیچ سقف اعلام‌شده‌ای ندارد (C4) |
| 2 | آستانه دقیق 429 در Yahoo برای یک IP | کل استک قیمتی به آن وابسته است (C5) |
| 3 | حجم کل Backfill تیک Dukascopy برای XAU/USD 2020–2026 | تعیین‌کننده بودجه ذخیره‌سازی |
| 4 | رفتار CME از IP غیر-Datacenter | تعیین می‌کند B1 یک محدودیت مطلق است یا محیطی |
| 5 | رفتار GDELT از IP غیر-Datacenter | همان، برای F07 |
| 6 | عمق تاریخچه `DFII10`، `T10YIE`، `NFCI`، `STLFSI4` در FRED | بدون کلید قابل اندازه‌گیری نبود؛ سقف Backtest فاکتورهای F01/F05/F08/F18 را تعیین می‌کند |
| 7 | وجود/نبود «Tail» و «Dealer Allocation» در پاسخ TreasuryDirect | آیتم ۴۹ سند (Auction Intelligence) به آن وابسته است |
| 8 | فرکانس و پایداری انتشار فایل‌های XLSX در Goldhub | تعیین‌کننده قابلیت اتکای F09/F10/F12/F19/F20 |
| 9 | دقت احتمالات Fed محاسبه‌شده داخلی در برابر FedWatch عمومی | سند خودش این Validation را الزامی کرده ولی معیار قبولی تعریف نکرده |
| 10 | نرخ موفقیت Parse صفحات والد WGC در طول زمان | سند Hard-Code کردن نام فایل را ممنوع کرده؛ شکنندگی Parser اندازه‌گیری نشده |

---

*پایان گزارش.*
