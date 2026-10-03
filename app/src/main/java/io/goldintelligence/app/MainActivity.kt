package io.goldintelligence.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.goldintelligence.client.Badge
import io.goldintelligence.client.BadgeKind
import io.goldintelligence.client.BrokerFeedProvider
import io.goldintelligence.client.ChartPayload
import io.goldintelligence.client.ChartTimeframe
import io.goldintelligence.client.ClientMode
import io.goldintelligence.client.GoldIntelligenceClient
import io.goldintelligence.client.Row
import io.goldintelligence.client.Screen
import io.goldintelligence.client.ScreenModel
import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.LogLevel
import io.goldintelligence.engine.LogStage
import io.goldintelligence.engine.MultiHorizonEngine
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

/**
 * SPEC v2.1 §18 — the seven normative screens.
 *
 * The activity is a renderer only: every display decision (proxy badges,
 * gating, staleness, probability suppression at 5m/15m, licence masking) is
 * taken in the client's ScreenModelBuilder, so the phone and the server can
 * never present the same state differently. The logger (§21) is the one
 * exception: it is rendered straight from DiagnosticLog so that it still
 * works when the pipeline itself has failed.
 */
class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gi-io").apply { isDaemon = true }
    }
    private val ui = Handler(Looper.getMainLooper())
    private val client = GoldIntelligenceClient(ClientMode.DIRECT)

    private lateinit var root: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var statusLine: TextView
    private lateinit var progress: ProgressBar

    private var model: ScreenModel? = null
    private var selected: String = ScreenModel.SCREEN_STATE
    private var persian: Boolean = true
    private var loading: Boolean = false
    private var lastError: String? = null

    /** SPEC v2.1 §21 — logger view state, kept apart from the screen model. */
    private var logFilter: String = ""
    private var logMinLevel: LogLevel = LogLevel.DEBUG
    private var pendingExport: Pair<String, String>? = null

    /** SPEC v2.1 §22 — live chart state. */
    private var chart: ChartPayload? = null
    private var chartView: ChartView? = null

    private val autoRefresh = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, AUTO_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        root.addView(buildHeader())
        root.addView(buildTabBar())

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress)

        val scroller = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(24))
        }
        scroller.addView(content)
        root.addView(scroller)
        root.addView(buildFooter())

        setContentView(root)
        // SPEC v2.1 §21.1 — the recorder is off unless the user turned it on
        // in a previous session; nothing is written until then.
        DiagnosticLog.shared.setRecording(
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_LOGGING, false)
        )
        render()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        ui.postDelayed(autoRefresh, AUTO_REFRESH_MS)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(autoRefresh)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    /* ------------------------- chrome ------------------------- */

    private fun buildHeader(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(8))
            setBackgroundColor(PANEL)
        }
        val title = TextView(this).apply {
            text = "GOLD INTELLIGENCE"
            setTextColor(ACCENT)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val lang = smallButton("EN / فا") {
            persian = !persian
            render()
        }
        val reload = smallButton("↻") { refresh() }
        bar.addView(title)
        bar.addView(lang)
        bar.addView(reload)

        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(bar)
        statusLine = TextView(this).apply {
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(14), 0, dp(14), dp(8))
            setBackgroundColor(PANEL)
            text = ""
        }
        wrapper.addView(statusLine)
        return wrapper
    }

    private fun buildTabBar(): View {
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(PANEL)
        }
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        scroller.addView(tabBar)
        return scroller
    }

    private fun buildFooter(): View = TextView(this).apply {
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        setPadding(dp(14), dp(6), dp(14), dp(10))
        setBackgroundColor(PANEL)
        text = "Sources: Treasury · CFTC · NY Fed · Cboe · ECB/Frankfurter · gold-api · TradingView scanner · " +
            "Kraken · OKX · Swissquote · WGC/LBMA · BLS · ForexFactory · SGE. Not investment advice."
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        background = pill(PANEL_HI, ACCENT_DIM)
        minWidth = dp(44)
        minimumWidth = dp(44)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    /* ------------------------- data ------------------------- */

    private fun refresh() {
        if (loading) return
        loading = true
        lastError = null
        progress.visibility = View.VISIBLE
        updateStatus()
        io.execute {
            val result = try {
                client.analyze()
            } catch (e: Throwable) {
                null.also { lastError = "${e.javaClass.simpleName}: ${e.message}" }
            }
            ui.post {
                loading = false
                progress.visibility = View.GONE
                if (result != null) {
                    model = result.screens
                    chart = result.chart
                }
                render()
            }
        }
    }

    /* ------------------------- rendering ------------------------- */

    private fun render() {
        renderTabs()
        content.removeAllViews()
        updateStatus()

        // The logger is available even before the first successful refresh —
        // that is precisely when it is needed.
        if (selected == ScreenModel.SCREEN_LOGS) {
            renderLogger()
            return
        }
        if (selected == ScreenModel.SCREEN_CHART) {
            renderChart()
            return
        }

        val m = model
        if (m == null) {
            content.addView(note(if (persian) "در حال دریافت داده‌های واقعی…" else "Fetching live data…"))
            return
        }
        if (m.screens.isEmpty()) {
            content.addView(note((if (persian) m.errorFa else m.errorEn) ?: "No data"))
            return
        }
        val screen = m.screens.firstOrNull { it.id == selected } ?: m.screens.first()
        renderScreen(screen)
    }

    private fun renderTabs() {
        tabBar.removeAllViews()
        val screens = model?.screens
        val ids = screens?.map { it.id to (if (persian) it.titleFa else it.titleEn) }
            ?: listOf(
                ScreenModel.SCREEN_STATE to (if (persian) "وضعیت" else "State"),
                ScreenModel.SCREEN_FACTORS to (if (persian) "فاکتورها" else "Factors"),
                ScreenModel.SCREEN_INDICATORS to (if (persian) "شاخص‌ها" else "Indicators"),
                ScreenModel.SCREEN_HORIZONS to (if (persian) "افق‌ها" else "Horizons"),
                ScreenModel.SCREEN_EVENTS to (if (persian) "رویدادها" else "Events"),
                ScreenModel.SCREEN_DIAGNOSTICS to (if (persian) "تشخیص" else "Diagnostics"),
                ScreenModel.SCREEN_CHART to (if (persian) "چارت زنده" else "Live Chart"),
                ScreenModel.SCREEN_LOGS to (if (persian) "گزارش‌گیر" else "Logger")
            )
        for ((id, label) in ids) {
            val active = id == selected
            val b = Button(this).apply {
                text = label
                isAllCaps = false
                setTextColor(if (active) BG else ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                background = pill(if (active) ACCENT else PANEL_HI, ACCENT_DIM)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) }
                setOnClickListener {
                    selected = id
                    render()
                }
            }
            tabBar.addView(b)
        }
    }

    /* ------------------------- live chart (SPEC v2.1 §22) -------------------------
     * The canvas and the engine share one coordinate space, so a level the
     * engine published is drawn exactly at that price. The textual mirror of
     * the same payload stays available on the CHART screen of the API.
     */

    private fun renderChart() {
        val c = chart
        ChartHandoff.payload = c
        ChartHandoff.persian = persian

        content.addView(chartControls())
        if (c != null) content.addView(verdictStrip(c))

        val view = chartView ?: ChartView(this).also { chartView = it }
        (view.parent as? ViewGroup)?.removeView(view)
        view.overlayVisible = false
        view.bind(c, persian)
        view.onTap = { openFullscreen() }
        view.layoutParams = LinearLayout.LayoutParams(MATCH, dp(300)).apply { bottomMargin = dp(6) }
        content.addView(view)
        content.addView(
            note(
                if (persian)
                    "برای جابه‌جایی، چارت را بکشید · برای تمام‌صفحه، روی چارت یا دکمهٔ ⛶ بزنید"
                else
                    "drag to pan · tap the chart or ⛶ for full screen"
            )
        )

        if (c == null) {
            content.addView(note(if (persian) "فید چارت هنوز دریافت نشده است." else "Chart feed not received yet."))
            return
        }

        content.addView(sectionHeader(if (persian) "سطوح قفل‌شده به قیمت" else "Price-anchored levels"))
        val levelCard = cardBox()
        if (c.levels.isEmpty()) {
            levelCard.addView(renderRow(Row("—", "No level anchored", "N/A")))
        } else {
            c.levels.forEachIndexed { i, l ->
                if (i > 0) levelCard.addView(divider())
                levelCard.addView(
                    renderRow(
                        Row(
                            l.labelFa, l.labelEn, "%.3f".format(l.price),
                            listOf(Badge(l.kind.name, BadgeKind.INFO))
                        )
                    )
                )
            }
        }
        content.addView(levelCard)

        if (c.dual.isNotEmpty()) {
            content.addView(sectionHeader(if (persian) "تحلیل دوگانه" else "Dual analysis"))
            val card = cardBox()
            c.dual.forEachIndexed { i, d ->
                if (i > 0) card.addView(divider())
                card.addView(
                    renderRow(
                        Row(
                            d.primary.labelFa, d.primary.labelEn, d.primary.direction,
                            listOf(
                                Badge(d.kind.name, BadgeKind.WARN),
                                Badge("%.2f".format(d.primary.strength), BadgeKind.INFO)
                            ),
                            noteFa = d.primary.detailFa, noteEn = d.primary.detailEn, emphasis = true
                        )
                    )
                )
                card.addView(
                    renderRow(
                        Row(
                            d.secondary.labelFa, d.secondary.labelEn, d.secondary.direction,
                            listOf(
                                Badge(if (persian) "خوانش دوم" else "second reading", BadgeKind.WARN),
                                Badge("%.2f".format(d.secondary.strength), BadgeKind.INFO)
                            ),
                            noteFa = d.secondary.detailFa, noteEn = d.secondary.detailEn, emphasis = true
                        )
                    )
                )
                card.addView(
                    renderRow(
                        Row("دلیل", "Reason", "—", noteFa = d.reasonFa, noteEn = d.reasonEn)
                    )
                )
            }
            content.addView(card)
        }

        val changed = c.deltas.filter { it.changed }
        content.addView(
            sectionHeader(
                (if (persian) "تغییر نسبت به اجرای قبلی" else "Change since last refresh") + "   ·   ${changed.size}"
            )
        )
        val deltaCard = cardBox()
        val shown = if (changed.isEmpty()) c.deltas else changed
        shown.forEachIndexed { i, d ->
            if (i > 0) deltaCard.addView(divider())
            deltaCard.addView(
                renderRow(
                    Row(
                        d.labelFa, d.labelEn,
                        if (d.changed) "${d.previous} → ${d.current}" else d.current,
                        listOf(
                            Badge(
                                when {
                                    d.firstObservation -> "FIRST"
                                    d.changed && d.direction > 0 -> "▲"
                                    d.changed && d.direction < 0 -> "▼"
                                    d.changed -> "CHANGED"
                                    else -> "SAME"
                                },
                                when {
                                    !d.changed -> BadgeKind.INFO
                                    d.direction > 0 -> BadgeKind.OK
                                    d.direction < 0 -> BadgeKind.WARN
                                    else -> BadgeKind.INFO
                                }
                            )
                        ),
                        emphasis = d.changed
                    )
                )
            )
        }
        content.addView(deltaCard)

        content.addView(
            sectionHeader((if (persian) "اکسپشن‌ها" else "Exceptions") + "   ·   ${c.exceptions.size}")
        )
        val exCard = cardBox()
        if (c.exceptions.isEmpty()) {
            exCard.addView(
                renderRow(
                    Row("—", "No exception", "CLEAN", listOf(Badge("OK", BadgeKind.OK)))
                )
            )
        } else {
            c.exceptions.forEachIndexed { i, e ->
                if (i > 0) exCard.addView(divider())
                exCard.addView(
                    renderRow(
                        Row(
                            e.code, e.code, e.component,
                            listOf(
                                Badge(
                                    e.severity,
                                    if (e.severity == "ERROR") BadgeKind.ERROR else BadgeKind.WARN
                                )
                            ),
                            noteFa = e.messageFa, noteEn = e.messageEn,
                            emphasis = e.severity == "ERROR"
                        )
                    )
                )
            }
        }
        content.addView(exCard)
    }

    /** One compact line of verdict above the plot; the canvas stays clean. */
    private fun verdictStrip(c: ChartPayload): View {
        val h = c.headline
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) }
        }

        val dirColor = when (h.direction) {
            "BULLISH" -> GREEN
            "BEARISH" -> RED
            else -> MUTED_HI
        }
        val arrow = when (h.direction) {
            "BULLISH" -> " ▲"
            "BEARISH" -> " ▼"
            else -> ""
        }
        val price = c.quote?.last?.let { "%.2f".format(it) } ?: "—"
        val line1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line1.addView(TextView(this).apply {
            text = price
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        })
        line1.addView(TextView(this).apply {
            text = "  " + (if (persian) faDirection(h.direction) else h.direction) + arrow
            setTextColor(dirColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) }
        })
        line1.addView(TextView(this).apply {
            text = "⛶"
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(10), dp(2), dp(4), dp(2))
            setOnClickListener { openFullscreen() }
        })
        box.addView(line1)

        box.addView(TextView(this).apply {
            text = listOfNotNull(
                h.horizon,
                h.confidence?.let { (if (persian) "اطمینان " else "conf ") + "%.0f%%".format(it * 100) },
                h.bias?.let { (if (persian) "سوگیری " else "bias ") + "%+.0f".format(it) },
                h.regime,
                h.signalState
            ).joinToString("  ·  ")
            setTextColor(MUTED_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(3), 0, 0)
        })

        val changed = c.deltas.filter { it.changed }
        box.addView(TextView(this).apply {
            text = if (changed.isEmpty()) {
                if (persian) "بدون تغییر نسبت به اجرای قبلی" else "no change since last refresh"
            } else {
                changed.take(3).joinToString("   ") {
                    (if (it.direction > 0) "▲" else if (it.direction < 0) "▼" else "•") +
                        " " + (if (persian) it.labelFa else it.labelEn) + " " + it.previous + "→" + it.current
                }
            }
            setTextColor(if (changed.isEmpty()) MUTED else AMBER)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(3), 0, 0)
        })

        if (c.series.seededBars > 0) {
            box.addView(TextView(this).apply {
                text = if (persian)
                    "کندل توخالی: ${c.series.seededBars} پیش‌بار بازمقیاس‌شده از ${c.series.seedSource ?: "—"}"
                else
                    "hollow candles: ${c.series.seededBars} rebased seed bars from ${c.series.seedSource ?: "—"}"
                setTextColor(MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(0, dp(3), 0, 0)
            })
        }
        return box
    }

    private fun faDirection(d: String): String = when (d) {
        "BULLISH" -> "صعودی"
        "BEARISH" -> "نزولی"
        "NEUTRAL" -> "خنثی"
        else -> d
    }

    private fun openFullscreen() {
        ChartHandoff.payload = chart
        ChartHandoff.persian = persian
        startActivity(Intent(this, ChartFullscreenActivity::class.java))
    }

    private fun chartControls(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
        }

        val brokers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        BrokerFeedProvider.VENUES.forEach { v ->
            val active = client.chartVenue.id == v.id
            brokers.addView(Button(this).apply {
                text = v.id
                isAllCaps = false
                setTextColor(if (active) BG else ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                background = pill(if (active) ACCENT else PANEL_HI, ACCENT_DIM)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(4) }
                setOnClickListener {
                    if (client.chartVenue.id != v.id) {
                        client.chartVenue = v
                        DiagnosticLog.shared.info(
                            LogStage.CHART, "MainActivity",
                            "VENUE_SWITCHED", "chart venue set to ${v.label}", key = "CHART_FEED"
                        )
                        refresh()
                    }
                }
            })
        }
        box.addView(brokers)

        val frames = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        ChartTimeframe.entries.forEach { tf ->
            val active = client.chartTimeframe == tf
            frames.addView(Button(this).apply {
                text = tf.code
                isAllCaps = false
                setTextColor(if (active) BG else ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                background = pill(if (active) ACCENT else PANEL_HI, ACCENT_DIM)
                setPadding(dp(4), dp(2), dp(4), dp(2))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(4) }
                setOnClickListener {
                    if (client.chartTimeframe != tf) {
                        client.chartTimeframe = tf
                        DiagnosticLog.shared.info(
                            LogStage.CHART, "MainActivity",
                            "TIMEFRAME_SWITCHED", "chart timeframe set to ${tf.code}", key = "CHART_FEED"
                        )
                        refresh()
                    }
                }
            })
        }
        box.addView(frames)

        box.addView(Button(this).apply {
            text = if (persian) "⛶  تمام‌صفحه" else "⛶  Full screen"
            isAllCaps = false
            setTextColor(BG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = pill(ACCENT, ACCENT_DIM)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
            setOnClickListener { openFullscreen() }
        })
        return box
    }

    private fun cardBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = card()
        setPadding(dp(10), dp(6), dp(10), dp(6))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
    }

    /* ------------------------- logger (SPEC v2.1 §21) -------------------------
     * A separate surface with its own controls. It never renders a market
     * value and no analytical screen renders a log line.
     */

    private fun renderLogger() {
        val log = DiagnosticLog.shared
        val counts = log.countsByLevel()
        val errors = counts[LogLevel.ERROR] ?: 0
        val warns = counts[LogLevel.WARN] ?: 0

        content.addView(sectionHeader(if (persian) "کنترل گزارش‌گیر" else "Logger controls"))
        content.addView(loggerControls())

        content.addView(
            sectionHeader(
                (if (persian) "خلاصه" else "Summary") +
                    "   ·   ${log.snapshot().size} " + (if (persian) "رکورد" else "records")
            )
        )
        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
        summary.addView(
            renderRow(
                Row(
                    "خطا", "Errors", "$errors",
                    listOf(Badge(if (errors == 0) "CLEAN" else "ATTENTION",
                        if (errors == 0) BadgeKind.OK else BadgeKind.ERROR)),
                    emphasis = errors > 0
                )
            )
        )
        summary.addView(divider())
        summary.addView(
            renderRow(
                Row(
                    "هشدار", "Warnings", "$warns",
                    listOf(Badge(if (warns == 0) "CLEAN" else "REVIEW",
                        if (warns == 0) BadgeKind.OK else BadgeKind.WARN))
                )
            )
        )
        log.countsByStage().forEach { (stage, n) ->
            summary.addView(divider())
            summary.addView(renderRow(Row(stage.name, stage.name, "$n")))
        }
        content.addView(summary)

        // Per-indicator roll-up: one line per indicator, worst state wins.
        val statuses = log.indicatorStatuses()
            .filter { logFilter.isBlank() || it.key.contains(logFilter, true) ||
                it.code.contains(logFilter, true) || it.message.contains(logFilter, true) }
        content.addView(
            sectionHeader(
                (if (persian) "وضعیت هر شاخص" else "Per-indicator status") + "   ·   ${statuses.size}"
            )
        )
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
        if (statuses.isEmpty()) {
            statusCard.addView(note(if (persian) "رکوردی مطابق فیلتر نیست" else "No record matches the filter"))
        } else {
            statuses.forEachIndexed { i, st ->
                if (i > 0) statusCard.addView(divider())
                statusCard.addView(
                    renderRow(
                        Row(
                            st.key, st.key,
                            if (st.ok) "OK" else st.code,
                            listOf(
                                Badge(
                                    st.level.name,
                                    when (st.level) {
                                        LogLevel.ERROR -> BadgeKind.ERROR
                                        LogLevel.WARN -> BadgeKind.WARN
                                        else -> BadgeKind.OK
                                    }
                                ),
                                Badge(st.component, BadgeKind.INFO)
                            ),
                            noteFa = st.message, noteEn = st.message,
                            emphasis = !st.ok
                        )
                    )
                )
            }
        }
        content.addView(statusCard)

        val entries = log.filter(logMinLevel, null, null, logFilter).takeLast(500).reversed()
        content.addView(
            sectionHeader((if (persian) "ردیابی" else "Trace") + "   ·   ${entries.size}")
        )
        val traceCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
        if (entries.isEmpty()) {
            traceCard.addView(note(if (persian) "رکوردی مطابق فیلتر نیست" else "No record matches the filter"))
        } else {
            entries.forEach { e ->
                traceCard.addView(TextView(this).apply {
                    text = e.oneLine()
                    setTextColor(
                        when (e.level) {
                            LogLevel.ERROR -> RED
                            LogLevel.WARN -> AMBER
                            LogLevel.INFO -> TEXT
                            else -> MUTED
                        }
                    )
                    setTypeface(Typeface.MONOSPACE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                    setPadding(0, dp(2), 0, dp(2))
                    setHorizontallyScrolling(false)
                })
            }
        }
        val traceScroll = HorizontalScrollView(this).apply {
            addView(traceCard)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        content.addView(traceScroll)
    }

    private fun loggerControls(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dp(10), dp(8), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }

        val recording = DiagnosticLog.shared.isRecording()
        val switch = Button(this).apply {
            text = when {
                recording && persian -> "گزارش‌گیری روشن است — برای خاموش‌کردن بزنید"
                recording -> "Recording is ON — tap to switch off"
                persian -> "گزارش‌گیری خاموش است — برای روشن‌کردن بزنید"
                else -> "Recording is OFF — tap to switch on"
            }
            isAllCaps = false
            setTextColor(if (recording) BG else ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = pill(if (recording) GREEN else PANEL_HI, if (recording) GREEN else ACCENT_DIM)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
            setOnClickListener {
                val on = !DiagnosticLog.shared.isRecording()
                DiagnosticLog.shared.setRecording(on)
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(PREF_LOGGING, on).apply()
                toast(
                    when {
                        on && persian -> "گزارش‌گیری روشن شد؛ ضبط از همین لحظه آغاز می‌شود"
                        on -> "Recording on; capture starts from this moment"
                        persian -> "گزارش‌گیری خاموش شد"
                        else -> "Recording off"
                    }
                )
                render()
            }
        }
        box.addView(switch)
        box.addView(
            note(
                if (persian)
                    "تا زمانی که این کلید خاموش است هیچ رکوردی نگهداری نمی‌شود. برای ثبت یک " +
                        "چرخه کامل، آن را روشن کنید و سپس «به‌روزرسانی» بزنید."
                else
                    "While the switch is off nothing is retained. To capture a full cycle, " +
                        "switch it on and then press Refresh."
            )
        )

        val search = EditText(this).apply {
            hint = if (persian) "جستجو در شاخص، کد یا پیام" else "Filter by indicator, code or message"
            setText(logFilter)
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = pill(PANEL_HI, ACCENT_DIM)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        search.setOnEditorActionListener { _, _, _ ->
            logFilter = search.text.toString().trim()
            render()
            true
        }
        box.addView(search)

        val levels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        listOf(LogLevel.ERROR, LogLevel.WARN, LogLevel.INFO, LogLevel.DEBUG).forEach { lvl ->
            val active = logMinLevel == lvl
            levels.addView(Button(this).apply {
                text = lvl.name
                isAllCaps = false
                setTextColor(if (active) BG else ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                background = pill(if (active) ACCENT else PANEL_HI, ACCENT_DIM)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(4) }
                setOnClickListener {
                    logMinLevel = lvl
                    render()
                }
            })
        }
        box.addView(levels)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        fun action(label: String, weight: Float, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            background = pill(PANEL_HI, ACCENT_DIM)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, weight).apply { rightMargin = dp(4) }
            setOnClickListener { onClick() }
        }
        actions.addView(action(if (persian) "کپی" else "Copy", 1f) { copyLog() })
        actions.addView(action(".md", 1f) { exportLog(true) })
        actions.addView(action(".txt", 1f) { exportLog(false) })
        actions.addView(action(if (persian) "پاک‌کردن" else "Clear", 1f) {
            DiagnosticLog.shared.clear()
            toast(if (persian) "گزارش پاک شد" else "Log cleared")
            render()
        })
        box.addView(actions)
        return box
    }

    private fun logHeader(): Map<String, String> = linkedMapOf(
        "app" to "Gold Intelligence",
        "spec" to (model?.specVersion ?: MultiHorizonEngine.SPEC_VERSION),
        "mode" to "DIRECT (on-device ingestion)",
        "android" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / API ${android.os.Build.VERSION.SDK_INT}",
        "lastRefresh" to (model?.generatedAt?.toString() ?: "never"),
        "filter" to (logFilter.ifBlank { "none" }),
        "minLevel" to logMinLevel.name
    )

    private fun copyLog() {
        val text = DiagnosticLog.shared.toPlainText(logHeader())
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Gold Intelligence log", text))
        toast(
            if (persian) "گزارش در حافظه کپی شد (${text.length} نویسه)"
            else "Log copied to clipboard (${text.length} chars)"
        )
    }

    /**
     * Saves through the Storage Access Framework, so the file lands wherever
     * the user chooses and the app needs no storage permission.
     */
    private fun exportLog(markdown: Boolean) {
        val stamp = java.time.format.DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(java.time.ZoneOffset.UTC)
            .format(Instant.now())
        val name = "gold-intelligence-log-$stamp." + if (markdown) "md" else "txt"
        val body = if (markdown) DiagnosticLog.shared.toMarkdown(logHeader())
        else DiagnosticLog.shared.toPlainText(logHeader())
        pendingExport = name to body

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (markdown) "text/markdown" else "text/plain"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            startActivityForResult(intent, REQ_EXPORT_LOG)
        } catch (_: Exception) {
            // No document provider on the device: fall back to the app's own
            // external files directory, which needs no permission either.
            writeFallback(name, body)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT_LOG) return
        val pending = pendingExport ?: return
        pendingExport = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            toast(if (persian) "ذخیره لغو شد" else "Save cancelled")
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.use {
                it.write(pending.second.toByteArray(Charsets.UTF_8))
            }
            toast(if (persian) "ذخیره شد: ${pending.first}" else "Saved: ${pending.first}")
        } catch (e: Exception) {
            toast("${e.javaClass.simpleName}: ${e.message}")
            writeFallback(pending.first, pending.second)
        }
    }

    private fun writeFallback(name: String, body: String) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = java.io.File(dir, name)
            file.writeText(body, Charsets.UTF_8)
            toast((if (persian) "ذخیره شد: " else "Saved: ") + file.absolutePath)
        } catch (e: Exception) {
            toast("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun renderScreen(screen: Screen) {
        for (section in screen.sections) {
            content.addView(sectionHeader(if (persian) section.titleFa else section.titleEn))
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = card()
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
            }
            if (section.rows.isEmpty()) {
                card.addView(note("—"))
            } else {
                for ((i, row) in section.rows.withIndex()) {
                    if (i > 0) card.addView(divider())
                    card.addView(renderRow(row))
                }
            }
            content.addView(card)
        }
    }

    private fun renderRow(row: Row): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = if (persian) row.labelFa else row.labelEn
            setTextColor(if (row.emphasis) TEXT else MUTED_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (row.emphasis) 13f else 12f)
            if (row.emphasis) setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1.25f)
        }
        val value = TextView(this).apply {
            text = row.value
            setTextColor(valueColor(row))
            setTypeface(Typeface.MONOSPACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (row.emphasis) 14f else 12f)
            if (row.emphasis) setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        line.addView(label)
        line.addView(value)
        container.addView(line)

        if (row.badges.isNotEmpty()) {
            val badges = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, 0)
            }
            for (b in row.badges) badges.addView(badgeView(b))
            val wrap = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(badges)
            }
            container.addView(wrap)
        }

        val note = if (persian) (row.noteFa ?: row.noteEn) else (row.noteEn ?: row.noteFa)
        if (!note.isNullOrBlank()) {
            container.addView(TextView(this).apply {
                text = note
                setTextColor(MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                setPadding(0, dp(3), 0, 0)
            })
        }
        return container
    }

    private fun badgeView(b: Badge): View = TextView(this).apply {
        text = b.text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        setTypeface(Typeface.MONOSPACE)
        setTextColor(badgeFg(b.kind))
        background = pill(badgeBg(b.kind), badgeFg(b.kind))
        setPadding(dp(6), dp(1), dp(6), dp(1))
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(5) }
    }

    private fun sectionHeader(title: String): View = TextView(this).apply {
        text = title
        setTextColor(ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        setPadding(dp(2), dp(10), dp(2), dp(5))
    }

    private fun note(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(MUTED_HI)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(4), dp(10), dp(4), dp(10))
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1) / 2 + 1)
    }

    private fun updateStatus() {
        val m = model
        val age = m?.generatedAt?.let { Duration.between(it, Instant.now()).seconds }
        statusLine.text = when {
            lastError != null -> (if (persian) "خطا: " else "Error: ") + lastError
            loading && m == null -> if (persian) "در حال اتصال…" else "Connecting…"
            loading -> if (persian) "به‌روزرسانی…" else "Refreshing…"
            m == null -> ""
            else -> buildString {
                append(if (persian) "آخرین به‌روزرسانی " else "Updated ")
                append(if (age == null) "-" else if (age < 60) "${age}s" else "${age / 60}m")
                append(if (persian) " پیش · " else " ago · ")
                append(m.specVersion)
                if (m.degraded) append(if (persian) " · حالت تخریب‌شده" else " · DEGRADED")
            }
        }
    }

    /* ------------------------- styling ------------------------- */

    private fun valueColor(row: Row): Int {
        val v = row.value
        return when {
            v.contains("BULLISH") -> GREEN
            v.contains("BEARISH") -> RED
            v == "N/A" || v == "—" -> MUTED
            row.emphasis -> ACCENT
            else -> TEXT
        }
    }

    private fun badgeFg(k: BadgeKind): Int = when (k) {
        BadgeKind.PROXY -> AMBER
        BadgeKind.GATED -> VIOLET
        BadgeKind.FRESH, BadgeKind.OK -> GREEN
        BadgeKind.STALE, BadgeKind.WARN -> AMBER
        BadgeKind.EXPIRED, BadgeKind.ERROR -> RED
        BadgeKind.INFO -> MUTED_HI
    }

    private fun badgeBg(k: BadgeKind): Int = Color.argb(32, Color.red(badgeFg(k)), Color.green(badgeFg(k)), Color.blue(badgeFg(k)))

    private fun pill(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1).coerceAtLeast(1), Color.argb(90, Color.red(stroke), Color.green(stroke), Color.blue(stroke)))
    }

    private fun card(): GradientDrawable = GradientDrawable().apply {
        setColor(PANEL)
        cornerRadius = dp(10).toFloat()
        setStroke(1, LINE)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val AUTO_REFRESH_MS = 60_000L
        private const val REQ_EXPORT_LOG = 7301

        /** Where the logger switch is remembered between sessions. */
        private const val PREFS = "gold_intelligence"
        private const val PREF_LOGGING = "logging_enabled"

        private val BG = Color.parseColor("#0B0D10")
        private val PANEL = Color.parseColor("#12161B")
        private val PANEL_HI = Color.parseColor("#1A2027")
        private val LINE = Color.parseColor("#232A33")
        private val TEXT = Color.parseColor("#E6E8EB")
        private val MUTED_HI = Color.parseColor("#9AA4B2")
        private val MUTED = Color.parseColor("#6B7584")
        private val ACCENT = Color.parseColor("#D6B36A")
        private val ACCENT_DIM = Color.parseColor("#8A7340")
        private val GREEN = Color.parseColor("#4ED38A")
        private val RED = Color.parseColor("#F26B6B")
        private val AMBER = Color.parseColor("#E8B04B")
        private val VIOLET = Color.parseColor("#9B8CF5")
    }
}
