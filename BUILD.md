# سند ساخت / Build Document — Gold Intelligence Engine

از صفر تا APK امضاشده و سرور در حال اجرا. هر فرمان در این سند روی یک ماشین
تمیز اجرا و تأیید شده است.
From an empty machine to a signed APK and a running server. Every command in
this document has been executed on a clean machine and verified.

| | |
|---|---|
| مخزن / repository | `https://github.com/alisport1384/IDIE_GLD_INT_ENGN` |
| نسخه / version | `1.0.0`, `versionCode 4` |
| اسپک / spec | `docs/SPEC_GOLD_INTELLIGENCE_V2.md` (v2.1) |
| ماژول‌ها / modules | `engine` · `ingestion` · `client` · `server` · `app` |
| فایل‌های ردگیری‌شده / tracked files | 86 (59 `.kt`) |
| آزمون‌ها / tests | 110، همگی سبز / all green |

---

## ۱. پیش‌نیازها / Prerequisites

| ابزار | نسخهٔ الزامی | بررسی |
|---|---|---|
| JDK | **17** (Temurin 17.0.20.1 تأییدشده) | `java -version` |
| Android SDK | `platforms;android-36` + `build-tools;35.0.0` | `sdkmanager --list_installed` |
| Gradle | **نصب نکنید** — `./gradlew` خودش ۹.۵.۰ را می‌آورد | — |
| دیسک / disk | ≈ ۳ گیگابایت برای SDK و کش Gradle | — |
| حافظه / RAM | ≥ ۴ گیگابایت (`org.gradle.jvmargs=-Xmx2048m`) | — |
| شبکه | فقط برای نخستین ساخت (وابستگی‌ها) و برای اجرای زنده | — |

JDK 21 برای ساخت **کافی نیست**: AGP 8.13.1 با `jvmToolchain(17)` پیکربندی شده
و زنجیرهٔ ابزار ۱۷ را می‌خواهد. اگر فقط ۲۱ دارید، JDK 17 را نصب کنید و
`JAVA_HOME` را به آن بدهید.

### ۱.۱ نصب خودکار روی لینوکس x64

```bash
# JDK 17 (Temurin)
sudo mkdir -p /opt/jdk17
curl -sSL -o /tmp/jdk17.tgz \
  "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
sudo tar xzf /tmp/jdk17.tgz -C /opt/jdk17 --strip-components=1 && rm /tmp/jdk17.tgz

# Android command-line tools
sudo mkdir -p /opt/android-sdk/cmdline-tools
sudo chown -R "$(id -u):$(id -g)" /opt/android-sdk
curl -sSL -o /tmp/clt.zip \
  "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
cd /tmp && unzip -q clt.zip && mv cmdline-tools /opt/android-sdk/cmdline-tools/latest

export JAVA_HOME=/opt/jdk17
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" "platforms;android-36" "build-tools;35.0.0"
```

### ۱.۲ متغیرهای محیطی — در هر پوستهٔ ساخت لازم‌اند

```bash
export JAVA_HOME=/opt/jdk17
export ANDROID_HOME=/opt/android-sdk
export GRADLE_USER_HOME=/opt/gradle-home   # اختیاری، ولی کش را از $HOME جدا می‌کند
```

روی ویندوز همین سه متغیر را با `setx` تنظیم کنید و به‌جای `./gradlew` از
`gradlew.bat` استفاده کنید.

---

## ۲. گرفتن منبع / Getting the source

```bash
git clone https://github.com/alisport1384/IDIE_GLD_INT_ENGN.git
cd IDIE_GLD_INT_ENGN
chmod +x gradlew                 # لازم است؛ برخی آرشیوها بیت اجرا را نگه نمی‌دارند
```

یا از بستهٔ تحویلی:

```bash
unzip IDIE_GLD_INT_ENGN-source.zip -d gold
cd gold/IDIE_GLD_INT_ENGN
chmod +x gradlew
```

هیچ وابستگی‌ای جز JUnit 4.13.2 وجود ندارد. شبکه، JSON، XML و CSV همگی با
کتابخانهٔ استاندارد نوشته شده‌اند؛ هیچ SDK، هیچ کلید API و هیچ حساب کاربری
لازم نیست.

---

## ۳. ساخت / Build

### ۳.۱ دروازهٔ کامل — همان چیزی که پیش از هر تحویل اجرا می‌شود

```bash
./gradlew --no-daemon test :app:assembleDebug :app:assembleRelease :server:installDist
```

نتیجهٔ مورد انتظار: `BUILD SUCCESSFUL`، **۱۱۰ آزمون، ۰ شکست**.
نخستین اجرا ۳ تا ۶ دقیقه (دانلود Gradle و وابستگی‌ها)، اجراهای بعدی ≈ ۲۰ ثانیه.

### ۳.۲ تک‌تک اهداف

| هدف | فرمان | خروجی |
|---|---|---|
| آزمون‌ها | `./gradlew --no-daemon test` | `*/build/test-results/test/*.xml` |
| APK دیباگ | `./gradlew --no-daemon :app:assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` |
| APK ریلیز امضاشده | `./gradlew --no-daemon :app:assembleRelease` | `app/build/outputs/apk/release/app-release.apk` |
| سرور | `./gradlew --no-daemon :server:installDist` | `server/build/install/server/bin/server` |
| پاک‌سازی | `./gradlew --no-daemon clean` | — |

`--no-daemon` اجباری نیست ولی در محیط‌های کم‌حافظه از کشته‌شدن دیمون جلوگیری
می‌کند.

### ۳.۳ شمارش آزمون‌ها

```bash
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
t = f = 0
for p in glob.glob('**/build/test-results/test/*.xml', recursive=True):
    r = ET.parse(p).getroot()
    t += int(r.get('tests')); f += int(r.get('failures')) + int(r.get('errors'))
print('tests', t, 'failures', f)
PY
```

---

## ۴. امضا / Signing

کلید توسعه عمداً در مخزن است تا یک کلون تمیز، APK **نصب‌شدنی** بسازد.
گذرواژه‌ها عمومی‌اند و باید پیش از هر انتشار عمومی عوض شوند.

| | |
|---|---|
| فایل | `keystore/gold-intelligence.keystore` (PKCS12) |
| نام مستعار / alias | `goldintelligence` |
| گذرواژه‌ها | `goldintelligence` (هر سه) |
| گواهی | `CN=Gold Intelligence, OU=Engineering, O=IDIE GLD INT ENGN, L=London, C=GB` |
| اثر انگشت SHA-256 | `d74d277f d77ee7c5 03fc8e51 ee523aa0 aea26628 a3883e29 22ad5292 c9af1b55` |
| طرح امضا | **v3 only** (`enableV1Signing = false`, v2 و v3 روشن) |

### ۴.۱ ساخت دوبارهٔ کلید، اگر گم شد

```bash
keytool -genkeypair \
  -keystore keystore/gold-intelligence.keystore -storetype PKCS12 \
  -alias goldintelligence -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass goldintelligence -keypass goldintelligence \
  -dname "CN=Gold Intelligence, OU=Engineering, O=IDIE GLD INT ENGN, L=London, C=GB"
```

اثر انگشت عوض می‌شود، و اندروید نصب روی نسخهٔ قبلی را رد می‌کند؛ باید نخست
اپ قبلی را حذف کرد.

### ۴.۲ کلید خودتان

```bash
./gradlew :app:assembleRelease \
  -PGI_KEYSTORE=/path/to/my.keystore \
  -PGI_KEYSTORE_PASSWORD=… -PGI_KEY_ALIAS=… -PGI_KEY_PASSWORD=…
```

اگر فایل کی‌استور وجود نداشته باشد، ساخت **شکست نمی‌خورد**: یک APK ریلیزِ
امضانشده تولید می‌شود.

### ۴.۳ راستی‌آزمایی امضا

```bash
JAVA_HOME=/opt/jdk17 "$ANDROID_HOME/build-tools/35.0.0/apksigner" verify \
  --print-certs --verbose app/build/outputs/apk/release/app-release.apk
```

باید `Verified using v3 scheme (APK Signature Scheme v3): true` و همان اثر
انگشت بالا را نشان دهد. `apksigner` بدون `JAVA_HOME=/opt/jdk17` اجرا نمی‌شود.

---

## ۵. نصب روی دستگاه / Install

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

یا فایل APK را روی گوشی کپی و از فایل‌منیجر باز کنید («نصب از منابع ناشناس»
را برای همان فایل‌منیجر فعال کنید).

- حداقل اندروید **۱۰ (API 29)**، هدف API 36.
- نصب روی نسخهٔ قبلی نیازی به حذف ندارد تا وقتی امضا یکی باشد.
- لنگر اعتماد Sectigo R46 داخل APK است (`res/raw/`), بنابراین فید ذخایر IMF
  روی اندروید ۱۰ هم کار می‌کند. §۸.۳.

---

## ۶. اجرای سرور تحلیلی / Running the analysis server

سرور همان موتور را بدون اندروید اجرا می‌کند و برای راستی‌آزمایی زنده است.

```bash
./gradlew --no-daemon :server:installDist

JAVA_HOME=/opt/jdk17 \
JAVA_OPTS="-Dsun.net.http.allowRestrictedHeaders=true" \
./server/build/install/server/bin/server
```

`-Dsun.net.http.allowRestrictedHeaders=true` روی دسکتاپ **الزامی** است: بدون
آن، JVM هدر `Origin` را بی‌صدا حذف می‌کند و تقویم اقتصادی ۴۰۳ برمی‌گرداند.
اندروید چنین محدودیتی ندارد.

نخستین چرخهٔ کامل ≈ ۹۰ ثانیه طول می‌کشد؛ تا آن زمان پاسخ‌ها ناقص‌اند.

| نقطهٔ پایانی | محتوا |
|---|---|
| `/v1/health` | زنده بودن |
| `/v1/screens` | هر هشت صفحه یک‌جا |
| `/v1/state` `/v1/factors` `/v1/indicators` `/v1/horizons` `/v1/events` `/v1/diagnostics` | صفحه‌به‌صفحه |
| `/v1/chart`، `/v1/chart.json` | چارت زنده (§۲۲) |
| `/v1/logs`، `.json` `.md` `.txt`، `/v1/logs/on`، `/v1/logs/off` | گزارش‌گیر (§۲۱) |
| `/v1/report` | گزارش کامل موتور |
| `/v1/stream` | SSE |

ضبط گزارش‌گیر پیش‌فرض خاموش است: با `GI_LOG=1` یا `/v1/logs/on` روشن می‌شود.

---

## ۷. معماری — چه چیزی کجاست

```
IDIE_GLD_INT_ENGN/
├── engine/      29 .kt  موتور خالص: بدون شبکه، بدون اندروید، بدون I/O
│   Model.kt · Interfaces.kt · Specification.kt · FeatureKeys.kt
│   GoldIntelligenceEngine.kt · SpecEngines.kt · CoreEngines.kt
│   AdvancedInference.kt (§۲۶/§۲۷) — ایزوتونیک، برایر/مورفی، کانفورمال تقسیمی و
│     موندریان، تعداد مؤثر شرط‌ها + ژاکوبی، BOCPD، نسبت واریانس + هرست، کالمن،
│     هرس‌شده، لجستیک ریج، ترکیب پیش‌بین‌ها، ضریب اطلاعات
│   RegimeDetector · ShockEngine · DivergenceEngine · InteractionEngine
│   Horizon.kt · InvalidationRuleEngine.kt · DiagnosticLog.kt
├── ingestion/   15 .kt  قرارداد داده، کیفیت، ویژگی‌ها، فاکتورها
│   MarketUniverse.kt · SeriesModel.kt · QualityGates.kt
│   SpecFeatureEngineer.kt · SpecFactorEngine.kt · IndicatorCatalog.kt
│   HistoricalAnalogues.kt (§۲۴) · WalkForwardCalibration.kt (§۲۶/§۲۷)
├── client/      14 .kt  شبکه و ارائه‌دهندگان و ساخت صفحه‌ها
│   Http.kt · Json.kt · Providers.kt · GapProviders.kt · QualityProviders.kt
│   FreeDataAggregator.kt · GoldIntelligenceClient.kt
│   ChartFeed.kt · ChartModels.kt · ChartOverlayBuilder.kt · LiveChartStore.kt
│   ScreenModel.kt · ScreenModelBuilder.kt
├── server/       1 .kt  HTTP برای راستی‌آزمایی روی دسکتاپ
├── app/          4 .kt  اندروید: MainActivity · ChartView · ChartFullscreenActivity
│   res/raw/sectigo_server_root_r46.pem · res/xml/network_security_config.xml
├── docs/        SPEC_GOLD_INTELLIGENCE_V2.md · PRODUCTION_RECORD.md · INDICATOR_RESEARCH.md
├── keystore/    کلید امضای توسعه
└── .github/workflows/build.yml
```

جهت وابستگی، یک‌طرفه و تغییرناپذیر است:

```
app ──► client ──► ingestion ──► engine
server ──► client
```

`engine` هیچ‌چیز از شبکه یا اندروید نمی‌داند و تنها با `FeatureKeys` و
`InputSnapshot` کار می‌کند. `ingestion` جهان داده و کیفیت را تعریف می‌کند.
`client` تنها جایی است که HTTP وجود دارد. **این جهت را نباید وارونه کرد.**

### ۷.۱ مسیر یک چرخهٔ تحلیل

```
FreeDataAggregator.refresh()        ← همهٔ ارائه‌دهندگان، با TTL و مدارشکن
        ↓ MarketUniverse(asOf, series, scalars, points, providerHealth)
SpecFeatureEngineer.build()         ← ۷۴ شاخص، هرکدام با منبع و کیفیت
        ↓ FeatureBundle
SpecFactorEngine.score()            ← ۲۲ فاکتور + تشخیص برای هر فاکتور غایب
        ↓ FactorResult
MultiHorizonEngine.evaluate()       ← رژیم، شوک، واگرایی، افق‌ها، سناریو
        ↓ IntelligenceReport
ScreenModelBuilder.build()          ← هشت صفحهٔ دوزبانه
        ↓ AnalysisResult → اپ یا سرور
```

### ۷.۲ قراردادهایی که نباید شکست

- `Http.get(providerId, url, accept, headers)` — مدارشکن به ازای هر
  `providerId` روی ۴۰۳/۴۲۹ به‌مدت ۱۵ دقیقه باز می‌شود. یک ارائه‌دهندهٔ پشتیبان
  **باید** شناسهٔ خودش را داشته باشد.
- `record(id, value, unit, provider, observedAt, frequency, expectedPeriod, …)`
  — تنها راه ورود یک عدد به جهان داده.
- `put(key, value, unit, sourceId, isProxy, note, …)` — تنها راه ساخت ویژگی؛
  مقدار غایب ⇒ `WARN INPUT_MISSING`، هرگز صفر.
- `emit(id, value, used, required, reason)` — فاکتور بدون ورودی، **حذف** و
  تشخیص‌دار می‌شود؛ هرگز صفر نمی‌گیرد.
- سطح خطا: `ERROR` فقط برای استثنای مدیریت‌نشده. امتناع یک ارائه‌دهندهٔ
  اختیاری ⇒ `WARN PROVIDER_UNAVAILABLE`.

---

## ۸. نکته‌های ساخت که بدون آن‌ها به دردسر می‌خورید

### ۸.۱ تله‌های محیط

| نشانه | علت | چاره |
|---|---|---|
| `Permission denied: ./gradlew` | آرشیو بیت اجرا را نگه نداشته | `chmod +x gradlew` |
| `JAVA_HOME is set to an invalid directory` | JDK جابه‌جا یا پاک شده | §۱.۱ را دوباره اجرا کنید |
| `Failed to install Build-Tools` | لایسنس پذیرفته نشده | `yes | sdkmanager --licenses` |
| ساخت در مرحلهٔ Kotlin کشته می‌شود | حافظهٔ کم | سواپ اضافه کنید یا `-Xmx1024m` |
| `apksigner: JAVA_HOME` | apksigner اسکریپت است | `JAVA_HOME=/opt/jdk17` را جلوی فرمان بگذارید |
| `:server:installDist` شکست می‌خورد | سرور در حال اجراست و فایل قفل است | نخست پروسه را ببندید |

### ۸.۲ هدرهایی که بدون آن‌ها منبع جواب نمی‌دهد

| ارائه‌دهنده | هدر | بدون آن |
|---|---|---|
| تقویم اقتصادی | `Origin: https://www.tradingview.com` + `Referer` | ۴۰۳ |
| BIS | `Accept: application/vnd.sdmx.data+json;version=1.0.0` | ۴۰۶ |
| FRED | `User-Agent` **غیرمرورگری** (پیش‌فرض پشته رد می‌شود) | اتصال بسته می‌شود |
| اسکرینر تریدینگ‌ویو | `User-Agent` مرورگری | ۴۰۳ |

### ۸.۳ لنگر اعتماد اندروید ۱۰

`api.imf.org` زیر ریشهٔ `Sectigo Public Server Authentication Root R46`
(ساخت ۲۰۲۱) امضا می‌شود که در مخزن اعتماد اندروید ۱۰ نیست. ریشه در
`app/src/main/res/raw/sectigo_server_root_r46.pem` منتشر می‌شود و
`app/src/main/res/xml/network_security_config.xml` آن را **فقط** برای
`api.imf.org` و `fiscaldata.treasury.gov` و **در کنار** مخزن سیستم معتبر
می‌کند. آزمون `TrustAnchorTest` اثر انگشت، خودامضا بودن، انقضا و سیم‌کشی
manifest را در هر ساخت بررسی می‌کند.

### ۸.۴ آنچه در ساخت وجود ندارد

AndroidX، Compose، Material، Retrofit، OkHttp، Gson، Moshi، Coroutines،
Hilt — هیچ‌کدام. رابط کاربری با `View` خام و `Canvas` نوشته شده و شبکه با
`HttpURLConnection`. این عمدی است: APK ریلیز ۲٫۹ مگابایت می‌ماند و روی
API 29 بدون کتابخانهٔ سازگاری اجرا می‌شود.

---

## ۹. یکپارچگی پیوسته / CI

`.github/workflows/build.yml` روی هر push و PR:

1. `actions/setup-java@v4` با Temurin 17
2. `./gradlew --no-daemon test`
3. `./gradlew --no-daemon :app:assembleDebug`
4. `./gradlew --no-daemon :server:installDist`
5. بارگذاری APK دیباگ و گزارش آزمون‌ها به‌عنوان artifact

ساخت ریلیز در CI اجرا نمی‌شود چون کلید امضا نباید به لاگ عمومی برسد.

---

## ۱۰. بستهٔ تحویل / Producing the deliverables

```bash
./gradlew --no-daemon test :app:assembleDebug :app:assembleRelease :server:installDist

mkdir -p deliverables
cp app/build/outputs/apk/release/app-release.apk deliverables/GoldIntelligence-1.0.0-release.apk
cp app/build/outputs/apk/debug/app-debug.apk     deliverables/GoldIntelligence-1.0.0-debug.apk
cp INSTALL.md                                    deliverables/INSTALL.md
git archive --format=zip --prefix=IDIE_GLD_INT_ENGN/ \
  -o deliverables/IDIE_GLD_INT_ENGN-source.zip HEAD

cd deliverables
sha256sum GoldIntelligence-1.0.0-release.apk GoldIntelligence-1.0.0-debug.apk \
          IDIE_GLD_INT_ENGN-source.zip INSTALL.md > SHA256SUMS.txt
sha256sum -c SHA256SUMS.txt
```

راستی‌آزمایی اینکه بسته واقعاً مستقل ساخته می‌شود:

```bash
rm -rf /tmp/ziptest && mkdir /tmp/ziptest && cd /tmp/ziptest
unzip -q …/IDIE_GLD_INT_ENGN-source.zip && cd IDIE_GLD_INT_ENGN
chmod +x gradlew && ./gradlew --no-daemon -q test     # باید ۱۱۰ آزمون سبز بدهد
```

---

## ۱۱. فهرست بازبینی پیش از تحویل / Release checklist

- [ ] `./gradlew --no-daemon test` → ۱۱۰ / ۰
- [ ] APK ریلیز با `apksigner verify` و اثر انگشت `d74d277f…1b55`
- [ ] `versionCode` یک واحد بالاتر از انتشار قبلی
- [ ] سرور ≥ ۹۰ ثانیه بالا، سپس `/v1/diagnostics`: کیفیت داده، شمار فاکتور،
      شمار شاخص، و «شکاف داده» همگی بررسی‌شده
- [ ] `/v1/logs.txt` با `GI_LOG=1`: **صفر** `ERROR`
- [ ] بستهٔ منبع در یک پوشهٔ خالی استخراج و آزموده شد
- [ ] `SHA256SUMS.txt` ساخته و `sha256sum -c` سبز
- [ ] هر منبع تازه در `README.md`، `NOTICE` و `docs/INDICATOR_RESEARCH.md` ثبت شد
