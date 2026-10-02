package io.goldintelligence.client

import io.goldintelligence.engine.Direction
import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.FactorScore
import io.goldintelligence.ingestion.FactorDiagnostic
import io.goldintelligence.engine.Horizon
import io.goldintelligence.engine.HorizonState
import io.goldintelligence.engine.IntelligenceReport
import io.goldintelligence.engine.LogLevel
import io.goldintelligence.engine.LogStage
import java.time.Instant
import kotlin.math.abs

/**
 * SPEC v2.1 §22 — what the chart draws on top of the price.
 *
 * Three obligations are met here: the engine's conclusion is anchored to the
 * price axis, every change since the previous refresh is stated explicitly,
 * and a market the engine reads two ways at once is published as two readings
 * rather than collapsed into one.
 */
class ChartOverlayBuilder(private val log: DiagnosticLog = DiagnosticLog.shared) {

    companion object {
        /** Horizons treated as short for the purposes of a horizon split. */
        val SHORT: Set<Horizon> = setOf(Horizon.M5, Horizon.M15, Horizon.H1)
        val LONG: Set<Horizon> = setOf(Horizon.H4, Horizon.D1, Horizon.W1)

        /** A factor must be at least this strong to count as a side of a conflict. */
        /** Prefix used when re-emitting a chart exception into the log. */
        const val EXCEPTION_PREFIX = "CHART_EXCEPTION_"
        const val CONFLICT_FLOOR = 25.0

        /** Below this, two opposing camps are not a genuine conflict, just noise. */
        const val CONFLICT_MIN_RATIO = 0.30
    }

    /** Snapshot of the published state, kept so the next refresh can be compared to it. */
    data class Memo(
        val at: Instant,
        val direction: String,
        val bias: Double?,
        val confidence: Double?,
        val regime: String,
        val signalState: String,
        val dataQuality: Double,
        val activeFactors: Int,
        val spot: Double?,
        val killSwitch: String?
    )

    fun memo(report: IntelligenceReport, diagnostics: List<FactorDiagnostic> = emptyList()): Memo = Memo(
        at = report.generatedAt,
        direction = report.state.direction.name,
        bias = report.state.goldBias,
        confidence = report.state.confidence,
        regime = report.state.regime.name,
        signalState = report.state.signalState.name,
        dataQuality = report.dataQuality,
        activeFactors = if (diagnostics.isEmpty()) report.factorScores.size
        else diagnostics.count { it.available },
        spot = report.spotPrice,
        killSwitch = report.horizons.firstOrNull { it.killSwitch.engaged }
            ?.killSwitch?.reasons?.joinToString()
    )

    fun build(
        report: IntelligenceReport?,
        quote: LiveQuote?,
        series: ChartSeries,
        venue: BrokerFeedProvider.Venue,
        previous: Memo?,
        feedFailure: String?,
        diagnostics: List<FactorDiagnostic> = emptyList(),
        horizon: Horizon = Horizon.D1,
        now: Instant = Instant.now()
    ): ChartPayload {
        val state = report?.state
        val hz = report?.horizons?.firstOrNull { it.horizon == horizon }
        val anchor = quote?.last ?: report?.spotPrice

        val headline = ChartHeadline(
            direction = state?.direction?.name ?: "UNKNOWN",
            bias = state?.goldBias,
            confidence = hz?.confidence ?: state?.confidence,
            regime = state?.regime?.name ?: "UNKNOWN",
            signalState = state?.signalState?.name ?: "UNKNOWN",
            probabilityStatus = hz?.probabilityStatus ?: "UNAVAILABLE",
            killSwitch = hz?.killSwitch?.takeIf { it.engaged }?.reasons?.joinToString(),
            horizon = horizon.code
        )

        val levels = levels(anchor, quote, hz, report)
        val deltas = deltas(report, previous, quote, diagnostics)
        val dual = dual(report, hz, diagnostics)
        val exceptions = exceptions(report, quote, series, feedFailure, now)

        log.debug(
            LogStage.CHART, "ChartOverlayBuilder", "OVERLAY_BUILT",
            "${levels.size} levels, ${deltas.count { it.changed }} changed fields, " +
                "${dual.size} dual reading(s), ${exceptions.size} exception(s)",
            key = CHART_KEY
        )

        return ChartPayload(
            symbol = venue.ticker,
            venue = venue.id,
            venueLabel = venue.label,
            quote = quote,
            timeframe = series.timeframe,
            series = series,
            levels = levels,
            deltas = deltas,
            dual = dual,
            exceptions = exceptions,
            headline = headline,
            asOf = now
        )
    }

    /* ------------------------- price-anchored levels ------------------------- */

    private fun levels(
        anchor: Double?,
        quote: LiveQuote?,
        hz: HorizonState?,
        report: IntelligenceReport?
    ): List<PriceLevel> {
        if (anchor == null || anchor <= 0.0) return emptyList()
        val out = mutableListOf(
            PriceLevel(anchor, "قیمت زنده", "Live price", LevelKind.SPOT)
        )
        quote?.bid?.let { out += PriceLevel(it, "بهترین خرید", "Best bid", LevelKind.BID) }
        quote?.ask?.let { out += PriceLevel(it, "بهترین فروش", "Best ask", LevelKind.ASK) }

        val band = hz?.expectedMove
        if (band != null) {
            out += PriceLevel(
                anchor + band.point,
                "حرکت مورد انتظار ${hz.horizon.code}",
                "Expected move ${hz.horizon.code}",
                LevelKind.EXPECTED_MOVE
            )
            out += PriceLevel(anchor + band.p95, "سقف ۹۵٪", "P95", LevelKind.BAND_HIGH)
            out += PriceLevel(anchor + band.p5, "کف ۵٪", "P5", LevelKind.BAND_LOW)
        }

        // The invalidation level is only drawn when the engine expressed it as a
        // price; a narrative invalidation is shown as text, never as a line.
        report?.features?.get("LBMA_BENCHMARK")?.let { benchmark ->
            if (benchmark > 0) {
                out += PriceLevel(benchmark, "بنچمارک LBMA", "LBMA benchmark", LevelKind.BENCHMARK)
            }
        }
        return out.sortedByDescending { it.price }
    }

    /* ------------------------- change since last refresh ------------------------- */

    private fun deltas(
        report: IntelligenceReport?,
        previous: Memo?,
        quote: LiveQuote?,
        diagnostics: List<FactorDiagnostic>
    ): List<AnalysisDelta> {
        if (report == null) return emptyList()
        val now = memo(report, diagnostics)
        // The suffix is appended after formatting: a literal '%' inside a
        // format string is a conversion, not a percent sign.
        fun fmt(v: Double?, digits: Int = 2, suffix: String = ""): String =
            if (v == null) "N/A" else "%.${digits}f".format(v) + suffix

        fun ordered(current: Double?, old: Double?): Int = when {
            current == null || old == null -> 0
            current > old -> 1
            current < old -> -1
            else -> 0
        }

        val list = listOf(
            AnalysisDelta(
                "DIRECTION", "جهت", "Direction", previous?.direction, now.direction,
                when {
                    previous == null -> 0
                    previous.direction == now.direction -> 0
                    now.direction == Direction.BULLISH.name -> 1
                    now.direction == Direction.BEARISH.name -> -1
                    else -> 0
                }
            ),
            AnalysisDelta(
                "BIAS", "سوگیری طلا", "Gold bias",
                previous?.let { fmt(it.bias, 1) }, fmt(now.bias, 1),
                ordered(now.bias, previous?.bias)
            ),
            AnalysisDelta(
                "CONFIDENCE", "اطمینان", "Confidence",
                previous?.let { fmt(it.confidence?.times(100), 1, "%") },
                fmt(now.confidence?.times(100), 1, "%"),
                ordered(now.confidence, previous?.confidence)
            ),
            AnalysisDelta(
                "REGIME", "رژیم", "Regime", previous?.regime, now.regime,
                if (previous != null && previous.regime != now.regime) -1 else 0
            ),
            AnalysisDelta(
                "SIGNAL", "وضعیت سیگنال", "Signal state",
                previous?.signalState, now.signalState,
                when {
                    previous == null || previous.signalState == now.signalState -> 0
                    now.signalState == "VALID" -> 1
                    else -> -1
                }
            ),
            AnalysisDelta(
                "QUALITY", "کیفیت داده", "Data quality",
                previous?.let { fmt(it.dataQuality * 100, 1, "%") },
                fmt(now.dataQuality * 100, 1, "%"),
                ordered(now.dataQuality, previous?.dataQuality)
            ),
            AnalysisDelta(
                "FACTORS", "فاکتورهای فعال", "Active factors",
                previous?.activeFactors?.toString(), now.activeFactors.toString(),
                ordered(now.activeFactors.toDouble(), previous?.activeFactors?.toDouble())
            ),
            AnalysisDelta(
                "PRICE", "قیمت", "Price",
                previous?.let { fmt(it.spot) },
                fmt(quote?.last ?: now.spot),
                ordered(quote?.last ?: now.spot, previous?.spot)
            ),
            AnalysisDelta(
                "KILL_SWITCH", "کلید قطع", "Kill switch",
                previous?.killSwitch ?: previous?.let { "NONE" },
                now.killSwitch ?: "NONE",
                when {
                    previous == null -> 0
                    (previous.killSwitch ?: "NONE") == (now.killSwitch ?: "NONE") -> 0
                    now.killSwitch == null -> 1
                    else -> -1
                }
            )
        )

        list.filter { it.changed }.forEach { d ->
            log.info(
                LogStage.CHART, "ChartOverlayBuilder", "STATE_CHANGED",
                "${d.labelEn}: ${d.previous} → ${d.current}", key = "CHART_DELTA_" + d.key
            )
        }
        return list
    }

    /* ------------------------- two simultaneous readings ------------------------- */

    private fun dual(
        report: IntelligenceReport?,
        hz: HorizonState?,
        diagnostics: List<FactorDiagnostic>
    ): List<DualAnalysis> {
        if (report == null) return emptyList()
        val out = mutableListOf<DualAnalysis>()
        horizonSplit(report)?.let { out += it }
        factorConflict(report, hz, diagnostics)?.let { out += it }
        out.forEach {
            log.warn(
                LogStage.CHART, "ChartOverlayBuilder", "DUAL_READING",
                "${it.kind}: ${it.primary.labelEn} ${it.primary.direction} vs " +
                    "${it.secondary.labelEn} ${it.secondary.direction}",
                key = "CHART_DUAL_" + it.kind.name
            )
        }
        return out
    }

    private fun horizonSplit(report: IntelligenceReport): DualAnalysis? {
        val short = report.horizons.filter { it.horizon in SHORT && it.direction != Direction.NEUTRAL }
        val long = report.horizons.filter { it.horizon in LONG && it.direction != Direction.NEUTRAL }
        if (short.isEmpty() || long.isEmpty()) return null
        val shortDir = dominantDirection(short) ?: return null
        val longDir = dominantDirection(long) ?: return null
        if (shortDir == longDir) return null

        fun branch(states: List<HorizonState>, dir: Direction, fa: String, en: String): AnalysisBranch {
            val agreeing = states.filter { it.direction == dir }
            return AnalysisBranch(
                labelFa = fa,
                labelEn = en,
                direction = dir.name,
                strength = agreeing.mapNotNull { it.confidence }.average().takeIf { !it.isNaN() } ?: 0.0,
                detailFa = agreeing.joinToString(" · ") {
                    "${it.horizon.code} پوشش ${"%.0f".format(it.coverage * 100)}٪"
                },
                detailEn = agreeing.joinToString(" · ") {
                    "${it.horizon.code} coverage ${"%.0f".format(it.coverage * 100)}%"
                }
            )
        }

        return DualAnalysis(
            kind = DualKind.HORIZON_SPLIT,
            primary = branch(long, longDir, "افق بلند (4H–1W)", "Long horizons (4H–1W)"),
            secondary = branch(short, shortDir, "افق کوتاه (5m–1H)", "Short horizons (5m–1H)"),
            reasonFa = "افق‌های کوتاه و بلند هم‌جهت نیستند؛ هر دو خوانش منتشر می‌شود و " +
                "هیچ‌کدام بر دیگری ترجیح داده نمی‌شود.",
            reasonEn = "Short and long horizons do not agree. Both readings are published; " +
                "neither is preferred over the other."
        )
    }

    private fun dominantDirection(states: List<HorizonState>): Direction? {
        val bull = states.count { it.direction == Direction.BULLISH }
        val bear = states.count { it.direction == Direction.BEARISH }
        return when {
            bull > bear -> Direction.BULLISH
            bear > bull -> Direction.BEARISH
            else -> null
        }
    }

    private fun factorConflict(
        report: IntelligenceReport,
        hz: HorizonState?,
        diagnostics: List<FactorDiagnostic>
    ): DualAnalysis? {
        val unavailable = diagnostics.filterNot { it.available }.map { it.factorId }.toSet()
        val active = report.factorScores
            .filter { it.factorId !in unavailable && abs(it.score) >= CONFLICT_FLOOR }
        if (active.size < 2) return null
        val weights = hz?.weights?.associate { it.factorId to it.weight }.orEmpty()
        fun weight(f: FactorScore) = weights[f.factorId] ?: 0.0

        val bulls = active.filter { it.score > 0 }
        val bears = active.filter { it.score < 0 }
        if (bulls.isEmpty() || bears.isEmpty()) return null

        val bullWeight = bulls.sumOf { weight(it) * abs(it.score) }
        val bearWeight = bears.sumOf { weight(it) * abs(it.score) }
        val total = bullWeight + bearWeight
        if (total <= 0.0) return null
        val minorityShare = minOf(bullWeight, bearWeight) / total
        if (minorityShare < CONFLICT_MIN_RATIO) return null

        fun branch(side: List<FactorScore>, fa: String, en: String, dir: String) = AnalysisBranch(
            labelFa = fa,
            labelEn = en,
            direction = dir,
            strength = side.sumOf { weight(it) * abs(it.score) } / total,
            detailFa = side.sortedByDescending { abs(it.score) }.take(3)
                .joinToString(" · ") { "${it.factorId} ${"%+.0f".format(it.score)}" },
            detailEn = side.sortedByDescending { abs(it.score) }.take(3)
                .joinToString(" · ") { "${it.factorId} ${"%+.0f".format(it.score)}" }
        )

        val bullBranch = branch(bulls, "اردوگاه صعودی", "Bullish camp", Direction.BULLISH.name)
        val bearBranch = branch(bears, "اردوگاه نزولی", "Bearish camp", Direction.BEARISH.name)
        val primaryIsBull = bullWeight >= bearWeight
        return DualAnalysis(
            kind = DualKind.FACTOR_CONFLICT,
            primary = if (primaryIsBull) bullBranch else bearBranch,
            secondary = if (primaryIsBull) bearBranch else bullBranch,
            reasonFa = "فاکتورها هم‌زمان به دو سو اشاره می‌کنند؛ سهم وزنی اردوگاه اقلیت " +
                "${"%.0f".format(minorityShare * 100)}٪ است.",
            reasonEn = "Factors point both ways at once; the minority camp carries " +
                "${"%.0f".format(minorityShare * 100)}% of the active weight."
        )
    }

    /* ------------------------- exceptions ------------------------- */

    private fun exceptions(
        report: IntelligenceReport?,
        quote: LiveQuote?,
        series: ChartSeries,
        feedFailure: String?,
        now: Instant
    ): List<ChartException> {
        val out = mutableListOf<ChartException>()

        if (feedFailure != null) {
            out += ChartException(
                code = feedFailure,
                component = BrokerFeedProvider.ID,
                messageFa = "فید زندهٔ بروکر در دسترس نیست؛ چارت آخرین دادهٔ موجود را نشان می‌دهد.",
                messageEn = "The broker feed is unavailable; the chart shows the last data it holds.",
                severity = "ERROR",
                at = now
            )
        }
        if (quote == null && feedFailure == null) {
            out += ChartException(
                code = "QUOTE_ABSENT",
                component = BrokerFeedProvider.ID,
                messageFa = "قیمت زنده دریافت نشد.",
                messageEn = "No live quote was received.",
                severity = "ERROR",
                at = now
            )
        }
        if (series.isEmpty) {
            out += ChartException(
                code = "SERIES_EMPTY",
                component = "LiveChartStore",
                messageFa = "هیچ کندلی در دسترس نیست؛ هیچ داده‌ای ساخته نمی‌شود.",
                messageEn = "No candles are available; nothing is synthesised to fill the gap.",
                severity = "ERROR",
                at = now
            )
        } else if (series.liveBars == 0) {
            out += ChartException(
                code = "SERIES_SEED_ONLY",
                component = "LiveChartStore",
                messageFa = "همهٔ کندل‌ها از منبع جایگزین و بازمقیاس‌شده‌اند؛ هنوز کندل زندهٔ بروکر ثبت نشده است.",
                messageEn = "Every candle is rebased seed data; no live broker bar has been sealed yet.",
                severity = "WARN",
                at = now
            )
        }
        if (report == null) {
            out += ChartException(
                code = "ANALYSIS_ABSENT",
                component = "GoldIntelligenceClient",
                messageFa = "تحلیلی برای نمایش روی چارت وجود ندارد.",
                messageEn = "There is no analysis to overlay on the chart.",
                severity = "ERROR",
                at = now
            )
        } else {
            report.horizons.firstOrNull { it.killSwitch.engaged }?.let { h ->
                out += ChartException(
                    code = "KILL_SWITCH",
                    component = "MultiHorizonEngine",
                    messageFa = "انتشار در افق ${h.horizon.code} متوقف شد: ${h.killSwitch.reasons.joinToString()}",
                    messageEn = "Publication suppressed at ${h.horizon.code}: ${h.killSwitch.reasons.joinToString()}",
                    severity = "ERROR",
                    at = now
                )
            }
            if (report.dataQuality < 0.60) {
                out += ChartException(
                    code = "LOW_DATA_QUALITY",
                    component = "MultiHorizonEngine",
                    messageFa = "کیفیت دادهٔ کل ${"%.1f".format(report.dataQuality * 100)}٪ است.",
                    messageEn = "Aggregate data quality is ${"%.1f".format(report.dataQuality * 100)}%.",
                    severity = "WARN",
                    at = now
                )
            }
        }

        // Anything the rest of the pipeline logged as an error this refresh is
        // surfaced on the chart too, so a silent failure cannot hide behind it.
        // The chart's own re-emissions are excluded, otherwise each refresh
        // would wrap the previous refresh's entry and the codes would nest.
        log.failures()
            .filter {
                it.level == LogLevel.ERROR &&
                    it.stage != LogStage.CHART &&
                    !it.code.startsWith(EXCEPTION_PREFIX)
            }
            .distinctBy { it.code to it.component }
            .takeLast(3)
            .forEach { e ->
                out += ChartException(
                    code = e.code,
                    component = e.component,
                    messageFa = e.message,
                    messageEn = e.message,
                    severity = e.level.name,
                    at = e.timestamp
                )
            }

        out.forEach {
            log.log(
                level = if (it.severity == "ERROR") LogLevel.ERROR else LogLevel.WARN,
                stage = LogStage.CHART,
                component = "ChartOverlayBuilder",
                code = EXCEPTION_PREFIX + it.code,
                message = it.messageEn,
                key = CHART_KEY
            )
        }
        return out.distinctBy { it.code to it.component }
    }
}
