package io.goldintelligence.ingestion

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
        assertEquals(1.90, snapshot.observations["US10Y_REAL_YIELD"]!!.expected, 1e-9)
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
}
