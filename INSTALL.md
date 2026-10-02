# Gold Intelligence — نصب و بهره‌برداری / Install & Operation

نسخه / version **1.0.0** · `versionCode 2` · اسپک / spec **SPEC_GOLD_INTELLIGENCE_V2.1**

---

## ۱. فایل‌های تحویلی / Deliverables

| فایل / File | اندازه / Size | SHA-256 |
|---|---|---|
| `GoldIntelligence-1.0.0-release.apk` | 2,840,584 B | `af419395d1d447f8f44a5ef77023339494e9c331f7a8f0450e5976a0ed11a959` |
| `GoldIntelligence-1.0.0-debug.apk` | 3,770,879 B | `f011dcbd71ad0741074fe0e8a22fc85e757a216ed6f85f69abc23cb63004a41a` |
| `IDIE_GLD_INT_ENGN-source.zip` | 997,523 B · 643 فایل | `fcab8cd5d9ad8672c4b3076e74e8952d39c2248963fbfdd1b348a59363574c86` |
| `SHA256SUMS.txt` | — | چک‌سام سه فایل بالا |

بررسی / verify:

```bash
sha256sum -c SHA256SUMS.txt
```

---

## ۲. نصب روی اندروید / Install on Android

حداقل / minimum **Android 10 (API 29)** · هدف / target **API 36** · امضا / signature **APK Signature Scheme v3**
`CN=Gold Intelligence, OU=Engineering, O=IDIE GLD INT ENGN, L=London, C=GB` · گواهی / cert SHA-256 `d74d277fd77ee7c503fc8e51ee523aa0aea26628a3883e2922ad5292c9af1b55`

```bash
adb install -r GoldIntelligence-1.0.0-release.apk
```

بدون adb: فایل APK را به گوشی منتقل کنید، آن را باز کنید و «نصب از منبع ناشناس» را برای فایل‌منیجر خود مجاز کنید.
Without adb: copy the APK to the phone, open it, allow “install unknown apps” for your file manager.

اپ به اینترنت نیاز دارد و هیچ کلید API نمی‌خواهد. / The app needs internet access and no API key.

---

## ۳. هشت صفحهٔ اپ / The eight screens

| # | صفحه / Screen | محتوا / Contents |
|---|---|---|
| ۱ | **وضعیت / State** | جهت، سوگیری طلا، اطمینان، رژیم و پایداری آن، محرک‌های اصلی، تضادها، تأیید بین‌بازاری، نقدشوندگی، شوک، حرکت مورد انتظار، سناریوها، نقطهٔ ابطال |
| ۲ | **فاکتورها / Factors** | هر ۲۲ فاکتور با امتیاز، وزن پایه، وزن مؤثر، کیفیت، پراکسی بودن، افق حداقلی و علت در دسترس نبودن |
| ۳ | **اندیکاتورها / Indicators** | ۶۳ اندیکاتور کاتالوگ با مقدار، تازگی (FRESH/STALE/EXPIRED) و منبع |
| ۴ | **افق‌ها / Horizons** | ۵m تا ۱W: حالت، پوشش، جهت، احتمال یا دلیل انتشارنشدن، اطمینان، تضاد، کلید قطع |
| ۵ | **رویدادها / Events** | تقویم اقتصادی هفتهٔ جاری، پنجره‌های قفل، وضعیت خبری |
| ۶ | **تشخیص / Diagnostics** | کیفیت داده به تفکیک گیت، وضعیت هر ارائه‌دهنده، عمق دفتر سفارش، نردبان OTC، منحنی آتی، شکاف‌های حل‌نشده |
| ۷ | **چارت زنده / Live Chart** | کندل XAU/USD از بروکر انتخابی + تحلیل نهایی قفل‌شده روی محور قیمت |
| ۸ | **گزارش‌گیر / Logger** | گزارش عملیاتی کامل، فیلتر و خروجی‌گیری |

---

## ۴. چارت زنده / Live Chart

**بروکر / venue:** `OANDA:XAUUSD` پیش‌فرض · `FX:XAUUSD` (FXCM) · `FOREXCOM:XAUUSD` — با دکمه‌های بالای چارت سوئیچ می‌شود؛ هر سوئیچ در گزارش‌گیر با کد `VENUE_SWITCHED` ثبت می‌شود.
**بازهٔ زمانی / timeframe:** `5m · 15m · 1H · 4H · 1D` — کد `TIMEFRAME_SWITCHED`.

روی خود محور قیمت رسم می‌شود / drawn on the price axis itself:

| لایه / Layer | معنا / Meaning |
|---|---|
| `SPOT` (طلایی ممتد) | آخرین قیمت بروکر |
| `BID` / `ASK` (نقطه‌چین سبز/قرمز) | مظنهٔ دوطرفه، فقط وقتی با آخرین قیمت هم‌خوان باشد |
| `EXPECTED_MOVE` (بنفش) | نقطهٔ حرکت مورد انتظار افق ۱D |
| `BAND_HIGH` / `BAND_LOW` + ناحیهٔ سبز کم‌رنگ | کریدور P95…P5 |
| `BENCHMARK` (خاکستری) | بنچمارک LBMA |

**چیدمان خوانا:** روی بوم چارت فقط قیمت رسم می‌شود. نوار تحلیل (جهت، افق، اطمینان، سوگیری، رژیم، وضعیت سیگنال و تغییرات) **بالای** چارت و کارت‌های سطوح، تغییرات، تحلیل دوگانه و اکسپشن‌ها **زیر** چارت قرار دارند؛ هیچ کادری روی کندل‌ها نمی‌افتد.
برچسب هر سطح در ناودان سمت راست و کنار مقیاس قیمت نشان داده می‌شود و در صورت تداخل، برچسب‌ها از هم جدا می‌شوند.

**تمام‌صفحه:** روی خود چارت بزنید یا دکمهٔ `⛶ تمام‌صفحه` را فشار دهید. صفحهٔ تمام‌صفحه افقی و بدون نوارهای سیستم است و این کنترل‌ها را دارد:

| دکمه | کار |
|---|---|
| `−` / `+` | تعداد کندل در پنجره (۳۰ تا ۲۴۰، پیش‌فرض ۷۰) |
| `تحلیل` | کارت تحلیل روی چارت؛ **پیش‌فرض خاموش** |
| `✕` | بازگشت |

کشیدن انگشت روی چارت، پنجره را در زمان جابه‌جا می‌کند. یک‌بار زدن روی چارت در حالت تمام‌صفحه، نوار دکمه‌ها را پنهان یا آشکار می‌کند. باز و بسته شدن تمام‌صفحه با کدهای `FULLSCREEN_OPENED` و `FULLSCREEN_CLOSED` ثبت می‌شود.

**کندل توخالی = پیش‌بار.** تاریخچهٔ اولیه از Kraken PAXG/USD گرفته و با ضریب `brokerLast / seedLast` روی سطح بروکر بازمقیاس می‌شود؛ تعداد و ضریب هر دو منتشر می‌شوند. کندل‌های بروکر به‌مرور جای آن‌ها را می‌گیرند و توپر رسم می‌شوند.
**Hollow candle = rebased seed bar** from Kraken PAXG/USD; the rebase factor and the seeded count are both published. Venue-printed bars replace them and are drawn solid.

**تحلیل دوگانه / dual analysis** وقتی ظاهر می‌شود که:
- جهت افق‌های کوتاه (5m–1H) با جهت افق‌های بلند (4H–1W) مخالف باشد → `HORIZON_SPLIT`؛
- دو اردوگاه فاکتوری مخالف با |score| ≥ ۲۵ وجود داشته باشد و سهم وزنی اردوگاه اقلیت ≥ ۳۰٪ باشد → `FACTOR_CONFLICT`.

هر دو شاخه با قوت، سهم وزنی و فاکتورهای پشتیبان نمایش داده می‌شوند؛ هیچ‌کدام میانگین‌گیری یا حذف نمی‌شود.

**اکسپشن‌ها / exceptions:** `FEED_UNAVAILABLE` · `SYMBOL_NOT_QUOTED` · `PRICE_ABSENT` · `QUOTE_ABSENT` · `SERIES_EMPTY` · `SERIES_SEED_ONLY` · `ANALYSIS_ABSENT` · `KILL_SWITCH` · `LOW_DATA_QUALITY` و سه خطای آخر سایر مراحل خط لوله.

---

## ۵. گزارش‌گیر / Logger

- سطوح / levels: `TRACE · DEBUG · INFO · WARN · ERROR` — آستانه در خود صفحه تغییر می‌کند.
- مراحل / stages: `STARTUP · NETWORK · PARSE · QUALITY · FEATURE · FACTOR · REGIME · HORIZON · RENDER · EXPORT · CHART`.
- جست‌وجوی متنی، شمارش به تفکیک سطح و مرحله، شمارندهٔ رکوردهای افتاده.
- خروجی `.md` و `.txt` با دکمهٔ Export.
- **قرارداد سطح خطا:** `ERROR` فقط برای شکستی است که هیچ‌کس مدیریت نکرده. ارائه‌دهنده‌ای که مسیر جایگزین دارد (`YAHOO`, `EASTMONEY_SGE`) با `WARN` و کد `PROVIDER_UNAVAILABLE` ثبت می‌شود؛ اکسپشن چارت که روی صفحه دیده می‌شود `WARN` با کد `CHART_EXCEPTION_*` است؛ ورودی آینه‌شده از مراحل دیگر فقط یک‌بار و با `DEBUG`/`EXCEPTION_MIRRORED` ثبت می‌شود تا دوبار شمرده نشود.
- کدهای مرحلهٔ `CHART`: `FEED_OK · FEED_UNAVAILABLE · SYMBOL_NOT_QUOTED · PRICE_ABSENT · BAR_INCOMPLETE · FEED_NOT_STREAMING · BIDASK_STALE · SEED_OK · SEED_EMPTY · SEED_UNAVAILABLE · SEED_VENUE_ERROR · SERIES_SEEDED · REBASE_IMPOSSIBLE · BAR_SEALED · BAR_OUT_OF_ORDER · OVERLAY_BUILT · STATE_CHANGED · DUAL_READING · CHART_EXCEPTION_* · EXCEPTION_MIRRORED · VENUE_SWITCHED · TIMEFRAME_SWITCHED · FULLSCREEN_OPENED · FULLSCREEN_CLOSED · OVERLAY_TOGGLED`.

---

## ۵.۱ مستندات داخل مخزن / In-repo documentation

| فایل | محتوا |
|---|---|
| `README.md` | معماری، درخت منبع، مدل تحلیلی، منابع داده، چارت، گزارش‌گیر، API، قرارداد پروژه، محدودیت‌ها |
| `docs/PRODUCTION_RECORD.md` | سند تولید: نسخه‌ها v0.1 تا v1.1.2، مراحل P0–P12، بازسازی از صفر، نقاط پایانی تأییدشده، بن‌بست‌ها، سوابق راستی‌آزمایی |
| `docs/SPEC_GOLD_INTELLIGENCE_V2.md` | مشخصات معتبر (SPEC_GOLD_INTELLIGENCE_V2.1) |
| `DATA_DICTIONARY.md` · `NOTICE` · `SPEC_SOURCE.md` | واژه‌نامهٔ داده، انتساب منابع، منشأ مشخصات |

---

## ۶. ساخت از منبع / Build from source

پیش‌نیاز / prerequisites: **JDK 17** و **Android SDK** با `compileSdk 36` و `build-tools 35.0.0`.

```bash
unzip IDIE_GLD_INT_ENGN-source.zip -d gold
cd gold
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
chmod +x gradlew
./gradlew test                 # ۸۲ تست
./gradlew :app:assembleRelease # APK امضاشده
./gradlew :server:installDist  # سرور REST/SSE
```

کلید امضا داخل مخزن است / the signing key ships with the repo:
`keystore/gold-intelligence.keystore` · alias `goldintelligence` · store & key password `goldintelligence`.

جایگزینی با کلید خودتان / override with your own key:

```bash
./gradlew :app:assembleRelease \
  -PGI_KEYSTORE=/path/to/my.keystore \
  -PGI_KEYSTORE_PASSWORD=… -PGI_KEY_ALIAS=… -PGI_KEY_PASSWORD=…
```

در نبود فایل کلید، بیلد به حالت امضانشده برمی‌گردد و متوقف نمی‌شود.
Without the keystore the build falls back to unsigned instead of failing.

---

## ۷. سرور / Server

```bash
./server/build/install/server/bin/server    # 0.0.0.0:8080 · بازخوانی هر ۲ دقیقه
```

| مسیر / Endpoint | محتوا |
|---|---|
| `/v1/health` | سلامت و زمان آخرین بازخوانی |
| `/v1/screens` | هر هشت صفحه |
| `/v1/state` `/v1/factors` `/v1/indicators` `/v1/horizons` `/v1/events` `/v1/diagnostics` | صفحه‌های منفرد |
| `/v1/chart` | صفحهٔ چارت به‌صورت سطرها |
| `/v1/chart.json` | کندل‌ها + لایه‌ها + دلتاها + تحلیل دوگانه + اکسپشن‌ها |
| `/v1/logs` `/v1/logs.json?level=&stage=&key=&q=&limit=` `/v1/logs.md` `/v1/logs.txt` | گزارش‌گیر |
| `/v1/stream` | SSE |

---

## ۸. محدودیت‌های اعلام‌شده / Declared limitations

1. **تقاضای بانک مرکزی (F10)** هیچ منبع رایگان ندارد؛ فاکتور غیرفعال می‌ماند و وزن آن بازتوزیع می‌شود. هیچ مقدار جایگزینی ساخته نمی‌شود.
2. **تطبیق تاریخی (Historical analogue)** در دسترس نیست.
3. **احتمال کالیبره‌شده** تا پر شدن نمونهٔ Brier منتشر نمی‌شود؛ به‌جای عدد، وضعیت `UNCALIBRATED_NO_SAMPLE` نمایش داده می‌شود.
4. **افق‌های 5m و 15m** ساختاراً `DIRECTIONAL_ONLY` هستند و هرگز عدد احتمال نشان نمی‌دهند.
5. **کیفیت داده در لایهٔ رایگان** معمولاً زیر ۰٫۶ است؛ بنابراین کلید قطع فعال می‌شود و این موضوع روی چارت و در تشخیص صریحاً اعلام می‌شود.
6. **پیش‌بارهای چارت از PAXG/USD** هستند، نه از خود بروکر؛ بازمقیاس‌شده، توخالی و شمارش‌شده نمایش داده می‌شوند.
7. **bid/ask اسکرینر** مستقل از آخرین قیمت کش می‌شود؛ انحراف بیش از ۲۵ واحد پایه باعث حذف آن می‌شود.
8. **برخی ارائه‌دهندگان از این شبکه محدود می‌شوند** (مثلاً Yahoo با ۴۲۹). مدارشکن هر ارائه‌دهنده ۱۵ دقیقه باز می‌ماند و رویداد در گزارش‌گیر ثبت می‌شود.
9. داده‌ها صرفاً تحلیلی‌اند و توصیهٔ سرمایه‌گذاری نیستند. شرایط استفادهٔ هر منبع در `NOTICE` آمده است.
