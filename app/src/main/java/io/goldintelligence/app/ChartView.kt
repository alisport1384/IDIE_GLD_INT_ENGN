package io.goldintelligence.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import io.goldintelligence.client.BarOrigin
import io.goldintelligence.client.Candle
import io.goldintelligence.client.ChartPayload
import io.goldintelligence.client.LevelKind
import io.goldintelligence.client.PriceLevel
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * SPEC v2.1 §22 — the price surface.
 *
 * Candles and the engine's own output are drawn in the same coordinate space,
 * so a level the engine published sits exactly where that price is. Bars that
 * were seeded from another venue are drawn hollow and dimmed: the chart never
 * presents borrowed data as if the broker had printed it.
 */
class ChartView(context: Context) : View(context) {

    private var payload: ChartPayload? = null
    private var persian: Boolean = true

    private val candleUp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN }
    private val candleDown = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED }
    private val wick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f) }
    private val seedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LINE
        strokeWidth = dp(1f)
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED
        textSize = sp(9f)
        typeface = Typeface.MONOSPACE
    }
    private val levelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9f)
        typeface = Typeface.MONOSPACE
    }
    private val hudBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D00B0D10") }
    private val hudTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        textSize = sp(11f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val hudText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        textSize = sp(10f)
    }
    private val hudMuted = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED_HI
        textSize = sp(9f)
    }

    private val timeFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneOffset.UTC)

    fun bind(payload: ChartPayload?, persian: Boolean) {
        this.payload = payload
        this.persian = persian
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(BG)

        val p = payload
        if (p == null || p.series.candles.isEmpty()) {
            val msg = if (persian) "داده‌ای برای رسم وجود ندارد" else "No data to plot"
            hudText.color = MUTED
            canvas.drawText(msg, dp(12f), height / 2f, hudText)
            hudText.color = TEXT
            return
        }

        val padLeft = dp(6f)
        val padRight = dp(62f)
        val padTop = dp(8f)
        val padBottom = dp(18f)
        val plotW = width - padLeft - padRight
        val plotH = height - padTop - padBottom
        if (plotW <= 0 || plotH <= 0) return

        val candles = p.series.candles.takeLast(maxBars(plotW))
        var lo = candles.minOf { it.low }
        var hi = candles.maxOf { it.high }
        // Price-anchored levels must be inside the window, otherwise an
        // overlay the engine published would silently fall off the chart.
        p.levels.forEach {
            lo = min(lo, it.price)
            hi = max(hi, it.price)
        }
        if (hi - lo < 1e-9) {
            hi += 1.0
            lo -= 1.0
        }
        val pad = (hi - lo) * 0.06
        lo -= pad
        hi += pad

        fun y(price: Double): Float = (padTop + plotH * (hi - price) / (hi - lo)).toFloat()
        val slot = plotW / candles.size
        fun x(i: Int): Float = padLeft + slot * (i + 0.5f)

        drawGrid(canvas, padLeft, padTop, plotW, plotH, lo, hi, ::y)
        drawBand(canvas, p.levels, padLeft, plotW, ::y)
        drawCandles(canvas, candles, slot, ::x, ::y)
        drawLevels(canvas, p.levels, padLeft, plotW, padRight, ::y)
        drawTimeAxis(canvas, candles, ::x, padTop + plotH)
        drawHud(canvas, p)
    }

    private fun maxBars(plotW: Float): Int = max(20, (plotW / dp(5f)).toInt())

    private fun drawGrid(
        canvas: Canvas, left: Float, top: Float, w: Float, h: Float,
        lo: Double, hi: Double, y: (Double) -> Float
    ) {
        val steps = 4
        for (i in 0..steps) {
            val price = lo + (hi - lo) * i / steps
            val yy = y(price)
            canvas.drawLine(left, yy, left + w, yy, grid)
            canvas.drawText("%.2f".format(price), left + w + dp(4f), yy + sp(3f), axisText)
        }
    }

    /** The expected-move band is a region, not a line, so it is drawn as one. */
    private fun drawBand(canvas: Canvas, levels: List<PriceLevel>, left: Float, w: Float, y: (Double) -> Float) {
        val high = levels.firstOrNull { it.kind == LevelKind.BAND_HIGH } ?: return
        val low = levels.firstOrNull { it.kind == LevelKind.BAND_LOW } ?: return
        bandPaint.color = Color.parseColor("#1A4ED38A")
        canvas.drawRect(left, y(high.price), left + w, y(low.price), bandPaint)
    }

    private fun drawCandles(
        canvas: Canvas, candles: List<Candle>, slot: Float,
        x: (Int) -> Float, y: (Double) -> Float
    ) {
        val bodyW = max(dp(1.2f), slot * 0.62f)
        candles.forEachIndexed { i, c ->
            val cx = x(i)
            val up = c.bullish
            val color = if (up) GREEN else RED
            val live = c.origin == BarOrigin.LIVE
            wick.color = if (live) color else dim(color)
            canvas.drawLine(cx, y(c.high), cx, y(c.low), wick)

            val top = y(max(c.open, c.close))
            val bottom = y(min(c.open, c.close))
            val rect = android.graphics.RectF(
                cx - bodyW / 2f, top, cx + bodyW / 2f, max(bottom, top + dp(0.8f))
            )
            if (live) {
                val body = if (up) candleUp else candleDown
                canvas.drawRect(rect, body)
            } else {
                seedStroke.color = dim(color)
                canvas.drawRect(rect, seedStroke)
            }
        }
    }

    private fun drawLevels(
        canvas: Canvas, levels: List<PriceLevel>, left: Float, w: Float,
        rightPad: Float, y: (Double) -> Float
    ) {
        levels.forEach { l ->
            val color = levelColor(l.kind)
            levelPaint.color = color
            levelPaint.pathEffect = when (l.kind) {
                LevelKind.SPOT -> null
                LevelKind.BID, LevelKind.ASK -> DashPathEffect(floatArrayOf(dp(2f), dp(4f)), 0f)
                else -> DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
            }
            val yy = y(l.price)
            canvas.drawLine(left, yy, left + w, yy, levelPaint)

            val label = (if (persian) l.labelFa else l.labelEn) + "  " + "%.2f".format(l.price)
            levelText.color = color
            val tw = levelText.measureText(label)
            val tx = (left + w - tw - dp(4f)).coerceAtLeast(left + dp(2f))
            canvas.drawText(label, tx, yy - dp(3f), levelText)
        }
    }

    private fun drawTimeAxis(canvas: Canvas, candles: List<Candle>, x: (Int) -> Float, baseline: Float) {
        if (candles.size < 2) return
        val step = max(1, candles.size / 4)
        var i = 0
        while (i < candles.size) {
            canvas.drawText(timeFmt.format(candles[i].time), x(i) - dp(16f), baseline + sp(11f), axisText)
            i += step
        }
    }

    /**
     * The heads-up display: the conclusion, what moved since the previous
     * refresh, both readings when the engine holds two, and any exception.
     */
    private fun drawHud(canvas: Canvas, p: ChartPayload) {
        val h = p.headline
        val lines = mutableListOf<Pair<String, Paint>>()

        val dir = when (h.direction) {
            "BULLISH" -> if (persian) "صعودی ▲" else "BULLISH ▲"
            "BEARISH" -> if (persian) "نزولی ▼" else "BEARISH ▼"
            else -> h.direction
        }
        val conf = h.confidence?.let { "%.0f%%".format(it * 100) } ?: "—"
        val bias = h.bias?.let { "%+.0f".format(it) } ?: "—"
        lines += "$dir   ${h.horizon}   conf $conf   bias $bias" to hudTitle
        lines += "${h.regime} · ${h.signalState} · ${h.probabilityStatus}" to hudMuted

        val changed = p.deltas.filter { it.changed }
        if (changed.isEmpty()) {
            lines += (if (persian) "بدون تغییر نسبت به اجرای قبلی" else "No change since last refresh") to hudMuted
        } else {
            changed.take(4).forEach { d ->
                val arrow = if (d.direction > 0) "▲" else if (d.direction < 0) "▼" else "•"
                val label = if (persian) d.labelFa else d.labelEn
                lines += "$arrow $label: ${d.previous} → ${d.current}" to hudText
            }
        }

        p.dual.forEach { d ->
            val a = if (persian) d.primary.labelFa else d.primary.labelEn
            val b = if (persian) d.secondary.labelFa else d.secondary.labelEn
            lines += "⇄ $a ${d.primary.direction}  |  $b ${d.secondary.direction}" to hudWarn
        }

        p.exceptions.take(2).forEach { e ->
            lines += "! ${e.code} · ${e.component}" to hudError
        }

        if (p.series.seededBars > 0) {
            val note = if (persian)
                "کندل توخالی = پیش‌بار بازمقیاس‌شده (${p.series.seededBars})"
            else "hollow = rebased seed bars (${p.series.seededBars})"
            lines += note to hudMuted
        }

        val padding = dp(8f)
        val lineGap = sp(13f)
        var maxW = 0f
        lines.forEach { maxW = max(maxW, it.second.measureText(it.first)) }
        val boxW = min(width - dp(16f), maxW + padding * 2)
        val boxH = lines.size * lineGap + padding * 2

        canvas.drawRoundRect(
            dp(6f), dp(6f), dp(6f) + boxW, dp(6f) + boxH, dp(8f), dp(8f), hudBg
        )
        var yy = dp(6f) + padding + sp(9f)
        lines.forEach { (text, paint) ->
            canvas.drawText(text, dp(6f) + padding, yy, paint)
            yy += lineGap
        }

        val q = p.quote
        val footer = buildString {
            append(p.venueLabel)
            append("  ·  ").append(p.timeframe.code)
            if (q != null) {
                append("  ·  ").append("%.3f".format(q.last))
                q.spreadBp?.let { append("  ·  spread %.1f bp".format(it)) }
                append("  ·  ").append(q.updateMode ?: "?")
            }
        }
        val fw = hudMuted.measureText(footer)
        canvas.drawText(footer, max(dp(6f), width - fw - dp(8f)), height - dp(4f), hudMuted)
    }

    private val hudWarn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AMBER
        textSize = sp(10f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val hudError = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = RED
        textSize = sp(10f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun levelColor(kind: LevelKind): Int = when (kind) {
        LevelKind.SPOT -> ACCENT
        LevelKind.BID -> GREEN
        LevelKind.ASK -> RED
        LevelKind.INVALIDATION -> RED
        LevelKind.EXPECTED_MOVE -> VIOLET
        LevelKind.BAND_HIGH, LevelKind.BAND_LOW -> Color.parseColor("#4ED38A")
        LevelKind.BENCHMARK -> Color.parseColor("#9AA4B2")
    }

    private fun dim(color: Int): Int = Color.argb(
        110, Color.red(color), Color.green(color), Color.blue(color)
    )

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    companion object {
        private val BG = Color.parseColor("#0B0D10")
        private val LINE = Color.parseColor("#1C232B")
        private val TEXT = Color.parseColor("#E6E8EB")
        private val MUTED = Color.parseColor("#6B7584")
        private val MUTED_HI = Color.parseColor("#9AA4B2")
        private val ACCENT = Color.parseColor("#D6B36A")
        private val GREEN = Color.parseColor("#4ED38A")
        private val RED = Color.parseColor("#F26B6B")
        private val AMBER = Color.parseColor("#E8B04B")
        private val VIOLET = Color.parseColor("#9B8CF5")
    }

    init {
        setWillNotDraw(false)
    }
}
