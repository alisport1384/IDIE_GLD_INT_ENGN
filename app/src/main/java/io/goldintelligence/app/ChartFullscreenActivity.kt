package io.goldintelligence.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import io.goldintelligence.client.ChartPayload
import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.LogStage

/**
 * SPEC v2.1 §22 — the chart on the whole screen.
 *
 * The payload is handed over in memory rather than serialized: the chart is a
 * view of the live snapshot the main activity already holds, and a stale copy
 * restored from a bundle would show a conclusion the engine has withdrawn.
 */
object ChartHandoff {
    @Volatile
    var payload: ChartPayload? = null

    @Volatile
    var persian: Boolean = true
}

class ChartFullscreenActivity : Activity() {

    private lateinit var chart: ChartView
    private lateinit var barsLabel: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()

        val root = FrameLayout(this).apply {
            setBackgroundColor(BG)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        chart = ChartView(this).apply {
            visibleBars = ChartView.DEFAULT_VISIBLE
            bind(ChartHandoff.payload, ChartHandoff.persian)
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            onTap = { toggleControls() }
        }
        root.addView(chart)
        root.addView(buildControls())

        setContentView(root)
        DiagnosticLog.shared.info(
            LogStage.CHART, "ChartFullscreenActivity", "FULLSCREEN_OPENED",
            "chart opened full screen", key = "CHART_FEED"
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onDestroy() {
        super.onDestroy()
        DiagnosticLog.shared.info(
            LogStage.CHART, "ChartFullscreenActivity", "FULLSCREEN_CLOSED",
            "chart full screen closed", key = "CHART_FEED"
        )
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
            window.insetsController?.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private var controls: LinearLayout? = null

    private fun buildControls(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
        }
        bar.addView(smallButton("−") { step(+20) })
        barsLabel = smallButton(chart.visibleBars.toString()) { }
        bar.addView(barsLabel)
        bar.addView(smallButton("+") { step(-20) })
        bar.addView(smallButton(if (ChartHandoff.persian) "تحلیل" else "INFO") {
            chart.overlayVisible = !chart.overlayVisible
            DiagnosticLog.shared.debug(
                LogStage.CHART, "ChartFullscreenActivity", "OVERLAY_TOGGLED",
                "analysis card " + if (chart.overlayVisible) "shown" else "hidden",
                key = "CHART_FEED"
            )
        })
        bar.addView(smallButton("✕") { finish() })
        controls = bar
        return bar
    }

    /** More bars on screen means a wider span; fewer means clearer candles. */
    private fun step(delta: Int) {
        chart.visibleBars = chart.visibleBars + delta
        barsLabel.text = chart.visibleBars.toString()
    }

    private fun toggleControls() {
        controls?.let { it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        minWidth = dp(34)
        minimumWidth = dp(34)
        minHeight = dp(28)
        minimumHeight = dp(28)
        setPadding(dp(8), dp(2), dp(8), dp(2))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#CC12161B"))
            cornerRadius = dp(6).toFloat()
            setStroke(dp(1), Color.parseColor("#8A7340"))
        }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val BG: Int = Color.parseColor("#0B0D10")
        val ACCENT: Int = Color.parseColor("#D6B36A")
    }
}
