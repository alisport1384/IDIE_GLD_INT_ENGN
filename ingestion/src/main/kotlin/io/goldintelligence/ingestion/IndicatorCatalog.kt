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

        Indicator(FeatureKeys.CENTRAL_BANK_NET_BUYING_3M, "F10_CENTRAL_BANK_DEMAND", "خرید خالص ۳ماهه بانک مرکزی", "CB Net Buying 3M", "z"),
        Indicator(FeatureKeys.CENTRAL_BANK_PROXY_FLOW, "F10_CENTRAL_BANK_DEMAND", "گستره خرید رسمی", "Official Flow Breadth", "%"),

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
            "نرخ بیکاری", "Unemployment Rate", "%"),
        /* ---- SPEC v2.1 §25 — measured macro, options and news ------- */
        Indicator(FeatureKeys.FED_IMPLIED_PATH_12M, "F03_FED", "نرخ ضمنی ۱۲ماهه", "Implied Rate 12M", "%"),
        Indicator(FeatureKeys.INFLATION_EXPECTATION_5Y5Y, "F05_INFLATION", "انتظار تورم ۵س۵س", "5Y5Y Inflation Expectation", "%"),
        Indicator(FeatureKeys.POLICY_UNCERTAINTY, "F07_GEOPOLITICAL_RISK", "عدم‌قطعیت سیاستی", "Policy Uncertainty", "index"),
        Indicator(FeatureKeys.NEWS_UNCERTAINTY, "F07_GEOPOLITICAL_RISK", "عدم‌قطعیت خبری بازار", "News Market Uncertainty", "index"),
        Indicator(FeatureKeys.BOND_VOLATILITY, "F08_FINANCIAL_STRESS", "نوسان بازار اوراق", "Bond Volatility (MOVE)", "index"),
        Indicator(FeatureKeys.VIX_TERM_SLOPE, "F08_FINANCIAL_STRESS", "شیب ساختار زمانی VIX", "VIX Term Slope", "vol pts"),
        Indicator(FeatureKeys.GOLD_RISK_REVERSAL, "F15_OPTIONS_VOLATILITY", "ریسک‌ریورسال ۲۵ دلتا", "25Δ Risk Reversal", "vol pts"),
        Indicator(FeatureKeys.GOLD_PUT_CALL_OI, "F15_OPTIONS_VOLATILITY", "نسبت پوت به کال", "Put/Call Open Interest", "ratio"),
        Indicator(FeatureKeys.FINANCIAL_CONDITIONS, "F17_LIQUIDITY", "شرایط مالی", "Financial Conditions", "index"),
        Indicator(FeatureKeys.FED_NET_LIQUIDITY, "F17_LIQUIDITY", "نقدینگی خالص فدرال‌رزرو", "Fed Net Liquidity", "USD bn"),
        Indicator(FeatureKeys.FED_NET_LIQUIDITY_CHANGE, "F17_LIQUIDITY", "تغییر نقدینگی خالص", "Net Liquidity Change", "% (13w)"),

        /* ---- SPEC v2.1 §27 — measured additions ---------------------- */
        Indicator(FeatureKeys.REAL_YIELD_5Y, "F01_REAL_RATE", "بازده واقعی ۵ ساله", "5Y Real Yield", "%"),
        Indicator(FeatureKeys.REAL_CURVE_SLOPE, "F01_REAL_RATE", "شیب منحنی واقعی", "Real Curve Slope", "pp"),
        Indicator(FeatureKeys.DOLLAR_AFE, "F02_USD", "دلار در برابر اقتصادهای پیشرفته", "Dollar vs Advanced Economies", "index"),
        Indicator(FeatureKeys.YIELD_CURVE_10Y_3M, "F04_TREASURY_CURVE", "اسپرد ۱۰ساله-۳ماهه", "10Y-3M Spread", "pp"),
        Indicator(FeatureKeys.BREAKEVEN_5Y, "F05_INFLATION", "نرخ سربه‌سر ۵ ساله", "5Y Breakeven", "%"),
        Indicator(FeatureKeys.BREAKEVEN_SLOPE, "F05_INFLATION", "شیب سربه‌سر", "Breakeven Slope", "pp"),
        Indicator(FeatureKeys.OECD_LEADING_INDICATOR, "F06_ECONOMIC_SURPRISE", "شاخص پیشروی OECD", "OECD Leading Indicator", "index"),
        Indicator(FeatureKeys.OECD_LEADING_CHANGE, "F06_ECONOMIC_SURPRISE", "تغییر ۶ماههٔ شاخص پیشرو", "OECD Leading Change", "index pts"),
        Indicator(FeatureKeys.INFECTIOUS_DISEASE_EMV, "F07_GEOPOLITICAL_RISK", "ردیاب نوسان بیماری واگیر", "Infectious Disease EMV", "index"),
        Indicator(FeatureKeys.VOL_DISPERSION_VXN_VIX, "F08_FINANCIAL_STRESS", "نسبت VXN به VIX", "VXN / VIX", "ratio"),
        Indicator(FeatureKeys.COT_MANAGED_MONEY_Z, "F11_FUTURES_POSITIONING", "Z پول مدیریت‌شده", "Managed Money Z", "z"),
        Indicator(FeatureKeys.COT_COMMERCIAL_Z, "F11_FUTURES_POSITIONING", "Z پوشش‌دهندگان تجاری", "Commercial Hedger Z", "z"),
        Indicator(FeatureKeys.OIL_VOLATILITY, "F15_OPTIONS_VOLATILITY", "نوسان ضمنی نفت", "Crude Implied Vol (OVX)", "index"),
        Indicator(FeatureKeys.CREDIT_SPREAD_IG, "F18_CREDIT", "اسپرد درجه سرمایه‌گذاری", "IG Credit Spread", "pp"),
        Indicator(FeatureKeys.CREDIT_SPREAD_CCC, "F18_CREDIT", "اسپرد CCC", "CCC Credit Spread", "pp"),
        Indicator(FeatureKeys.CREDIT_SPREAD_EM, "F18_CREDIT", "اسپرد شرکتی بازار نوظهور", "EM Corporate Spread", "pp"),
        Indicator(FeatureKeys.USDCNY, "F19_CHINA", "یوان به ازای دلار", "CNY per USD", "CNY"),
        Indicator(FeatureKeys.SOMA_CHANGE, "F17_LIQUIDITY", "تغییر پرتفوی SOMA", "SOMA Portfolio Change", "% (13w)"),
        Indicator(FeatureKeys.REPO_TAIL_SPREAD, "F17_LIQUIDITY", "دم توزیع ریپو", "Repo Tail Spread", "bp"),
        Indicator(FeatureKeys.RESERVE_BALANCES, "F17_LIQUIDITY", "ذخایر بانکی نزد فدرال‌رزرو", "Bank Reserve Balances", "USD bn"),
        Indicator(FeatureKeys.WTI_SPOT, "F21_OIL_ENERGY", "نفت وست تگزاس نقدی", "WTI Spot", "USD/bbl"),
        Indicator(FeatureKeys.OIL_MOMENTUM_SPOT, "F21_OIL_ENERGY", "مومنتوم نفت نقدی", "WTI Spot Momentum", "% (20d)"),
        Indicator(FeatureKeys.BRENT_WTI_SPREAD, "F21_OIL_ENERGY", "اسپرد برنت-وست تگزاس", "Brent − WTI Spread", "USD/bbl")
    )

    val byFactor: Map<String, List<Indicator>> = indicators.groupBy { it.factorId }
    val byKey: Map<String, Indicator> = indicators.associateBy { it.key }

    /* ------------------------------------------------------------------ */
    /* SPEC v2.1 §27 — the provenance audit                                */
    /* ------------------------------------------------------------------ */

    /**
     * What an indicator actually is. The engine already carries `isProxy` per
     * observation; this is the standing classification of the *input*, so the
     * app can state, for every indicator it publishes, whether the number is
     * the thing itself, an arithmetic combination of published things, or a
     * declared stand-in for something no free source publishes.
     */
    enum class Provenance {
        /** The published series itself, from its publisher. */
        MEASURED,

        /** Arithmetic on measured series only — a spread, a ratio, a z-score. */
        DERIVED,

        /** A declared stand-in: correlated with the target, not the target. */
        PROXY
    }

    data class Audit(
        val key: String,
        val provenance: Provenance,
        val source: String,
        val note: String
    )

    /**
     * Every indicator whose provenance is not plainly MEASURED is listed here
     * with the reason. An indicator absent from this map is measured from the
     * provider named in its observation record.
     */
    val audit: Map<String, Audit> = listOf(
        Audit(
            FeatureKeys.REAL_YIELD_INTRADAY_PROXY, Provenance.PROXY, "TRADINGVIEW",
            "The Treasury real curve is published once a day. Between publications the quoted " +
                "nominal ten-year less the last breakeven is the only available read; it never " +
                "replaces REAL_YIELD."
        ),
        Audit(
            FeatureKeys.DXY, Provenance.PROXY, "ECB_FX",
            "ICE's DXY is not freely licensed. The index is reconstructed from ECB reference " +
                "rates on the same six currencies and the same published geometric weights."
        ),
        Audit(
            FeatureKeys.ETF_FLOW_ZSCORE, Provenance.PROXY, "CBOE",
            "Signed dollar volume on the gold ETF. The sponsor's daily holdings file is not " +
                "published in a machine-readable form; every free route was tried and failed."
        ),
        Audit(
            FeatureKeys.ETF_HOLDINGS_CHANGE, Provenance.PROXY, "CBOE",
            "Same limitation as ETF_FLOW_ZSCORE: volume stands in for creations and redemptions."
        ),
        Audit(
            FeatureKeys.RECESSION_PROBABILITY, Provenance.DERIVED, "TREASURY",
            "Estrella-Mishkin style logistic map of the 10y−3m spread. A published formula " +
                "applied to measured yields, not an observed probability."
        ),
        Audit(
            FeatureKeys.GEOPOLITICAL_RISK_SCORE, Provenance.DERIVED, "FRED",
            "Five-year percentile of the published policy-uncertainty and equity-uncertainty " +
                "indices. The inputs are measured; the percentile is this engine's."
        ),
        Audit(
            FeatureKeys.FINANCIAL_STRESS_SCORE, Provenance.DERIVED, "FRED",
            "Mean of the published St. Louis Fed financial stress index and the Chicago Fed " +
                "national financial conditions index; both are measured, the average is this engine's."
        ),
        Audit(
            FeatureKeys.FED_NET_LIQUIDITY, Provenance.DERIVED, "FRED",
            "(WALCL − WTREGEN)/1000 − RRPONTSYD. Three published Federal Reserve series and one " +
                "subtraction; the daily legs are read as of the weekly balance-sheet date."
        ),
        Audit(
            FeatureKeys.REAL_CURVE_SLOPE, Provenance.DERIVED, "FRED",
            "Thirty-year minus five-year TIPS yield. Both legs are published daily by the " +
                "Treasury and by FRED; the slope is the subtraction and nothing else."
        ),
        Audit(
            FeatureKeys.BREAKEVEN_SLOPE, Provenance.DERIVED, "FRED",
            "Ten-year minus five-year breakeven inflation rate. Both legs are published " +
                "daily by FRED; the slope is the subtraction and nothing else."
        ),
        Audit(
            FeatureKeys.BRENT_WTI_SPREAD, Provenance.DERIVED, "FRED",
            "Brent minus WTI, both published daily by the Energy Information Administration " +
                "through FRED. The spread reads physical dislocation between the two benchmarks."
        ),
        Audit(
            FeatureKeys.VOL_DISPERSION_VXN_VIX, Provenance.DERIVED, "FRED",
            "Nasdaq implied volatility over S&P implied volatility; both published daily " +
                "by Cboe through FRED. The ratio is the tech-risk tilt, not a third index."
        ),
        Audit(
            FeatureKeys.SOMA_CHANGE, Provenance.DERIVED, "NY_FED",
            "Thirteen-week change in the New York Fed desk's published SOMA portfolio total; " +
                "the level is measured and only the difference is taken here."
        ),
        Audit(
            FeatureKeys.REPO_TAIL_SPREAD, Provenance.DERIVED, "NY_FED",
            "Published 99th percentile of SOFR less its published volume-weighted median."
        ),
        Audit(
            FeatureKeys.INDIA_PREMIUM, Provenance.DERIVED, "YAHOO",
            "MCX quote less the statutory duty and tax wedge, so only the discretionary part " +
                "reaches the factor. The quote is measured; the wedge is a published constant."
        ),
        Audit(
            FeatureKeys.CENTRAL_BANK_PROXY_FLOW, Provenance.DERIVED, "IMF_RESERVES",
            "Share of reporting central banks that added gold in the window. Derived from the " +
                "IMF reserves template; national scale errors are dropped, never corrected."
        )
    ).associateBy { it.key }

    fun provenanceOf(key: String): Provenance = audit[key]?.provenance ?: Provenance.MEASURED

    /** Counts for the diagnostics screen: measured, derived, proxy. */
    fun provenanceCounts(): Map<Provenance, Int> =
        indicators.groupingBy { provenanceOf(it.key) }.eachCount()
}
