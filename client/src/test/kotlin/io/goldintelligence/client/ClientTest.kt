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
    fun `screens cover the seven normative surfaces`() {
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
            listOf("STATE", "FACTORS", "INDICATORS", "HORIZONS", "EVENTS", "DIAGNOSTICS", "LOGS"),
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
