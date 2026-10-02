package io.goldintelligence.ingestion

import io.goldintelligence.engine.FeatureKeys

/**
 * SPEC v2 §18 screen 3 — the Master-List indicator surface.
 *
 * Every variable the engine can consume is declared here with the factor it
 * belongs to and a bilingual label, so the app can render the complete
 * indicator list grouped by factor, including the ones that are currently
 * unavailable. An indicator that is missing from the live feed still appears,
 * labelled with the reason, rather than silently disappearing from the UI.
 */
object IndicatorCatalog {
    data class Indicator(
        val key: String,
        val factorId: String,
        val labelFa: String,
        val labelEn: String,
        val unit: String
    )

    val indicators: List<Indicator> = listOf(
        Indicator(FeatureKeys.REAL_YIELD, "F01_REAL_RATE", "بازده واقعی ۱۰ ساله", "10Y Real Yield", "%"),
        Indicator(FeatureKeys.REAL_YIELD_ZSCORE, "F01_REAL_RATE", "Z بازده واقعی", "Real Yield Z-Score", "z"),
        Indicator(FeatureKeys.REAL_YIELD_TREND, "F01_REAL_RATE", "روند بازده واقعی", "Real Yield Trend", "slope"),
        Indicator(FeatureKeys.REAL_YIELD_INTRADAY_PROXY, "F01_REAL_RATE", "پراکسی درون‌روزی بازده واقعی", "Intraday Real-Yield Proxy", "%"),

        Indicator(FeatureKeys.DXY, "F02_USD", "شاخص دلار", "Dollar Index", "index"),
        Indicator(FeatureKeys.DXY_ZSCORE, "F02_USD", "Z شاخص دلار", "DXY Z-Score", "z"),
        Indicator(FeatureKeys.DXY_TREND, "F02_USD", "روند دلار", "DXY Trend", "slope"),
        Indicator(FeatureKeys.DXY_MOVE_SIGMA, "F02_USD", "حرکت سیگما دلار", "DXY Move Sigma", "σ"),

        Indicator(FeatureKeys.FED_EXPECTED_RATE_CHANGE, "F03_FED", "تغییر انتظارات نرخ", "Expected Rate Change", "pp"),
        Indicator(FeatureKeys.FED_SURPRISE, "F03_FED", "شگفتی FOMC", "FOMC Surprise", "pp"),

        Indicator(FeatureKeys.US10Y_YIELD, "F04_TREASURY_CURVE", "بازده ۱۰ ساله", "US 10Y Yield", "%"),
        Indicator(FeatureKeys.US10Y_MOVE_SIGMA, "F04_TREASURY_CURVE", "حرکت سیگما ۱۰ ساله", "US10Y Move Sigma", "σ"),
        Indicator(FeatureKeys.YIELD_CURVE_10Y_2Y, "F04_TREASURY_CURVE", "اسپرد ۱۰ق۲ ساله", "10Y-2Y Spread", "pp"),

        Indicator(FeatureKeys.INFLATION_SURPRISE, "F05_INFLATION", "شگفتی تورمی", "Inflation Surprise", "z"),
        Indicator(FeatureKeys.BREAKEVEN_CHANGE, "F05_INFLATION", "تغییر نرخ سربه‌سر", "Breakeven Change", "pp"),

        Indicator(FeatureKeys.ECONOMIC_SURPRISE_INDEX, "F06_ECONOMIC_SURPRISE", "شاخص شگفتی اقتصادی", "Economic Surprise Index", "pp"),

        Indicator(FeatureKeys.GEOPOLITICAL_RISK_SCORE, "F07_GEOPOLITICAL_RISK", "امتیاز ریسک ژئوپلیتیک", "Geopolitical Risk Score", "0-100"),
        Indicator(FeatureKeys.GEOPOLITICAL_RISK_DELTA, "F07_GEOPOLITICAL_RISK", "تغییر ریسک ژئوپلیتیک", "Geopolitical Risk Delta", "pp"),

        Indicator(FeatureKeys.FINANCIAL_STRESS_SCORE, "F08_FINANCIAL_STRESS", "شاخص استرس مالی", "Financial Stress Score", "z"),
        Indicator(FeatureKeys.VIX, "F08_FINANCIAL_STRESS", "شاخص VIX", "VIX", "index"),
        Indicator(FeatureKeys.VIX_ZSCORE, "F08_FINANCIAL_STRESS", "Z شاخص VIX", "VIX Z-Score", "z"),

        Indicator(FeatureKeys.ETF_FLOW_ZSCORE, "F09_GOLD_FLOW", "Z جریان ETF", "ETF Flow Z-Score", "z"),
        Indicator(FeatureKeys.ETF_HOLDINGS_CHANGE, "F09_GOLD_FLOW", "تغییر دارایی ETF", "ETF Holdings Change", "%"),

        Indicator(FeatureKeys.CENTRAL_BANK_NET_BUYING_3M, "F10_CENTRAL_BANK_DEMAND", "خرید خالص ۳ماهه بانک مرکزی", "CB Net Buying 3M", "t"),
        Indicator(FeatureKeys.CENTRAL_BANK_PROXY_FLOW, "F10_CENTRAL_BANK_DEMAND", "پراکسی جریان رسمی", "Official Flow Proxy", "idx"),

        Indicator(FeatureKeys.COT_NET_POSITION_ZSCORE, "F11_FUTURES_POSITIONING", "Z پوزیشن خالص COT", "COT Net Position Z", "z"),
        Indicator(FeatureKeys.COT_EXTREME_LONG, "F11_FUTURES_POSITIONING", "حد نهایی خرید", "COT Extreme Long", "flag"),
        Indicator(FeatureKeys.COT_EXTREME_SHORT, "F11_FUTURES_POSITIONING", "حد نهایی فروش", "COT Extreme Short", "flag"),

        Indicator(FeatureKeys.PHYSICAL_DEMAND_INDEX, "F12_PHYSICAL_DEMAND", "شاخص تقاضای فیزیکی", "Physical Demand Index", "%"),

        Indicator(FeatureKeys.GOLD_MOMENTUM, "F13_MARKET_MOMENTUM", "مومنتوم طلا", "Gold Momentum", "composite"),
        Indicator(FeatureKeys.GOLD_RETURN, "F13_MARKET_MOMENTUM", "بازده طلا", "Gold Return", "%"),
        Indicator(FeatureKeys.GOLD_MOVE_SIGMA, "F13_MARKET_MOMENTUM", "حرکت سیگما طلا", "Gold Move Sigma", "σ"),

        Indicator(FeatureKeys.MICROSTRUCTURE_IMBALANCE, "F14_MARKET_MICROSTRUCTURE", "عدم‌تعادل ریزساختار", "Microstructure Imbalance", "-1..1"),
        Indicator(FeatureKeys.FUTURES_BASIS, "F14_MARKET_MICROSTRUCTURE", "بیسیس فیوچرز", "Futures Basis", "%"),
        Indicator(FeatureKeys.TERM_STRUCTURE_SLOPE, "F14_MARKET_MICROSTRUCTURE", "شیب ساختار زمانی", "Term Structure Slope", "%"),

        Indicator(FeatureKeys.GOLD_IV_SKEW, "F15_OPTIONS_VOLATILITY", "اسکیو نوسان ضمنی", "Gold IV Premium", "vol pts"),
        Indicator(FeatureKeys.GOLD_REALIZED_VOL_ZSCORE, "F15_OPTIONS_VOLATILITY", "Z نوسان محقق‌شده", "Realized Vol Z", "z"),

        Indicator(FeatureKeys.GOLD_SILVER_RATIO, "F16_CROSS_ASSET", "نسبت طلا به نقره", "Gold/Silver Ratio", "ratio"),
        Indicator(FeatureKeys.GOLD_SILVER_RATIO_ZSCORE, "F16_CROSS_ASSET", "Z نسبت طلا به نقره", "Gold/Silver Ratio Z", "z"),

        Indicator(FeatureKeys.DOLLAR_FUNDING_STRESS, "F17_LIQUIDITY", "استرس تأمین مالی دلاری", "Dollar Funding Stress", "z"),

        Indicator(FeatureKeys.CREDIT_SPREAD_HY, "F18_CREDIT", "اسپرد اعتباری پربازده", "HY Credit Spread", "z"),

        Indicator(FeatureKeys.CHINA_DEMAND_INDEX, "F19_CHINA", "شاخص تقاضای چین", "China Demand Index", "%"),
        Indicator(FeatureKeys.INDIA_DEMAND_INDEX, "F20_INDIA", "شاخص تقاضای هند", "India Demand Index", "%"),
        Indicator(FeatureKeys.OIL_MOMENTUM, "F21_OIL_ENERGY", "مومنتوم نفت", "Oil Momentum", "%"),
        Indicator(FeatureKeys.GLOBAL_CB_POLICY_DIVERGENCE, "F22_GLOBAL_CB_POLICY", "واگرایی سیاست پولی جهانی", "Global Policy Divergence", "idx"),

        Indicator(FeatureKeys.RECESSION_PROBABILITY, "F06_ECONOMIC_SURPRISE", "احتمال رکود", "Recession Probability", "p"),

        /* ---- SPEC v2.1 — indicators unlocked when the paid gaps closed ---- */
        Indicator(FeatureKeys.BOOK_IMBALANCE, "F14_MARKET_MICROSTRUCTURE",
            "عدم‌تعادل دفتر سفارش", "Order Book Imbalance", "-1..1"),
        Indicator(FeatureKeys.BOOK_DEPTH_TOTAL, "F14_MARKET_MICROSTRUCTURE",
            "عمق کل دفتر سفارش", "Total Book Depth", "oz"),
        Indicator(FeatureKeys.BOOK_SPREAD_BP, "F14_MARKET_MICROSTRUCTURE",
            "اسپرد بهترین قیمت", "Top-of-Book Spread", "bp"),
        Indicator(FeatureKeys.OTC_SPREAD_BP, "F14_MARKET_MICROSTRUCTURE",
            "اسپرد بازار خارج از بورس", "OTC Spread", "bp"),

        Indicator(FeatureKeys.OPEN_INTEREST, "F11_FUTURES_POSITIONING",
            "بهره باز روزانه", "Daily Open Interest", "contracts"),
        Indicator(FeatureKeys.OPEN_INTEREST_ZSCORE, "F11_FUTURES_POSITIONING",
            "Z بهره باز", "Open Interest Z-Score", "z"),
        Indicator(FeatureKeys.OPEN_INTEREST_CHANGE, "F11_FUTURES_POSITIONING",
            "تغییر بهره باز", "Open Interest Change", "%"),

        Indicator(FeatureKeys.CHINA_PREMIUM, "F19_CHINA",
            "پریمیوم فیزیکی شانگهای", "Shanghai Physical Premium", "%"),
        Indicator(FeatureKeys.INDIA_PREMIUM, "F20_INDIA",
            "پریمیوم فیزیکی هند", "India Physical Premium", "%"),

        Indicator(FeatureKeys.LBMA_BENCHMARK, "F16_CROSS_ASSET",
            "بنچمارک LBMA", "LBMA Benchmark", "USD/oz"),
        Indicator(FeatureKeys.LBMA_DEVIATION, "F16_CROSS_ASSET",
            "انحراف از بنچمارک", "Benchmark Deviation", "%"),
        Indicator(FeatureKeys.SPOT_SOURCE_DISPERSION, "F16_CROSS_ASSET",
            "پراکندگی منابع قیمت نقدی", "Spot Source Dispersion", "bp"),

        Indicator(FeatureKeys.CALENDAR_HIGH_IMPACT_24H, "F06_ECONOMIC_SURPRISE",
            "رویدادهای پراهمیت ۲۴ ساعت آینده", "High-Impact Events (24h)", "events"),
        Indicator(FeatureKeys.CALENDAR_HOURS_TO_EVENT, "F06_ECONOMIC_SURPRISE",
            "ساعت تا رویداد بعدی", "Hours to Next Event", "h"),
        Indicator(FeatureKeys.CONSENSUS_SURPRISE, "F05_INFLATION",
            "شگفتی نسبت به اجماع", "Consensus Surprise", "normalized"),
        Indicator(FeatureKeys.CPI_YOY, "F05_INFLATION",
            "تورم سالانه CPI", "CPI Year-over-Year", "%"),
        Indicator(FeatureKeys.UNEMPLOYMENT_RATE, "F06_ECONOMIC_SURPRISE",
            "نرخ بیکاری", "Unemployment Rate", "%")
    )

    val byFactor: Map<String, List<Indicator>> = indicators.groupBy { it.factorId }
    val byKey: Map<String, Indicator> = indicators.associateBy { it.key }
}
