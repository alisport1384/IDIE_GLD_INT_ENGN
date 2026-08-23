package io.goldintelligence.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.goldintelligence.engine.GoldIntelligenceEngine
import io.goldintelligence.engine.GoldIntelligenceState

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val engine = GoldIntelligenceEngine()
        val state = engine.evaluate(SampleData.snapshot())
        setContentView(buildView(state))
    }

    private fun buildView(state: GoldIntelligenceState): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.rgb(11, 13, 16))
        }

        fun addLine(label: String, value: String) {
            val tv = TextView(this).apply {
                text = "$label\n$value"
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 28)
            }
            content.addView(tv)
        }

        val title = TextView(this).apply {
            text = "GOLD INTELLIGENCE STATE"
            textSize = 24f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.rgb(214, 179, 106))
            setPadding(0, 0, 0, 12)
        }
        content.addView(title)

        val demoNotice = TextView(this).apply {
            text = "SAMPLE DATA — not live market data (no data feed connected yet)"
            textSize = 12f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.rgb(180, 90, 90))
            setPadding(0, 0, 0, 40)
        }
        content.addView(demoNotice)

        addLine("Signal State", state.signalState.name)
        addLine("Direction", state.direction.name)
        addLine("Gold Bias", state.goldBias?.let { "%.1f / 100".format(it) } ?: "N/A")
        addLine(
            "Probability (heuristic, non-calibrated)",
            state.probability?.let { "%.2f%%".format(it * 100) } ?: "N/A"
        )
        addLine("Confidence", state.confidence?.let { "%.2f%%".format(it * 100) } ?: "N/A")
        addLine("Regime", state.regime.name)
        addLine("Regime Stability", state.regimeStability.name)
        addLine("Dominant Factor", state.dominantFactor ?: "N/A")
        addLine(
            "Primary Drivers",
            state.attribution.take(5).joinToString("\n") { "${it.factorId}: %.1f".format(it.contribution) }
                .ifEmpty { "N/A" }
        )
        addLine("Conflict", state.conflict.name)
        addLine("Liquidity", state.liquidity.name)
        addLine(
            "Shock",
            state.shockDetail?.let {
                if (it.triggeredConditions.isEmpty()) state.shock.name
                else "${state.shock.name} (${it.triggeredConditions.joinToString()})"
            } ?: state.shock.name
        )
        addLine(
            "Divergences",
            state.divergences.joinToString("\n") { "${it.type.name}: ${it.description}" }.ifEmpty { "None" }
        )
        addLine("News State", state.newsState)
        addLine("Uncertainty", state.uncertainty.name)
        addLine(
            "Scenario Outlook",
            state.scenarioOutlook.joinToString("\n\n") { "${it.name}: ${it.trigger}" }.ifEmpty { "N/A" }
        )
        addLine("Invalidation", state.invalidation ?: "N/A")

        return ScrollView(this).apply { addView(content) }
    }
}
