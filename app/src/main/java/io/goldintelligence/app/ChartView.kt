package io.goldintelligence.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import io.goldintelligence.client.BarOrigin
import io.goldintelligence.client.Candle
import io.goldintelligence.client.ChartPayload
import io.goldintelligence.client.LevelKind
import io.goldintelligence.client.PriceLevel
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * SPEC v2.1 §22 — the price surface.
 *
 * The canvas draws price and the engine's price-anchored levels and nothing
 * else: every narrative row lives in native views outside the plot, so the
 * analysis can never sit on top of the candles. Level labels are placed in a
 * dedicated right-hand gutter and pushed apart when they collide, which is
 * why two levels a few cents apart stay readable.
 */
class ChartView(context: Context) : View(context) {

    private var payload: ChartPayload? = null
    private var persian: Boolean = true

    /** How many bars are in the window; fewer bars means wider, clearer candles. */
    var visibleBars: Int = DEFAULT_VISIBLE
        set(value) {
            field = value.coerceIn(MIN_VISIBLE, MAX_VISIBLE)
            clampScroll()
            invalidate()
        }

    /** Bars hidden to the right of the window; 0 keeps the latest bar visible. */
    private var scrollBars: Int = 0

    /** Optional analysis card, off by default so it never hides the chart. */
    var overlayVisible: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var onTap: (() -> Unit)? = null

    private val candleUp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN }
    private val candleDown = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED }
    private val candleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val wick = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRID }
    private val gutterBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL }
    private val seedWash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0CFFFFFF") }
    private val bandFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#144ED38A") }
    private val levelLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tagFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED
        typeface = Typeface.MONOSPACE
    }
    private val watermark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED }
    private val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E60E1217") }
    private val cardEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A333D")
        style = Paint.Style.STROKE
    }
    private val cardText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT }
    private val cardHead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val emptyText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED }

    private val timeFmtIntraday: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd HH:mm").withZone(ZoneOffset.UTC)
    private val timeFmtDaily: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd").withZone(ZoneOffset.UTC)

    private var downX = 0f
    private var downY = 0f
    private var dragged = false
    private var scrollAtDown = 0

    init {
        setWillNotDraw(false)
        isClickable = true
        val d = resources.displayMetrics.density
        wick.strokeWidth = 1.3f * d
        candleEdge.strokeWidth = 1.2f * d
        grid.strokeWidth = 1f * d
        levelLine.strokeWidth = 1.5f * d
        cardEdge.strokeWidth = 1f * d
        axisText.textSize = 10f * d
        tagText.textSize = 10f * d
        watermark.textSize = 10f * d
        cardText.textSize = 11f * d
        cardHead.textSize = 12f * d
        emptyText.textSize = 12f * d
    }

    fun bind(payload: ChartPayload?, persian: Boolean) {
        val symbolChanged = payload?.symbol != this.payload?.symbol ||
            payload?.timeframe != this.payload?.timeframe
        this.payload = payload
        this.persian = persian
        if (symbolChanged) scrollBars = 0
        clampScroll()
        invalidate()
    }

    /* ----------------------------- interaction ----------------------------- */

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val slot = slotWidth()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragged = false
                scrollAtDown = scrollBars
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                if (abs(dx) > touchSlop()) {
                    dragged = true
                    scrollBars = scrollAtDown + (dx / max(slot, 1f)).roundToInt()
                    clampScroll()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!dragged) {
                    performClick()
                    onTap?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun touchSlop(): Float = 6f * resources.displayMetrics.density

    private fun clampScroll() {
        val total = payload?.series?.candles?.size ?: 0
        val maxScroll = max(0, total - visibleBars)
        scrollBars = scrollBars.coerceIn(0, maxScroll)
    }

    private fun slotWidth(): Float {
        val w = width - gutterWidth() - padLeft()
        return if (visibleBars <= 0) 1f else w / visibleBars
    }

    private fun gutterWidth(): Float = 58f * resources.displayMetrics.density
    private fun padLeft(): Float = 4f * resources.displayMetrics.density
    private fun axisHeight(): Float = 16f * resources.displayMetrics.density

    /* -------------------------------- draw --------------------------------- */

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(BG)

        val p = payload
        val all = p?.series?.candles.orEmpty()
        if (p == null || all.isEmpty()) {
            val msg = if (persian) "داده‌ای برای رسم نیست" else "No data to plot"
            canvas.drawText(msg, 12f * resources.displayMetrics.density, height / 2f, emptyText)
            return
        }

        val d = resources.displayMetrics.density
        val gutter = gutterWidth()
        val left = padLeft()
        val plotRight = width - gutter
        val top = 2f * d
        val plotBottom = height - axisHeight()
        val plotW = plotRight - left
        val plotH = plotBottom - top
        if (plotW <= 0 || plotH <= 0) return

        clampScroll()
        val end = all.size - scrollBars
        val start = max(0, end - visibleBars)
        val window = all.subList(start, max(start, end))
        if (window.isEmpty()) return

        var lo = window.minOf { it.low }
        var hi = window.maxOf { it.high }
        // A level the engine published must stay inside the window, otherwise
        // the chart would show a conclusion that is drawn nowhere. A level far
        // outside the price action is dropped instead, so one distant line
        // cannot flatten every candle; when the view is panned back in time
        // even the live price is allowed to fall outside.
        val span = hi - lo
        val live = scrollBars == 0
        val levels = p.levels.filter {
            it.price >= lo - span && it.price <= hi + span && (live || it.kind != LevelKind.SPOT) ||
                (live && it.kind == LevelKind.SPOT)
        }
        levels.forEach {
            lo = min(lo, it.price)
            hi = max(hi, it.price)
        }
        if (hi - lo < 1e-9) {
            hi += 1.0; lo -= 1.0
        }
        val headroom = (hi - lo) * 0.08
        lo -= headroom
        hi += headroom

        fun y(price: Double): Float = (top + plotH * (hi - price) / (hi - lo)).toFloat()
        val slot = plotW / window.size
        fun x(i: Int): Float = left + slot * (i + 0.5f)

        // seeded region wash, so borrowed history is visible at a glance
        val lastSeed = window.indexOfLast { it.origin == BarOrigin.SEED_REBASED }
        if (lastSeed >= 0) {
            canvas.drawRect(left, top, left + slot * (lastSeed + 1), plotBottom, seedWash)
        }

        drawGrid(canvas, left, plotRight, top, plotH, lo, hi, ::y)
        drawBand(canvas, levels, left, plotRight, ::y)
        drawCandles(canvas, window, slot, ::x, ::y)
        drawLevelLines(canvas, levels, left, plotRight, ::y)
        drawTimeAxis(canvas, window, ::x, plotBottom, p.timeframe.code)

        canvas.drawRect(plotRight, 0f, width.toFloat(), height.toFloat(), gutterBg)
        drawGutter(canvas, levels, p, plotRight, top, plotH, lo, hi, ::y)

        val head = buildString {
            append(p.symbol).append("  ").append(p.timeframe.code)
            append("   ").append(window.size).append("/").append(all.size)
            if (scrollBars > 0) append("  -").append(scrollBars)
        }
        canvas.drawText(head, left + 2f * d, top + 11f * d, watermark)

        if (overlayVisible) drawCard(canvas, p, left, plotRight, plotBottom)
    }

    private fun drawGrid(
        canvas: Canvas, left: Float, right: Float, top: Float, h: Float,
        lo: Double, hi: Double, y: (Double) -> Float
    ) {
        for (i in 0..GRID_LINES) {
            val price = lo + (hi - lo) * i / GRID_LINES
            val yy = y(price)
            canvas.drawLine(left, yy, right, yy, grid)
        }
    }

    private fun drawBand(canvas: Canvas, levels: List<PriceLevel>, left: Float, right: Float, y: (Double) -> Float) {
        val high = levels.firstOrNull { it.kind == LevelKind.BAND_HIGH } ?: return
        val low = levels.firstOrNull { it.kind == LevelKind.BAND_LOW } ?: return
        canvas.drawRect(left, y(high.price), right, y(low.price), bandFill)
    }

    private fun drawCandles(
        canvas: Canvas, window: List<Candle>, slot: Float,
        x: (Int) -> Float, y: (Double) -> Float
    ) {
        val d = resources.displayMetrics.density
        val bodyW = max(1.5f * d, slot * 0.68f)
        window.forEachIndexed { i, c ->
            val cx = x(i)
            val up = c.bullish
            val base = if (up) GREEN else RED
            val live = c.origin == BarOrigin.LIVE
            val color = if (live) base else dim(base)
            wick.color = color
            canvas.drawLine(cx, y(c.high), cx, y(c.low), wick)

            val top = y(max(c.open, c.close))
            val bottom = max(y(min(c.open, c.close)), top + 1.2f * d)
            val rect = RectF(cx - bodyW / 2f, top, cx + bodyW / 2f, bottom)
            if (live) {
                canvas.drawRect(rect, if (up) candleUp else candleDown)
            } else {
                candleEdge.color = color
                canvas.drawRect(rect, candleEdge)
            }
        }
    }

    private fun drawLevelLines(
        canvas: Canvas, levels: List<PriceLevel>, left: Float, right: Float, y: (Double) -> Float
    ) {
        val d = resources.displayMetrics.density
        levels.forEach { l ->
            levelLine.color = levelColor(l.kind)
            levelLine.pathEffect = when (l.kind) {
                LevelKind.SPOT -> null
                LevelKind.BID, LevelKind.ASK -> DashPathEffect(floatArrayOf(2f * d, 3f * d), 0f)
                else -> DashPathEffect(floatArrayOf(7f * d, 5f * d), 0f)
            }
            canvas.drawLine(left, y(l.price), right, y(l.price), levelLine)
        }
    }

    /**
     * Price scale plus one tag per published level. Tags are laid out from the
     * top down and pushed apart on collision, so overlapping levels stay legible.
     */
    private fun drawGutter(
        canvas: Canvas, levels: List<PriceLevel>, p: ChartPayload,
        gutterLeft: Float, top: Float, h: Float, lo: Double, hi: Double, y: (Double) -> Float
    ) {
        val d = resources.displayMetrics.density
        for (i in 0..GRID_LINES) {
            val price = lo + (hi - lo) * i / GRID_LINES
            canvas.drawText("%.2f".format(price), gutterLeft + 4f * d, y(price) + 3.5f * d, axisText)
        }

        data class Tag(val y: Float, val text: String, val color: Int, val strong: Boolean)

        val tags = levels.sortedByDescending { it.price }.map {
            Tag(y(it.price), shortLabel(it) + " " + "%.2f".format(it.price), levelColor(it.kind), it.kind == LevelKind.SPOT)
        }
        val tagH = 13f * d
        var cursor = top
        tags.forEach { t ->
            val ty = max(t.y - tagH / 2f, cursor)
            cursor = ty + tagH + 1.5f * d
            val rect = RectF(gutterLeft + 1f * d, ty, width - 1f * d, ty + tagH)
            tagFill.color = if (t.strong) t.color else Color.argb(
                46, Color.red(t.color), Color.green(t.color), Color.blue(t.color)
            )
            canvas.drawRoundRect(rect, 2f * d, 2f * d, tagFill)
            tagText.color = if (t.strong) BG else t.color
            canvas.drawText(t.text, rect.left + 3f * d, rect.bottom - 3.5f * d, tagText)
        }
    }

    private fun drawTimeAxis(
        canvas: Canvas, window: List<Candle>, x: (Int) -> Float, baseline: Float, timeframe: String
    ) {
        if (window.size < 2) return
        val d = resources.displayMetrics.density
        val fmt = if (timeframe == "1D") timeFmtDaily else timeFmtIntraday
        val step = max(1, window.size / 5)
        var i = 0
        while (i < window.size) {
            val label = fmt.format(window[i].time)
            canvas.drawText(label, x(i) - axisText.measureText(label) / 2f, baseline + 12f * d, axisText)
            i += step
        }
    }

    /** Fullscreen-only analysis card; bounded in height and dismissible. */
    private fun drawCard(canvas: Canvas, p: ChartPayload, left: Float, right: Float, bottom: Float) {
        val d = resources.displayMetrics.density
        val h = p.headline
        val lines = mutableListOf<Pair<String, Paint>>()
        val dir = when (h.direction) {
            "BULLISH" -> (if (persian) "صعودی" else "BULLISH") + " ▲"
            "BEARISH" -> (if (persian) "نزولی" else "BEARISH") + " ▼"
            else -> h.direction
        }
        lines += "$dir  ${h.horizon}  ${h.confidence?.let { "%.0f%%".format(it * 100) } ?: "—"}  ${h.bias?.let { "%+.0f".format(it) } ?: "—"}" to cardHead
        lines += "${h.regime} · ${h.signalState}" to cardText
        p.deltas.filter { it.changed }.take(3).forEach {
            val arrow = if (it.direction > 0) "▲" else if (it.direction < 0) "▼" else "•"
            lines += "$arrow ${if (persian) it.labelFa else it.labelEn}: ${it.previous} → ${it.current}" to cardText
        }
        p.dual.take(2).forEach {
            lines += "⇄ ${it.primary.direction} / ${it.secondary.direction}" to cardText
        }
        if (p.exceptions.isNotEmpty()) {
            lines += "! " + p.exceptions.take(2).joinToString(" · ") { it.code } to cardText
        }

        var w = 0f
        lines.forEach { w = max(w, it.second.measureText(it.first)) }
        val pad = 8f * d
        val lineH = 15f * d
        val boxW = min(right - left - 8f * d, w + pad * 2)
        val boxH = lines.size * lineH + pad * 2
        val x0 = left + 4f * d
        val y0 = bottom - boxH - 4f * d
        val rect = RectF(x0, y0, x0 + boxW, y0 + boxH)
        canvas.drawRoundRect(rect, 6f * d, 6f * d, cardBg)
        canvas.drawRoundRect(rect, 6f * d, 6f * d, cardEdge)
        var ty = y0 + pad + 10f * d
        lines.forEach { (text, paint) ->
            canvas.drawText(text, x0 + pad, ty, paint)
            ty += lineH
        }
    }

    private fun shortLabel(l: PriceLevel): String = when (l.kind) {
        LevelKind.SPOT -> "LAST"
        LevelKind.BID -> "BID"
        LevelKind.ASK -> "ASK"
        LevelKind.INVALIDATION -> "INV"
        LevelKind.EXPECTED_MOVE -> "EXP"
        LevelKind.BAND_HIGH -> "P95"
        LevelKind.BAND_LOW -> "P5"
        LevelKind.BENCHMARK -> "LBMA"
    }

    private fun levelColor(kind: LevelKind): Int = when (kind) {
        LevelKind.SPOT -> ACCENT
        LevelKind.BID -> GREEN
        LevelKind.ASK -> RED
        LevelKind.INVALIDATION -> RED
        LevelKind.EXPECTED_MOVE -> VIOLET
        LevelKind.BAND_HIGH, LevelKind.BAND_LOW -> GREEN
        LevelKind.BENCHMARK -> MUTED_HI
    }

    private fun dim(color: Int): Int =
        Color.argb(120, Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        const val DEFAULT_VISIBLE = 70
        const val MIN_VISIBLE = 30
        const val MAX_VISIBLE = 240
        private const val GRID_LINES = 4

        private val BG = Color.parseColor("#0B0D10")
        private val PANEL = Color.parseColor("#101419")
        private val GRID = Color.parseColor("#1A212A")
        private val TEXT = Color.parseColor("#E6E8EB")
        private val MUTED = Color.parseColor("#7B8694")
        private val MUTED_HI = Color.parseColor("#9AA4B2")
        private val ACCENT = Color.parseColor("#D6B36A")
        private val GREEN = Color.parseColor("#4ED38A")
        private val RED = Color.parseColor("#F26B6B")
        private val VIOLET = Color.parseColor("#9B8CF5")
    }
}
