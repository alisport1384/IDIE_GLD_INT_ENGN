package io.goldintelligence.app

import android.app.Activity
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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import io.goldintelligence.client.Badge
import io.goldintelligence.client.BadgeKind
import io.goldintelligence.client.ClientMode
import io.goldintelligence.client.GoldIntelligenceClient
import io.goldintelligence.client.Row
import io.goldintelligence.client.Screen
import io.goldintelligence.client.ScreenModel
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

/**
 * SPEC v2 §18 — the six normative screens.
 *
 * The activity is a renderer only: every display decision (proxy badges,
 * gating, staleness, probability suppression at 5m/15m, licence masking) is
 * taken in the client's ScreenModelBuilder, so the phone and the server can
 * never present the same state differently.
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
        text = "Sources: Treasury · CFTC · NY Fed · Cboe (delayed) · ECB/Frankfurter · gold-api · Yahoo (derived only). " +
            "Not investment advice."
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
                if (result != null) model = result.screens
                render()
            }
        }
    }

    /* ------------------------- rendering ------------------------- */

    private fun render() {
        renderTabs()
        content.removeAllViews()
        updateStatus()

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
                ScreenModel.SCREEN_DIAGNOSTICS to (if (persian) "تشخیص" else "Diagnostics")
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
