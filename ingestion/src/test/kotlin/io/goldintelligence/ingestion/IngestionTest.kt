package io.goldintelligence.ingestion

import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.FactorCatalog
import io.goldintelligence.engine.InputSnapshot
import io.goldintelligence.engine.StrictDataValidator
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeSynchronizerTest {

    private val asOf = Instant.parse("2026-08-24T12:00:00Z")
    private val synchronizer = TimeSynchronizer(newsLookback = Duration.ofHours(24))

    @Test
    fun `future ticks are excluded to prevent look-ahead`() {
        val futureTick = RawMarketTick(
            symbol = "GC", price = 2500.0,
            exchangeTimestamp = asOf.plusSeconds(60),
            ingestTimestamp = asOf.plusSeconds(60),
            source = "TEST"
        )
        val frame = synchronizer.sync(asOf, marketTicks = listOf(futureTick))
        assertTrue(frame.marketTicks.isEmpty())
    }

    @Test
    fun `latest tick at or before asOf is kept per symbol`() {
        val older = RawMarketTick(
            symbol = "GC", price = 2490.0,
            exchangeTimestamp = asOf.minusSeconds(120),
            ingestTimestamp = asOf.minusSeconds(120),
            source = "TEST"
        )
        val newer = RawMarketTick(
            symbol = "GC", price = 2495.0,
            exchangeTimestamp = asOf.minusSeconds(10),
            ingestTimestamp = asOf.minusSeconds(10),
            source = "TEST"
        )
        val frame = synchronizer.sync(asOf, marketTicks = listOf(older, newer))
        assertEquals(2495.0, frame.marketTicks["GC"]!!.price, 1e-9)
    }

    @Test
    fun `macro release published after asOf is not visible yet`() {
        val notYetReleased = RawMacroRelease(
            seriesId = "CPI",
            actual = 3.1,
            releaseTimestamp = asOf.plusSeconds(3600),
            ingestTimestamp = asOf.plusSeconds(3600),
            source = "TEST"
        )
        val frame = synchronizer.sync(asOf, macroReleases = listOf(notYetReleased))
        assertNull(frame.macro["CPI"])
    }

    @Test
    fun `news outside the lookback window is dropped`() {
        val stale = RawNewsItem(
            headline = "old",
            publishTimestamp = asOf.minus(Duration.ofHours(48)),
            ingestTimestamp = asOf.minus(Duration.ofHours(48)),
            source = "TEST"
        )
        val fresh = RawNewsItem(
            headline = "new",
            publishTimestamp = asOf.minus(Duration.ofHours(1)),
            ingestTimestamp = asOf.minus(Duration.ofHours(1)),
            source = "TEST"
        )
        val frame = synchronizer.sync(asOf, newsItems = listOf(stale, fresh))
        assertEquals(listOf("new"), frame.news.map { it.headline })
    }
}

class SnapshotAdapterTest {

    private val asOf = Instant.parse("2026-08-24T12:00:00Z")
    private val adapter = SnapshotAdapter()

    @Test
    fun `market spot and macro map to validator-passing observations`() {
        val frame = SyncedFrame(
            asOf = asOf,
            marketTicks = mapOf(
                "GC" to RawMarketTick(
                    symbol = "GC", price = 2500.0,
                    exchangeTimestamp = asOf, ingestTimestamp = asOf, source = "Databento"
                )
            ),
            spot = RawSpotQuote(
                price = 2498.0, exchangeTimestamp = asOf, ingestTimestamp = asOf, source = "SpotRef"
            ),
            macro = mapOf(
                "US10Y_REAL_YIELD" to RawMacroRelease(
                    seriesId = "US10Y_REAL_YIELD", actual = 1.85, expected = 1.90,
                    releaseTimestamp = asOf, ingestTimestamp = asOf, source = "Treasury"
                )
            ),
            news = emptyList()
        )

        val snapshot = adapter.toInputSnapshot(frame)

        assertTrue(StrictDataValidator().validate(snapshot))
        assertEquals(2500.0, snapshot.observations["GC"]!!.value, 1e-9)
        assertEquals(2498.0, snapshot.observations["GOLD_SPOT"]!!.value, 1e-9)
        assertEquals(1.85, snapshot.observations["US10Y_REAL_YIELD"]!!.value, 1e-9)
        assertEquals(1.90, snapshot.observations["US10Y_REAL_YIELD"]!!.expected!!, 1e-9)
    }

    @Test
    fun `macro release with no actual value yet is omitted, not fabricated`() {
        val frame = SyncedFrame(
            asOf = asOf,
            marketTicks = emptyMap(),
            spot = null,
            macro = mapOf(
                "NFP" to RawMacroRelease(
                    seriesId = "NFP", actual = null, expected = 180.0,
                    releaseTimestamp = asOf, ingestTimestamp = asOf, source = "BLS"
                )
            ),
            news = emptyList()
        )
        val snapshot = adapter.toInputSnapshot(frame)
        assertTrue(snapshot.observations.isEmpty())
    }

    @Test
    fun `news is not silently scored without a scoring adapter`() {
        val frame = SyncedFrame(
            asOf = asOf,
            marketTicks = emptyMap(),
            spot = null,
            macro = emptyMap(),
            news = listOf(
                RawNewsItem(
                    headline = "Fed signals pause",
                    publishTimestamp = asOf,
                    ingestTimestamp = asOf,
                    source = "TEST"
                )
            )
        )
        val snapshot = adapter.toInputSnapshot(frame)
        assertTrue(snapshot.news.isEmpty())
    }

    /** A duplicated catalogue key renders the same indicator twice on screen. */
    @Test
    fun `indicator catalogue has no duplicate keys`() {
        val duplicates = IndicatorCatalog.indicators
            .groupBy { it.key }
            .filterValues { it.size > 1 }
            .keys
        assertTrue("duplicated indicator keys: $duplicates", duplicates.isEmpty())
        assertEquals(IndicatorCatalog.indicators.size, IndicatorCatalog.byKey.size)
    }

    /** Every catalogued indicator must name a factor the engine actually scores. */
    @Test
    fun `every catalogued indicator belongs to a declared factor`() {
        val declared = FactorCatalog.factors.map { it.id }.toSet()
        val unknown = IndicatorCatalog.indicators.map { it.factorId }.filterNot { it in declared }.distinct()
        assertTrue("indicators attached to unknown factors: $unknown", unknown.isEmpty())
    }
}

/**
 * SPEC v2.1 §24 — the analogue matcher. The fixtures below are synthetic but
 * shaped like the real downloads: ~5 years of daily bars for gold and the
 * conditions it is described by.
 */
class SeriesAnalogueEngineTest {

    private val start = Instant.parse("2006-01-02T00:00:00Z")

    private fun bars(n: Int, f: (Int) -> Double): List<Bar> = (0 until n).map { i ->
        val c = f(i)
        Bar(start.plus(Duration.ofDays(i.toLong())), c, c, c, c)
    }

    /** A history that repeats itself, so a close match provably exists. */
    private fun cyclical(n: Int, phase: Double, amplitude: Double, period: Double) =
        bars(n) { i -> 100.0 + amplitude * kotlin.math.sin(2 * Math.PI * (i + phase) / period) }

    private fun universe(n: Int, withConditions: Boolean = true): MarketUniverse {
        val series = mutableMapOf(
            MarketUniverse.GOLD_PROXY_ETF to TimeSeries("GLD", cyclical(n, 0.0, 12.0, 250.0))
        )
        if (withConditions) {
            series[MarketUniverse.EQUITY_ETF] = TimeSeries("SPY", cyclical(n, 40.0, 9.0, 250.0))
            series[MarketUniverse.LONG_BOND_ETF] = TimeSeries("TLT", cyclical(n, 90.0, 6.0, 250.0))
            series[MarketUniverse.DOLLAR_ETF] = TimeSeries("UUP", cyclical(n, 125.0, 4.0, 250.0))
            series[MarketUniverse.VIX] = TimeSeries("VIX", cyclical(n, 60.0, 5.0, 250.0))
        }
        return MarketUniverse(asOf = start.plus(Duration.ofDays(n.toLong())), series = series)
    }

    @Test
    fun `no universe yields no analogue rather than an invented one`() {
        val found = SeriesAnalogueEngine(DiagnosticLog(enabled = false)).find(InputSnapshot.empty())
        assertTrue(found.isEmpty())
    }

    @Test
    fun `history shorter than the minimum yields nothing`() {
        val engine = SeriesAnalogueEngine(DiagnosticLog(enabled = false))
        engine.universe = universe(SeriesAnalogueEngine.MIN_HISTORY - 1)
        assertTrue(engine.find(InputSnapshot.empty()).isEmpty())
    }

    @Test
    fun `too few conditions yields nothing rather than a weak match`() {
        val engine = SeriesAnalogueEngine(DiagnosticLog(enabled = false))
        engine.universe = universe(1500, withConditions = false)
        assertTrue(engine.find(InputSnapshot.empty()).isEmpty())
    }

    @Test
    fun `matches are real dates carrying the return that actually followed`() {
        val engine = SeriesAnalogueEngine(DiagnosticLog(enabled = false))
        val u = universe(1800)
        engine.universe = u
        val found = engine.find(InputSnapshot.empty())

        assertTrue(found.isNotEmpty())
        assertTrue(found.size <= SeriesAnalogueEngine.MATCHES)

        val closes = u.seriesOf(MarketUniverse.GOLD_PROXY_ETF)!!.bars
        engine.lastMatches.forEach { m ->
            val index = closes.indexOfFirst {
                it.timestamp.atZone(java.time.ZoneOffset.UTC).toLocalDate() == m.date
            }
            assertTrue("match is a real session", index >= 0)
            val from = closes[index].close
            val to = closes[index + SeriesAnalogueEngine.FORWARD_DAYS].close
            assertEquals((to / from - 1.0) * 100.0, m.forwardReturnPct, 1e-9)
            assertTrue(m.similarity > 0.0 && m.similarity <= 1.0)
        }
    }

    @Test
    fun `matches do not overlap so one episode cannot be counted twice`() {
        val engine = SeriesAnalogueEngine(DiagnosticLog(enabled = false))
        engine.universe = universe(2500)
        engine.find(InputSnapshot.empty())
        val dates = engine.lastMatches.map { it.date }.sorted()
        dates.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "separation",
                java.time.temporal.ChronoUnit.DAYS.between(a, b) >= SeriesAnalogueEngine.MIN_SEPARATION_DAYS
            )
        }
    }

    @Test
    fun `the lookback of the current window is excluded from the candidates`() {
        val engine = SeriesAnalogueEngine(DiagnosticLog(enabled = false))
        val u = universe(1800)
        engine.universe = u
        engine.find(InputSnapshot.empty())
        val lastDate = u.seriesOf(MarketUniverse.GOLD_PROXY_ETF)!!.bars.last()
            .timestamp.atZone(java.time.ZoneOffset.UTC).toLocalDate()
        engine.lastMatches.forEach { m ->
            assertTrue(
                java.time.temporal.ChronoUnit.DAYS.between(m.date, lastDate) >
                    SeriesAnalogueEngine.FORWARD_DAYS
            )
        }
    }
}
