package io.goldintelligence.client

import io.goldintelligence.engine.CrossMarketConfirmation
import io.goldintelligence.engine.GoldIntelligenceEngine
import io.goldintelligence.engine.Horizon
import io.goldintelligence.engine.InferenceBundle
import io.goldintelligence.engine.InputSnapshot
import io.goldintelligence.engine.IntelligenceReport
import io.goldintelligence.engine.MarketContext
import io.goldintelligence.engine.MultiHorizonEngine
import io.goldintelligence.ingestion.FactorDiagnostic
import io.goldintelligence.ingestion.FeatureBundle
import io.goldintelligence.ingestion.MarketUniverse
import io.goldintelligence.ingestion.SpecFactorEngine
import io.goldintelligence.ingestion.SeriesAnalogueEngine
import io.goldintelligence.ingestion.SpecFeatureEngineer
import io.goldintelligence.ingestion.TimeSeries
import io.goldintelligence.ingestion.WalkForwardCalibration
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
    val screens: ScreenModel,
    /** SPEC v2.1 §22 — live chart surface; null when the feed produced nothing. */
    val chart: ChartPayload? = null
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
    /**
     * SPEC v2.1 §24 — the matcher the engine asks for historical analogues.
     * It is held here because it needs the downloaded history, which the
     * engine layer never sees.
     */
    private val analogues: SeriesAnalogueEngine = SeriesAnalogueEngine(),
    /**
     * SPEC v2.1 §26 — the walk-forward replay that turns the downloaded
     * history into a calibration record. Held here for the same reason as
     * [analogues]: it needs the history, which the engine layer never sees.
     */
    private val calibrator: WalkForwardCalibration = WalkForwardCalibration(),
    private val engine: MultiHorizonEngine = MultiHorizonEngine(
        core = GoldIntelligenceEngine(analogueEngine = analogues)
    ),
    private val screenBuilder: ScreenModelBuilder = ScreenModelBuilder(),
    private val chartFeed: BrokerFeedProvider = BrokerFeedProvider(http),
    private val chartStore: LiveChartStore = LiveChartStore(),
    private val overlayBuilder: ChartOverlayBuilder = ChartOverlayBuilder()
) {
    /** Broker the chart draws. Switchable from the app; OANDA by default. */
    @Volatile
    var chartVenue: BrokerFeedProvider.Venue = BrokerFeedProvider.DEFAULT

    @Volatile
    var chartTimeframe: ChartTimeframe = ChartTimeframe.H1

    @Volatile
    private var lastMemo: ChartOverlayBuilder.Memo? = null

    @Volatile
    private var lastFactorHistory: MutableMap<String, MutableList<Double>> = HashMap()

    @Volatile
    private var lastGoldHistory: MutableList<Double> = ArrayList()

    /**
     * The §26 replay reads 20 years of daily bars; those bars change once a
     * session, so the result is memoised against the history it was built
     * from rather than recomputed on every 60-second refresh.
     */
    @Volatile
    private var lastInference: InferenceBundle? = null

    @Volatile
    private var lastInferenceKey: String? = null

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

        analogues.universe = universe
        val context = buildContext(universe, factors.scores.associate { it.factorId to it.score }, now)
        val report = engine.evaluate(snapshot, context, now)
        val chart = buildChart(report, factors.diagnostics, now)

        val result = AnalysisResult(
            report = report,
            universe = universe,
            features = features,
            diagnostics = factors.diagnostics,
            screens = screenBuilder.build(
                report, universe, features, factors.diagnostics,
                calendar = aggregator.lastCalendar,
                books = aggregator.lastBooks,
                otcTiers = aggregator.lastOtcTiers,
                goldCurve = aggregator.lastGoldCurve,
                chart = chart
            ),
            chart = chart
        )
        lastMemo = overlayBuilder.memo(report, factors.diagnostics)
        return result
    }

    /**
     * SPEC v2.1 §22 — refreshes the broker feed, advances the bar store and
     * rebuilds the overlay. A feed failure degrades the chart to whatever is
     * already stored and is reported on the chart itself, never swallowed.
     */
    private fun buildChart(
        report: IntelligenceReport?,
        diagnostics: List<FactorDiagnostic>,
        now: Instant
    ): ChartPayload {
        val venue = chartVenue
        val timeframe = chartTimeframe
        val snapshot = chartFeed.snapshot(venue, listOf(timeframe))

        val last = snapshot.quote?.last
        if (last != null && !chartStore.hasHistory(timeframe)) {
            val seed = chartFeed.seedCandles(timeframe)
            if (seed.isNotEmpty()) {
                chartStore.seed(timeframe, seed, last, "Kraken PAXG/USD")
            }
        }
        // Bars are bucketed by ingest time: the venue's own stamp is a session
        // marker and would place the forming bar in the wrong period.
        snapshot.bars[timeframe]?.let { chartStore.apply(timeframe, it, now) }

        val series = chartStore.series(timeframe, venue.ticker, venue.label)
        return overlayBuilder.build(
            report = report,
            quote = snapshot.quote,
            series = series,
            venue = venue,
            previous = lastMemo,
            feedFailure = snapshot.failureCode,
            diagnostics = diagnostics,
            now = now
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

        val inference = inferenceFor(u)

        return MarketContext(
            factorHistory = lastFactorHistory.filterValues { it.size >= 4 }.mapValues { it.value.toList() },
            goldHistory = lastGoldHistory.toList(),
            sigmaByHorizon = sigmaByHorizon(goldSeries),
            witnesses = witnesses(u),
            brierDriftRatio = brierDrift(inference),
            // SPEC v2.1 §26: measured by the walk-forward replay. Null only
            // when the replay produced nothing — never assumed.
            calibrationQuality = inference?.qualityFor(Horizon.D1)?.takeIf { it > 0.0 },
            spotPrice = u.scalar(MarketUniverse.GOLD_SPOT) ?: goldSeries?.last?.close,
            inference = inference
        )
    }

    /**
     * SPEC v2.1 §26.1. Runs the replay once per set of daily bars; the key is
     * the gold history's length and last close, which is exactly what changes
     * when a new session lands.
     */
    private fun inferenceFor(u: MarketUniverse): InferenceBundle? {
        val gold = u.seriesOf(MarketUniverse.GOLD_PROXY_ETF)
            ?: u.seriesOf(MarketUniverse.GOLD_SPOT_HISTORY)
            ?: return lastInference
        val key = "${gold.size}:${gold.last?.close}"
        if (key == lastInferenceKey) return lastInference
        calibrator.universe = u
        val bundle = calibrator.run()
        lastInference = bundle
        lastInferenceKey = key
        return bundle
    }

    /**
     * SPEC v2 §D4.8 kill-switch input: the out-of-sample Brier score of the
     * published mapping against the base-rate forecast it has to beat.
     * Above 1.0 the mapping is doing worse than climatology.
     */
    private fun brierDrift(inference: InferenceBundle?): Double? {
        val record = inference?.brier?.get(Horizon.D1) ?: return null
        if (record.uncertainty <= 0.0) return null
        return record.brier / record.uncertainty
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
