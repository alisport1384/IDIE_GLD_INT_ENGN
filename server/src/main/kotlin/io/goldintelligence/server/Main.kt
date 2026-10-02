package io.goldintelligence.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.goldintelligence.client.AnalysisResult
import io.goldintelligence.client.ClientMode
import io.goldintelligence.client.GoldIntelligenceClient
import io.goldintelligence.client.JsonWriter
import io.goldintelligence.client.Screen
import io.goldintelligence.client.ScreenModel
import io.goldintelligence.client.ScreenModelCodec
import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.LogLevel
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * SPEC v2 §17 — REST / streaming contract.
 *
 * Implemented on the JDK's own HTTP server: the deployment target is a small
 * always-on poller, and adding a web framework would add a dependency tree
 * larger than the entire analytical core for no functional gain.
 */
class IntelligenceServer(
    private val port: Int = 8080,
    private val refreshInterval: Duration = Duration.ofMinutes(2),
    private val client: GoldIntelligenceClient = GoldIntelligenceClient(ClientMode.DIRECT)
) {
    private val cache = AtomicReference<AnalysisResult?>(null)
    private val lastRefresh = AtomicReference<Instant?>(null)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "gi-refresh").apply { isDaemon = true }
    }
    private val subscribers = java.util.concurrent.CopyOnWriteArrayList<OutputStream>()

    fun start() {
        val server = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
        server.executor = Executors.newFixedThreadPool(4)

        server.createContext("/v1/health") { ex -> json(ex, health()) }
        server.createContext("/v1/screens") { ex -> json(ex, ScreenModelCodec.encode(model())) }
        server.createContext("/v1/state") { ex -> json(ex, screen(ScreenModel.SCREEN_STATE)) }
        server.createContext("/v1/factors") { ex -> json(ex, screen(ScreenModel.SCREEN_FACTORS)) }
        server.createContext("/v1/indicators") { ex -> json(ex, screen(ScreenModel.SCREEN_INDICATORS)) }
        server.createContext("/v1/horizons") { ex -> json(ex, screen(ScreenModel.SCREEN_HORIZONS)) }
        server.createContext("/v1/events") { ex -> json(ex, screen(ScreenModel.SCREEN_EVENTS)) }
        server.createContext("/v1/diagnostics") { ex -> json(ex, screen(ScreenModel.SCREEN_DIAGNOSTICS)) }
        // SPEC v2.1 §21 — the operational log, on its own endpoints so it is
        // never mixed into an analytical payload.
        server.createContext("/v1/logs") { ex -> json(ex, screen(ScreenModel.SCREEN_LOGS)) }
        server.createContext("/v1/logs.json") { ex -> json(ex, logsJson(ex)) }
        server.createContext("/v1/logs.md") { ex ->
            download(ex, "text/markdown", "gold-intelligence-log.md", DiagnosticLog.shared.toMarkdown(logHeader()))
        }
        server.createContext("/v1/logs.txt") { ex ->
            download(ex, "text/plain", "gold-intelligence-log.txt", DiagnosticLog.shared.toPlainText(logHeader()))
        }
        server.createContext("/v1/report") { ex -> json(ex, reportJson()) }
        server.createContext("/v1/stream") { ex -> stream(ex) }
        server.createContext("/") { ex -> json(ex, index()) }

        scheduler.scheduleWithFixedDelay(
            { refresh() },
            0,
            refreshInterval.seconds.coerceAtLeast(30),
            TimeUnit.SECONDS
        )
        server.start()
        println("Gold Intelligence server listening on 0.0.0.0:$port")
    }

    private fun refresh() {
        try {
            val result = client.analyze()
            cache.set(result)
            lastRefresh.set(Instant.now())
            push(ScreenModelCodec.encode(result.screens))
        } catch (e: Exception) {
            System.err.println("refresh failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun model(): ScreenModel =
        cache.get()?.screens ?: ScreenModel.error("هنوز داده‌ای واکشی نشده است.", "No data fetched yet.")

    private fun screen(id: String): String {
        val s: Screen? = model().screens.firstOrNull { it.id == id }
        return s?.let { ScreenModelCodec.encodeScreen(it) }
            ?: JsonWriter.obj("error" to JsonWriter.str("SCREEN_NOT_READY"), "id" to JsonWriter.str(id))
    }

    private fun health(): String {
        val r = cache.get()
        return JsonWriter.obj(
            "status" to JsonWriter.str(if (r?.report != null) "OK" else "DEGRADED"),
            "specVersion" to JsonWriter.str(io.goldintelligence.engine.MultiHorizonEngine.SPEC_VERSION),
            "lastRefresh" to JsonWriter.str(lastRefresh.get()?.toString()),
            "activeFactors" to JsonWriter.num(r?.report?.factorScores?.size),
            "dataQuality" to JsonWriter.num(r?.report?.dataQuality),
            "providers" to JsonWriter.arr(r?.universe?.providerHealth?.values ?: emptyList()) { h ->
                JsonWriter.obj(
                    "id" to JsonWriter.str(h.providerId),
                    "state" to JsonWriter.str(h.state),
                    "httpStatus" to JsonWriter.num(h.httpStatus),
                    "latencyMillis" to JsonWriter.num(h.latencyMillis?.toDouble())
                )
            }
        )
    }

    private fun reportJson(): String {
        val r = cache.get()?.report
            ?: return JsonWriter.obj("error" to JsonWriter.str("NOT_READY"))
        return JsonWriter.obj(
            "generatedAt" to JsonWriter.str(r.generatedAt.toString()),
            "specVersion" to JsonWriter.str(r.specVersion),
            "spot" to JsonWriter.num(r.spotPrice),
            "dataQuality" to JsonWriter.num(r.dataQuality),
            "direction" to JsonWriter.str(r.state.direction.name),
            "regime" to JsonWriter.str(r.state.regime.name),
            "regimeStability" to JsonWriter.str(r.state.regimeStability.name),
            "signalState" to JsonWriter.str(r.state.signalState.name),
            "crossMarketConfirmation" to JsonWriter.num(r.crossMarketConfirmation.value),
            "factors" to JsonWriter.arr(r.factorScores) { f ->
                JsonWriter.obj(
                    "id" to JsonWriter.str(f.factorId),
                    "score" to JsonWriter.num(f.score),
                    "quality" to JsonWriter.num(f.quality),
                    "asOf" to JsonWriter.str(f.asOf?.toString())
                )
            },
            "horizons" to JsonWriter.arr(r.horizons) { h ->
                JsonWriter.obj(
                    "horizon" to JsonWriter.str(h.horizon.code),
                    "mode" to JsonWriter.str(h.mode.name),
                    "coverage" to JsonWriter.num(h.coverage),
                    "direction" to JsonWriter.str(h.direction.name),
                    "probability" to JsonWriter.num(h.probability),
                    "probabilityStatus" to JsonWriter.str(h.probabilityStatus),
                    "confidence" to JsonWriter.num(h.confidence),
                    "goldBias" to JsonWriter.num(h.goldBias),
                    "conflictRatio" to JsonWriter.num(h.conflictRatio),
                    "killSwitch" to JsonWriter.arr(h.killSwitch.reasons) { JsonWriter.str(it) },
                    "expectedMove" to (h.expectedMove?.let {
                        JsonWriter.obj(
                            "point" to JsonWriter.num(it.point),
                            "p5" to JsonWriter.num(it.p5),
                            "p95" to JsonWriter.num(it.p95),
                            "sigma" to JsonWriter.num(it.sigma)
                        )
                    } ?: "null")
                )
            },
            "features" to JsonWriter.obj(
                *r.features.map { (k, v) -> k to JsonWriter.num(v) }.toTypedArray()
            )
        )
    }

    private fun index(): String = JsonWriter.obj(
        "service" to JsonWriter.str("Gold Intelligence Engine"),
        "spec" to JsonWriter.str(io.goldintelligence.engine.MultiHorizonEngine.SPEC_VERSION),
        "endpoints" to JsonWriter.arr(
            listOf(
                "/v1/health", "/v1/screens", "/v1/state", "/v1/factors", "/v1/indicators",
                "/v1/horizons", "/v1/events", "/v1/diagnostics", "/v1/report", "/v1/stream",
                "/v1/logs", "/v1/logs.json", "/v1/logs.md", "/v1/logs.txt"
            )
        ) { JsonWriter.str(it) }
    )

    private fun logHeader(): Map<String, String> = linkedMapOf(
        "spec" to io.goldintelligence.engine.MultiHorizonEngine.SPEC_VERSION,
        "source" to "server",
        "lastRefresh" to (lastRefresh.get()?.toString() ?: "never")
    )

    /**
     * Structured log as JSON. `?level=WARN&stage=NETWORK&q=treasury&limit=500`
     * narrow the result; defaults return the whole ring.
     */
    private fun logsJson(ex: HttpExchange): String {
        val q = (ex.requestURI.query ?: "").split('&')
            .mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }
            .associate { java.net.URLDecoder.decode(it[0], "UTF-8") to java.net.URLDecoder.decode(it[1], "UTF-8") }
        val level = q["level"]?.uppercase()?.let { name -> LogLevel.entries.firstOrNull { it.name == name } }
            ?: LogLevel.TRACE
        val stage = q["stage"]?.uppercase()?.let { name ->
            io.goldintelligence.engine.LogStage.entries.firstOrNull { it.name == name }
        }
        val limit = q["limit"]?.toIntOrNull() ?: 1000
        val entries = DiagnosticLog.shared.filter(level, stage, q["key"], q["q"]).takeLast(limit)
        val counts = DiagnosticLog.shared.countsByLevel()
        return JsonWriter.obj(
            "generatedAt" to JsonWriter.str(Instant.now().toString()),
            "total" to JsonWriter.num(DiagnosticLog.shared.snapshot().size),
            "returned" to JsonWriter.num(entries.size),
            "dropped" to JsonWriter.num(DiagnosticLog.shared.droppedCount().toInt()),
            "counts" to JsonWriter.obj(*counts.map { (k, v) -> k.name to JsonWriter.num(v) }.toTypedArray()),
            "indicators" to JsonWriter.arr(DiagnosticLog.shared.indicatorStatuses()) { st ->
                JsonWriter.obj(
                    "key" to JsonWriter.str(st.key),
                    "ok" to JsonWriter.bool(st.ok),
                    "level" to JsonWriter.str(st.level.name),
                    "code" to JsonWriter.str(st.code),
                    "component" to JsonWriter.str(st.component),
                    "message" to JsonWriter.str(st.message),
                    "timestamp" to JsonWriter.str(st.timestamp.toString())
                )
            },
            "entries" to JsonWriter.arr(entries) { e ->
                JsonWriter.obj(
                    "seq" to JsonWriter.num(e.sequence.toInt()),
                    "timestamp" to JsonWriter.str(e.timestamp.toString()),
                    "level" to JsonWriter.str(e.level.name),
                    "stage" to JsonWriter.str(e.stage.name),
                    "component" to JsonWriter.str(e.component),
                    "code" to JsonWriter.str(e.code),
                    "message" to JsonWriter.str(e.message),
                    "key" to JsonWriter.str(e.key),
                    "url" to JsonWriter.str(e.url),
                    "httpStatus" to JsonWriter.num(e.httpStatus),
                    "latencyMillis" to JsonWriter.num(e.latencyMillis?.toInt()),
                    "detail" to JsonWriter.str(e.detail)
                )
            }
        )
    }

    private fun download(ex: HttpExchange, contentType: String, filename: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "$contentType; charset=utf-8")
        ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"$filename\"")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun json(ex: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** Server-sent events: one `data:` frame per completed refresh. */
    private fun stream(ex: HttpExchange) {
        ex.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.responseHeaders.add("Connection", "keep-alive")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(200, 0)
        val out = ex.responseBody
        subscribers.add(out)
        try {
            out.write("event: hello\ndata: ${ScreenModelCodec.encode(model())}\n\n".toByteArray(StandardCharsets.UTF_8))
            out.flush()
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(15_000)
                out.write(": keepalive\n\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
        } catch (_: Exception) {
            // client disconnected
        } finally {
            subscribers.remove(out)
            try {
                out.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun push(payload: String) {
        val frame = "event: update\ndata: $payload\n\n".toByteArray(StandardCharsets.UTF_8)
        for (s in subscribers) {
            try {
                s.write(frame)
                s.flush()
            } catch (_: Exception) {
                subscribers.remove(s)
            }
        }
    }
}

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull()
        ?: System.getenv("PORT")?.toIntOrNull()
        ?: 8080
    IntelligenceServer(port = port).start()
    Thread.currentThread().join()
}
