package io.goldintelligence.client

import io.goldintelligence.engine.FeatureKeys
import io.goldintelligence.engine.Tier
import io.goldintelligence.ingestion.Bar
import io.goldintelligence.ingestion.DataPoint
import io.goldintelligence.ingestion.Frequency
import io.goldintelligence.ingestion.IndicatorCatalog
import io.goldintelligence.ingestion.LicenseClass
import io.goldintelligence.ingestion.MarketUniverse
import io.goldintelligence.ingestion.QualityGates
import io.goldintelligence.ingestion.SpecFactorEngine
import io.goldintelligence.ingestion.SpecFeatureEngineer
import io.goldintelligence.ingestion.TimeSeries
import io.goldintelligence.ingestion.ValidationStatus
import io.goldintelligence.engine.Horizon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

class JsonTest {
    @Test
    fun `parses the cboe quote shape`() {
        val j = Json.parse("""{"timestamp":"2026-10-01 17:22:32","data":{"symbol":"GLD","current_price":382.245,"bid_size":1600,"iv30":20.922}}""")
        assertEquals("GLD", j["data"]?.get("symbol")?.asString)
        assertEquals(382.245, j["data"]?.get("current_price")?.asDouble!!, 1e-9)
        assertEquals(1600.0, j["data"]?.get("bid_size")?.asDouble!!, 1e-9)
    }

    @Test
    fun `accepts numbers encoded as strings`() {
        val j = Json.parse("""{"data":[{"date":"2026-09-30","close":"16.340000","volume":"0.0"}]}""")
        assertEquals(16.34, j["data"]?.get(0)?.get("close")?.asDouble!!, 1e-9)
    }

    @Test
    fun `rejects malformed input without throwing to the caller`() {
        assertNull(Json.parseOrNull("{not json"))
    }

    @Test
    fun `round trips the screen contract`() {
        val model = ScreenModel(
            generatedAt = Instant.parse("2026-10-01T10:00:00Z"),
            specVersion = "SPEC_GOLD_INTELLIGENCE_V2",
            screens = listOf(
                Screen(
                    "STATE", "وضعیت", "State",
                    listOf(
                        Section(
                            "خلاصه", "Headline",
                            listOf(Row("طلا", "Gold", "4175.50", listOf(Badge("FRESH", BadgeKind.FRESH)), "منبع", "source", true))
                        )
                    )
                )
            ),
            attribution = listOf("Treasury — public domain"),
            degraded = false
        )
        val decoded = ScreenModelCodec.decode(ScreenModelCodec.encode(model))
        assertNotNull(decoded)
        assertEquals(model.specVersion, decoded!!.specVersion)
        assertEquals("وضعیت", decoded.screens[0].titleFa)
        assertEquals(BadgeKind.FRESH, decoded.screens[0].sections[0].rows[0].badges[0].kind)
        assertTrue(decoded.screens[0].sections[0].rows[0].emphasis)
    }
}

class QualityGateTest {
    private val now: Instant = Instant.parse("2026-10-01T12:00:00Z")

    @Test
    fun `rejects a future timestamp`() {
        val v = QualityGates.check(1.0, now.plus(2, ChronoUnit.HOURS), now, QualityGates.Spec(Duration.ofDays(1)))
        assertTrue(v.failed.contains(QualityGates.G03))
        assertFalse(v.accepted)
    }

    @Test
    fun `rejects an empty dataset behind a successful response`() {
        val v = QualityGates.check(1.0, now, now, QualityGates.Spec(Duration.ofDays(1)), datasetSize = 0)
        assertTrue(v.failed.contains(QualityGates.G08))
    }

    @Test
    fun `rejects a granularity the provider silently downgraded`() {
        val v = QualityGates.check(
            1.0, now, now,
            QualityGates.Spec(Duration.ofDays(1), requestedGranularity = "1m"),
            reportedGranularity = "1d"
        )
        assertTrue(v.failed.contains(QualityGates.G09))
    }

    @Test
    fun `flags staleness without rejecting the record`() {
        val v = QualityGates.check(1.0, now.minus(5, ChronoUnit.DAYS), now, QualityGates.Spec(Duration.ofDays(1)))
        assertEquals(ValidationStatus.DEGRADED, v.status)
        assertTrue(v.accepted)
    }

    @Test
    fun `rejects an implausible level`() {
        val v = QualityGates.check(-9.0, now, now, QualityGates.Spec(Duration.ofDays(1), min = 0.0))
        assertTrue(v.failed.contains(QualityGates.G06))
    }
}

class DataContractTest {
    @Test
    fun `publication is not licence-gated`() {
        fun point(license: LicenseClass) = DataPoint(
            seriesId = "X", value = 1.0, unit = "u",
            observationTimestamp = Instant.EPOCH, releaseTimestamp = null, ingestTimestamp = Instant.EPOCH,
            source = "s", sourceUrl = "u", licenseClass = license, tier = Tier.A, quality = 1.0,
            isProxy = false, proxyOf = null, revision = null, vintage = Instant.EPOCH,
            frequency = Frequency.DAILY, latencySeconds = 0, validationStatus = ValidationStatus.PASSED,
            minHorizon = Horizon.D1
        )
        LicenseClass.entries.forEach {
            assertTrue("licence $it must not block display", point(it).rawPublishable)
        }
    }
}

class PipelineTest {
    private val now: Instant = Instant.parse("2026-10-01T12:00:00Z")

    private fun ramp(id: String, n: Int, start: Double, step: Double) = TimeSeries(
        id,
        (0 until n).map {
            val v = start + step * it
            Bar(now.minus((n - it).toLong(), ChronoUnit.DAYS), v, v, v, v, 1_000_000.0)
        }
    )

    private fun universe(): MarketUniverse = MarketUniverse(
        asOf = now,
        series = mapOf(
            MarketUniverse.REAL10Y to ramp(MarketUniverse.REAL10Y, 300, 1.0, 0.004),
            MarketUniverse.US10Y to ramp(MarketUniverse.US10Y, 300, 3.0, 0.005),
            MarketUniverse.US02Y to ramp(MarketUniverse.US02Y, 300, 3.5, 0.003),
            MarketUniverse.BREAKEVEN10Y to ramp(MarketUniverse.BREAKEVEN10Y, 300, 2.0, 0.001),
            MarketUniverse.DXY_SYNTHETIC to ramp(MarketUniverse.DXY_SYNTHETIC, 300, 95.0, 0.02),
            MarketUniverse.GOLD_PROXY_ETF to ramp(MarketUniverse.GOLD_PROXY_ETF, 300, 200.0, 0.5),
            MarketUniverse.SILVER_PROXY_ETF to ramp(MarketUniverse.SILVER_PROXY_ETF, 300, 20.0, 0.1),
            MarketUniverse.VIX to ramp(MarketUniverse.VIX, 300, 15.0, 0.01),
            MarketUniverse.GVZ to ramp(MarketUniverse.GVZ, 300, 18.0, 0.01),
            MarketUniverse.HY_ETF to ramp(MarketUniverse.HY_ETF, 300, 75.0, 0.01),
            MarketUniverse.IG_ETF to ramp(MarketUniverse.IG_ETF, 300, 100.0, 0.005),
            MarketUniverse.OIL_ETF to ramp(MarketUniverse.OIL_ETF, 300, 70.0, 0.05),
            MarketUniverse.EQUITY_ETF to ramp(MarketUniverse.EQUITY_ETF, 300, 500.0, 0.5),
            MarketUniverse.LONG_BOND_ETF to ramp(MarketUniverse.LONG_BOND_ETF, 300, 90.0, -0.02),
            MarketUniverse.COT_NET_NONCOMM to ramp(MarketUniverse.COT_NET_NONCOMM, 160, 100_000.0, 500.0),
            MarketUniverse.FUNDING_SPREAD to ramp(MarketUniverse.FUNDING_SPREAD, 300, 1.0, 0.002),
            MarketUniverse.FX_CNY to ramp(MarketUniverse.FX_CNY, 300, 7.0, -0.0005),
            MarketUniverse.FX_INR to ramp(MarketUniverse.FX_INR, 300, 85.0, 0.01)
        ),
        scalars = mapOf(
            MarketUniverse.GOLD_SPOT to 4175.5,
            MarketUniverse.SILVER_SPOT to 61.0,
            MarketUniverse.SOFR to 3.90,
            MarketUniverse.EFFR to 3.88,
            MarketUniverse.US03M to 4.20
        )
    )

    @Test
    fun `feature engineer never fabricates a missing input`() {
        val features = SpecFeatureEngineer().build(universe())
        assertNull(features[FeatureKeys.CENTRAL_BANK_NET_BUYING_3M])
        assertNull(features[FeatureKeys.PHYSICAL_DEMAND_INDEX])
        assertNotNull(features[FeatureKeys.REAL_YIELD])
        assertNotNull(features[FeatureKeys.DOLLAR_FUNDING_STRESS])
    }

    @Test
    fun `proxy features are capped and flagged`() {
        val features = SpecFeatureEngineer().build(universe())
        val dxy = features[FeatureKeys.DXY]
        assertNotNull(dxy)
        assertTrue(dxy!!.isProxy)
        assertTrue(dxy.quality <= 0.6 + 1e-9)
        assertEquals(Tier.PROXY, dxy.tier)
    }

    @Test
    fun `rising real yield scores bearish for gold`() {
        val features = SpecFeatureEngineer().build(universe())
        val result = SpecFactorEngine().score(features, now)
        val f01 = result.scores.firstOrNull { it.factorId == "F01_REAL_RATE" }
        assertNotNull(f01)
        assertTrue("a rising real yield must not score bullish", f01!!.score < 0.0)
    }

    @Test
    fun `unavailable factors are diagnosed rather than scored zero`() {
        val result = SpecFactorEngine().score(SpecFeatureEngineer().build(universe()), now)
        assertTrue(result.scores.none { it.factorId == "F10_CENTRAL_BANK_DEMAND" })
        val diag = result.diagnostics.first { it.factorId == "F10_CENTRAL_BANK_DEMAND" }
        assertFalse(diag.available)
        assertTrue(diag.reason!!.startsWith("NO_FREE_SOURCE"))
    }

    @Test
    fun `every catalogued indicator belongs to a declared factor`() {
        val ids = io.goldintelligence.engine.FactorCatalog.byId.keys
        IndicatorCatalog.indicators.forEach {
            assertTrue("${it.key} points at unknown factor ${it.factorId}", it.factorId in ids)
        }
    }

    @Test
    fun `screens cover the eight normative surfaces`() {
        val u = universe()
        val features = SpecFeatureEngineer().build(u)
        val factors = SpecFactorEngine().score(features, now)
        val snapshot = io.goldintelligence.engine.InputSnapshot(
            observations = emptyMap(),
            features = SpecFeatureEngineer().toFeatureSet(features),
            factorScores = factors.scores
        )
        val report = io.goldintelligence.engine.MultiHorizonEngine().evaluate(
            snapshot, io.goldintelligence.engine.MarketContext(spotPrice = 4175.5), now
        )
        val model = ScreenModelBuilder().build(report, u, features, factors.diagnostics)
        assertEquals(
            listOf("STATE", "FACTORS", "INDICATORS", "HORIZONS", "EVENTS", "DIAGNOSTICS", "CHART", "LOGS"),
            model.screens.map { it.id }
        )
        val indicators = model.screens.first { it.id == "INDICATORS" }
        assertEquals(
            "every factor with catalogued indicators gets a section",
            IndicatorCatalog.byFactor.size, indicators.sections.size
        )
        val horizons = model.screens.first { it.id == "HORIZONS" }
        assertEquals(6, horizons.sections.first().rows.size)
        assertTrue(model.attribution.isNotEmpty())
    }

    /** §21 — the logger is its own surface, and it stays separate from the content. */
    @Test
    fun `logger screen is built only from the diagnostic log`() {
        val log = io.goldintelligence.engine.DiagnosticLog()
        log.info(
            io.goldintelligence.engine.LogStage.NETWORK, "TestProvider", "HTTP_200",
            "fetched 128 rows", key = "TEST_SERIES"
        )
        log.error(
            io.goldintelligence.engine.LogStage.NETWORK, "BrokenProvider", "HTTP_403",
            "forbidden", key = "BROKEN_SERIES"
        )
        val u = universe()
        val features = SpecFeatureEngineer().build(u)
        val factors = SpecFactorEngine().score(features, now)
        val snapshot = io.goldintelligence.engine.InputSnapshot(
            observations = emptyMap(),
            features = SpecFeatureEngineer().toFeatureSet(features),
            factorScores = factors.scores
        )
        val report = io.goldintelligence.engine.MultiHorizonEngine().evaluate(
            snapshot, io.goldintelligence.engine.MarketContext(spotPrice = 4175.5), now
        )
        val model = ScreenModelBuilder(log).build(report, u, features, factors.diagnostics)
        val logs = model.screens.first { it.id == ScreenModel.SCREEN_LOGS }

        val text = logs.sections.flatMap { it.rows }
            .joinToString(" ") { "${it.labelEn} ${it.value} ${it.noteEn}" }
        assertTrue("the failing indicator is named", text.contains("BROKEN_SERIES"))
        assertTrue("the reason is named", text.contains("HTTP_403"))
        assertTrue("the healthy indicator is named", text.contains("TEST_SERIES"))

        // The log must not bleed into any analytical screen.
        val others = model.screens.filter { it.id != ScreenModel.SCREEN_LOGS }
            .flatMap { it.sections }.flatMap { it.rows }
            .joinToString(" ") { "${it.labelEn} ${it.value}" }
        assertTrue("no log line on the analytical screens", !others.contains("HTTP_403"))
    }

    /** The exports must carry the same failure detail the screen shows. */
    @Test
    fun `log exports name the failing indicator and the reason`() {
        val log = io.goldintelligence.engine.DiagnosticLog()
        log.error(
            io.goldintelligence.engine.LogStage.PARSE, "CalendarProvider", "SCHEMA_MISMATCH",
            "field forecast absent", key = "CALENDAR_CONSENSUS"
        )
        val md = log.toMarkdown(mapOf("spec" to "v2.1"))
        val txt = log.toPlainText(mapOf("spec" to "v2.1"))
        listOf(md, txt).forEach { out ->
            assertTrue(out.contains("CALENDAR_CONSENSUS"))
            assertTrue(out.contains("SCHEMA_MISMATCH"))
            assertTrue(out.contains("field forecast absent"))
            assertTrue(out.contains("v2.1"))
        }
        assertTrue("markdown is tabular", md.contains("|"))
    }

}

/** SPEC v2.1 §22 — live chart surface. */
class ChartTest {

    private val now: java.time.Instant = java.time.Instant.parse("2026-10-02T12:00:00Z")

    private fun candle(minute: Long, close: Double, origin: BarOrigin) = Candle(
        time = now.minusSeconds((60 - minute) * 3600),
        open = close - 1.0, high = close + 2.0, low = close - 2.0, close = close, origin = origin
    )

    /** A seeded series must be rebased onto the broker level and say so. */
    @Test
    fun `seeded bars are rebased and labelled`() {
        val store = LiveChartStore()
        val seed = (1..5).map { candle(it.toLong(), 4000.0 + it, BarOrigin.SEED_REBASED) }
        store.seed(ChartTimeframe.H1, seed, brokerLast = 4200.0, sourceLabel = "Kraken PAXG/USD")
        val series = store.series(ChartTimeframe.H1, "OANDA:XAUUSD", "OANDA")

        assertEquals(5, series.candles.size)
        assertEquals(0, series.liveBars)
        assertEquals(5, series.seededBars)
        assertEquals("Kraken PAXG/USD", series.seedSource)
        val factor = series.rebaseFactor!!
        assertEquals(4200.0 / 4005.0, factor, 1e-9)
        assertEquals(4200.0, series.candles.last().close, 1e-6)
        series.candles.forEach { assertEquals(BarOrigin.SEED_REBASED, it.origin) }
    }

    /** The forming bar is replaced in place, then sealed when the period rolls. */
    @Test
    fun `forming bar is replaced and sealed on the period boundary`() {
        val store = LiveChartStore()
        val tf = ChartTimeframe.H1
        val t0 = java.time.Instant.parse("2026-10-02T10:10:00Z")
        store.apply(tf, BrokerFeedProvider.FormingBar(tf, 4000.0, 4010.0, 3995.0, 4005.0), t0)
        store.apply(tf, BrokerFeedProvider.FormingBar(tf, 4000.0, 4020.0, 3995.0, 4018.0), t0.plusSeconds(600))
        var series = store.series(tf, "OANDA:XAUUSD", "OANDA")
        assertEquals("same period keeps one bar", 1, series.candles.size)
        assertEquals(4018.0, series.candles.last().close, 1e-9)

        store.apply(tf, BrokerFeedProvider.FormingBar(tf, 4018.0, 4025.0, 4015.0, 4020.0), t0.plusSeconds(3600))
        series = store.series(tf, "OANDA:XAUUSD", "OANDA")
        assertEquals("next period opens a new bar", 2, series.candles.size)
        assertEquals(2, series.liveBars)
        assertEquals(4018.0, series.candles.first().close, 1e-9)
    }

    /** An empty feed must produce an exception, never an invented candle. */
    @Test
    fun `empty feed yields exceptions and no fabricated data`() {
        val series = ChartSeries(
            "OANDA:XAUUSD", "OANDA", ChartTimeframe.H1, emptyList(), null, null, 0, 0
        )
        val payload = ChartOverlayBuilder(io.goldintelligence.engine.DiagnosticLog()).build(
            report = null, quote = null, series = series,
            venue = BrokerFeedProvider.DEFAULT, previous = null,
            feedFailure = "FEED_UNAVAILABLE", now = now
        )
        assertTrue(payload.series.candles.isEmpty())
        val codes = payload.exceptions.map { it.code }
        assertTrue("feed failure is reported", codes.contains("FEED_UNAVAILABLE"))
        assertTrue("empty series is reported", codes.contains("SERIES_EMPTY"))
        assertTrue("absent analysis is reported", codes.contains("ANALYSIS_ABSENT"))
        assertEquals("UNKNOWN", payload.headline.direction)
    }

    /** Opposing horizons must be published as two readings, not averaged away. */
    @Test
    fun `horizon split produces a dual reading`() {
        val log = io.goldintelligence.engine.DiagnosticLog()
        val report = reportWith(
            shortDirection = io.goldintelligence.engine.Direction.BULLISH,
            longDirection = io.goldintelligence.engine.Direction.BEARISH
        )
        val payload = ChartOverlayBuilder(log).build(
            report = report, quote = quote(), series = series(), venue = BrokerFeedProvider.DEFAULT,
            previous = null, feedFailure = null, now = now
        )
        val split = payload.dual.firstOrNull { it.kind == DualKind.HORIZON_SPLIT }
        assertTrue("a horizon split is published", split != null)
        assertEquals("BEARISH", split!!.primary.direction)
        assertEquals("BULLISH", split.secondary.direction)
        assertTrue(
            "the dual reading is logged",
            log.snapshot().any { it.code == "DUAL_READING" }
        )
    }

    /** Agreeing horizons must not fabricate a conflict. */
    @Test
    fun `agreeing horizons produce no dual reading`() {
        val report = reportWith(
            shortDirection = io.goldintelligence.engine.Direction.BEARISH,
            longDirection = io.goldintelligence.engine.Direction.BEARISH
        )
        val payload = ChartOverlayBuilder(io.goldintelligence.engine.DiagnosticLog()).build(
            report = report, quote = quote(), series = series(), venue = BrokerFeedProvider.DEFAULT,
            previous = null, feedFailure = null, now = now
        )
        assertTrue(payload.dual.none { it.kind == DualKind.HORIZON_SPLIT })
    }

    /** Every change against the previous refresh must be named and logged. */
    @Test
    fun `deltas name what changed since the previous refresh`() {
        val log = io.goldintelligence.engine.DiagnosticLog()
        val builder = ChartOverlayBuilder(log)
        val first = reportWith(
            io.goldintelligence.engine.Direction.BEARISH,
            io.goldintelligence.engine.Direction.BEARISH
        )
        val memo = builder.memo(first)
        val second = reportWith(
            io.goldintelligence.engine.Direction.BULLISH,
            io.goldintelligence.engine.Direction.BULLISH
        )
        val payload = builder.build(
            report = second, quote = quote(), series = series(), venue = BrokerFeedProvider.DEFAULT,
            previous = memo, feedFailure = null, now = now
        )
        val direction = payload.deltas.first { it.key == "DIRECTION" }
        assertTrue(direction.changed)
        assertEquals("BEARISH", direction.previous)
        assertEquals("BULLISH", direction.current)
        assertEquals(1, direction.direction)
        assertTrue(log.snapshot().any { it.code == "STATE_CHANGED" && it.key == "CHART_DELTA_DIRECTION" })
    }

    /** Levels must be anchored to the live price, not to a stale snapshot. */
    @Test
    fun `levels are anchored to the live quote`() {
        val payload = ChartOverlayBuilder(io.goldintelligence.engine.DiagnosticLog()).build(
            report = reportWith(
                io.goldintelligence.engine.Direction.BEARISH,
                io.goldintelligence.engine.Direction.BEARISH
            ),
            quote = quote(4321.0), series = series(), venue = BrokerFeedProvider.DEFAULT,
            previous = null, feedFailure = null, now = now
        )
        val spot = payload.levels.first { it.kind == LevelKind.SPOT }
        assertEquals(4321.0, spot.price, 1e-9)
        assertTrue(payload.levels.any { it.kind == LevelKind.BID })
        assertTrue(payload.levels.any { it.kind == LevelKind.ASK })
    }

    /* ---------------- fixtures ---------------- */

    private fun quote(last: Double = 4200.0) = LiveQuote(
        symbol = "OANDA:XAUUSD", venue = "OANDA", last = last,
        bid = last - 0.25, ask = last + 0.25, open = last - 5, high = last + 6, low = last - 9,
        changePct = 0.12, changeAbs = 5.0, volume = 1000.0, updateMode = "streaming",
        quotedAt = now, receivedAt = now
    )

    private fun series() = ChartSeries(
        "OANDA:XAUUSD", "OANDA", ChartTimeframe.H1,
        listOf(candle(1, 4190.0, BarOrigin.LIVE), candle(2, 4200.0, BarOrigin.LIVE)),
        null, null, 2, 0
    )

    private fun reportWith(
        shortDirection: io.goldintelligence.engine.Direction,
        longDirection: io.goldintelligence.engine.Direction
    ): io.goldintelligence.engine.IntelligenceReport {
        fun hz(h: io.goldintelligence.engine.Horizon, d: io.goldintelligence.engine.Direction) =
            io.goldintelligence.engine.HorizonState(
                horizon = h,
                mode = io.goldintelligence.engine.HorizonMode.CALIBRATED,
                coverage = 0.8,
                direction = d,
                probability = null,
                probabilityStatus = "UNCALIBRATED_NO_SAMPLE",
                confidence = 0.5,
                confidenceBreakdown = null,
                goldBias = if (d == io.goldintelligence.engine.Direction.BULLISH) 40.0 else -40.0,
                expectedMove = null,
                activeFactorIds = emptyList(),
                gatedFactorIds = emptyList(),
                attribution = emptyList(),
                weights = emptyList(),
                conflict = io.goldintelligence.engine.ConflictLevel.LOW,
                conflictRatio = 0.1,
                killSwitch = io.goldintelligence.engine.KillSwitchResult(false, emptyList()),
                signalState = io.goldintelligence.engine.SignalState.VALID,
                uncertainty = io.goldintelligence.engine.Uncertainty.HIGH_CONFIDENCE
            )
        val state = io.goldintelligence.engine.GoldIntelligenceState(
            direction = longDirection,
            probability = null,
            confidence = 0.5,
            regime = io.goldintelligence.engine.Regime.MONETARY_TIGHTENING,
            regimeStability = io.goldintelligence.engine.Stability.HIGH,
            dominantFactor = "F01_REAL_RATE",
            primaryDrivers = emptyList(),
            contradictions = emptyList(),
            crossMarketConfirmation = 0.8,
            newsState = "NONE",
            liquidity = io.goldintelligence.engine.Liquidity.NORMAL,
            shock = io.goldintelligence.engine.ShockState.NONE,
            expectedMove = null,
            uncertainty = io.goldintelligence.engine.Uncertainty.HIGH_CONFIDENCE,
            scenarios = emptyList(),
            invalidation = null,
            signalState = io.goldintelligence.engine.SignalState.VALID,
            goldBias = if (longDirection == io.goldintelligence.engine.Direction.BULLISH) 40.0 else -40.0
        )
        return io.goldintelligence.engine.IntelligenceReport(
            generatedAt = now,
            specVersion = "SPEC_GOLD_INTELLIGENCE_V2.1",
            state = state,
            horizons = listOf(
                hz(io.goldintelligence.engine.Horizon.M5, shortDirection),
                hz(io.goldintelligence.engine.Horizon.M15, shortDirection),
                hz(io.goldintelligence.engine.Horizon.H1, shortDirection),
                hz(io.goldintelligence.engine.Horizon.H4, longDirection),
                hz(io.goldintelligence.engine.Horizon.D1, longDirection),
                hz(io.goldintelligence.engine.Horizon.W1, longDirection)
            ),
            crossMarketConfirmation = io.goldintelligence.engine.CrossMarketConfirmation.Result(
                0.8, emptyList(), emptyList(), emptyList()
            ),
            dominance = emptyMap(),
            factorScores = emptyList(),
            features = emptyMap(),
            dataQuality = 0.7,
            spotPrice = 4200.0
        )
    }

    /** A chart exception must never be re-wrapped on the next refresh. */
    @Test
    fun `chart exceptions are not nested across refreshes`() {
        val log = io.goldintelligence.engine.DiagnosticLog()
        val builder = ChartOverlayBuilder(log)
        val empty = ChartSeries("OANDA:XAUUSD", "OANDA", ChartTimeframe.H1, emptyList(), null, null, 0, 0)
        repeat(3) {
            builder.build(
                report = null, quote = null, series = empty,
                venue = BrokerFeedProvider.DEFAULT, previous = null,
                feedFailure = "FEED_UNAVAILABLE", now = now
            )
        }
        val payload = builder.build(
            report = null, quote = null, series = empty,
            venue = BrokerFeedProvider.DEFAULT, previous = null,
            feedFailure = "FEED_UNAVAILABLE", now = now
        )
        assertTrue(
            "no exception code is nested",
            payload.exceptions.none { it.code.startsWith("CHART_EXCEPTION_") }
        )
        assertTrue(
            "no log code is doubly prefixed",
            log.snapshot().none { it.code.startsWith("CHART_EXCEPTION_CHART_EXCEPTION_") }
        )
    }

    /** ERROR is reserved for unhandled failures; the chart never raises one. */
    @Test
    fun `chart derived exceptions are logged as warnings not errors`() {
        val log = io.goldintelligence.engine.DiagnosticLog()
        val empty = ChartSeries("OANDA:XAUUSD", "OANDA", ChartTimeframe.H1, emptyList(), null, null, 0, 0)
        ChartOverlayBuilder(log).build(
            report = null, quote = null, series = empty,
            venue = BrokerFeedProvider.DEFAULT, previous = null,
            feedFailure = "FEED_UNAVAILABLE", now = now
        )
        val chartRows = log.snapshot().filter { it.stage == io.goldintelligence.engine.LogStage.CHART }
        assertTrue("the chart logged its exceptions", chartRows.any { it.code.startsWith("CHART_EXCEPTION_") })
        assertTrue(
            "no chart row is an ERROR",
            chartRows.none { it.level == io.goldintelligence.engine.LogLevel.ERROR }
        )
    }

    /** A provider with a declared fallback must not raise an ERROR either. */
    @Test
    fun `optional provider refusal is a warning`() {
        assertTrue("YAHOO is declared optional", Providers.optionalIds.contains("YAHOO"))
        assertTrue(
            "every optional id is a real provider",
            Providers.optionalIds.all { id -> Providers.all.any { it.id == id } }
        )
    }
}
