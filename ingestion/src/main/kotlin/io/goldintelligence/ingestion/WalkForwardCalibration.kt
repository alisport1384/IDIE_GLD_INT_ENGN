package io.goldintelligence.ingestion

import io.goldintelligence.engine.BrierDecomposition
import io.goldintelligence.engine.BrierReport
import io.goldintelligence.engine.ChangepointDetector
import io.goldintelligence.engine.CombinationReport
import io.goldintelligence.engine.ConformalBand
import io.goldintelligence.engine.ConformalInterval
import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.EffectiveBreadth
import io.goldintelligence.engine.ExpectedMoveEngine
import io.goldintelligence.engine.ForecastCombination
import io.goldintelligence.engine.GoldSpecification
import io.goldintelligence.engine.Horizon
import io.goldintelligence.engine.InferenceBundle
import io.goldintelligence.engine.InformationCoefficient
import io.goldintelligence.engine.IsotonicCalibration
import io.goldintelligence.engine.LegInformation
import io.goldintelligence.engine.LocalLevelFilter
import io.goldintelligence.engine.LogStage
import io.goldintelligence.engine.MondrianConformal
import io.goldintelligence.engine.MondrianConformalInterval
import io.goldintelligence.engine.RidgeLogistic
import io.goldintelligence.engine.RobustAggregate
import io.goldintelligence.engine.TrendValidityTest
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * SPEC v2.1 §26 and §27 — walk-forward calibration over the measured factor
 * panel.
 *
 * §D2 of the specification records the factor weights as *initial priors, to
 * be re-estimated by walk-forward calibration once a record exists*, and §19
 * withholds every numeric probability until one does.
 *
 * §26 built that record from seven price stand-ins — ETFs behaving roughly
 * like the factors they replaced — covering 0.440 of the normative weight.
 * §27 replaces the stand-ins with **the published series themselves**: the
 * ten-year real yield rather than a TIPS fund, the Federal Reserve's broad
 * dollar index rather than a dollar ETF, the ten-year breakeven, the policy
 * -uncertainty index, the VIX close, the WTI spot price, the two-year and
 * ten-year Treasury yields and the 10y−2y spread. All are keyless daily FRED
 * series with twenty-plus years of history, and together they reproduce
 * [coveredWeight] of the normative weight.
 *
 * The replay itself is anchored walk-forward: every fit sees only what was
 * known at its own origin, and every scored session lies forward of it.
 *
 * §27 also adds a second forecaster and a sharper interval:
 *
 *  - a **ridge logistic** on the whole standardised panel, which can see
 *    joint structure the single weighted composite cannot. It does not touch
 *    the normative weight table; it is a separate model whose probability is
 *    combined with the isotonic one, and only if its own out-of-sample skill
 *    is positive;
 *  - a **Mondrian conformal** band conditioned on the trailing-volatility
 *    tercile, so the published interval is narrow in a calm tape and wide in
 *    a violent one instead of being wrong in both;
 *  - a per-leg **information coefficient**, so the diagnostics screen reports
 *    which factor actually carried information rather than only how much
 *    weight it was assigned.
 *
 * Nothing here invents a number. A horizon that cannot be replayed from daily
 * bars is absent from the result and stays uncalibrated.
 */
class WalkForwardCalibration(
    private val log: DiagnosticLog = DiagnosticLog.shared
) {

    /** Set by the ingestion client before each evaluation. */
    @Volatile
    var universe: MarketUniverse? = null

    /** How a leg's raw series becomes a ±100 score. */
    enum class Transform {
        /** Percentage change over the window — price and index series. */
        RETURN,

        /** Change in level over the window — rates, spreads, yields. */
        DIFFERENCE,

        /** Standardised level against its own trailing window — indices. */
        ZSCORE
    }

    /**
     * One leg of the replay panel: which factor it reconstructs, from which
     * published series, how, and which way round.
     *
     * [scale] is the move that saturates the ±100 factor scale, matching the
     * convention [Stats.ratioToScore] uses live. [fallbackSeriesId] is the
     * §26 price stand-in, used only when the measured series is unavailable;
     * which one was used is published.
     */
    data class Leg(
        val factorId: String,
        val seriesId: String,
        val transform: Transform,
        val window: Int,
        val scale: Double,
        val sign: Double,
        val note: String,
        val fallbackSeriesId: String? = null,
        val fallbackTransform: Transform = Transform.RETURN,
        val fallbackScale: Double = scale,
        val fallbackSign: Double = sign
    )

    /** What a leg resolved to this cycle. */
    private data class Resolved(
        val leg: Leg,
        val seriesId: String,
        val measured: Boolean,
        val column: DoubleArray
    )

    fun run(): InferenceBundle {
        val u = universe ?: return empty("NO_UNIVERSE", "no market universe was handed to the calibrator")
        val gold = u.seriesOf(MarketUniverse.GOLD_PROXY_ETF)
            ?: u.seriesOf(MarketUniverse.GOLD_SPOT_HISTORY)
            ?: return empty("NO_GOLD_HISTORY", "no gold history to calibrate against")

        val closes = gold.closes
        if (closes.size < MIN_HISTORY) {
            return empty("HISTORY_TOO_SHORT", "${closes.size} sessions, $MIN_HISTORY required")
        }
        val n = closes.size
        val dates = gold.bars.map { it.timestamp.atZone(ZoneOffset.UTC).toLocalDate() }

        /* ---- panel -------------------------------------------------- */
        val resolved = LinkedHashMap<String, Resolved>()
        for (leg in PANEL) {
            val primary = alignAsOf(u.seriesOf(leg.seriesId), dates)
            val built = primary?.let { scoreColumn(it, leg.transform, leg.window, leg.scale, leg.sign) }
            if (built != null) {
                resolved[leg.factorId] = Resolved(leg, leg.seriesId, measured = true, column = built)
                continue
            }
            val fb = leg.fallbackSeriesId
            val fallback = fb?.let { alignAsOf(u.seriesOf(it), dates) }
            val builtFallback = fallback?.let {
                scoreColumn(it, leg.fallbackTransform, leg.window, leg.fallbackScale, leg.fallbackSign)
            }
            if (builtFallback != null) {
                resolved[leg.factorId] = Resolved(leg, fb, measured = false, column = builtFallback)
                log.debug(
                    LogStage.FEATURE, SOURCE, "LEG_FELL_BACK",
                    "${leg.factorId}: ${leg.seriesId} unusable, replayed on the $fb stand-in", key = KEY
                )
                continue
            }
            log.debug(
                LogStage.FEATURE, SOURCE, "LEG_SKIPPED",
                "${leg.factorId}: neither ${leg.seriesId} nor ${fb ?: "any stand-in"} is usable", key = KEY
            )
        }
        if (resolved.size < MIN_LEGS) {
            return empty("TOO_FEW_LEGS", "${resolved.size} usable legs, $MIN_LEGS required")
        }

        val weights = resolved.keys.associateWith { GoldSpecification.baseFactorWeights[it] ?: 0.0 }
        val covered = weights.values.sum()
        if (covered <= 0.0) return empty("NO_WEIGHT", "the usable legs carry no normative weight")
        val measuredWeight = resolved.values.filter { it.measured }
            .sumOf { GoldSpecification.baseFactorWeights[it.leg.factorId] ?: 0.0 }
        val columns = resolved.mapValues { it.value.column }

        // Composite on the engine's own scale: weighted mean over the legs
        // populated at that session, renormalised to the weight present.
        val composite = DoubleArray(n) { Double.NaN }
        for (i in 0 until n) {
            var acc = 0.0
            var w = 0.0
            for ((id, col) in columns) {
                val v = col[i]
                if (v.isNaN()) continue
                val wi = weights.getValue(id)
                acc += v * wi
                w += wi
            }
            if (w >= covered * MIN_WEIGHT_PRESENT) composite[i] = (acc / w).coerceIn(-100.0, 100.0)
        }

        /* ---- replay ------------------------------------------------- */
        val calibration = LinkedHashMap<Horizon, IsotonicCalibration>()
        val brier = LinkedHashMap<Horizon, BrierReport>()
        val conformal = LinkedHashMap<Horizon, ConformalBand>()
        val mondrian = LinkedHashMap<Horizon, MondrianConformal>()
        val combination = LinkedHashMap<Horizon, CombinationReport>()
        val legInfo = LinkedHashMap<Horizon, List<LegInformation>>()
        var scored = 0
        var panelModel: RidgeLogistic? = null
        var panelOrder: List<String> = emptyList()

        val legIds = columns.keys.toList()

        for ((horizon, step) in FORWARD_STEPS) {
            val rows = ArrayList<Row>(n)
            for (i in VOL_WINDOW until (n - step)) {
                val x = composite[i]
                if (x.isNaN()) continue
                val from = closes[i]
                val to = closes[i + step]
                if (from <= 0.0 || to <= 0.0) continue
                val sigma = trailingVolPct(closes, i, VOL_WINDOW) ?: continue
                if (sigma <= 0.0) continue
                // Both forecasters must see the same sessions, or their
                // scores are not comparable and the combination weights mean
                // nothing. A session is replayable only when every resolved
                // leg is populated for it.
                val panel = DoubleArray(legIds.size) { k -> columns.getValue(legIds[k])[i] }
                if (panel.any { it.isNaN() }) continue
                rows += Row(
                    score = x,
                    panel = panel,
                    forwardPct = (to / from - 1.0) * 100.0,
                    sigmaPct = sigma * sqrt(step.toDouble()),
                    volPct = sigma
                )
            }
            if (rows.size < MIN_ROWS) {
                log.warn(
                    LogStage.FEATURE, SOURCE, "HORIZON_NOT_CALIBRATED",
                    "${horizon.code}: ${rows.size} sessions with a complete panel, $MIN_ROWS required",
                    key = KEY
                )
                continue
            }

            // Anchored walk-forward. Every fit sees only what was known at its
            // own origin; every scored session lies forward of that origin.
            val outcomes = ArrayList<Boolean>(rows.size)
            val isotonicP = ArrayList<Double>(rows.size)
            val ridgeP = ArrayList<Double>(rows.size)
            val conditioning = ArrayList<Double>(rows.size)
            val scoreOut = ArrayList<Double>(rows.size)
            val forwardOut = ArrayList<Double>(rows.size)
            val sigmaOut = ArrayList<Double>(rows.size)
            var ridgeComplete = true
            var latestIsotonic: IsotonicCalibration? = null
            var latestRidge: RidgeLogistic? = null
            var fits = 0

            var origin = minOf(WARMUP, rows.size / 2)
            while (origin + REFIT_EVERY <= rows.size) {
                val train = rows.subList(0, origin)
                val fit = IsotonicCalibration.fit(train.map { it.score to (it.forwardPct > 0.0) })
                if (fit == null) {
                    origin += REFIT_EVERY
                    continue
                }
                latestIsotonic = fit
                fits++

                val ridge = if (train.size >= RidgeLogistic.MIN_SAMPLE) {
                    RidgeLogistic.fit(train.map { it.panel }, train.map { it.forwardPct > 0.0 })
                } else {
                    null
                }
                if (ridge != null) latestRidge = ridge

                val block = rows.subList(origin, minOf(rows.size, origin + REFIT_EVERY))
                for (r in block) {
                    val pIso = fit.probability(r.score)
                    outcomes += r.forwardPct > 0.0
                    isotonicP += pIso
                    scoreOut += r.score
                    forwardOut += r.forwardPct
                    sigmaOut += r.sigmaPct
                    conditioning += r.volPct
                    val pRidge = ridge?.probability(r.panel)
                    if (pRidge == null) ridgeComplete = false else ridgeP += pRidge
                }
                origin += REFIT_EVERY
            }

            val fit = latestIsotonic
            if (fit == null || outcomes.size < MIN_TEST) {
                log.warn(
                    LogStage.FEATURE, SOURCE, "HORIZON_NOT_CALIBRATED",
                    "${horizon.code}: ${outcomes.size} out-of-sample sessions from $fits refits, " +
                        "$MIN_TEST required",
                    key = KEY
                )
                continue
            }

            // Combination: the isotonic map on the normative composite, and
            // the ridge model on the panel. A member enters only on positive
            // out-of-sample skill.
            val candidates = LinkedHashMap<String, List<Double>>()
            candidates[MEMBER_ISOTONIC] = isotonicP
            if (ridgeComplete && ridgeP.size == outcomes.size) candidates[MEMBER_RIDGE] = ridgeP
            val combined = ForecastCombination.evaluate(candidates, outcomes)

            // The published record is the combination's when it has a member,
            // and the isotonic one otherwise — never a figure nothing scored.
            val pooled = combined?.second?.takeIf { it.isNotEmpty() }
            val graded = (pooled ?: isotonicP).zip(outcomes)
            val report = BrierDecomposition.evaluate(graded) ?: continue

            val residuals = graded.indices.map { i ->
                val p = graded[i].first
                val predicted = ExpectedMoveEngine.k(horizon) * sigmaOut[i] * (2.0 * p - 1.0)
                abs(forwardOut[i] - predicted) / sigmaOut[i]
            }
            val band = ConformalInterval.band(residuals, ALPHA)
            val conditional = MondrianConformalInterval.band(residuals, conditioning, ALPHA)

            calibration[horizon] = fit
            brier[horizon] = report
            if (band != null) conformal[horizon] = band
            if (conditional != null) mondrian[horizon] = conditional
            combined?.first?.let { combination[horizon] = it }
            scored = maxOf(scored, outcomes.size)
            if (horizon == PANEL_MODEL_HORIZON) {
                panelModel = latestRidge
                panelOrder = legIds
            }

            // Which leg actually carried information over the scored window.
            legInfo[horizon] = legIds.mapNotNull { id ->
                val col = columns.getValue(id)
                val s = ArrayList<Double>(outcomes.size)
                val f = ArrayList<Double>(outcomes.size)
                var k = 0
                var o = minOf(WARMUP, rows.size / 2)
                // Rebuild the scored index set the same way the loop above did.
                while (o + REFIT_EVERY <= rows.size && k < outcomes.size) {
                    val upto = minOf(rows.size, o + REFIT_EVERY)
                    for (idx in o until upto) {
                        if (k >= outcomes.size) break
                        s += rows[idx].legScore(legIds.indexOf(id))
                        f += rows[idx].forwardPct
                        k++
                    }
                    o += REFIT_EVERY
                }
                InformationCoefficient.evaluate(id, s, f)
            }.sortedByDescending { abs(it.rankCorrelation) }

            log.info(
                LogStage.FEATURE, SOURCE, "HORIZON_CALIBRATED",
                ("%s: %d anchored refits from a %d-session warm-up, %d sessions scored forward · " +
                    "Brier %.4f (rel %.4f, res %.4f, unc %.4f) · skill %+.4f · base rate %.3f · %s%s%s")
                    .format(
                        horizon.code, fits, WARMUP, outcomes.size, report.brier,
                        report.reliability, report.resolution, report.uncertainty,
                        report.skill, report.baseRate,
                        combined?.first?.let { c ->
                            "members " + c.members.joinToString(", ") { m ->
                                "%s skill %+.4f%s".format(m.name, m.skill, if (m.used) " w=%.2f".format(m.weight) else " unused")
                            }
                        } ?: "single member",
                        band?.let {
                            " · conformal ±%.2fσ at %.0f%% nominal, %.1f%% realised"
                                .format(it.halfWidth, it.targetCoverage * 100, it.realisedCoverage?.times(100) ?: 0.0)
                        } ?: " · conformal band refused",
                        conditional?.buckets?.takeIf { it.isNotEmpty() }?.let { b ->
                            " · conditional " + b.joinToString(", ") { x ->
                                "%s ±%.2fσ (%.1f%%)".format(x.label, x.band.halfWidth, (x.band.realisedCoverage ?: 0.0) * 100)
                            }
                        } ?: ""
                    ),
                key = KEY
            )
        }

        /* ---- structure of the evidence ------------------------------ */
        val recent = minOf(n, BREADTH_WINDOW)
        val breadthInput = columns.mapValues { (_, col) ->
            col.takeLast(recent).filter { !it.isNaN() }
        }.filterValues { it.size >= EffectiveBreadth.MIN_OBSERVATIONS }
        val breadth = EffectiveBreadth.of(breadthInput)
        val breadthRatio = if (breadth == null || breadthInput.isEmpty()) null
        else (breadth / breadthInput.size).coerceIn(0.0, 1.0)

        val returns = ArrayList<Double>(n)
        for (i in 1 until n) {
            val a = closes[i - 1]
            val b = closes[i]
            if (a > 0.0 && b > 0.0) returns += ln(b / a)
        }
        val runLength = ChangepointDetector().process(returns.takeLast(CHANGEPOINT_WINDOW))
        val trend = TrendValidityTest.evaluate(closes.takeLast(TREND_WINDOW))
        val filtered = LocalLevelFilter.run(composite.takeLast(FILTER_WINDOW).filter { !it.isNaN() })
        val lastIndex = composite.indexOfLast { !it.isNaN() }
        val robustness = if (lastIndex < 0) null else RobustAggregate.evaluate(
            weighted = composite[lastIndex],
            scores = columns.values.mapNotNull { col -> col[lastIndex].takeIf { !it.isNaN() } }
        )

        // The live panel row, so the engine can evaluate the ridge member on
        // today's reading with the model fitted on everything before it.
        val livePanel = if (lastIndex < 0) null else panelOrder
            .map { columns.getValue(it)[lastIndex] }
            .takeIf { row -> row.isNotEmpty() && row.all { !it.isNaN() } }
        val liveRidge = if (livePanel == null) null else panelModel?.probability(livePanel.toDoubleArray())

        log.info(
            LogStage.FEATURE, SOURCE, "CALIBRATION_COMPLETE",
            ("%d sessions · %d legs covering %.3f of the normative weight (%.3f measured) · " +
                "%d horizons calibrated · breadth %s of %d · regime age %s · tape %s")
                .format(
                    n, resolved.size, covered, measuredWeight, calibration.size,
                    breadth?.let { "%.2f".format(it) } ?: "n/a",
                    breadthInput.size,
                    runLength?.let { "%d sessions, P(change) %.3f".format(it.mapRunLength, it.changeProbability) }
                        ?: "n/a",
                    trend?.let { "%s (VR %.3f, z %+.2f)".format(it.label, it.varianceRatio, it.zStatistic) } ?: "n/a"
                ),
            key = KEY
        )

        return InferenceBundle(
            calibration = calibration,
            brier = brier,
            conformal = conformal,
            mondrian = mondrian,
            combination = combination,
            legInformation = legInfo,
            effectiveBreadth = breadth,
            breadthRatio = breadthRatio,
            runLength = runLength,
            trend = trend,
            filtered = filtered,
            robustness = robustness,
            sampleSize = scored,
            calibratedOn = resolved.keys.toList(),
            measuredOn = resolved.values.filter { it.measured }.map { it.leg.factorId },
            coveredWeight = covered,
            measuredWeight = measuredWeight,
            liveRidgeProbability = liveRidge,
            liveVolatilityPct = if (lastIndex < 0) null else trailingVolPct(closes, lastIndex, VOL_WINDOW)
        )
    }

    /** One replayable session: what was known, and what happened next. */
    private data class Row(
        val score: Double,
        val panel: DoubleArray,
        val forwardPct: Double,
        val sigmaPct: Double,
        val volPct: Double
    ) {
        fun legScore(index: Int): Double =
            if (index < 0 || index >= panel.size) Double.NaN else panel[index]
    }

    private fun empty(code: String, message: String): InferenceBundle {
        log.warn(LogStage.FEATURE, SOURCE, code, message, key = KEY)
        return InferenceBundle()
    }

    /**
     * As-of join onto the gold session calendar: each session takes the last
     * observation published on or before its own date. Positional alignment
     * would be wrong here — the macro series keep a different calendar from
     * the exchange — and taking a later observation would be look-ahead.
     *
     * [MAX_STALENESS_DAYS] caps how old an observation may be before the
     * session is left empty rather than carried forward indefinitely.
     */
    private fun alignAsOf(series: TimeSeries?, dates: List<LocalDate>): DoubleArray? {
        if (series == null) return null
        val bars = series.bars
        if (bars.size < MIN_SERIES) return null
        val out = DoubleArray(dates.size) { Double.NaN }
        var cursor = 0
        var lastValue = Double.NaN
        var lastDate: LocalDate? = null
        for (i in dates.indices) {
            val d = dates[i]
            while (cursor < bars.size) {
                val bd = bars[cursor].timestamp.atZone(ZoneOffset.UTC).toLocalDate()
                if (bd > d) break
                val v = bars[cursor].close
                if (v.isFinite()) {
                    lastValue = v
                    lastDate = bd
                }
                cursor++
            }
            val age = lastDate?.let { java.time.temporal.ChronoUnit.DAYS.between(it, d) }
            out[i] = if (age != null && age <= MAX_STALENESS_DAYS) lastValue else Double.NaN
        }
        return out
    }

    /** Trailing change over the window, mapped onto the ±100 factor scale. */
    private fun scoreColumn(
        values: DoubleArray,
        transform: Transform,
        window: Int,
        scale: Double,
        sign: Double
    ): DoubleArray? {
        if (values.size <= window) return null
        val out = DoubleArray(values.size) { Double.NaN }
        var populated = 0
        for (i in window until values.size) {
            val a = values[i]
            if (a.isNaN()) continue
            val score = when (transform) {
                Transform.RETURN -> {
                    val b = values[i - window]
                    if (b.isNaN() || b <= 0.0) null
                    else Stats.ratioToScore((a / b - 1.0) * 100.0, scale)
                }

                Transform.DIFFERENCE -> {
                    val b = values[i - window]
                    if (b.isNaN()) null else Stats.ratioToScore(a - b, scale)
                }

                Transform.ZSCORE -> {
                    if (i < window) null else {
                        val w = ArrayList<Double>(window)
                        for (k in (i - window + 1)..i) if (!values[k].isNaN()) w += values[k]
                        if (w.size < window / 2) null else {
                            val mean = w.average()
                            val sd = Stats.stdev(w)
                            if (sd == null || sd <= 0.0) null
                            else Stats.zToScore((a - mean) / sd, scale)
                        }
                    }
                }
            } ?: continue
            out[i] = sign * score
            populated++
        }
        return if (populated < MIN_POPULATED) null else out
    }

    /** Sample standard deviation of one-session returns ending at [index], in per cent. */
    private fun trailingVolPct(closes: List<Double>, index: Int, window: Int): Double? {
        if (index < window) return null
        val rs = ArrayList<Double>(window)
        for (k in (index - window + 1)..index) {
            val prev = closes[k - 1]
            val cur = closes[k]
            if (prev <= 0.0 || cur <= 0.0) return null
            rs += (cur / prev - 1.0) * 100.0
        }
        return Stats.stdev(rs)
    }

    companion object {
        const val SOURCE = "WalkForwardCalibration"
        const val KEY = "WALK_FORWARD_CALIBRATION"

        const val MEMBER_ISOTONIC = "ISOTONIC_COMPOSITE"
        const val MEMBER_RIDGE = "RIDGE_PANEL"

        /**
         * SPEC v2.1 §27 — the replay panel, measured series first.
         *
         * The sign is always "what makes gold go up":
         *
         *  - real yields rising ⇒ the opportunity cost of holding gold rises
         *    ⇒ bearish,
         *  - the broad dollar rising ⇒ bearish,
         *  - the two-year yield rising ⇒ tighter policy priced ⇒ bearish,
         *  - the curve steepening ⇒ easing priced ⇒ bullish,
         *  - breakevens rising ⇒ bullish,
         *  - policy uncertainty high against its own history ⇒ bullish,
         *  - equity fear rising ⇒ bullish,
         *  - gold's own trend ⇒ F13,
         *  - gold implied volatility rising ⇒ bullish,
         *  - equities rising ⇒ risk-on ⇒ bearish,
         *  - high yield rising ⇒ credit appetite ⇒ bearish,
         *  - crude rising ⇒ inflation pass-through ⇒ bullish,
         *  - the long nominal yield rising ⇒ bearish.
         *
         * Each `fallbackSeriesId` is the §26 stand-in, used only when the
         * measured series is missing; the split is published every cycle.
         */
        val PANEL: List<Leg> = listOf(
            Leg(
                "F01_REAL_RATE", MarketUniverse.REAL10Y_DEEP, Transform.DIFFERENCE, 20, 0.25, -1.0,
                "10-year TIPS yield, published daily (FRED DFII10)",
                fallbackSeriesId = MarketUniverse.TIPS_ETF, fallbackScale = 2.0, fallbackSign = +1.0
            ),
            Leg(
                "F02_USD", MarketUniverse.DOLLAR_BROAD_INDEX, Transform.RETURN, 20, 2.0, -1.0,
                "Fed broad dollar index, published daily (FRED DTWEXBGS)",
                fallbackSeriesId = MarketUniverse.DOLLAR_ETF, fallbackScale = 3.0, fallbackSign = -1.0
            ),
            Leg(
                "F03_FED", MarketUniverse.US02Y_DEEP, Transform.DIFFERENCE, 20, 0.40, -1.0,
                "2-year Treasury yield as the priced policy path (FRED DGS2)"
            ),
            Leg(
                "F04_TREASURY_CURVE", MarketUniverse.CURVE_10Y2Y_DEEP, Transform.DIFFERENCE, 20, 0.30, +1.0,
                "10y−2y spread, published daily (FRED T10Y2Y)"
            ),
            Leg(
                "F05_INFLATION", MarketUniverse.BREAKEVEN10Y_DEEP, Transform.DIFFERENCE, 20, 0.20, +1.0,
                "10-year breakeven inflation rate (FRED T10YIE)"
            ),
            Leg(
                "F07_GEOPOLITICAL_RISK", MarketUniverse.POLICY_UNCERTAINTY_DAILY, Transform.ZSCORE, 250, 3.0, +1.0,
                "economic policy uncertainty index against its own year (FRED USEPUINDXD)"
            ),
            Leg(
                "F08_FINANCIAL_STRESS", MarketUniverse.VIX_DEEP, Transform.RETURN, 20, 40.0, +1.0,
                "VIX close, published daily (FRED VIXCLS)",
                fallbackSeriesId = MarketUniverse.VIX, fallbackScale = 40.0, fallbackSign = +1.0
            ),
            Leg(
                "F13_MARKET_MOMENTUM", MarketUniverse.GOLD_PROXY_ETF, Transform.RETURN, 20, 6.0, +1.0,
                "gold's own trailing trend"
            ),
            Leg(
                "F15_OPTIONS_VOLATILITY", MarketUniverse.GVZ_DEEP, Transform.RETURN, 20, 30.0, +1.0,
                "gold implied volatility, published daily (FRED GVZCLS)"
            ),
            Leg(
                "F16_CROSS_ASSET", MarketUniverse.EQUITY_ETF, Transform.RETURN, 20, 6.0, -1.0,
                "equities as the risk-on leg"
            ),
            Leg(
                "F18_CREDIT", MarketUniverse.HY_ETF, Transform.RETURN, 20, 3.0, -1.0,
                "high yield as the credit-appetite leg"
            ),
            Leg(
                "F21_OIL_ENERGY", MarketUniverse.WTI_SPOT, Transform.RETURN, 20, 15.0, +1.0,
                "WTI spot, published daily (FRED DCOILWTICO)",
                fallbackSeriesId = MarketUniverse.OIL_ETF, fallbackScale = 15.0, fallbackSign = +1.0
            ),
            Leg(
                "F22_GLOBAL_CB_POLICY", MarketUniverse.US10Y_DEEP, Transform.DIFFERENCE, 20, 0.40, -1.0,
                "10-year Treasury yield as the global discount rate (FRED DGS10)"
            )
        )

        /** Normative weight the panel reproduces; published with every result. */
        val coveredWeight: Double =
            PANEL.sumOf { GoldSpecification.baseFactorWeights[it.factorId] ?: 0.0 }

        /** Horizon → forward sessions. Only what daily bars can resolve. */
        val FORWARD_STEPS: List<Pair<Horizon, Int>> = listOf(
            Horizon.D1 to 1,
            Horizon.W1 to 5
        )

        /** Which horizon's ridge fit is carried forward for the live reading. */
        val PANEL_MODEL_HORIZON: Horizon = Horizon.D1

        /** Nominal miscoverage of the conformal band: a 90 % interval. */
        const val ALPHA = 0.10

        const val MIN_HISTORY = 1_000
        const val MIN_SERIES = 400
        const val MIN_LEGS = 5
        const val MIN_POPULATED = 400
        const val MIN_ROWS = 400
        const val MIN_TEST = 150

        /** How stale a macro observation may be before a session is left empty. */
        const val MAX_STALENESS_DAYS = 7L

        /** Sessions the first fit must see before anything is scored — about five years. */
        const val WARMUP = 1_250

        /** Sessions scored forward before the mapping is refitted — about six months. */
        const val REFIT_EVERY = 125

        /** Share of the available leg weight a session needs to be scored. */
        const val MIN_WEIGHT_PRESENT = 0.60

        const val VOL_WINDOW = 20
        const val BREADTH_WINDOW = 500
        const val CHANGEPOINT_WINDOW = 750
        const val TREND_WINDOW = 500
        const val FILTER_WINDOW = 250
    }
}
