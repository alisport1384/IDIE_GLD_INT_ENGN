# سند تولید / Production Record — IDIE_GLD_INT_ENGN

نسخهٔ سند **1.1** · آخرین به‌روزرسانی **2026-10-02** · مخزن `https://github.com/alisport1384/IDIE_GLD_INT_ENGN`

این سند، تاریخچهٔ کامل تولید پروژه از صفر تا نسخهٔ جاری است و به‌گونه‌ای نوشته شده که **با تکیه بر همین سند بتوان پروژه را از صفر دوباره ساخت**: هر مرحله، ورودی، خروجی، تصمیم‌های الزام‌آور، فایل‌های ایجادشده، و فرمان‌های دقیق بیلد در آن آمده است.

ساختار سند:

| بخش | محتوا |
|---|---|
| §1 | جدول نسخه‌ها |
| §2 | محصول و دامنه |
| §3 | مراحل P0–P12 به ترتیب تولید |
| §4 | قرارداد پروژه (قواعد غیرقابل‌نقض) |
| §5 | بازسازی از صفر: فرمان به فرمان |
| §6 | بازسازی محیط ساخت |
| §7 | فهرست نقاط پایانی تأییدشده |
| §8 | بن‌بست‌ها — تکرار نشوند |
| §9 | سوابق راستی‌آزمایی |

---

## ۱. جدول نسخه‌ها / Version table

| نسخه | تاریخ | کامیت | دامنه | خروجی |
|---|---|---|---|---|
| **v0.1** | 2026-10-01 | — | تحلیل سند طراحی و استخراج محدودیت‌ها | `CONSTRAINTS_REPORT.md` — ۹۴ محدودیت، دسته‌های A–J |
| **v0.2** | 2026-10-01 | — | طرح اصلاح | `REMEDIATION_PLAN.md` — ۱۰۹ آیتم، مراحل S0–S6 |
| **v0.3** | 2026-10-01 | — | بازنویسی مشخصات | `SPEC_GOLD_INTELLIGENCE_V2.md` (v2) |
| **v1.0.0-α** | 2026-10-01 | `3732e27` | اسکلت مخزن | ماژول‌ها، wrapper، CI |
| **v1.0.0-β** | 2026-10-01 | `3d32146` | پیاده‌سازی کامل اسپک روی دادهٔ زنده | engine/ingestion/client/server/app |
| **v1.0.0-β2** | 2026-10-01 | `3a6bd7b` | قابل‌حمل‌سازی `gradle.properties` | پین‌های سندباکس به `GRADLE_USER_HOME` منتقل شد |
| **v1.0.0** | 2026-10-02 | `aed123f` | اسپک v2.1: بستن ۸ شکاف داده، صفحهٔ گزارش‌گیر، حذف قفل انتشار | APK امضاشده + زیپ منبع |
| **v1.1.0** | 2026-10-02 | `fac3f12` | §22 چارت زنده با تحلیل روی محور قیمت | تب هشتم، `/v1/chart`, `/v1/chart.json` |
| **v1.1.1** | 2026-10-02 | `e1ba124` | رفع تودرتو شدن کد اکسپشن‌های چارت | — |
| **v1.1.2** | 2026-10-02 | `HEAD` | خوانایی چارت، حالت تمام‌صفحه، قرارداد سطح خطا در گزارش‌گیر، README و همین سند | APK جدید + مستندات |

نسخهٔ اپ در تمام این مسیر `versionName 1.0.0` / `versionCode 2` است؛ نسخه‌های جدول بالا نسخهٔ **تولید** است، نه نسخهٔ منتشرشدهٔ بسته.

---

## ۲. محصول و دامنه / Product and scope

**هدف:** موتور تحلیل چندافقی طلا بر پایهٔ دادهٔ **زنده، رایگان و بدون کلید**، با خروجی اپ اندروید + سرور REST/SSE.

**دامنهٔ ثابت:**
- ۲۲ فاکتور، ۶ افق (5m, 15m, 1H, 4H, 1D, 1W)، ۶۳ اندیکاتور.
- ۸ صفحه: STATE, FACTORS, INDICATORS, HORIZONS, EVENTS, DIAGNOSTICS, CHART, LOGS.
- بدون دادهٔ نمونه، بدون سری ساختگی، بدون پرکردن با صفر.
- بدون وابستگی شخص‌ثالث در زمان اجرا برای `engine`/`ingestion`/`client`.

---

## ۳. مراحل تولید

### P0 — تحلیل سند ورودی ‹v0.1›

**ورودی:** `شاخص های طلا - نسخه رایگان نهایی.md` (۴۸۵۰ سطر، فارسی/انگلیسی).
**کار انجام‌شده:** استخراج هر الزام، آزمون‌پذیری آن، و منبع دادهٔ لازم؛ دسته‌بندی محدودیت‌ها در ۱۰ دسته A–J (داده، مجوز، محاسبات، کالیبراسیون، زمان، معماری، اجرا، کیفیت، امنیت، تحویل).
**خروجی:** `CONSTRAINTS_REPORT.md` — ۹۴ محدودیت، هر کدام با شناسه، شدت، اثر، و راه‌حل ممکن.
**تصمیم‌های الزام‌آور که از این مرحله باقی ماند:**
1. هیچ احتمالی بدون کالیبراسیون منتشر نمی‌شود.
2. هیچ مقدار پراکسی بدون برچسب PROXY و سقف کیفیت ۰٫۶ منتشر نمی‌شود.
3. هر مقدار باید منبع، کلاس مجوز و لایهٔ کیفیت همراه داشته باشد.

### P1 — طرح اصلاح ‹v0.2›

**خروجی:** `REMEDIATION_PLAN.md` — ۱۰۹ آیتم در مراحل S0 (پیش‌نیاز) تا S6 (تحویل)، هر آیتم با وابستگی و معیار پذیرش.

### P2 — مشخصات نهایی ‹v0.3 → v2.1›

**خروجی:** `SPEC_GOLD_INTELLIGENCE_V2.md` (نسخهٔ معتبر: **SPEC_GOLD_INTELLIGENCE_V2.1**) و نسخهٔ یکسان در `docs/`.
**بخش‌های کلیدی:** §D4 فرمول‌ها · §12 گیت‌های کیفیت · §14 پراکسی‌ها · §17 API · §18 صفحه‌ها · §21 گزارش‌گیر · §22 چارت زنده.
**اعداد قطعی:** جدول وزن‌ها (Σ=1.000)، `min_horizon` هر فاکتور، پوشش هر افق، `k(h)`، `τ = 48/ln2`، شرط‌های کلید قطع. (در README §2 خلاصه شده است.)

### P3 — اسکلت مخزن ‹`3732e27`›

| مورد | مقدار قطعی |
|---|---|
| Gradle | 9.5.0 (wrapper؛ `gradle-wrapper.jar` = ۴۸٬۴۶۲ بایت) |
| AGP | 8.13.1 (`apply false` در ریشه) |
| Kotlin | 2.2.21 (`jvm` و `android`) |
| JDK | 17 (`jvmToolchain(17)` در همهٔ ماژول‌ها، بدون بلوک `java { }`) |
| ماژول‌ها | `:app, :engine, :ingestion, :client, :server` |
| مخازن | `FAIL_ON_PROJECT_REPOS` + google + mavenCentral |
| اندروید | `compileSdk 36`, `targetSdk 36`, `minSdk 29`, `useAndroidX=false`, `lint.abortOnError=false` |
| تست | JUnit 4.13.2 |

**کلید امضا** (ساخت مجدد در صورت نیاز):

```bash
keytool -genkeypair -v \
  -keystore keystore/gold-intelligence.keystore -storetype PKCS12 \
  -alias goldintelligence -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass goldintelligence -keypass goldintelligence \
  -dname "CN=Gold Intelligence, OU=Engineering, O=IDIE GLD INT ENGN, L=London, C=GB"
```

`.gitignore` یک استثنا برای همین فایل دارد تا کلون تمیز، بیلد نصب‌شدنی بدهد.

**CI:** `.github/workflows/android.yml`، job `verify`: JDK 17 temurin، `gradle/actions/setup-gradle@v4`، اجرای `test` + `:app:assembleDebug` + `:server:installDist`، آپلود APK و گزارش‌ها.

### P4 — موتور تحلیل ‹`3d32146`›

ترتیب ساخت فایل‌ها در `engine`:

1. `Specification.kt` — `FactorCatalog.factors`, `GoldSpecification.baseFactorWeights`. (توجه: `Specification` یک شیء قابل‌ارجاع نیست.)
2. `Horizon.kt` — `enum Horizon(code, minutes, rank)`, `HorizonMode`, `FactorHorizons.minHorizon`.
3. `Model.kt` — `Direction`, `Regime`, `Stability`, `Uncertainty{HIGH_CONFIDENCE, LOW_CONFIDENCE, INSUFFICIENT_INFORMATION}`, `FactorScore(factorId, score, quality, asOf)`, `DynamicWeight(factorId, weight)`, `InputSnapshot`, `IntelligenceReport`, `HorizonState`, `GoldIntelligenceState`.
4. `SpecEngines.kt` — کیفیت، زوال زمانی، غلبه، تأیید بین‌بازاری، اطمینان.
5. `CoreEngines.kt` — `ExpectedMoveEngine.Band(point, p5, p95, sigma)`, تضاد، `SignalState`.
6. `MultiHorizonEngine.kt` — ارزیابی هر افق، `KillSwitchResult(engaged, reasons)`, `SPEC_VERSION = "SPEC_GOLD_INTELLIGENCE_V2.1"`.
7. `DiagnosticLog.kt` — حلقهٔ ثبت با `LogLevel`, `LogStage`, `LogEntry`, فیلترها و خروجی‌گیری.

### P5 — لایهٔ ingestion ‹`3d32146`›

`DataContract.kt` (۱۹ فیلد) → `QualityGates.kt` (G01–G11، ضریب لایه، کهنگی) → `SeriesModel.kt` → `MarketUniverse.kt` (کلیدهای متعارف) → `SpecFeatureEngineer.kt` → `SpecFactorEngine.kt` (`FactorDiagnostic(factorId, available, score, quality, asOf, usedFeatures, missingFeatures, isProxy, baseWeight, minHorizon, reason)`) → `IndicatorCatalog.kt` (۶۳ اندیکاتور).

### P6 — لایهٔ client ‹`3d32146`›

`Json.kt` → `Http.kt` → `Providers.kt` → `FreeDataAggregator.kt` → `ScreenModel.kt` → `ScreenModelBuilder.kt` → `GoldIntelligenceClient.kt`.

**قراردادهای API که نباید بی‌اعلام تغییر کنند:**

```kotlin
Row(labelFa, labelEn, value, badges, noteFa, noteEn, emphasis)   // متن منبع در noteEn/noteFa
Badge(text, kind); BadgeKind{PROXY,GATED,FRESH,STALE,EXPIRED,OK,WARN,ERROR,INFO}
Section(titleFa, titleEn, rows); Screen(id, titleFa, titleEn, sections)
ScreenModel(generatedAt, specVersion, screens, attribution, degraded, errorFa, errorEn)
AnalysisResult(report, universe, features, diagnostics, screens, chart)
Http.get/getJson/getText(providerId, url, …, headers), post/postJson(id, url, body)
JsonWriter.escape/str/num/bool/obj/arr
```

### P7 — اپ اندروید و سرور ‹`3d32146`›

- `MainActivity` فقط رندر می‌کند؛ هیچ منطق تحلیلی ندارد. پالت: `BG #0B0D10`, `PANEL #12161B`, `LINE #232A33`, `TEXT #E6E8EB`, `MUTED #6B7584`, `ACCENT #D6B36A`, `GREEN #4ED38A`, `RED #F26B6B`, `AMBER #E8B04B`, `VIOLET #9B8CF5`. بازخوانی خودکار ۶۰ ثانیه.
- `server/Main.kt`: REST + SSE روی `0.0.0.0:8080`، بازخوانی دو دقیقه‌ای.

### P8 — بستن شکاف‌های داده ‹`aed123f`› ‹v1.0.0›

هشت شکاف با منبع رایگان بسته شد: عمق دفتر سفارش، اجماع تحلیلگران، بنچمارک LBMA، پرمیوم چین، پرمیوم هند، OI روزانه، منحنی فوروارد COMEX، و DXY واقعی.
دو شکاف باز ماند: **تقاضای بانک مرکزی (F10) `NO_FREE_SOURCE`** و **تطبیق تاریخی `NOT_AVAILABLE`**.
ارتقای فاکتورها: F02 ← `TVC:DXY` · F14 ← L2 واقعی (Kraken+OKX) بدون سقف پراکسی · F12 ← پرمیوم منطقه‌ای خالص از `INDIA_STRUCTURAL_WEDGE_PCT = 9.0` · F19/F20 ← پرمیوم اندازه‌گیری‌شده · F05 ← سورپرایز اجماع اندازه‌گیری‌شده.

### P9 — گزارش‌گیر عملیاتی ‹`aed123f`› ‹§21›

تب مستقل، تغذیه مستقیم از `DiagnosticLog` تا در صورت شکست خط لوله هم کار کند؛ فیلتر سطح/متن، شمارش به تفکیک سطح و مرحله، فهرست خطاها، وضعیت هر اندیکاتور، خروجی `.md`/`.txt`.

### P10 — چارت زنده ‹`fac3f12`› ‹v1.1.0 · §22›

فایل‌های جدید در `client`: `ChartModels.kt`, `ChartFeed.kt`, `LiveChartStore.kt`, `ChartOverlayBuilder.kt`؛ در `app`: `ChartView.kt`؛ در `engine`: افزودن `LogStage.CHART`؛ در `server`: `/v1/chart`, `/v1/chart.json`.

**تصمیم‌های فنی که باید حفظ شوند:**
1. ویجت رایگان TradingView در iframe ایزوله است و **نمی‌توان روی آن رسم کرد** → چارت اختصاصی.
2. اسکرینر TradingView فقط **کندل در حال شکل‌گیری** می‌دهد؛ تاریخچه ندارد → انباشت محلی + پیش‌بار از Kraken.
3. پیش‌بار با ضریب `brokerLast / seedLast` بازمقیاس و توخالی رسم می‌شود.
4. کلاک کندل = **زمان دریافت**؛ ستون `time` اسکرینر مهر جلسه است.
5. bid/ask اسکرینر مستقل کش می‌شود؛ انحراف > ۲۵ bp ⇒ حذف + `BIDASK_STALE`.

### P11 — رفع تودرتویی اکسپشن ‹`e1ba124`› ‹v1.1.1›

اکسپشن‌هایی که خود چارت در گزارش ثبت کرده بود، در بازخوانی بعد دوباره برداشته می‌شدند و کد به‌صورت `CHART_EXCEPTION_CHART_EXCEPTION_…` تودرتو می‌شد. برداشت از `log.failures()` اکنون مرحلهٔ `CHART` و پیشوند `CHART_EXCEPTION_` را کنار می‌گذارد.

### P12 — خوانایی چارت، تمام‌صفحه، قرارداد سطح خطا ‹v1.1.2›

**الف) چارت خوانا شد.** بوم فقط «قیمت» را می‌کشد:
- هیچ کادر تحلیلی روی بوم نیست؛ همهٔ سطرهای روایی به ویوهای نیتیو بیرون از بوم منتقل شد (`verdictStrip` بالای چارت + کارت‌های سطوح/تغییرات/دوگانه/اکسپشن زیر آن).
- ناودان راست مخصوص مقیاس قیمت و برچسب سطوح؛ برچسب‌ها هنگام تداخل از هم جدا می‌شوند.
- پنجرهٔ پیش‌فرض ۷۰ کندل (۳۰ تا ۲۴۰)، کشیدن افقی برای جابه‌جایی، ناحیهٔ پیش‌بار با سایهٔ روشن مشخص.

**ب) تمام‌صفحه اضافه شد.** `ChartFullscreenActivity` + `ChartHandoff` (انتقال در حافظه، نه سریال‌سازی) · `sensorLandscape` · immersive · کنترل تعداد کندل · کارت تحلیل اختیاری و **پیش‌فرض خاموش** · ثبت `FULLSCREEN_OPENED`/`FULLSCREEN_CLOSED`/`OVERLAY_TOGGLED` · استایل `AppTheme.Fullscreen`.

**ج) قرارداد سطح خطا در گزارش‌گیر.**
- `Providers.optionalIds = {YAHOO, EASTMONEY_SGE}`؛ رد شدن این‌ها `WARN` با کد `PROVIDER_UNAVAILABLE` است، نه `ERROR`.
- ثبت دوگانهٔ پاسخ ۴۲۹/۴۰۳ حذف شد (قبلاً هم در حلقهٔ تلاش و هم در `logResponse` ثبت می‌شد).
- اکسپشن چارت که روی صفحه نمایش داده می‌شود ⇒ `WARN` با کد `CHART_EXCEPTION_*`؛ ورودی آینه‌شده از سایر مراحل ⇒ `DEBUG` با کد `EXCEPTION_MIRRORED`.
- **تعریف:** `ERROR` یعنی شکستی که هیچ‌کس مدیریت نکرده است.

نتیجهٔ اجرای زنده پس از این تغییر: ۱۷۳ رکورد، **۰ خطا**، ۲۲ هشدار، ۱۴۵ دیباگ، ۰ رکورد افتاده.

---

## ۴. قرارداد پروژه / Project contract

۱. جهت وابستگی ماژول‌ها ثابت است: `app → client → ingestion → engine`، `server → client`.
۲. تمام تصمیم‌های نمایشی فقط در `ScreenModelBuilder`.
۳. هیچ دادهٔ ساختگی، نمونه، صفرپرشده یا درون‌یابی‌شده.
۴. منبع هر مقدار در `noteEn`/`noteFa` می‌آید؛ فیلد مستقل منبع وجود ندارد.
۵. هر ارائه‌دهنده شناسهٔ مستقل دارد (مدارشکن بر اساس شناسه کلید می‌خورد).
۶. `ERROR` فقط برای شکست مدیریت‌نشده.
۷. بدون وابستگی شخص‌ثالث در زمان اجرا برای سه ماژول هسته.
۸. `jvmToolchain(17)` در همه‌جا، بدون `java { sourceCompatibility }`.
۹. هر قاعدهٔ جدید با تست خودش می‌آید.

---

## ۵. بازسازی از صفر / Rebuild from zero

```bash
# 1) اسکلت
mkdir GoldIntelligenceEngine && cd $_
gradle wrapper --gradle-version 9.5.0        # یا کپی wrapper از همین مخزن
# settings.gradle.kts: include(":app", ":engine", ":ingestion", ":client", ":server")
# build.gradle.kts   : AGP 8.13.1 + Kotlin 2.2.21، همه apply false

# 2) ماژول‌ها به ترتیب وابستگی
#    engine  → P4
#    ingestion (api(project(":engine")))   → P5
#    client    (api(project(":ingestion"))) → P6 و P10
#    server    (application, mainClass io.goldintelligence.server.MainKt) → P7
#    app       (implementation(project(":client")))  → P7 و P12

# 3) کلید امضا
keytool -genkeypair -v -keystore keystore/gold-intelligence.keystore \
  -storetype PKCS12 -alias goldintelligence -keyalg RSA -keysize 4096 \
  -validity 10950 -storepass goldintelligence -keypass goldintelligence \
  -dname "CN=Gold Intelligence, OU=Engineering, O=IDIE GLD INT ENGN, L=London, C=GB"

# 4) بیلد و راستی‌آزمایی
export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk
chmod +x gradlew
./gradlew --no-daemon test
./gradlew --no-daemon :app:assembleDebug :app:assembleRelease
./gradlew --no-daemon :server:installDist
./server/build/install/server/bin/server      # 0.0.0.0:8080

# 5) آزمون دود روی دادهٔ زنده
curl -s localhost:8080/v1/health
curl -s "localhost:8080/v1/logs.json?level=ERROR&limit=50"   # باید خالی باشد
curl -s localhost:8080/v1/chart.json | head -c 400
```

---

## ۶. بازسازی محیط ساخت / Build environment

| متغیر | مقدار |
|---|---|
| `JAVA_HOME` | `/opt/jdk17` (برای کامپایل سریع‌تر کاتلین `/opt/jdk21` هم کار می‌کند) |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | `/opt/android-sdk` با `platforms;android-36` و `build-tools;35.0.0` |
| `GRADLE_USER_HOME` | `/opt/gradle-home` |

`gradle.properties` داخل `GRADLE_USER_HOME` (برای ماشین کم‌حافظه):

```properties
org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m
org.gradle.parallel=false
org.gradle.caching=true
kotlin.compiler.execution.strategy=in-process
kotlin.incremental=false
org.gradle.java.installations.paths=/opt/jdk17,/opt/jdk21
org.gradle.java.installations.auto-download=false
```

روی ماشین با ۲ گیگ رم همیشه `--no-daemon`. بیلد کامل از کش سرد ≈ ۳ دقیقه و ۴۰ ثانیه.

---

## ۷. نقاط پایانی تأییدشده / Verified endpoints

| منبع | فراخوانی | نکته |
|---|---|---|
| TradingView scanner | `POST https://scanner.tradingview.com/global/scan` با هدر `User-Agent: Mozilla/5.0`، بدنه `{"symbols":{"tickers":[…],"query":{"types":[]}},"columns":[…]}` | ستون‌های کار‌کرده: `name, description, close, open, high, low, change, change_abs, bid, ask, volume, update_mode, time, currency, pricescale, minmov, open_interest` و ستون‌های بازه‌ای `open\|5 … close\|240`, `ATR\|60`, `RSI\|60`, `Recommend.All\|60` |
| Kraken OHLC | `GET https://api.kraken.com/0/public/OHLC?pair=PAXGUSD&interval={1,5,15,60,240,1440}` | ۷۲۱ کندل؛ o/h/l/c **رشته**‌اند؛ کلید `last` را در انتخاب سری رد کنید |
| OKX | `GET https://www.okx.com/api/v5/market/history-candles?instId=XAUT-USDT&bar=1D&limit=300` و `/books?instId=XAUT-USDT&sz=100` | — |
| Swissquote | `GET https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/XAU/USD` | نردبان اندازه |
| SGE | `GET https://hq.sinajs.cn/list=gds_AUTD` با `Referer: finance.sina.com.cn`؛ پشتیبان `https://push2.eastmoney.com/api/qt/stock/get?secid=118.AUTD&fields=f43,f57,f58` (`f43`÷100) | پاسخ GB18030؛ فقط نام چینی خراب می‌شود، اعداد سالم‌اند |
| WGC | `GET https://fsapi.gold.org/api/goldprice/v11/chart/price/USD/max/false` | ۴۹۲ نقطهٔ ماهانه |
| goldprice.org | `GET https://data-asg.goldprice.org/dbXRates/USD` با `Referer: goldprice.org` | — |
| ForexFactory | `GET https://nfs.faireconomy.media/ff_calendar_thisweek.json` | بدون فیلد `actual` |
| BLS | `GET https://api.bls.gov/publicAPI/v1/timeseries/data/{CUUR0000SA0|LNS14000000}` | بدون کلید |
| Frankfurter | `GET https://api.frankfurter.app/latest?base=USD&symbols=CNY,INR` | — |

---

## ۸. بن‌بست‌ها / Dead ends — تکرار نشوند

- ویجت رایگان TradingView قابل رسم نیست (iframe ایزوله)؛ overlay قفل‌شده به قیمت فقط با Charting Library لایسنس‌دار ممکن است.
- `scanner.tradingview.com` تاریخچهٔ OHLC ندارد — فقط کندل در حال شکل‌گیری.
- Yahoo از این شبکه ۴۲۹ می‌دهد؛ `range=max` بی‌صدا تنزل می‌کند؛ ۱m>8d و 5m/15m>60d ⇒ ۴۲۲؛ قراردادهای تاریخ‌دار نیاز به پسوند `.CMX` دارند و هرگز OI ندارند.
- cmegroup.com ⇒ ۴۰۳ · datamine ⇒ ۰۰۰ · Dukascopy `.bi5` ⇒ ۵۰۳ · FRED ⇒ ۴۰۰ · Twelve Data/Finnhub ⇒ ۴۰۱ · EIA ⇒ ۴۰۳ بدون کلید · GDELT ⇒ ۴۲۹ · `en.sge.com.cn` ⇒ ۰۰۰ · `mcxindia.com` ⇒ ۴۰۳ · Stooq چالش PoW · nasdaq.com ⇒ ۴۰۰ · CSV اسپایدر در واقع PDF برمی‌گرداند.
- منحنی واقعی خزانه فقط پایان‌روز است و نیاز به `-L` و ۲۵ ثانیه مهلت دارد.
- هم‌زمانی `java { sourceCompatibility }` با `jvmToolchain` ⇒ شکست بیلد.
- `private val io` در اکتیویتی، ریشهٔ پکیج `io` را سایه می‌اندازد.
- یک پشتیبان با شناسهٔ ارائه‌دهندهٔ اصلی، در حالت `CIRCUIT_OPEN` رد می‌شود؛ شناسهٔ مستقل لازم است.
- `"%.2f%"` در `String.format` خطای `UnknownFormatConversionException` می‌دهد؛ پسوند درصد باید بعد از قالب‌بندی چسبانده شود.
- JUnit4 `assertEquals` با `Double?` کامپایل نمی‌شود.

---

## ۹. سوابق راستی‌آزمایی / Verification record

| مورد | مقدار |
|---|---|
| تست‌ها | **۸۲** تست، ۰ شکست (engine 45، ingestion 9، client 28) |
| امضای APK | فقط v3 (minSdk 29)، `CN=Gold Intelligence, OU=Engineering, O=IDIE GLD INT ENGN, L=London, C=GB`، گواهی SHA-256 `d74d277fd77ee7c503fc8e51ee523aa0aea26628a3883e2922ad5292c9af1b55` |
| بسته | `io.goldintelligence.app`، `versionCode 2`، `versionName 1.0.0`، `targetSdk 36` |
| گزارش‌گیر در اجرای زنده | ۱۷۳ رکورد · ۰ خطا · ۲۲ هشدار · ۱۴۵ دیباگ · ۰ افتاده |
| چارت در اجرای زنده | `OANDA — XAU/USD` · ۲۴۰ کندل · ضریب بازمقیاس ≈ ۰٫۹۹۹ · سطوح `P95/ASK/BID/LAST/EXP/P5` · دلتاهای ثبت‌شده `BIAS`, `PRICE` |
| وضعیت دادهٔ زنده | DQ ≈ ۰٫۳۴ · ۲۱ فاکتور فعال · ۱۵/۱۶ ارائه‌دهنده سالم · تنها `YAHOO` در حالت مدارشکن باز |
