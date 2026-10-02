package io.goldintelligence.client

import java.time.Instant

/**
 * SPEC v2.1 §22 — live chart surface.
 *
 * The chart is drawn by the application itself rather than by an embedded
 * third-party widget, because the product requirement is that the engine's
 * own output is anchored to the price axis: an embedded widget renders in an
 * isolated frame and cannot be annotated. Every bar therefore carries its
 * provenance, and a bar that was not observed on the quoted venue is marked
 * as such instead of being presented as if it were.
 */

/** Where a single bar came from. Never inferred, always recorded. */
enum class BarOrigin {
    /** Assembled from quotes observed on the configured broker feed. */
    LIVE,

    /** Seeded from an independent gold venue and rebased onto the broker's price level. */
    SEED_REBASED
}

data class Candle(
    val time: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val origin: BarOrigin
) {
    val bullish: Boolean get() = close >= open
}

/** Chart timeframes offered by the app, with the venue parameters for each. */
enum class ChartTimeframe(
    val code: String,
    val labelFa: String,
    val seconds: Long,
    /** TradingView column suffix: `close|5`, `open|60`, … Empty means the daily column. */
    val tvSuffix: String,
    /** Kraken OHLC `interval` parameter, in minutes, used for the seed series. */
    val seedMinutes: Int
) {
    M5("5m", "۵ دقیقه", 300, "|5", 5),
    M15("15m", "۱۵ دقیقه", 900, "|15", 15),
    H1("1H", "۱ ساعت", 3_600, "|60", 60),
    H4("4H", "۴ ساعت", 14_400, "|240", 240),
    D1("1D", "۱ روز", 86_400, "", 1_440);

    companion object {
        fun of(code: String): ChartTimeframe? = entries.firstOrNull { it.code.equals(code, true) }
    }
}

/**
 * One timeframe of the chart.
 *
 * [rebaseFactor] is the multiplier applied to the seeded bars so that they sit
 * on the broker's price level; it is published so the adjustment is auditable
 * and is never silently applied.
 */
data class ChartSeries(
    val symbol: String,
    val venue: String,
    val timeframe: ChartTimeframe,
    val candles: List<Candle>,
    val seedSource: String?,
    val rebaseFactor: Double?,
    val liveBars: Int,
    val seededBars: Int
) {
    val isEmpty: Boolean get() = candles.isEmpty()
    val last: Candle? get() = candles.lastOrNull()
}

/** A horizontal line the app draws on the price axis. */
enum class LevelKind { SPOT, BID, ASK, INVALIDATION, EXPECTED_MOVE, BAND_HIGH, BAND_LOW, BENCHMARK }

data class PriceLevel(
    val price: Double,
    val labelFa: String,
    val labelEn: String,
    val kind: LevelKind
)

/** One field of the published state, compared against the previous refresh. */
data class AnalysisDelta(
    val key: String,
    val labelFa: String,
    val labelEn: String,
    val previous: String?,
    val current: String,
    /** +1 improved / rose, −1 deteriorated / fell, 0 unchanged or not ordered. */
    val direction: Int
) {
    val changed: Boolean get() = previous != null && previous != current
    val firstObservation: Boolean get() = previous == null
}

/** Which rule produced a second, competing reading of the same market. */
enum class DualKind {
    /** Short horizons disagree with long horizons. */
    HORIZON_SPLIT,

    /** Factor scores point both ways at once. */
    FACTOR_CONFLICT
}

data class AnalysisBranch(
    val labelFa: String,
    val labelEn: String,
    val direction: String,
    val strength: Double,
    val detailFa: String,
    val detailEn: String
)

/**
 * Two readings that are both defensible from the same snapshot. Published
 * side by side; the engine does not pick a winner it cannot justify.
 */
data class DualAnalysis(
    val kind: DualKind,
    val primary: AnalysisBranch,
    val secondary: AnalysisBranch,
    val reasonFa: String,
    val reasonEn: String
)

/** A failure or degradation that must be visible on the chart itself. */
data class ChartException(
    val code: String,
    val component: String,
    val messageFa: String,
    val messageEn: String,
    val severity: String,
    val at: Instant
)

/**
 * Everything the chart surface needs: the series, the levels anchored to the
 * price axis, what changed since the previous refresh, any competing reading,
 * and any exception that would otherwise be invisible to the user.
 */
data class ChartPayload(
    val symbol: String,
    val venue: String,
    val venueLabel: String,
    val quote: LiveQuote?,
    val timeframe: ChartTimeframe,
    val series: ChartSeries,
    val levels: List<PriceLevel>,
    val deltas: List<AnalysisDelta>,
    val dual: List<DualAnalysis>,
    val exceptions: List<ChartException>,
    val headline: ChartHeadline,
    val asOf: Instant
)

/** The engine's conclusion in the shortest form that is still complete. */
data class ChartHeadline(
    val direction: String,
    val bias: Double?,
    val confidence: Double?,
    val regime: String,
    val signalState: String,
    val probabilityStatus: String,
    val killSwitch: String?,
    val horizon: String
)

/** Live broker quote behind the chart. */
data class LiveQuote(
    val symbol: String,
    val venue: String,
    val last: Double,
    val bid: Double?,
    val ask: Double?,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val changePct: Double?,
    val changeAbs: Double?,
    val volume: Double?,
    val updateMode: String?,
    val quotedAt: Instant,
    val receivedAt: Instant
) {
    val spreadBp: Double?
        get() {
            val b = bid ?: return null
            val a = ask ?: return null
            val mid = (a + b) / 2.0
            return if (mid > 0) (a - b) / mid * 10_000.0 else null
        }
}
