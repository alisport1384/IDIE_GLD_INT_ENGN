package io.goldintelligence.client

import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.LogStage
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * SPEC v2.1 §22 — bar assembly.
 *
 * The feed prints the bar that is currently forming on the venue. The store
 * keeps the newest bar of each timeframe open, seals it when the venue moves
 * to the next period, and keeps a bounded history. Seeded bars are rebased
 * once onto the broker's price level and are kept separate from live bars so
 * the two are never confused.
 */
class LiveChartStore(
    private val capacity: Int = 240,
    private val log: DiagnosticLog = DiagnosticLog.shared
) {
    private data class Frame(
        val candles: ArrayDeque<Candle> = ArrayDeque(),
        var seedSource: String? = null,
        var rebaseFactor: Double? = null
    )

    private val frames = ConcurrentHashMap<ChartTimeframe, Frame>()

    /** Bucket start for a timestamp, so a bar is identified by its own period. */
    private fun bucket(at: Instant, tf: ChartTimeframe): Instant =
        Instant.ofEpochSecond(at.epochSecond / tf.seconds * tf.seconds)

    @Synchronized
    fun seed(timeframe: ChartTimeframe, seedCandles: List<Candle>, brokerLast: Double, sourceLabel: String) {
        if (seedCandles.isEmpty()) return
        val frame = frames.getOrPut(timeframe) { Frame() }
        if (frame.candles.any { it.origin == BarOrigin.LIVE } || frame.seedSource != null) return

        val seedLast = seedCandles.last().close
        if (seedLast <= 0.0 || brokerLast <= 0.0) {
            log.warn(
                LogStage.CHART, "LiveChartStore", "REBASE_IMPOSSIBLE",
                "cannot rebase ${timeframe.code}: a reference price is missing",
                key = CHART_KEY + "_" + timeframe.code
            )
            return
        }
        val factor = brokerLast / seedLast
        seedCandles.takeLast(capacity).forEach { c ->
            frame.candles.addLast(
                Candle(
                    time = bucket(c.time, timeframe),
                    open = c.open * factor,
                    high = c.high * factor,
                    low = c.low * factor,
                    close = c.close * factor,
                    origin = BarOrigin.SEED_REBASED
                )
            )
        }
        frame.seedSource = sourceLabel
        frame.rebaseFactor = factor
        trim(frame)
        log.info(
            LogStage.CHART, "LiveChartStore", "SERIES_SEEDED",
            "${frame.candles.size} ${timeframe.code} bars seeded from $sourceLabel, " +
                "rebased by ${"%.6f".format(factor)} onto the broker level",
            key = CHART_KEY + "_" + timeframe.code
        )
    }

    /**
     * Applies the venue's forming bar. The open bar is replaced in place while
     * it is still forming, and sealed the moment the venue starts a new one.
     */
    @Synchronized
    fun apply(timeframe: ChartTimeframe, bar: BrokerFeedProvider.FormingBar, at: Instant) {
        val frame = frames.getOrPut(timeframe) { Frame() }
        val slot = bucket(at, timeframe)
        val candle = Candle(slot, bar.open, bar.high, bar.low, bar.close, BarOrigin.LIVE)
        val head = frame.candles.lastOrNull()
        when {
            head == null || head.time.isBefore(slot) -> {
                if (head != null && head.origin == BarOrigin.LIVE) {
                    log.debug(
                        LogStage.CHART, "LiveChartStore", "BAR_SEALED",
                        "${timeframe.code} bar ${head.time} closed at ${head.close}",
                        key = CHART_KEY + "_" + timeframe.code
                    )
                }
                frame.candles.addLast(candle)
            }

            head.time == slot -> {
                frame.candles.removeLast()
                frame.candles.addLast(candle)
            }

            else -> log.warn(
                LogStage.CHART, "LiveChartStore", "BAR_OUT_OF_ORDER",
                "venue printed ${slot} behind the stored ${head.time}",
                key = CHART_KEY + "_" + timeframe.code
            )
        }
        trim(frame)
    }

    @Synchronized
    fun series(timeframe: ChartTimeframe, symbol: String, venue: String): ChartSeries {
        val frame = frames[timeframe]
        val candles = frame?.candles?.toList().orEmpty()
        return ChartSeries(
            symbol = symbol,
            venue = venue,
            timeframe = timeframe,
            candles = candles,
            seedSource = frame?.seedSource,
            rebaseFactor = frame?.rebaseFactor,
            liveBars = candles.count { it.origin == BarOrigin.LIVE },
            seededBars = candles.count { it.origin == BarOrigin.SEED_REBASED }
        )
    }

    @Synchronized
    fun hasHistory(timeframe: ChartTimeframe): Boolean = !frames[timeframe]?.candles.isNullOrEmpty()

    @Synchronized
    fun clear() = frames.clear()

    private fun trim(frame: Frame) {
        while (frame.candles.size > capacity) frame.candles.removeFirst()
    }
}
