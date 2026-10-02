package io.goldintelligence.engine

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * SPEC v2.1 §21 — Operational Log.
 *
 * Every indicator, every provider call and every quality decision writes here.
 * The log is the answer to "which part failed and why": an entry always names
 * the pipeline stage, the component, the indicator key it was working on, and
 * a machine-readable reason code — never a bare message.
 *
 * The log is deliberately held apart from the analytical output. Nothing in
 * `GoldIntelligenceState` or the screen rows reads from it; it is exported as
 * its own surface so that diagnostics can never contaminate displayed values.
 */
enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }

/** Pipeline stage, so a failure can be located without reading code. */
enum class LogStage {
    STARTUP,
    NETWORK,
    PARSE,
    QUALITY,
    FEATURE,
    FACTOR,
    REGIME,
    HORIZON,
    RENDER,
    EXPORT,

    /** SPEC v2.1 §22 — live chart feed, bar assembly and overlay rendering. */
    CHART
}

/**
 * One structured record.
 *
 * @param component the class or provider that produced the record
 * @param key the indicator / series / factor id the record is about, if any
 * @param code a stable reason code (`HTTP_403`, `MISSING_INPUT`, `GATE_G06`…)
 */
data class LogEntry(
    val sequence: Long,
    val timestamp: Instant,
    val level: LogLevel,
    val stage: LogStage,
    val component: String,
    val code: String,
    val message: String,
    val key: String? = null,
    val url: String? = null,
    val httpStatus: Int? = null,
    val latencyMillis: Long? = null,
    val detail: String? = null
) {
    fun oneLine(): String = buildString {
        append(TS.format(timestamp))
        append("  ").append(level.name.padEnd(5))
        append("  ").append(stage.name.padEnd(8))
        append("  ").append(component)
        if (key != null) append(" [").append(key).append(']')
        append("  ").append(code)
        append("  ").append(message)
        if (httpStatus != null) append("  http=").append(httpStatus)
        if (latencyMillis != null) append("  ").append(latencyMillis).append("ms")
        if (url != null) append("  <").append(url).append('>')
        if (detail != null) append("  | ").append(detail)
    }

    companion object {
        val TS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
    }
}

/** Per-indicator roll-up, so the UI can show one status line per indicator. */
data class IndicatorStatus(
    val key: String,
    val level: LogLevel,
    val code: String,
    val message: String,
    val component: String,
    val timestamp: Instant
) {
    val ok: Boolean get() = level == LogLevel.INFO || level == LogLevel.DEBUG || level == LogLevel.TRACE
}

/**
 * Bounded, thread-safe, allocation-cheap log.
 *
 * A ring of at most [capacity] entries: an Android session that runs for days
 * must not grow without bound, and the newest records are the ones that
 * explain the current screen.
 */
class DiagnosticLog(private val capacity: Int = DEFAULT_CAPACITY) {

    private val entries = ConcurrentLinkedDeque<LogEntry>()
    private val counter = AtomicLong(0)
    private val size = AtomicLong(0)
    @Volatile
    private var minimumLevel: LogLevel = LogLevel.DEBUG
    @Volatile
    private var dropped: Long = 0

    fun setMinimumLevel(level: LogLevel) {
        minimumLevel = level
    }

    fun minimumLevel(): LogLevel = minimumLevel

    fun droppedCount(): Long = dropped

    fun log(
        level: LogLevel,
        stage: LogStage,
        component: String,
        code: String,
        message: String,
        key: String? = null,
        url: String? = null,
        httpStatus: Int? = null,
        latencyMillis: Long? = null,
        detail: String? = null
    ) {
        if (level.ordinal < minimumLevel.ordinal) return
        val entry = LogEntry(
            sequence = counter.incrementAndGet(),
            timestamp = Instant.now(),
            level = level,
            stage = stage,
            component = component,
            code = code,
            message = message,
            key = key,
            url = url,
            httpStatus = httpStatus,
            latencyMillis = latencyMillis,
            detail = detail?.take(MAX_DETAIL)
        )
        entries.addLast(entry)
        if (size.incrementAndGet() > capacity) {
            entries.pollFirst()
            size.decrementAndGet()
            dropped++
        }
    }

    fun trace(stage: LogStage, component: String, code: String, message: String, key: String? = null) =
        log(LogLevel.TRACE, stage, component, code, message, key)

    fun debug(stage: LogStage, component: String, code: String, message: String, key: String? = null) =
        log(LogLevel.DEBUG, stage, component, code, message, key)

    fun info(stage: LogStage, component: String, code: String, message: String, key: String? = null) =
        log(LogLevel.INFO, stage, component, code, message, key)

    fun warn(
        stage: LogStage,
        component: String,
        code: String,
        message: String,
        key: String? = null,
        detail: String? = null
    ) = log(LogLevel.WARN, stage, component, code, message, key, detail = detail)

    fun error(
        stage: LogStage,
        component: String,
        code: String,
        message: String,
        key: String? = null,
        detail: String? = null
    ) = log(LogLevel.ERROR, stage, component, code, message, key, detail = detail)

    /** Records a thrown exception without letting it escape the logging call. */
    fun exception(stage: LogStage, component: String, key: String?, t: Throwable) = log(
        level = LogLevel.ERROR,
        stage = stage,
        component = component,
        code = "EXCEPTION_" + t.javaClass.simpleName.uppercase(),
        message = t.message ?: t.javaClass.name,
        key = key,
        detail = t.stackTrace.take(4).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
    )

    fun snapshot(): List<LogEntry> = entries.toList()

    fun clear() {
        entries.clear()
        size.set(0)
        dropped = 0
    }

    fun filter(
        minLevel: LogLevel = LogLevel.TRACE,
        stage: LogStage? = null,
        key: String? = null,
        contains: String? = null
    ): List<LogEntry> = snapshot().filter { e ->
        e.level.ordinal >= minLevel.ordinal &&
            (stage == null || e.stage == stage) &&
            (key == null || e.key == key) &&
            (contains == null || contains.isBlank() ||
                e.message.contains(contains, true) ||
                e.code.contains(contains, true) ||
                e.component.contains(contains, true) ||
                (e.key?.contains(contains, true) == true))
    }

    fun countsByLevel(): Map<LogLevel, Int> {
        val out = LinkedHashMap<LogLevel, Int>()
        LogLevel.entries.forEach { out[it] = 0 }
        snapshot().forEach { out[it.level] = (out[it.level] ?: 0) + 1 }
        return out
    }

    fun countsByStage(): Map<LogStage, Int> {
        val out = LinkedHashMap<LogStage, Int>()
        snapshot().forEach { out[it.stage] = (out[it.stage] ?: 0) + 1 }
        return out
    }

    /**
     * Latest record per indicator key, worst level wins within the same refresh.
     * This is what the per-indicator status column is built from.
     */
    fun indicatorStatuses(): List<IndicatorStatus> {
        val best = LinkedHashMap<String, LogEntry>()
        snapshot().forEach { e ->
            val k = e.key ?: return@forEach
            val current = best[k]
            if (current == null ||
                e.level.ordinal > current.level.ordinal ||
                (e.level == current.level && e.sequence > current.sequence)
            ) {
                best[k] = e
            }
        }
        return best.values
            .sortedWith(compareByDescending<LogEntry> { it.level.ordinal }.thenBy { it.key })
            .map { IndicatorStatus(it.key!!, it.level, it.code, it.message, it.component, it.timestamp) }
    }

    fun failures(): List<LogEntry> = snapshot().filter { it.level.ordinal >= LogLevel.WARN.ordinal }

    /* ---------------- export ---------------- */

    fun toPlainText(header: Map<String, String> = emptyMap()): String = buildString {
        appendLine("Gold Intelligence — operational log")
        appendLine("=".repeat(72))
        header.forEach { (k, v) -> appendLine("${k.padEnd(22)}: $v") }
        appendLine("${"exported".padEnd(22)}: ${LogEntry.TS.format(Instant.now())} UTC")
        val counts = countsByLevel()
        appendLine(
            "${"records".padEnd(22)}: ${snapshot().size}" +
                "  (error ${counts[LogLevel.ERROR]}, warn ${counts[LogLevel.WARN]}," +
                " info ${counts[LogLevel.INFO]}, debug ${counts[LogLevel.DEBUG]})"
        )
        if (dropped > 0) appendLine("${"dropped (ring full)".padEnd(22)}: $dropped")
        appendLine("=".repeat(72))
        appendLine()
        snapshot().forEach { appendLine(it.oneLine()) }
    }

    fun toMarkdown(header: Map<String, String> = emptyMap()): String = buildString {
        appendLine("# Gold Intelligence — operational log")
        appendLine()
        if (header.isNotEmpty()) {
            appendLine("| field | value |")
            appendLine("|---|---|")
            header.forEach { (k, v) -> appendLine("| $k | ${md(v)} |") }
            appendLine("| exported | ${LogEntry.TS.format(Instant.now())} UTC |")
            appendLine()
        }

        val counts = countsByLevel()
        appendLine("## Summary")
        appendLine()
        appendLine("| level | count |")
        appendLine("|---|---:|")
        counts.forEach { (l, c) -> appendLine("| ${l.name} | $c |") }
        if (dropped > 0) appendLine("| dropped (ring full) | $dropped |")
        appendLine()

        val stages = countsByStage()
        if (stages.isNotEmpty()) {
            appendLine("| stage | count |")
            appendLine("|---|---:|")
            stages.forEach { (s, c) -> appendLine("| ${s.name} | $c |") }
            appendLine()
        }

        val problems = failures()
        appendLine("## Failures (${problems.size})")
        appendLine()
        if (problems.isEmpty()) {
            appendLine("None. Every indicator resolved.")
        } else {
            appendLine("| time (UTC) | level | stage | component | key | code | message | http |")
            appendLine("|---|---|---|---|---|---|---|---|")
            problems.forEach {
                appendLine(
                    "| ${LogEntry.TS.format(it.timestamp)} | ${it.level} | ${it.stage} |" +
                        " ${md(it.component)} | ${md(it.key ?: "—")} | `${md(it.code)}` |" +
                        " ${md(it.message)} | ${it.httpStatus ?: "—"} |"
                )
            }
        }
        appendLine()

        val statuses = indicatorStatuses()
        appendLine("## Indicator status (${statuses.size})")
        appendLine()
        appendLine("| indicator | state | code | component | message |")
        appendLine("|---|---|---|---|---|")
        statuses.forEach {
            appendLine(
                "| ${md(it.key)} | ${if (it.ok) "OK" else it.level.name} | `${md(it.code)}` |" +
                    " ${md(it.component)} | ${md(it.message)} |"
            )
        }
        appendLine()

        appendLine("## Full trace")
        appendLine()
        appendLine("```log")
        snapshot().forEach { appendLine(it.oneLine()) }
        appendLine("```")
    }

    private fun md(s: String): String = s.replace("|", "\\|").replace("\n", " ")

    companion object {
        const val DEFAULT_CAPACITY = 4000
        const val MAX_DETAIL = 600

        /** Process-wide default sink. */
        val shared: DiagnosticLog = DiagnosticLog()
    }
}

/** Convenience for timing a block and logging the outcome either way. */
inline fun <T> DiagnosticLog.timed(
    stage: LogStage,
    component: String,
    key: String?,
    code: String,
    block: () -> T
): T? {
    val started = System.currentTimeMillis()
    return try {
        val value = block()
        log(
            level = if (value == null) LogLevel.WARN else LogLevel.DEBUG,
            stage = stage,
            component = component,
            code = if (value == null) "${code}_EMPTY" else code,
            message = if (value == null) "produced no value" else "ok",
            key = key,
            latencyMillis = System.currentTimeMillis() - started
        )
        value
    } catch (t: Throwable) {
        exception(stage, component, key, t)
        null
    }
}

/** Age of the newest record, used by the UI to show log freshness. */
fun DiagnosticLog.age(now: Instant = Instant.now()): Duration? =
    snapshot().lastOrNull()?.let { Duration.between(it.timestamp, now) }
