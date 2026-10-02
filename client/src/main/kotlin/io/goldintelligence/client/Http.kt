package io.goldintelligence.client

import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.LogLevel
import io.goldintelligence.engine.LogStage
import io.goldintelligence.ingestion.ProviderHealth
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

data class HttpResponse(
    val url: String,
    val status: Int,
    val body: String,
    val latencyMillis: Long,
    val error: String? = null
) {
    val ok: Boolean get() = status in 200..299 && body.isNotBlank()
}

/**
 * Blocking HTTP client with the three protections the live probing showed are
 * mandatory on this data stack: a browser User-Agent (several providers answer
 * 429 to the default JVM agent), a per-host minimum interval, and a circuit
 * breaker that stops hammering a provider that has started refusing traffic.
 *
 * Deliberately blocking: callers run it on a background executor. That keeps
 * the module free of a coroutines dependency and keeps the Android call path
 * explicit.
 */
class HttpClient(
    private val userAgent: String = DEFAULT_UA,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 25_000,
    private val minIntervalMillis: Long = 250,
    private val maxRetries: Int = 2,
    private val circuitOpenDuration: Duration = Duration.ofMinutes(15),
    private val log: DiagnosticLog = DiagnosticLog.shared
) {
    private val lastCall = ConcurrentHashMap<String, Long>()
    private val circuitUntil = ConcurrentHashMap<String, Instant>()
    private val health = ConcurrentHashMap<String, ProviderHealth>()

    fun healthSnapshot(): Map<String, ProviderHealth> = health.toMap()

    fun isOpen(providerId: String, now: Instant = Instant.now()): Boolean {
        val until = circuitUntil[providerId] ?: return false
        if (now.isAfter(until)) {
            circuitUntil.remove(providerId)
            return false
        }
        return true
    }

    fun get(
        providerId: String,
        url: String,
        accept: String = "*/*",
        headers: Map<String, String> = emptyMap()
    ): HttpResponse {
        val now = Instant.now()
        if (isOpen(providerId, now)) {
            val r = HttpResponse(url, 0, "", 0, "CIRCUIT_OPEN")
            record(providerId, r, now)
            log.warn(
                LogStage.NETWORK, providerId, "CIRCUIT_OPEN",
                "call skipped, breaker open until " + circuitUntil[providerId],
                key = null, detail = url
            )
            return r
        }
        throttle(hostOf(url))

        var last: HttpResponse? = null
        for (attempt in 0..maxRetries) {
            val r = execute(url, accept, headers = headers)
            last = r
            if (r.ok) {
                record(providerId, r, Instant.now())
                return r
            }
            // 429/403 are provider-side refusals: open the breaker, do not retry harder.
            if (r.status == 429 || r.status == 403) {
                circuitUntil[providerId] = Instant.now().plus(circuitOpenDuration)
                log.error(
                    LogStage.NETWORK, providerId, "HTTP_" + r.status,
                    "provider refused the request; breaker opened for " +
                        circuitOpenDuration.toMinutes() + " min",
                    detail = url
                )
                break
            }
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(400L * (attempt + 1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        val result = last ?: HttpResponse(url, 0, "", 0, "NO_RESPONSE")
        record(providerId, result, Instant.now())
        logResponse(providerId, result)
        return result
    }

    /**
     * POST with a request body. Several of the free venues that replaced the
     * paid gaps (the TradingView screener in particular) only answer to POST.
     */
    fun post(
        providerId: String,
        url: String,
        body: String,
        contentType: String = "application/json",
        accept: String = "application/json"
    ): HttpResponse {
        val now = Instant.now()
        if (isOpen(providerId, now)) {
            val r = HttpResponse(url, 0, "", 0, "CIRCUIT_OPEN")
            record(providerId, r, now)
            log.warn(LogStage.NETWORK, providerId, "CIRCUIT_OPEN", "POST skipped, breaker open", detail = url)
            return r
        }
        throttle(hostOf(url))
        val r = execute(url, accept, method = "POST", body = body, contentType = contentType)
        record(providerId, r, Instant.now())
        logResponse(providerId, r)
        if (r.status == 429 || r.status == 403) {
            circuitUntil[providerId] = Instant.now().plus(circuitOpenDuration)
        }
        return r
    }

    fun postJson(providerId: String, url: String, body: String): Json? {
        val r = post(providerId, url, body)
        if (!r.ok) return null
        val parsed = Json.parseOrNull(r.body)
        if (parsed == null) {
            log.error(
                LogStage.PARSE, providerId, "JSON_MALFORMED",
                "response was not valid JSON", detail = r.body.take(200)
            )
        }
        return parsed
    }

    private fun logResponse(providerId: String, r: HttpResponse) {
        log.log(
            level = if (r.ok) LogLevel.DEBUG else LogLevel.ERROR,
            stage = LogStage.NETWORK,
            component = providerId,
            code = when {
                r.ok -> "HTTP_OK"
                r.error != null && r.status == 0 -> "TRANSPORT_ERROR"
                else -> "HTTP_" + r.status
            },
            message = if (r.ok) "${r.body.length} chars" else (r.error ?: "HTTP ${r.status}"),
            url = r.url,
            httpStatus = r.status.takeIf { it > 0 },
            latencyMillis = r.latencyMillis
        )
    }

    fun getJson(providerId: String, url: String, headers: Map<String, String> = emptyMap()): Json? {
        val r = get(providerId, url, "application/json", headers)
        if (!r.ok) return null
        val parsed = Json.parseOrNull(r.body)
        if (parsed == null) {
            log.error(
                LogStage.PARSE, providerId, "JSON_MALFORMED",
                "response was not valid JSON", detail = r.body.take(200)
            )
        }
        return parsed
    }

    fun getText(providerId: String, url: String, headers: Map<String, String> = emptyMap()): String? {
        val r = get(providerId, url, "text/csv,text/plain,*/*", headers)
        return if (r.ok) r.body else null
    }

    private fun execute(
        url: String,
        accept: String,
        method: String = "GET",
        body: String? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse {
        val started = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", accept)
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                // Two of the free feeds answer only when the request carries the
                // Referer their own page would send; without it they return 403.
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType ?: "application/json")
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { raw ->
                val decoded = if (conn.contentEncoding?.contains("gzip", true) == true) {
                    GZIPInputStream(raw)
                } else raw
                decoded.use { readAll(it) }
            } ?: ""
            HttpResponse(url, status, body, System.currentTimeMillis() - started)
        } catch (e: Exception) {
            HttpResponse(url, 0, "", System.currentTimeMillis() - started, e.javaClass.simpleName + ": " + e.message)
        } finally {
            conn?.disconnect()
        }
    }

    private fun readAll(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BODY_BYTES) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    private fun throttle(host: String) {
        val now = System.currentTimeMillis()
        val previous = lastCall[host]
        if (previous != null) {
            val wait = minIntervalMillis - (now - previous)
            if (wait > 0) {
                try {
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        lastCall[host] = System.currentTimeMillis()
    }

    private fun record(providerId: String, r: HttpResponse, now: Instant) {
        val previous = health[providerId]
        health[providerId] = ProviderHealth(
            providerId = providerId,
            reachable = r.ok,
            httpStatus = r.status.takeIf { it > 0 },
            latencyMillis = r.latencyMillis,
            lastSuccess = if (r.ok) now else previous?.lastSuccess,
            message = r.error ?: if (r.ok) null else "HTTP ${r.status}",
            circuitOpenUntil = circuitUntil[providerId]
        )
    }

    private fun hostOf(url: String): String = try {
        URL(url).host
    } catch (_: Exception) {
        url
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 GoldIntelligence/1.0"
        const val MAX_BODY_BYTES = 12 * 1024 * 1024
    }
}
