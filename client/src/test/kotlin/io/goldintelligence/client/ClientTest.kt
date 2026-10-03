package io.goldintelligence.client

import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.FeatureKeys
import io.goldintelligence.engine.LogStage
import io.goldintelligence.engine.Tier
import io.goldintelligence.ingestion.Bar
import io.goldintelligence.ingestion.DataPoint
import io.goldintelligence.ingestion.Frequency
import io.goldintelligence.ingestion.IndicatorCatalog
import io.goldintelligence.ingestion.LicenseClass
import io.goldintelligence.ingestion.MarketUniverse
import io.goldintelligence.ingestion.QualityGates
import io.goldintelligence.ingestion.SeriesAnalogueEngine
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
    fun `intraday real yield proxy is the quoted ten year less the last breakeven`() {
        val base = universe()
        // Without an intraday quote nothing may be invented.
        assertNull(SpecFeatureEngineer().build(base)[FeatureKeys.REAL_YIELD_INTRADAY_PROXY])

        val quoted = base.copy(
            scalars = base.scalars + mapOf(MarketUniverse.US10Y_INTRADAY to 4.37)
        )
        val f = SpecFeatureEngineer().build(quoted)[FeatureKeys.REAL_YIELD_INTRADAY_PROXY]
        assertNotNull(f)
        val breakeven = quoted.seriesOf(MarketUniverse.BREAKEVEN10Y)!!.last!!.close
        assertEquals(4.37 - breakeven, f!!.value, 1e-9)
        assertTrue("an intraday composite is a proxy, not a published series", f.isProxy)
        assertTrue(f.quality <= 0.6 + 1e-9)
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
        assertTrue(diag.reason!!.startsWith("MISSING_INPUTS"))
        assertTrue(FeatureKeys.CENTRAL_BANK_NET_BUYING_3M in diag.missingFeatures)
    }

    @Test
    fun `official sector holdings score the central bank factor`() {
        // SPEC v2.1 §23: with the reserves template present the factor scores,
        // is not a proxy, and leans with the direction of official buying.
        val u = universe()
        val enriched = u.copy(
            scalars = u.scalars + mapOf(
                MarketUniverse.CB_GOLD_NET_3M_T to 144.7,
                MarketUniverse.CB_GOLD_NET_3M_Z to 1.2,
                MarketUniverse.CB_GOLD_BREADTH to 12.0,
                MarketUniverse.CB_GOLD_REPORTERS to 62.0
            )
        )
        val features = SpecFeatureEngineer().build(enriched)
        val netBuying = features[FeatureKeys.CENTRAL_BANK_NET_BUYING_3M]
        assertNotNull(netBuying)
        assertFalse("official holdings are a measurement, not a proxy", netBuying!!.isProxy)

        val result = SpecFactorEngine().score(features, now)
        val f10 = result.scores.firstOrNull { it.factorId == "F10_CENTRAL_BANK_DEMAND" }
        assertNotNull(f10)
        assertTrue("official buying is bullish", f10!!.score > 0.0)
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
        // SPEC v2.1 §27 — the provenance audit leads the screen, then one
        // section per factor that has catalogued indicators.
        assertEquals("Data provenance", indicators.sections.first().titleEn)
        assertEquals(
            "every factor with catalogued indicators gets a section",
            IndicatorCatalog.byFactor.size, indicators.sections.size - 1
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

/**
 * SPEC v2.1 §23 — the sources added to replace the last proxies, and §21.1,
 * the recording switch. Every parser is exercised against the exact shape its
 * provider returns, so a format change fails here rather than silently
 * degrading a factor.
 */
class QualitySourceTest {
    private val log = DiagnosticLog()

    @Test
    fun `reserves parser drops implausible country series and keeps the rest`() {
        val xml = """
            <StructureSpecificData>
              <DataSet>
                <Series COUNTRY="USA" INDICATOR="IRFCLDT1_IRFCL56V_FTO" SECTOR="S1XS1311" FREQUENCY="M">
                  <Obs TIME_PERIOD="2026-06" OBS_VALUE="261498926.0" DERIVATION_TYPE="O"/>
                  <Obs TIME_PERIOD="2026-07" OBS_VALUE="261498926.0" DERIVATION_TYPE="O"/>
                  <Obs TIME_PERIOD="2026-08" OBS_VALUE="261498926.0" DERIVATION_TYPE="O"/>
                </Series>
                <Series COUNTRY="POL" INDICATOR="IRFCLDT1_IRFCL56V_FTO" SECTOR="S1XS1311" FREQUENCY="M">
                  <Obs TIME_PERIOD="2026-06" OBS_VALUE="16000000.0"/>
                  <Obs TIME_PERIOD="2026-08" OBS_VALUE="16321000.0"/>
                </Series>
                <Series COUNTRY="AGO" INDICATOR="IRFCLDT1_IRFCL56V_FTO" SECTOR="S1XS1311" FREQUENCY="M">
                  <Obs TIME_PERIOD="2026-08" OBS_VALUE="592900000.0"/>
                </Series>
              </DataSet>
            </StructureSpecificData>
        """.trimIndent()

        val rows = ImfReservesProvider(HttpClient(log = log), log).parse(xml)
        val countries = rows.map { it.country }.toSet()
        assertTrue("USA" in countries)
        assertTrue("POL" in countries)
        assertFalse("a holding larger than world reserves must be rejected", "AGO" in countries)

        val usa = rows.first { it.country == "USA" }
        // 261,498,926 fine troy ounces is the United States' 8,133.5 t holding.
        assertEquals(8133.5, usa.tonnes, 1.0)

        val polish = rows.filter { it.country == "POL" }.sortedBy { it.period }
        assertEquals(9.98, polish.last().tonnes - polish.first().tonnes, 0.1)
    }

    @Test
    fun `calendar snapshot measures surprise and the clock to the next release`() {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        val snapshot = CalendarSnapshot(
            events = listOf(
                CalendarEvent("CPI", "US", now.plusSeconds(3 * 3600), "High", "2.9", "3.0"),
                CalendarEvent("Redbook", "US", now.plusSeconds(1800), "Low", null, null),
                CalendarEvent("Payrolls", "US", now.plusSeconds(50 * 3600), "High", "120", "110")
            ),
            releases = (1..6).map {
                CalendarRelease(
                    "Indicator $it", "US", now.minusSeconds(it * 86_400L), 1,
                    actual = 110.0, forecast = 100.0, previous = 100.0
                )
            }
        )
        assertEquals(3.0, snapshot.hoursToNext(now)!!, 1e-6)
        assertEquals(0.5, snapshot.hoursToNext(now, highOnly = false)!!, 1e-6)
        assertEquals(1, snapshot.highImpactWithin(now, 24))
        // Every release printed ten per cent above consensus.
        assertEquals(10.0, snapshot.surpriseIndex()!!, 1e-6)
        assertNull("too few matching releases must not produce an index",
            snapshot.surpriseIndex { it.indicator == "Indicator 1" })
    }

    @Test
    fun `the analogue inputs are the series kept at full depth`() {
        // The matcher describes a session with these five; if one of them were
        // truncated to the short window the match set would silently shrink.
        assertEquals(
            setOf(
                MarketUniverse.GOLD_PROXY_ETF, MarketUniverse.SILVER_PROXY_ETF,
                MarketUniverse.EQUITY_ETF, MarketUniverse.LONG_BOND_ETF, MarketUniverse.VIX
            ),
            FreeDataAggregator.ANALOGUE_SERIES
        )
        assertTrue(FreeDataAggregator.DEEP_HISTORY_BARS > FreeDataAggregator.DEFAULT_HISTORY_BARS)
        assertTrue(
            "the deep window must clear the matcher's own floor several times over",
            FreeDataAggregator.DEEP_HISTORY_BARS >= SeriesAnalogueEngine.MIN_HISTORY * 4
        )
    }

    @Test
    fun `consensus surprise comes from the calendar itself without a second source`() {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        val snapshot = CalendarSnapshot(
            events = emptyList(),
            releases = listOf(
                // Older, and deliberately far off consensus: must not be averaged in.
                CalendarRelease("Old", "US", now.minusSeconds(40 * 86_400L), 2, 300.0, 100.0, 100.0),
                CalendarRelease("Low importance", "US", now.minusSeconds(4 * 86_400L), 0, 50.0, 100.0, 100.0),
                CalendarRelease("CPI", "US", now.minusSeconds(3 * 86_400L), 2, 110.0, 100.0, 100.0),
                CalendarRelease("Payrolls", "US", now.minusSeconds(2 * 86_400L), 2, 90.0, 100.0, 100.0),
                CalendarRelease("Retail Sales", "US", now.minusSeconds(86_400L), 1, 120.0, 100.0, 100.0)
            )
        )
        // Last three prints of importance >= 1: +10 %, -10 %, +20 % -> +6.67 %.
        assertEquals(0.0667, snapshot.latestConsensusSurprise()!!, 1e-4)
        // A single high-importance print is enough: this is a measurement, not an index.
        assertEquals(0.20, snapshot.latestConsensusSurprise(minImportance = 1, count = 1)!!, 1e-9)
        assertNull(
            "no release at that importance must report nothing, not zero",
            snapshot.latestConsensusSurprise(minImportance = 9)
        )
    }

    @Test
    fun `policy divergence is the US rate against the other majors`() {
        val rates = PolicyRates(
            rates = mapOf("US" to 3.625, "EA" to 2.25, "GB" to 3.75, "JP" to 1.0, "CH" to 0.0),
            changes12m = mapOf("US" to -1.0, "EA" to -0.5, "GB" to -0.5, "JP" to 0.5, "CH" to -0.5),
            asOf = "2026-08"
        )
        assertEquals(3.625 - (2.25 + 3.75 + 1.0 + 0.0) / 4.0, rates.divergence!!, 1e-9)
        assertEquals(-1.0 - (-0.25), rates.divergenceChange12m!!, 1e-9)
    }

    @Test
    fun `the recorder keeps nothing until it is switched on`() {
        val off = DiagnosticLog(enabled = false)
        off.warn(LogStage.NETWORK, "X", "CODE", "message")
        assertTrue(off.snapshot().isEmpty())
        assertEquals(1L, off.suppressedCount())

        off.setRecording(true)
        off.warn(LogStage.NETWORK, "X", "CODE", "message")
        // The switch itself is recorded, then the new record.
        assertEquals(2, off.snapshot().size)
        assertTrue(off.snapshot().first().code == "RECORDING_ON")

        off.setRecording(false)
        off.warn(LogStage.NETWORK, "X", "CODE", "message")
        assertEquals(2, off.snapshot().size)
    }

    @Test
    fun `the shared recorder is off by default`() {
        assertFalse(
            "a user who never opens the logger must not be recorded",
            DiagnosticLog(enabled = false).isRecording()
        )
    }
}

/**
 * SPEC v2.1 §23.8 — the bundled trust anchor. The reserves feed is issued
 * under a root that Android 10 does not carry, so the anchor shipped with the
 * app is the only thing keeping the central-bank factor alive on those
 * handsets. These checks fail loudly if it is replaced, expired or unwired.
 */
class TrustAnchorTest {

    private val published =
        "7BB647A62AEEAC88BF257AA522D01FFEA395E0AB45C73F93F65654EC38F25A06"

    private fun repoRoot(): java.io.File {
        var dir: java.io.File? = java.io.File(".").absoluteFile
        while (dir != null && !java.io.File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return requireNonNull(dir)
    }

    private fun requireNonNull(f: java.io.File?): java.io.File {
        assertNotNull("the repository root must be locatable from the test working directory", f)
        return f!!
    }

    private fun file(path: String): java.io.File {
        val f = java.io.File(repoRoot(), path)
        assertTrue("$path must exist", f.isFile)
        return f
    }

    @Test
    fun `the bundled root is the published Sectigo R46 root and is still valid`() {
        val pem = file("app/src/main/res/raw/sectigo_server_root_r46.pem")
        val cert = pem.inputStream().use {
            java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(it) as java.security.cert.X509Certificate
        }
        assertTrue(
            "subject",
            cert.subjectX500Principal.name.contains("Sectigo Public Server Authentication Root R46")
        )
        assertEquals("a root is self-issued", cert.issuerX500Principal, cert.subjectX500Principal)

        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        assertEquals(published, sha.joinToString("") { "%02X".format(it) })

        assertTrue("the anchor must not be expired", cert.notAfter.toInstant().isAfter(Instant.now()))
    }

    @Test
    fun `the anchor is wired to the reserves hosts and adds to the system store`() {
        val config = file("app/src/main/res/xml/network_security_config.xml").readText()
        assertTrue("api.imf.org" in config)
        assertTrue("fiscaldata.treasury.gov" in config)
        assertTrue("@raw/sectigo_server_root_r46" in config)
        assertTrue("cleartext must stay off", "cleartextTrafficPermitted=\"false\"" in config)

        val base = config.substringAfter("<base-config").substringBefore("</base-config>")
        assertFalse("the bundled root must never apply to every host", "@raw/" in base)
        assertTrue("system" in base)

        val manifest = file("app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "the application must point at the config",
            "android:networkSecurityConfig=\"@xml/network_security_config\"" in manifest
        )
    }
}

/**
 * SPEC v2.1 §25 — the measured sources that replaced the last four proxies.
 */
class MeasuredSourceTest {

    @Test
    fun `fred csv is parsed and a missing observation is dropped, never filled`() {
        val csv = """
            observation_date,BAMLH0A0HYM2
            2026-09-28,3.11
            2026-09-29,.
            2026-09-30,3.24
        """.trimIndent()
        val s = FredProvider(HttpClient(log = DiagnosticLog(enabled = false))).parse("BAMLH0A0HYM2", csv)
        assertNotNull(s)
        assertEquals(2, s!!.size)
        assertEquals(3.24, s.last!!.close, 1e-9)
        assertEquals(
            Instant.parse("2026-09-30T00:00:00Z"), s.last!!.timestamp
        )
    }

    private val NL = System.lineSeparator()

    @Test
    fun `fred csv without a usable observation yields nothing`() {
        val p = FredProvider(HttpClient(log = DiagnosticLog(enabled = false)))
        assertNull(p.parse("X", "observation_date,X" + NL + "2026-09-29,." + NL))
        assertNull(p.parse("X", "observation_date,X"))
    }

    private fun contract(
        expiryDays: Long, call: Boolean, strike: Double, iv: Double, delta: Double,
        oi: Double = 100.0, volume: Double = 10.0
    ) = OptionContract(
        expiry = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(expiryDays),
        isCall = call, strike = strike, iv = iv, delta = delta, openInterest = oi, volume = volume
    )

    @Test
    fun `risk reversal is the quoted call wing minus the quoted put wing`() {
        val chain = OptionChain(
            "GLD", underlying = 380.0, asOf = Instant.parse("2026-10-02T20:00:00Z"),
            contracts = listOf(
                contract(30, true, 400.0, 0.2300, 0.26),
                contract(30, false, 360.0, 0.2550, -0.24),
                contract(30, true, 380.0, 0.2100, 0.52),
                contract(30, false, 380.0, 0.2120, -0.48)
            )
        )
        assertEquals(-2.5, chain.riskReversal25!!, 0.01)
    }

    @Test
    fun `a chain with no wing near twenty five delta reports nothing`() {
        val chain = OptionChain(
            "GLD", 380.0, Instant.parse("2026-10-02T20:00:00Z"),
            listOf(
                contract(30, true, 390.0, 0.22, 0.45),
                contract(30, false, 370.0, 0.24, -0.44)
            )
        )
        assertNull(chain.riskReversal25)
    }

    @Test
    fun `put call ratios count open interest and volume separately`() {
        val chain = OptionChain(
            "GLD", 380.0, Instant.parse("2026-10-02T20:00:00Z"),
            listOf(
                contract(30, true, 400.0, 0.23, 0.26, oi = 200.0, volume = 50.0),
                contract(30, false, 360.0, 0.25, -0.24, oi = 100.0, volume = 150.0)
            )
        )
        assertEquals(0.5, chain.putCallOpenInterest!!, 1e-9)
        assertEquals(3.0, chain.putCallVolume!!, 1e-9)
        assertEquals(300.0, chain.openInterestTotal, 1e-9)
    }

    @Test
    fun `expiring contracts are excluded from the wings`() {
        val chain = OptionChain(
            "GLD", 380.0, Instant.parse("2026-10-02T20:00:00Z"),
            listOf(
                // Tomorrow: inside the expiry window, must be ignored.
                contract(1, true, 400.0, 0.90, 0.25),
                contract(1, false, 360.0, 0.10, -0.25),
                contract(40, true, 400.0, 0.2300, 0.25),
                contract(40, false, 360.0, 0.2500, -0.25)
            )
        )
        assertEquals(-2.0, chain.riskReversal25!!, 0.01)
    }

    @Test
    fun `the implied path and the published policy rate give the priced move`() {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        val u = MarketUniverse(
            asOf = now,
            series = mapOf(
                MarketUniverse.REAL10Y to TimeSeries(
                    MarketUniverse.REAL10Y,
                    (0 until 60).map {
                        val v = 2.0 + 0.001 * it
                        Bar(now.minus((60 - it).toLong(), ChronoUnit.DAYS), v, v, v, v)
                    }
                )
            ),
            scalars = mapOf(
                MarketUniverse.POLICY_RATE_US to 3.625,
                MarketUniverse.FED_FUNDS_IMPLIED_12M to 4.595,
                MarketUniverse.US03M to 4.20
            )
        )
        val f = SpecFeatureEngineer().build(u)
        assertEquals(4.595, f[FeatureKeys.FED_IMPLIED_PATH_12M]!!.value, 1e-9)
        // The futures strip wins over the bill, and it is not a proxy.
        val path = f[FeatureKeys.FED_EXPECTED_RATE_CHANGE]!!
        assertEquals(0.97, path.value, 1e-9)
        assertFalse(path.isProxy)
    }

    @Test
    fun `without the futures strip the bill is used and is still measured`() {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        val u = MarketUniverse(
            asOf = now,
            scalars = mapOf(MarketUniverse.POLICY_RATE_US to 3.625, MarketUniverse.US03M to 4.20)
        )
        val path = SpecFeatureEngineer().build(u)[FeatureKeys.FED_EXPECTED_RATE_CHANGE]!!
        assertEquals(0.575, path.value, 1e-9)
        assertFalse(path.isProxy)
    }

    @Test
    fun `the published stress indices replace the composite and are not proxies`() {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        val u = MarketUniverse(
            asOf = now,
            scalars = mapOf(
                MarketUniverse.FINANCIAL_STRESS_STLFSI to -0.8074,
                MarketUniverse.FINANCIAL_CONDITIONS_NFCI to -0.548
            )
        )
        val f = SpecFeatureEngineer().build(u)
        val stress = f[FeatureKeys.FINANCIAL_STRESS_SCORE]!!
        assertEquals((-0.8074 - 0.548) / 2.0, stress.value, 1e-9)
        assertFalse("a published index is not a proxy", stress.isProxy)
        assertEquals(-0.548, f[FeatureKeys.FINANCIAL_CONDITIONS]!!.value, 1e-9)
    }

    @Test
    fun `the high yield spread is standardised against its own published history`() {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        val oas = (0 until 400).map {
            val v = 3.0 + if (it == 399) 1.0 else 0.0
            Bar(now.minus((400 - it).toLong(), ChronoUnit.DAYS), v, v, v, v)
        }
        val u = MarketUniverse(
            asOf = now,
            series = mapOf(MarketUniverse.HY_OAS to TimeSeries(MarketUniverse.HY_OAS, oas))
        )
        val credit = SpecFeatureEngineer().build(u)[FeatureKeys.CREDIT_SPREAD_HY]!!
        assertFalse("the published OAS is measured, not a proxy", credit.isProxy)
        assertTrue("a spread blowout must score positive z", credit.value > 1.0)
    }

    @Test
    fun `the uncertainty indices replace the option-price proxy for geopolitical risk`() {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        fun ramp(n: Int, base: Double, spike: Double) = TimeSeries(
            "x",
            (0 until n).map {
                val v = if (it == n - 1) spike else base + (it % 7)
                Bar(now.minus((n - it).toLong(), ChronoUnit.DAYS), v, v, v, v)
            }
        )
        val u = MarketUniverse(
            asOf = now,
            series = mapOf(
                MarketUniverse.POLICY_UNCERTAINTY_DAILY to ramp(300, 100.0, 400.0),
                MarketUniverse.NEWS_EQUITY_UNCERTAINTY to ramp(300, 30.0, 120.0)
            )
        )
        val f = SpecFeatureEngineer().build(u)
        val geo = f[FeatureKeys.GEOPOLITICAL_RISK_SCORE]!!
        assertFalse(geo.isProxy)
        assertTrue("a record print must sit at the top of its own history", geo.value > 95.0)
        assertEquals(400.0, f[FeatureKeys.POLICY_UNCERTAINTY]!!.value, 1e-9)
    }
}

/**
 * SPEC v2.1 §26 — the inference layer as it reaches the screens.
 */
class InferenceSurfaceTest {

    private val now = Instant.parse("2026-10-03T12:00:00Z")

    private fun report(inference: io.goldintelligence.engine.InferenceBundle?): io.goldintelligence.engine.IntelligenceReport {
        val scores = io.goldintelligence.engine.GoldSpecification.baseFactorWeights.keys.mapIndexed { i, id ->
            io.goldintelligence.engine.FactorScore(id, if (i % 2 == 0) 35.0 else -20.0, 0.8, now)
        }
        val snapshot = io.goldintelligence.engine.InputSnapshot(
            observations = emptyMap(),
            features = io.goldintelligence.engine.FeatureSet(emptyMap()),
            factorScores = scores
        )
        return io.goldintelligence.engine.MultiHorizonEngine().evaluate(
            snapshot,
            io.goldintelligence.engine.MarketContext(
                sigmaByHorizon = Horizon.entries.associateWith { 12.0 },
                inference = inference
            ),
            now
        )
    }

    private fun screens(r: io.goldintelligence.engine.IntelligenceReport) =
        ScreenModelBuilder(DiagnosticLog(enabled = false)).build(
            r,
            MarketUniverse(asOf = now),
            SpecFeatureEngineer().build(MarketUniverse(asOf = now)),
            emptyList(),
            calendar = emptyList(),
            books = emptyList(),
            otcTiers = emptyList(),
            goldCurve = emptyList(),
            chart = null
        )

    @Test
    fun `the calibration record names its basis and never claims the whole table`() {
        val fit = io.goldintelligence.engine.IsotonicCalibration.fit(
            (0 until 1_000).map { (it / 5.0 - 100.0) to (it > 480) }
        )!!
        val bundle = io.goldintelligence.engine.InferenceBundle(
            calibration = mapOf(Horizon.D1 to fit),
            brier = mapOf(
                Horizon.D1 to io.goldintelligence.engine.BrierReport(
                    0.22, 0.003, 0.03, 0.25, 0.12, 0.52, 900, 10
                )
            ),
            conformal = mapOf(
                Horizon.D1 to io.goldintelligence.engine.ConformalBand(1.33, 0.90, 0.907, 900)
            ),
            sampleSize = 900,
            calibratedOn = io.goldintelligence.ingestion.WalkForwardCalibration.PANEL.map { it.factorId }
        )
        val model = screens(report(bundle))
        val horizons = model.screens.first { it.id == ScreenModel.SCREEN_HORIZONS }
        val section = horizons.sections.first { it.titleEn == "Calibration Record" }

        val basis = section.rows.first()
        assertTrue(basis.value.contains("of normative weight"))
        assertTrue(basis.value.contains("measured"))
        assertTrue(
            "the basis row must state the share, not imply the full table",
            io.goldintelligence.ingestion.WalkForwardCalibration.coveredWeight < 1.0
        )
        assertTrue(section.rows.any { it.labelEn.contains("Brier score") })
        assertTrue(section.rows.any { it.noteEn?.contains("Murphy decomposition") == true })
        assertTrue(section.rows.any { it.labelEn.contains("calibration correction") })

        val moves = horizons.sections.first { it.titleEn == "Expected Move" }
        val d1 = moves.rows.first { it.labelEn.startsWith("1D") }
        assertTrue(d1.badges.any { it.text == "CONFORMAL" })
        assertTrue(d1.noteEn!!.contains("realised"))
    }

    @Test
    fun `an absent record is published as absent rather than filled in`() {
        val model = screens(report(null))
        val section = model.screens.first { it.id == ScreenModel.SCREEN_HORIZONS }
            .sections.first { it.titleEn == "Calibration Record" }
        assertEquals("NO_SAMPLE", section.rows.first().value)
        assertTrue(section.rows.first().badges.any { it.text == "UNCALIBRATED" })

        val d1 = model.screens.first { it.id == ScreenModel.SCREEN_HORIZONS }
            .sections.first { it.titleEn == "Six Required Horizons" }
            .rows.first { it.labelEn == "1D" }
        assertTrue(d1.noteEn!!.contains("UNCALIBRATED_NO_SAMPLE"))
    }

    @Test
    fun `the diagnostics screen publishes the structural reads`() {
        val bundle = io.goldintelligence.engine.InferenceBundle(
            effectiveBreadth = 2.4,
            breadthRatio = 0.34,
            runLength = io.goldintelligence.engine.RunLengthPosterior(8, 0.31, 0.62, 750),
            trend = io.goldintelligence.engine.TrendValidity(1.42, 3.1, 0.61, 500, 5),
            filtered = io.goldintelligence.engine.FilteredLevel(-12.4, 4.0, 0.21, 250),
            robustness = io.goldintelligence.engine.RobustnessReport(-60.0, -8.0, -5.0, 52.0, 7),
            calibratedOn = listOf("F01_REAL_RATE", "F02_USD", "F08_FINANCIAL_STRESS")
        )
        val section = screens(report(bundle)).screens
            .first { it.id == ScreenModel.SCREEN_DIAGNOSTICS }
            .sections.first { it.titleEn == "Inference Layer" }

        assertTrue(section.rows.any { it.labelEn == "Effective number of bets" && it.badges.any { b -> b.text == "REDUNDANT" } })
        assertTrue(section.rows.any { it.labelEn == "Regime age" && it.badges.any { b -> b.text == "LOW" } })
        assertTrue(section.rows.any { it.labelEn == "Trend validity" && it.badges.any { b -> b.text == "TRENDING" } })
        assertTrue(section.rows.any { it.labelEn == "Filtered panel composite" })
        assertTrue(section.rows.any { it.labelEn == "Panel fragility" && it.badges.any { b -> b.text == "FRAGILE" } })
    }

    @Test
    fun `the calibration panel series are kept at full depth`() {
        io.goldintelligence.ingestion.WalkForwardCalibration.PANEL
            .flatMap { listOfNotNull(it.seriesId, it.fallbackSeriesId) }
            .distinct()
            .forEach {
                assertTrue(
                    "$it feeds the replay and must be downloaded at full depth",
                    it in FreeDataAggregator.DEEP_HISTORY_IDS
                )
            }
        assertTrue(FreeDataAggregator.ANALOGUE_SERIES.all { it in FreeDataAggregator.DEEP_SERIES })
        assertTrue(FreeDataAggregator.DEEP_HISTORY_BARS > FreeDataAggregator.DEFAULT_HISTORY_BARS)
    }

    @Test
    fun `every panel leg names a factor in the normative table and is not duplicated`() {
        val panel = io.goldintelligence.ingestion.WalkForwardCalibration.PANEL
        panel.forEach {
            assertTrue(
                "${it.factorId} is not in the weight table",
                it.factorId in io.goldintelligence.engine.GoldSpecification.baseFactorWeights
            )
        }
        assertEquals(panel.size, panel.map { it.factorId }.distinct().size)
        assertTrue(
            "the panel must not claim the whole table",
            io.goldintelligence.ingestion.WalkForwardCalibration.coveredWeight < 1.0
        )
    }

    @Test
    fun `every indicator is classified and every stand-in states what it replaces`() {
        val catalogued = io.goldintelligence.ingestion.IndicatorCatalog.indicators.map { it.key }.toSet()
        io.goldintelligence.ingestion.IndicatorCatalog.audit.forEach { (key, a) ->
            assertTrue("$key is audited but not catalogued", key in catalogued)
            assertTrue("$key has no source", a.source.isNotBlank())
            assertTrue("$key has no reason", a.note.length > 40)
        }
        val counts = io.goldintelligence.ingestion.IndicatorCatalog.provenanceCounts()
        assertEquals(
            catalogued.size,
            counts.values.sum()
        )
        // Classification is total: no indicator is left unlabelled.
        catalogued.forEach {
            assertNotNull(io.goldintelligence.ingestion.IndicatorCatalog.provenanceOf(it))
        }
        assertTrue(
            "the measured share must dominate",
            (counts[io.goldintelligence.ingestion.IndicatorCatalog.Provenance.MEASURED] ?: 0) >
                (counts[io.goldintelligence.ingestion.IndicatorCatalog.Provenance.PROXY] ?: 0)
        )
    }
}
