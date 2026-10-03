package io.goldintelligence.client

import io.goldintelligence.engine.Tier
import io.goldintelligence.ingestion.Bar
import io.goldintelligence.ingestion.Frequency
import io.goldintelligence.ingestion.LicenseClass
import io.goldintelligence.ingestion.TimeSeries
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Static provenance for every provider the free stack is allowed to use. */
data class ProviderSpec(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val license: LicenseClass,
    val tier: Tier,
    val attribution: String
)

object Providers {
    val CBOE = ProviderSpec(
        "CBOE", "Cboe Global Markets (delayed)", "https://cdn.cboe.com",
        LicenseClass.ATTRIBUTION, Tier.B,
        "Market data delayed, © Cboe Global Markets, Inc."
    )
    val GOLD_API = ProviderSpec(
        "GOLD_API", "gold-api.com", "https://api.gold-api.com",
        LicenseClass.ATTRIBUTION, Tier.C,
        "Spot reference prices courtesy of gold-api.com"
    )
    val TREASURY = ProviderSpec(
        "TREASURY", "U.S. Department of the Treasury", "https://home.treasury.gov",
        LicenseClass.PUBLIC_DOMAIN, Tier.A,
        "Daily Treasury Par Yield Curve Rates — public domain"
    )
    val CFTC = ProviderSpec(
        "CFTC", "U.S. Commodity Futures Trading Commission", "https://publicreporting.cftc.gov",
        LicenseClass.PUBLIC_DOMAIN, Tier.A,
        "Commitments of Traders — public domain"
    )
    val NY_FED = ProviderSpec(
        "NY_FED", "Federal Reserve Bank of New York", "https://markets.newyorkfed.org",
        LicenseClass.PUBLIC_DOMAIN, Tier.A,
        "Reference Rates — public domain"
    )
    val ECB_FX = ProviderSpec(
        "ECB_FX", "ECB reference rates via Frankfurter", "https://api.frankfurter.app",
        LicenseClass.ATTRIBUTION, Tier.B,
        "Exchange rates published by the European Central Bank"
    )
    val YAHOO = ProviderSpec(
        "YAHOO", "Yahoo Finance chart API", "https://query1.finance.yahoo.com",
        LicenseClass.RESTRICTED, Tier.C,
        "Derived values only; raw redistribution is not licensed."
    )

    val TRADINGVIEW = ProviderSpec(
        "TRADINGVIEW", "TradingView screener", "https://scanner.tradingview.com",
        LicenseClass.ATTRIBUTION, Tier.B,
        "Quotes and open interest via the TradingView screener"
    )
    val KRAKEN = ProviderSpec(
        "KRAKEN", "Kraken (PAXG allocated gold)", "https://api.kraken.com",
        LicenseClass.ATTRIBUTION, Tier.B,
        "Order book and candles for PAXG/USD"
    )
    val OKX = ProviderSpec(
        "OKX", "OKX (XAUT tokenised gold)", "https://www.okx.com",
        LicenseClass.ATTRIBUTION, Tier.B,
        "Order book and candles for XAUT/USDT"
    )
    val SWISSQUOTE = ProviderSpec(
        "SWISSQUOTE", "Swissquote OTC gold", "https://forex-data-feed.swissquote.com",
        LicenseClass.ATTRIBUTION, Tier.B,
        "Public best-bid/offer quotes by size tier"
    )
    val SINA_SGE = ProviderSpec(
        "SINA_SGE", "Shanghai Gold Exchange Au(T+D) via Sina", "https://hq.sinajs.cn",
        LicenseClass.ATTRIBUTION, Tier.C,
        "Shanghai Gold Exchange deferred contract quote"
    )
    val EASTMONEY_SGE = ProviderSpec(
        "EASTMONEY_SGE", "Shanghai Au(T+D) via Eastmoney (backup)", "https://push2.eastmoney.com",
        LicenseClass.ATTRIBUTION, Tier.C,
        "Backup quote for the Shanghai Gold Exchange deferred contract"
    )
    val FOREXFACTORY = ProviderSpec(
        "FOREXFACTORY", "ForexFactory economic calendar", "https://nfs.faireconomy.media",
        LicenseClass.ATTRIBUTION, Tier.C,
        "Scheduled releases with surveyed consensus forecasts"
    )
    val BLS = ProviderSpec(
        "BLS", "U.S. Bureau of Labor Statistics", "https://api.bls.gov",
        LicenseClass.PUBLIC_DOMAIN, Tier.A,
        "Consumer Price Index and labour statistics — public domain"
    )
    val GOLDPRICE_ORG = ProviderSpec(
        "GOLDPRICE_ORG", "goldprice.org", "https://data-asg.goldprice.org",
        LicenseClass.ATTRIBUTION, Tier.C,
        "Independent spot cross-check"
    )
    val WGC = ProviderSpec(
        "WGC", "World Gold Council (LBMA benchmark)", "https://fsapi.gold.org",
        LicenseClass.ATTRIBUTION, Tier.A,
        "LBMA-based gold benchmark series published by the World Gold Council"
    )

    /**
     * Providers the pipeline can lose without losing a published value:
     * Yahoo's futures history is covered by the Cboe GLD series and the
     * TradingView curve, and the Eastmoney mirror only backs up Sina.
     */
    /* ---- SPEC v2.1 §23 — quality sources ------------------------- */

    val IMF_RESERVES = ProviderSpec(
        "IMF_RESERVES", "IMF — International Reserves (IRFCL)", "https://api.imf.org",
        LicenseClass.ATTRIBUTION, Tier.A,
        "Official reserve assets as reported by national authorities to the IMF"
    )
    val CALENDAR = ProviderSpec(
        "CALENDAR", "Economic calendar with released actuals",
        "https://economic-calendar.tradingview.com",
        LicenseClass.ATTRIBUTION, Tier.B,
        "Scheduled releases with consensus and actual prints"
    )
    val BIS = ProviderSpec(
        "BIS", "Bank for International Settlements", "https://stats.bis.org",
        LicenseClass.ATTRIBUTION, Tier.A,
        "Central bank policy rates, © Bank for International Settlements"
    )
    val OECD = ProviderSpec(
        "OECD", "OECD SDMX", "https://sdmx.oecd.org",
        LicenseClass.ATTRIBUTION, Tier.A,
        "Organisation for Economic Co-operation and Development"
    )

    val COINBASE = ProviderSpec(
        "COINBASE", "Coinbase Exchange (PAXG/USD)", "https://api.exchange.coinbase.com",
        LicenseClass.ATTRIBUTION, Tier.B,
        "Level-2 order book for PAXG/USD"
    )

    /* ---- SPEC v2.1 §25 — measured macro and news-derived series ---- */

    /**
     * FRED's chart export serves any published series as CSV without an API
     * key. The documented `api.stlouisfed.org` route does require one, which
     * is why several inputs were proxies until now.
     */
    val FRED = ProviderSpec(
        "FRED", "Federal Reserve Bank of St. Louis (FRED)", "https://fred.stlouisfed.org",
        LicenseClass.PUBLIC_DOMAIN, Tier.A,
        "Series published by FRED, Federal Reserve Bank of St. Louis"
    )

    val optionalIds: Set<String> = setOf("YAHOO", "EASTMONEY_SGE", "COINBASE")

    val all = listOf(
        CBOE, GOLD_API, TREASURY, CFTC, NY_FED, ECB_FX, YAHOO,
        TRADINGVIEW, KRAKEN, OKX, SWISSQUOTE, SINA_SGE, EASTMONEY_SGE,
        FOREXFACTORY, BLS, GOLDPRICE_ORG, WGC,
        IMF_RESERVES, CALENDAR, BIS, COINBASE, FRED
    )
}

/* ------------------------------------------------------------------ */
/* FRED — keyless CSV export                                           */
/* ------------------------------------------------------------------ */

/**
 * SPEC v2.1 §25.1.
 *
 * `fred.stlouisfed.org/graph/fredgraph.csv?id=…` returns the full observation
 * history of a published series as two CSV columns and needs no key, no
 * account and no registration. Missing observations are published as `.` and
 * are dropped rather than carried forward.
 *
 * The endpoint refuses a browser User-Agent from datacentre ranges, so the
 * request carries this project's own agent string instead of the stack
 * default.
 */
class FredProvider(private val http: HttpClient) {

    fun series(id: String, since: String? = null): TimeSeries? {
        val url = buildString {
            append("https://fred.stlouisfed.org/graph/fredgraph.csv?id=").append(id)
            if (since != null) append("&cosd=").append(since)
        }
        val text = http.getText(Providers.FRED.id, url, headers = mapOf("User-Agent" to AGENT))
            ?: return null
        return parse(id, text)
    }

    /** Visible for test: the CSV shape FRED publishes. */
    fun parse(id: String, csv: String): TimeSeries? {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return null
        val header = lines.first().split(',')
        if (header.size < 2) return null
        val bars = ArrayList<Bar>(lines.size - 1)
        for (line in lines.drop(1)) {
            val cols = line.split(',')
            if (cols.size < 2) continue
            // FRED publishes a missing observation as "." — never zero-filled.
            val value = cols[1].trim().toDoubleOrNull() ?: continue
            val day = try {
                LocalDate.parse(cols[0].trim())
            } catch (_: Exception) {
                continue
            }
            val t = day.atStartOfDay(ZoneOffset.UTC).toInstant()
            bars += Bar(t, value, value, value, value)
        }
        if (bars.isEmpty()) return null
        return TimeSeries(id, bars.sortedBy { it.timestamp }, Frequency.DAILY)
    }

    companion object {
        const val AGENT = "GoldIntelligence/1.0 (+https://github.com/alisport1384/IDIE_GLD_INT_ENGN)"
    }
}

/* ------------------------------------------------------------------ */
/* Cboe delayed quotes + full daily history                            */
/* ------------------------------------------------------------------ */

data class CboeQuote(
    val symbol: String,
    val price: Double,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val prevClose: Double?,
    val volume: Double?,
    val bid: Double?,
    val ask: Double?,
    val bidSize: Double?,
    val askSize: Double?,
    val iv30: Double?,
    val asOf: Instant
)

/** One listed contract, reduced to the fields the measurements need. */
data class OptionContract(
    val expiry: LocalDate,
    val isCall: Boolean,
    val strike: Double,
    val iv: Double,
    val delta: Double,
    val openInterest: Double,
    val volume: Double
)

/**
 * SPEC v2.1 §25.2 — measurements taken from a published option chain.
 * Every figure below is a weighted count or an interpolation between two
 * quoted contracts; nothing is modelled.
 */
data class OptionChain(
    val symbol: String,
    val underlying: Double,
    val asOf: Instant,
    val contracts: List<OptionContract>
) {
    private val live = contracts.filter { it.iv > 0.0 && it.expiry.isAfter(LocalDate.now(ZoneOffset.UTC)) }

    val openInterestTotal: Double get() = contracts.sumOf { it.openInterest }

    /** Put open interest over call open interest; above 1 the chain is hedged for downside. */
    val putCallOpenInterest: Double?
        get() {
            val calls = contracts.filter { it.isCall }.sumOf { it.openInterest }
            val puts = contracts.filter { !it.isCall }.sumOf { it.openInterest }
            return if (calls > 0.0) puts / calls else null
        }

    /** Same ratio on the session's traded volume: today's demand, not the legacy book. */
    val putCallVolume: Double?
        get() {
            val calls = contracts.filter { it.isCall }.sumOf { it.volume }
            val puts = contracts.filter { !it.isCall }.sumOf { it.volume }
            return if (calls > 0.0) puts / calls else null
        }

    /**
     * 25-delta risk reversal in volatility points: the implied volatility the
     * market charges for a 25-delta call minus the one it charges for the
     * mirror put, on the nearest expiry at least [MIN_TENOR_DAYS] out.
     * Positive means upside is the expensive side.
     */
    val riskReversal25: Double? get() = riskReversalFor(nearTerm())

    /** The same measure on the next quarterly-sized tenor, for the term view. */
    val riskReversal25Far: Double? get() = riskReversalFor(farTerm())

    /** At-the-money implied volatility of the far tenor minus the near one. */
    val ivTermSlope: Double?
        get() {
            val near = atmIv(nearTerm()) ?: return null
            val far = atmIv(farTerm()) ?: return null
            return (far - near) * 100.0
        }

    val nearTermExpiry: LocalDate? get() = nearTerm()

    private fun nearTerm(): LocalDate? {
        val today = LocalDate.now(ZoneOffset.UTC)
        return live.map { it.expiry }
            .filter { java.time.temporal.ChronoUnit.DAYS.between(today, it) >= MIN_TENOR_DAYS }
            .minOrNull()
    }

    private fun farTerm(): LocalDate? {
        val today = LocalDate.now(ZoneOffset.UTC)
        return live.map { it.expiry }
            .filter { java.time.temporal.ChronoUnit.DAYS.between(today, it) >= FAR_TENOR_DAYS }
            .minOrNull()
    }

    private fun riskReversalFor(expiry: LocalDate?): Double? {
        if (expiry == null) return null
        val slice = live.filter { it.expiry == expiry }
        val call = slice.filter { it.isCall && it.delta > 0.0 }
            .minByOrNull { abs(it.delta - TARGET_DELTA) } ?: return null
        val put = slice.filter { !it.isCall && it.delta < 0.0 }
            .minByOrNull { abs(abs(it.delta) - TARGET_DELTA) } ?: return null
        // Refuse the reading when neither wing is close to 25 delta.
        if (abs(call.delta - TARGET_DELTA) > DELTA_TOLERANCE) return null
        if (abs(abs(put.delta) - TARGET_DELTA) > DELTA_TOLERANCE) return null
        return (call.iv - put.iv) * 100.0
    }

    private fun atmIv(expiry: LocalDate?): Double? {
        if (expiry == null) return null
        val slice = live.filter { it.expiry == expiry }
        if (slice.isEmpty()) return null
        val nearest = slice.minByOrNull { abs(it.strike - underlying) } ?: return null
        val pair = slice.filter { abs(it.strike - nearest.strike) < 1e-9 }
        return if (pair.isEmpty()) null else pair.map { it.iv }.average()
    }

    companion object {
        /** Contracts inside this window are dominated by expiry mechanics. */
        const val MIN_TENOR_DAYS = 14L
        const val FAR_TENOR_DAYS = 75L
        const val TARGET_DELTA = 0.25
        const val DELTA_TOLERANCE = 0.08
    }
}

class CboeProvider(private val http: HttpClient) {
    private val quoteBase = "https://cdn.cboe.com/api/global/delayed_quotes/quotes/"
    private val histBase = "https://cdn.cboe.com/api/global/delayed_quotes/charts/historical/"
    private val optionBase = "https://cdn.cboe.com/api/global/delayed_quotes/options/"

    /** Index symbols are requested with a leading underscore on this endpoint. */
    private fun encode(symbol: String): String = if (symbol.startsWith("^")) "_" + symbol.drop(1) else symbol

    fun quote(symbol: String): CboeQuote? {
        val json = http.getJson(Providers.CBOE.id, quoteBase + encode(symbol) + ".json") ?: return null
        val d = json["data"] ?: return null
        val price = d["current_price"]?.asDouble ?: d["close"]?.asDouble ?: return null
        val ts = json["timestamp"]?.asString?.let { parseCboeTimestamp(it) } ?: Instant.now()
        return CboeQuote(
            symbol = d["symbol"]?.asString ?: symbol,
            price = price,
            open = d["open"]?.asDouble,
            high = d["high"]?.asDouble,
            low = d["low"]?.asDouble,
            prevClose = d["prev_day_close"]?.asDouble,
            volume = d["volume"]?.asDouble,
            bid = d["bid"]?.asDouble,
            ask = d["ask"]?.asDouble,
            bidSize = d["bid_size"]?.asDouble,
            askSize = d["ask_size"]?.asDouble,
            iv30 = d["iv30"]?.asDouble,
            asOf = ts
        )
    }

    fun history(symbol: String, maxBars: Int = 600): TimeSeries? {
        val json = http.getJson(Providers.CBOE.id, histBase + encode(symbol) + ".json") ?: return null
        val rows = json["data"]?.asArray ?: return null
        if (rows.isEmpty()) return null
        val bars = rows.mapNotNull { row ->
            val date = row["date"]?.asString ?: return@mapNotNull null
            val close = row["close"]?.asDouble ?: return@mapNotNull null
            val instant = try {
                LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant()
            } catch (_: Exception) {
                return@mapNotNull null
            }
            Bar(
                timestamp = instant,
                open = row["open"]?.asDouble ?: close,
                high = row["high"]?.asDouble ?: close,
                low = row["low"]?.asDouble ?: close,
                close = close,
                volume = row["volume"]?.asDouble
            )
        }.sortedBy { it.timestamp }
        if (bars.isEmpty()) return null
        return TimeSeries(symbol, bars.takeLast(maxBars), Frequency.DAILY)
    }

    /**
     * SPEC v2.1 §25.2 — the full delayed option chain for one underlying.
     * Cboe publishes every listed contract with its implied volatility,
     * open interest, volume and greeks, which is what makes a measured
     * risk reversal and a measured put/call ratio possible at all.
     */
    fun optionChain(symbol: String): OptionChain? {
        val json = http.getJson(Providers.CBOE.id, optionBase + encode(symbol) + ".json") ?: return null
        val d = json["data"] ?: return null
        val rows = d["options"]?.asArray ?: return null
        if (rows.isEmpty()) return null
        val underlying = d["current_price"]?.asDouble ?: d["close"]?.asDouble ?: return null
        val asOf = json["timestamp"]?.asString?.let { parseCboeTimestamp(it) } ?: Instant.now()
        val contracts = rows.mapNotNull { row ->
            val name = row["option"]?.asString ?: return@mapNotNull null
            val parsed = parseOptionSymbol(name) ?: return@mapNotNull null
            OptionContract(
                expiry = parsed.first,
                isCall = parsed.second,
                strike = parsed.third,
                iv = row["iv"]?.asDouble ?: 0.0,
                delta = row["delta"]?.asDouble ?: 0.0,
                openInterest = row["open_interest"]?.asDouble ?: 0.0,
                volume = row["volume"]?.asDouble ?: 0.0
            )
        }
        if (contracts.isEmpty()) return null
        return OptionChain(symbol, underlying, asOf, contracts)
    }

    /**
     * OCC symbol: root, `yyMMdd`, `C`/`P`, then the strike in thousandths.
     * Returns null rather than guessing when the shape is not the OCC one.
     */
    private fun parseOptionSymbol(raw: String): Triple<LocalDate, Boolean, Double>? {
        val m = OCC.find(raw) ?: return null
        val (yy, mm, dd, cp, strike) = m.destructured
        val date = try {
            LocalDate.of(2000 + yy.toInt(), mm.toInt(), dd.toInt())
        } catch (_: Exception) {
            return null
        }
        val k = strike.toDoubleOrNull()?.div(1000.0) ?: return null
        return Triple(date, cp == "C", k)
    }

    private fun parseCboeTimestamp(raw: String): Instant? = try {
        LocalDateTime.parse(raw.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .toInstant(ZoneOffset.UTC)
    } catch (_: Exception) {
        null
    }

    companion object {
        /** OCC contract symbol: root, yyMMdd, C or P, strike in thousandths. */
        private val OCC = Regex("""[A-Z]+(\d{2})(\d{2})(\d{2})([CP])(\d{8})""")
    }
}

/* ------------------------------------------------------------------ */
/* Spot reference                                                      */
/* ------------------------------------------------------------------ */

data class SpotQuote(val symbol: String, val price: Double, val asOf: Instant)

class GoldApiProvider(private val http: HttpClient) {
    fun spot(symbol: String): SpotQuote? {
        val json = http.getJson(Providers.GOLD_API.id, "https://api.gold-api.com/price/$symbol") ?: return null
        val price = json["price"]?.asDouble ?: return null
        val asOf = json["updatedAt"]?.asString?.let {
            try {
                Instant.parse(it)
            } catch (_: Exception) {
                null
            }
        } ?: Instant.now()
        return SpotQuote(symbol, price, asOf)
    }
}

/* ------------------------------------------------------------------ */
/* Treasury par yield curves (nominal and real)                        */
/* ------------------------------------------------------------------ */

class TreasuryProvider(private val http: HttpClient) {
    enum class CurveType(val code: String) {
        NOMINAL("daily_treasury_yield_curve"),
        REAL("daily_treasury_real_yield_curve")
    }

    /** date → (tenor label → yield). Newest last. */
    fun curve(type: CurveType, years: List<Int>): Map<LocalDate, Map<String, Double>> {
        val merged = sortedMapOf<LocalDate, Map<String, Double>>()
        for (year in years) {
            val url = "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/" +
                "daily-treasury-rates.csv/$year/all?type=${type.code}" +
                "&field_tdr_date_value=$year&page&_format=csv"
            val csv = http.getText(Providers.TREASURY.id, url) ?: continue
            merged.putAll(parseCsv(csv))
        }
        return merged
    }

    fun seriesOf(curve: Map<LocalDate, Map<String, Double>>, tenor: String, id: String): TimeSeries? {
        val bars = curve.entries.mapNotNull { (date, row) ->
            val v = row[tenor] ?: return@mapNotNull null
            Bar(date.atStartOfDay(ZoneOffset.UTC).toInstant(), v, v, v, v, null)
        }.sortedBy { it.timestamp }
        return if (bars.isEmpty()) null else TimeSeries(id, bars, Frequency.DAILY)
    }

    private fun parseCsv(csv: String): Map<LocalDate, Map<String, Double>> {
        val lines = csv.trim().lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyMap()
        val header = splitCsvLine(lines.first()).map { it.trim().trim('"') }
        val out = LinkedHashMap<LocalDate, Map<String, Double>>()
        for (line in lines.drop(1)) {
            val cells = splitCsvLine(line)
            if (cells.size < 2) continue
            val date = parseDate(cells[0].trim().trim('"')) ?: continue
            val row = LinkedHashMap<String, Double>()
            for (c in 1 until minOf(cells.size, header.size)) {
                val v = cells[c].trim().trim('"').toDoubleOrNull() ?: continue
                row[normalizeTenor(header[c])] = v
            }
            if (row.isNotEmpty()) out[date] = row
        }
        return out
    }

    private fun normalizeTenor(label: String): String =
        label.uppercase().replace("YR", "Y").replace("YEAR", "Y")
            .replace("MO", "M").replace("MONTH", "M").replace(" ", "")

    private fun parseDate(raw: String): LocalDate? {
        for (p in listOf("MM/dd/yyyy", "yyyy-MM-dd", "M/d/yyyy")) {
            try {
                return LocalDate.parse(raw, DateTimeFormatter.ofPattern(p))
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun splitCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
        }
        out += sb.toString()
        return out
    }

    companion object {
        const val T10Y = "10Y"
        const val T2Y = "2Y"
        const val T3M = "3M"
        const val T30Y = "30Y"
        const val T5Y = "5Y"
        const val T7Y = "7Y"
        const val T20Y = "20Y"
    }
}

/* ------------------------------------------------------------------ */
/* CFTC Commitments of Traders                                         */
/* ------------------------------------------------------------------ */

class CftcProvider(private val http: HttpClient) {
    /** COMEX gold, legacy futures-only report. */
    private val contractCode = "088691"

    data class CotRow(val date: Instant, val net: Double, val openInterest: Double?)

    fun netNonCommercial(maxWeeks: Int = 200): List<CotRow> {
        val url = "https://publicreporting.cftc.gov/resource/6dca-aqww.json" +
            "?\$limit=$maxWeeks&\$order=report_date_as_yyyy_mm_dd%20DESC" +
            "&cftc_contract_market_code=$contractCode"
        val json = http.getJson(Providers.CFTC.id, url) ?: return emptyList()
        return json.asArray.mapNotNull { row ->
            val d = row["report_date_as_yyyy_mm_dd"]?.asString ?: return@mapNotNull null
            val long = row["noncomm_positions_long_all"]?.asDouble ?: return@mapNotNull null
            val short = row["noncomm_positions_short_all"]?.asDouble ?: return@mapNotNull null
            val ts = try {
                LocalDateTime.parse(d.substringBefore('.')).toInstant(ZoneOffset.UTC)
            } catch (_: Exception) {
                try {
                    LocalDate.parse(d.substring(0, 10)).atStartOfDay(ZoneOffset.UTC).toInstant()
                } catch (_: Exception) {
                    return@mapNotNull null
                }
            }
            CotRow(ts, long - short, row["open_interest_all"]?.asDouble)
        }.sortedBy { it.date }
    }

    /**
     * SPEC v2.1 §27 — the disaggregated report, which splits the old
     * "non-commercial" bucket into the categories the CFTC actually
     * publishes. Managed money is the speculative leg; the swap dealers and
     * producers sit on the other side. Same host, same licence, one request.
     */
    data class CotDetail(
        val date: Instant,
        val managedMoneyNet: Double,
        val commercialNet: Double,
        val openInterest: Double?
    )

    fun disaggregated(maxWeeks: Int = 400): List<CotDetail> {
        val url = "https://publicreporting.cftc.gov/resource/72hh-3qpy.json" +
            "?\$limit=$maxWeeks&\$order=report_date_as_yyyy_mm_dd%20DESC" +
            "&cftc_contract_market_code=$contractCode"
        val json = http.getJson(Providers.CFTC.id, url) ?: return emptyList()
        return json.asArray.mapNotNull { row ->
            val ts = instantOf(row["report_date_as_yyyy_mm_dd"]?.asString) ?: return@mapNotNull null
            val mmLong = row["m_money_positions_long_all"]?.asDouble ?: return@mapNotNull null
            val mmShort = row["m_money_positions_short_all"]?.asDouble ?: return@mapNotNull null
            // Producers/merchants/processors/users — the commercial hedgers.
            val cLong = row["prod_merc_positions_long"]?.asDouble
            val cShort = row["prod_merc_positions_short"]?.asDouble
            CotDetail(
                ts,
                mmLong - mmShort,
                if (cLong != null && cShort != null) cLong - cShort else Double.NaN,
                row["open_interest_all"]?.asDouble
            )
        }.filter { it.managedMoneyNet.isFinite() }.sortedBy { it.date }
    }

    private fun instantOf(raw: String?): Instant? {
        val d = raw ?: return null
        return try {
            LocalDateTime.parse(d.substringBefore('.')).toInstant(ZoneOffset.UTC)
        } catch (_: Exception) {
            try {
                LocalDate.parse(d.substring(0, 10)).atStartOfDay(ZoneOffset.UTC).toInstant()
            } catch (_: Exception) {
                null
            }
        }
    }

    fun detailSeries(rows: List<CotDetail>, id: String, pick: (CotDetail) -> Double?): TimeSeries? {
        val bars = rows.mapNotNull { r ->
            val v = pick(r)?.takeIf { it.isFinite() } ?: return@mapNotNull null
            Bar(r.date, v, v, v, v, r.openInterest)
        }
        return if (bars.size < 10) null else TimeSeries(id, bars, Frequency.WEEKLY)
    }

    fun toSeries(rows: List<CotRow>, id: String): TimeSeries? {
        if (rows.isEmpty()) return null
        return TimeSeries(
            id,
            rows.map { Bar(it.date, it.net, it.net, it.net, it.net, it.openInterest) },
            Frequency.WEEKLY
        )
    }
}

/* ------------------------------------------------------------------ */
/* NY Fed reference rates                                              */
/* ------------------------------------------------------------------ */

class NyFedProvider(private val http: HttpClient) {
    /**
     * Daily history of one reference rate. The funding-stress feature is a
     * standardized spread, so it needs a distribution, not a single reading.
     */
    fun rateHistory(group: String, type: String, days: Int = 250): List<Pair<LocalDate, Double>> {
        val url = "https://markets.newyorkfed.org/api/rates/$group/$type/last/$days.json"
        val json = http.getJson(Providers.NY_FED.id, url) ?: return emptyList()
        return json["refRates"]?.asArray?.mapNotNull { r ->
            val d = r["effectiveDate"]?.asString ?: return@mapNotNull null
            val v = r["percentRate"]?.asDouble ?: return@mapNotNull null
            val date = try {
                LocalDate.parse(d)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            date to v
        }?.sortedBy { it.first } ?: emptyList()
    }

    /** SOFR minus EFFR, in basis points, as a daily series. */
    fun fundingSpreadSeries(days: Int = 250): TimeSeries? {
        val sofr = rateHistory("secured", "sofr", days).toMap()
        val effr = rateHistory("unsecured", "effr", days).toMap()
        if (sofr.isEmpty() || effr.isEmpty()) return null
        val bars = sofr.keys.intersect(effr.keys).sorted().mapNotNull { d ->
            val s = sofr[d] ?: return@mapNotNull null
            val e = effr[d] ?: return@mapNotNull null
            val v = (s - e) * 100.0
            Bar(d.atStartOfDay(ZoneOffset.UTC).toInstant(), v, v, v, v, null)
        }
        return if (bars.size < 20) null else TimeSeries("FUNDING_SPREAD", bars, Frequency.DAILY)
    }

    /**
     * SPEC v2.1 §27 — the System Open Market Account, i.e. the securities the
     * Fed actually holds, published weekly by the desk that holds them. The
     * balance-sheet leg of F17 previously came from the FRED aggregate alone.
     */
    fun somaTotal(): TimeSeries? {
        val json = http.getJson(Providers.NY_FED.id, "https://markets.newyorkfed.org/api/soma/summary.json")
            ?: return null
        val rows = json["soma"]?.get("summary")?.asArray ?: return null
        val bars = rows.mapNotNull { r ->
            val d = r["asOfDate"]?.asString ?: return@mapNotNull null
            val total = r["total"]?.asDouble ?: return@mapNotNull null
            val date = try {
                LocalDate.parse(d)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            // Published in dollars; carried in billions like the other
            // liquidity series so the scales on screen are comparable.
            val v = total / 1_000_000_000.0
            Bar(date.atStartOfDay(ZoneOffset.UTC).toInstant(), v, v, v, v, null)
        }.sortedBy { it.timestamp }
        return if (bars.size < 50) null else TimeSeries("SOMA_TOTAL", bars, Frequency.WEEKLY)
    }

    /**
     * SPEC v2.1 §27 — the 99th-percentile SOFR print minus the volume-weighted
     * median, in basis points. The tail of the repo distribution moves before
     * the average does, so it is the earlier read on funding stress.
     */
    fun sofrTailSeries(days: Int = 250): TimeSeries? {
        val url = "https://markets.newyorkfed.org/api/rates/secured/sofr/last/$days.json"
        val json = http.getJson(Providers.NY_FED.id, url) ?: return null
        val bars = json["refRates"]?.asArray?.mapNotNull { r ->
            val d = r["effectiveDate"]?.asString ?: return@mapNotNull null
            val mid = r["percentRate"]?.asDouble ?: return@mapNotNull null
            val p99 = r["percentPercentile99"]?.asDouble ?: return@mapNotNull null
            val date = try {
                LocalDate.parse(d)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            val v = (p99 - mid) * 100.0
            Bar(date.atStartOfDay(ZoneOffset.UTC).toInstant(), v, v, v, v, null)
        }?.sortedBy { it.timestamp } ?: return null
        return if (bars.size < 20) null else TimeSeries("SOFR_P99_SPREAD", bars, Frequency.DAILY)
    }

    fun latestRates(): Map<String, Double> {
        val json = http.getJson(Providers.NY_FED.id, "https://markets.newyorkfed.org/api/rates/all/latest.json")
            ?: return emptyMap()
        val out = LinkedHashMap<String, Double>()
        json["refRates"]?.asArray?.forEach { r ->
            val type = r["type"]?.asString ?: return@forEach
            val rate = r["percentRate"]?.asDouble ?: return@forEach
            out[type] = rate
        }
        return out
    }
}

/* ------------------------------------------------------------------ */
/* FX / synthetic dollar index                                         */
/* ------------------------------------------------------------------ */

class FxProvider(private val http: HttpClient) {
    private val symbols = listOf("EUR", "JPY", "GBP", "CAD", "SEK", "CHF", "CNY", "INR")

    /** date → (currency → units per USD). */
    fun series(from: LocalDate, to: LocalDate): Map<LocalDate, Map<String, Double>> {
        val url = "https://api.frankfurter.app/$from..$to?base=USD&symbols=${symbols.joinToString(",")}"
        val json = http.getJson(Providers.ECB_FX.id, url) ?: return emptyMap()
        val rates = json["rates"]?.asObject ?: return emptyMap()
        val out = sortedMapOf<LocalDate, Map<String, Double>>()
        rates.forEach { (dateStr, row) ->
            val date = try {
                LocalDate.parse(dateStr)
            } catch (_: Exception) {
                return@forEach
            }
            val map = row.asObject.mapNotNull { (k, v) -> v.asDouble?.let { k to it } }.toMap()
            if (map.isNotEmpty()) out[date] = map
        }
        return out
    }

    /**
     * ICE's DXY basket replicated from ECB reference rates. The published
     * index is not freely licensed; this reconstruction uses the same six
     * currencies and the same published geometric weights, so it tracks the
     * index closely but is declared as a proxy, never as DXY itself.
     */
    fun syntheticDxy(row: Map<String, Double>): Double? {
        val eur = row["EUR"] ?: return null
        val jpy = row["JPY"] ?: return null
        val gbp = row["GBP"] ?: return null
        val cad = row["CAD"] ?: return null
        val sek = row["SEK"] ?: return null
        val chf = row["CHF"] ?: return null
        if (eur <= 0 || gbp <= 0) return null
        val eurUsd = 1.0 / eur
        val gbpUsd = 1.0 / gbp
        return 50.14348112 *
            Math.pow(eurUsd, -0.576) *
            Math.pow(jpy, 0.136) *
            Math.pow(gbpUsd, -0.119) *
            Math.pow(cad, 0.091) *
            Math.pow(sek, 0.042) *
            Math.pow(chf, 0.036)
    }

    fun dxySeries(fx: Map<LocalDate, Map<String, Double>>): TimeSeries? {
        val bars = fx.entries.mapNotNull { (date, row) ->
            val v = syntheticDxy(row) ?: return@mapNotNull null
            Bar(date.atStartOfDay(ZoneOffset.UTC).toInstant(), v, v, v, v, null)
        }.sortedBy { it.timestamp }
        return if (bars.size < 10) null else TimeSeries("DXY_SYNTHETIC", bars, Frequency.DAILY)
    }

    fun currencySeries(fx: Map<LocalDate, Map<String, Double>>, currency: String, id: String): TimeSeries? {
        val bars = fx.entries.mapNotNull { (date, row) ->
            val v = row[currency] ?: return@mapNotNull null
            Bar(date.atStartOfDay(ZoneOffset.UTC).toInstant(), v, v, v, v, null)
        }.sortedBy { it.timestamp }
        return if (bars.size < 10) null else TimeSeries(id, bars, Frequency.DAILY)
    }
}

/* ------------------------------------------------------------------ */
/* Yahoo chart API — optional, derived use only                        */
/* ------------------------------------------------------------------ */

class YahooProvider(private val http: HttpClient) {
    /**
     * COMEX dated contracts require the `.CMX` suffix; the bare root returns
     * "Not Found". Yahoo answers 429 to server IP ranges often enough that
     * every caller must treat a null return as normal, not exceptional.
     */
    fun chart(symbol: String, interval: String = "1d", range: String = "2y"): TimeSeries? {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/" +
            java.net.URLEncoder.encode(symbol, "UTF-8") + "?interval=$interval&range=$range"
        val json = http.getJson(Providers.YAHOO.id, url) ?: return null
        val result = json["chart"]?.get("result")?.get(0) ?: return null
        // SPEC v2 §12.4 G09: reject a response whose granularity is not the one requested.
        val granularity = result["meta"]?.get("dataGranularity")?.asString
        if (granularity != null && !granularity.equals(interval, ignoreCase = true)) return null
        val stamps = result["timestamp"]?.asArray ?: return null
        val quote = result["indicators"]?.get("quote")?.get(0) ?: return null
        val closes = quote["close"]?.asArray ?: return null
        val opens = quote["open"]?.asArray
        val highs = quote["high"]?.asArray
        val lows = quote["low"]?.asArray
        val volumes = quote["volume"]?.asArray
        val bars = ArrayList<Bar>(stamps.size)
        for (i in stamps.indices) {
            val t = stamps[i].asDouble?.toLong() ?: continue
            val c = closes.getOrNull(i)?.asDouble ?: continue
            bars += Bar(
                timestamp = Instant.ofEpochSecond(t),
                open = opens?.getOrNull(i)?.asDouble ?: c,
                high = highs?.getOrNull(i)?.asDouble ?: c,
                low = lows?.getOrNull(i)?.asDouble ?: c,
                close = c,
                volume = volumes?.getOrNull(i)?.asDouble
            )
        }
        if (bars.isEmpty()) return null
        return TimeSeries(symbol, bars.sortedBy { it.timestamp }, Frequency.DAILY)
    }

    /** SPEC v2 §1.5 — forward curve from dated COMEX contracts. */
    fun forwardCurve(contracts: List<String>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (c in contracts) {
            val s = chart(c, "1d", "5d") ?: continue
            val last = s.last?.close ?: continue
            out[c] = last
        }
        return out
    }
}


/* ------------------------------------------------------------------ */
/* OECD composite leading indicator (SPEC v2.1 §27)                    */
/* ------------------------------------------------------------------ */

/**
 * The OECD's composite leading indicator for the United States, from the
 * organisation's own SDMX endpoint in CSV form. It is the growth-turning-point
 * read F06 previously had to infer from the surprise index alone: an amplitude
 * -adjusted index where 100 is trend, above 100 is expansion.
 */
class OecdProvider(private val http: HttpClient) {

    fun compositeLeadingIndicator(startPeriod: String = "2003-01"): TimeSeries? {
        val url = "https://sdmx.oecd.org/public/rest/data/" +
            "OECD.SDD.STES,DSD_STES@DF_CLI,/USA.M.LI...AA...H" +
            "?startPeriod=$startPeriod&format=csvfile"
        val csv = http.getText(Providers.OECD.id, url) ?: return null
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return null
        val header = lines.first().split(',').map { it.trim().trim('"') }
        val periodAt = header.indexOf("TIME_PERIOD")
        val valueAt = header.indexOf("OBS_VALUE")
        if (periodAt < 0 || valueAt < 0) return null
        val bars = lines.drop(1).mapNotNull { line ->
            val cols = splitCsv(line)
            if (cols.size <= maxOf(periodAt, valueAt)) return@mapNotNull null
            val period = cols[periodAt].trim().trim('"')
            val value = cols[valueAt].trim().trim('"').toDoubleOrNull() ?: return@mapNotNull null
            // Monthly periods arrive as YYYY-MM and are dated to the first.
            val date = try {
                LocalDate.parse(if (period.length == 7) "$period-01" else period)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            Bar(date.atStartOfDay(ZoneOffset.UTC).toInstant(), value, value, value, value, null)
        }.sortedBy { it.timestamp }
        return if (bars.size < 12) null else TimeSeries("OECD_CLI_US", bars, Frequency.MONTHLY)
    }

    /** Minimal RFC-4180 split: quoted fields may contain commas. */
    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        for (c in line) {
            when {
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    out += sb.toString(); sb.setLength(0)
                }
                else -> sb.append(c)
            }
        }
        out += sb.toString()
        return out
    }
}
