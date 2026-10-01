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

    val all = listOf(CBOE, GOLD_API, TREASURY, CFTC, NY_FED, ECB_FX, YAHOO)
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

class CboeProvider(private val http: HttpClient) {
    private val quoteBase = "https://cdn.cboe.com/api/global/delayed_quotes/quotes/"
    private val histBase = "https://cdn.cboe.com/api/global/delayed_quotes/charts/historical/"

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

    private fun parseCboeTimestamp(raw: String): Instant? = try {
        LocalDateTime.parse(raw.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .toInstant(ZoneOffset.UTC)
    } catch (_: Exception) {
        null
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
