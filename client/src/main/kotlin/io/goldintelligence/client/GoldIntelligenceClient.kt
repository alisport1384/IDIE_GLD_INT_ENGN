package io.goldintelligence.client

import io.goldintelligence.engine.CrossMarketConfirmation
import io.goldintelligence.engine.Horizon
import io.goldintelligence.engine.InputSnapshot
import io.goldintelligence.engine.IntelligenceReport
import io.goldintelligence.engine.MarketContext
import io.goldintelligence.engine.MultiHorizonEngine
import io.goldintelligence.ingestion.FactorDiagnostic
import io.goldintelligence.ingestion.FeatureBundle
import io.goldintelligence.ingestion.MarketUniverse
import io.goldintelligence.ingestion.SpecFactorEngine
import io.goldintelligence.ingestion.SpecFeatureEngineer
import io.goldintelligence.ingestion.TimeSeries
import java.time.Instant

/** Where the analysis is produced. */
enum class ClientMode {
    /** Fetch the free data stack and run the engine locally. */
    DIRECT,

    /** Fetch an already-computed report from a server implementing SPEC v2 §17. */
    REMOTE
}

data class AnalysisResult(
    val report: IntelligenceReport?,
    val universe: MarketUniverse?,
    val features: FeatureBundle?,
    val diagnostics: List<FactorDiagnostic>,
    val screens: ScreenModel
)

/**
 * The single entry point used by the Android app and by the server process.
 *
 * DIRECT mode is self-contained: the device fetches the free providers itself
 * and runs the whole pipeline locally, so the application has no hard
 * dependency on a backend being up. REMOTE mode exists for deployments that
 * prefer to centralize polling, and consumes exactly the same screen contract.
 */
class GoldIntelligenceClient(
    private val mode: ClientMode = ClientMode.DIRECT,
    private val baseUrl: String? = null,
    private val http: HttpClient = HttpClient(),
    private val aggregator: FreeDataAggregator = FreeDataAggregator(http),
    private val featureEngineer: SpecFeatureEngineer = SpecFeatureEngineer(),
    private val factorEngine: SpecFactorEngine = SpecFactorEngine(),
    private val engine: MultiHorizonEngine = MultiHorizonEngine(),
    private val screenBuilder: ScreenModelBuilder = ScreenModelBuilder()
) {
    @Volatile
    private var lastFactorHistory: MutableMap<String, MutableList<Double>> = HashMap()

    @Volatile
    private var lastGoldHistory: MutableList<Double> = ArrayList()

    fun analyze(now: Instant = Instant.now()): AnalysisResult = when (mode) {
        ClientMode.DIRECT -> analyzeDirect(now)
        ClientMode.REMOTE -> analyzeRemote(now)
    }

    private fun analyzeDirect(now: Instant): AnalysisResult {
        val universe = aggregator.refresh(now)
        if (universe.series.isEmpty() && universe.scalars.isEmpty()) {
            return AnalysisResult(
                null, universe, null, emptyList(),
                ScreenModel.error(
                    "هیچ ارائه‌دهنده‌ای پاسخ نداد. اتصال شبکه را بررسی کنید.",
                    "No provider responded. Check the network connection.",
                    now
                )
            )
        }

        val features = featureEngineer.build(universe)
        val factors = factorEngine.score(features, now)
        val snapshot = InputSnapshot(
            observations = universe.points.mapValues { it.value.toObservation() },
            features = featureEngineer.toFeatureSet(features),
            factorScores = factors.scores
        )

        val context = buildContext(universe, factors.scores.associate { it.factorId to it.score }, now)
        val report = engine.evaluate(snapshot, context, now)

        return AnalysisResult(
            report = report,
            universe = universe,
            features = features,
            diagnostics = factors.diagnostics,
            screens = screenBuilder.build(
                report, universe, features, factors.diagnostics,
                calendar = aggregator.lastCalendar,
                books = aggregator.lastBooks,
                otcTiers = aggregator.lastOtcTiers,
                goldCurve = aggregator.lastGoldCurve
            )
        )
    }

    private fun analyzeRemote(now: Instant): AnalysisResult {
        val root = baseUrl?.trimEnd('/')
            ?: return AnalysisResult(
                null, null, null, emptyList(),
                ScreenModel.error("آدرس سرور تنظیم نشده است.", "No server base URL configured.", now)
            )
        val body = http.get("REMOTE", "$root/v1/screens")
        if (!body.ok) {
            return AnalysisResult(
                null, null, null, emptyList(),
                ScreenModel.error(
                    "سرور پاسخ نداد (${body.status}).",
                    "Server did not respond (${body.status}).",
                    now
                )
            )
        }
        val model = ScreenModelCodec.decode(body.body)
            ?: return AnalysisResult(
                null, null, null, emptyList(),
                ScreenModel.error("پاسخ سرور قابل تجزیه نبود.", "Server response could not be parsed.", now)
            )
        return AnalysisResult(null, null, null, emptyList(), model)
    }

    /**
     * Builds the §D4 context. Factor history accumulates across refreshes in
     * this process; until enough aligned observations exist, dominance stays
     * neutral rather than being inferred from the current score.
     */
    private fun buildContext(
        u: MarketUniverse,
        currentScores: Map<String, Double>,
        now: Instant
    ): MarketContext {
        val goldSeries = u.seriesOf(MarketUniverse.GOLD_FUT_FRONT)
            ?: u.seriesOf(MarketUniverse.GOLD_PROXY_ETF)

        currentScores.forEach { (id, score) ->
            val list = lastFactorHistory.getOrPut(id) { ArrayList() }
            list.add(score)
            if (list.size > HISTORY_CAP) list.removeAt(0)
        }
        goldSeries?.last?.close?.let {
            lastGoldHistory.add(it)
            if (lastGoldHistory.size > HISTORY_CAP) lastGoldHistory.removeAt(0)
        }

        return MarketContext(
            factorHistory = lastFactorHistory.filterValues { it.size >= 4 }.mapValues { it.value.toList() },
            goldHistory = lastGoldHistory.toList(),
            sigmaByHorizon = sigmaByHorizon(goldSeries),
            witnesses = witnesses(u),
            brierDriftRatio = null,
            // SPEC v2 §19: no live record yet ⇒ calibration quality is zero, not assumed.
            calibrationQuality = null,
            spotPrice = u.scalar(MarketUniverse.GOLD_SPOT) ?: goldSeries?.last?.close
        )
    }

    private fun sigmaByHorizon(gold: TimeSeries?): Map<Horizon, Double> {
        val dailySigma = gold?.sigmaPrice(20) ?: return emptyMap()
        // Square-root-of-time scaling from the daily estimate; declared as such.
        fun scale(minutes: Double): Double = dailySigma * Math.sqrt(minutes / 1440.0)
        return mapOf(
            Horizon.M5 to scale(5.0),
            Horizon.M15 to scale(15.0),
            Horizon.H1 to scale(60.0),
            Horizon.H4 to scale(240.0),
            Horizon.D1 to dailySigma,
            Horizon.W1 to dailySigma * Math.sqrt(5.0)
        )
    }

    /**
     * The eight witness markets of §D4.5, each oriented so that a positive
     * value means "this market is behaving bullishly for gold".
     */
    private fun witnesses(u: MarketUniverse): List<CrossMarketConfirmation.Witness> {
        fun move(id: String, periods: Int = 5): Double? = u.seriesOf(id)?.returnOver(periods)
        val dxy = move(MarketUniverse.DXY_SYNTHETIC)
        val us10 = u.seriesOf(MarketUniverse.US10Y)?.let { s ->
            val a = s.closeAt(0)
            val b = s.closeAt(5)
            if (a != null && b != null) a - b else null
        }
        val real = u.seriesOf(MarketUniverse.REAL10Y)?.let { s ->
            val a = s.closeAt(0)
            val b = s.closeAt(5)
            if (a != null && b != null) a - b else null
        }
        val silver = move(MarketUniverse.SILVER_PROXY_ETF)
        val oil = move(MarketUniverse.OIL_ETF)
        val vix = move(MarketUniverse.VIX)
        val etf = u.seriesOf(MarketUniverse.GOLD_PROXY_ETF)?.last?.volume?.let { v ->
            val avg = u.seriesOf(MarketUniverse.GOLD_PROXY_ETF)?.bars?.takeLast(20)?.mapNotNull { it.volume }?.average()
            if (avg != null && avg > 0.0) (v - avg) / avg else null
        }
        val basis = u.scalar(MarketUniverse.GOLD_FUT_FRONT)?.let { f ->
            u.scalar(MarketUniverse.GOLD_SPOT)?.let { s -> if (s > 0) (f - s) / s else null }
        }

        return listOf(
            CrossMarketConfirmation.Witness("DXY", dxy?.let { -it }, 1.0),
            CrossMarketConfirmation.Witness("US10Y", us10?.let { -it }, 1.0),
            CrossMarketConfirmation.Witness("REAL_YIELD", real?.let { -it }, 1.0),
            CrossMarketConfirmation.Witness("SILVER", silver, 0.8),
            CrossMarketConfirmation.Witness("OIL", oil, 0.5),
            CrossMarketConfirmation.Witness("VIX", vix, 0.7),
            CrossMarketConfirmation.Witness("ETF_FLOW", etf, 0.6),
            CrossMarketConfirmation.Witness("FUTURES_BASIS", basis, 0.6)
        )
    }

    companion object {
        const val HISTORY_CAP = 240
    }
}
