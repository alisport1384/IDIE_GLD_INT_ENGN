package io.goldintelligence.engine

import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * History and volatility context required by the corrected formulas
 * (§D4.3 dominance, §D4.5 CMC, §D4.7 expected move). Everything here is
 * optional: absent context degrades the corresponding component to its
 * declared neutral value, it never fabricates one.
 */
data class MarketContext(
    /** factorId → recent factor scores, oldest first, aligned with [goldHistory]. */
    val factorHistory: Map<String, List<Double>> = emptyMap(),
    /** Recent gold prices, oldest first. */
    val goldHistory: List<Double> = emptyList(),
    /** Horizon → realized sigma of gold in price units. */
    val sigmaByHorizon: Map<Horizon, Double> = emptyMap(),
    /** Gold-oriented signed moves of the eight witness markets. */
    val witnesses: List<CrossMarketConfirmation.Witness> = emptyList(),
    /** Rolling Brier score / reference Brier; null until a live record exists. */
    val brierDriftRatio: Double? = null,
    /** Fraction of the calibration sample requirement met, 0..1; 0 ⇒ uncalibrated. */
    val calibrationQuality: Double? = null,
    val spotPrice: Double? = null,
    /**
     * SPEC v2.1 §26 — the walk-forward record and the structural reads built
     * from it. Absent ⇒ the engine behaves exactly as it did before §26:
     * uncalibrated, Gaussian bands, agreement uncorrected.
     */
    val inference: InferenceBundle? = null
)

/** Per-horizon result (SPEC v2 §D1 and §18 screen 4). */
data class HorizonState(
    val horizon: Horizon,
    val mode: HorizonMode,
    val coverage: Double,
    val direction: Direction,
    val probability: Double?,
    val probabilityStatus: String,
    val confidence: Double?,
    val confidenceBreakdown: ConfidenceBreakdown?,
    val goldBias: Double?,
    val expectedMove: ExpectedMoveEngine.Band?,
    val activeFactorIds: List<String>,
    val gatedFactorIds: List<String>,
    val attribution: List<AttributionEntry>,
    val weights: List<DynamicWeight>,
    val conflict: ConflictLevel,
    val conflictRatio: Double,
    val killSwitch: KillSwitchResult,
    val signalState: SignalState,
    val uncertainty: Uncertainty,
    /**
     * SPEC v2.1 §26. The uncalibrated logistic of the bias, kept beside
     * [probability] so the size of the calibration correction is visible.
     */
    val rawProbability: Double? = null,
    /** Out-of-sample score of the mapping that produced [probability]. */
    val calibrationRecord: BrierReport? = null,
    /** Conformal half-width behind [expectedMove], in sigma. */
    val conformal: ConformalBand? = null
)

/** Full report consumed by the API layer and by the app's six screens. */
data class IntelligenceReport(
    val generatedAt: Instant,
    val specVersion: String,
    val state: GoldIntelligenceState,
    val horizons: List<HorizonState>,
    val crossMarketConfirmation: CrossMarketConfirmation.Result,
    val dominance: Map<String, Double>,
    val factorScores: List<FactorScore>,
    val features: Map<String, Double>,
    val dataQuality: Double,
    val spotPrice: Double?,
    /** SPEC v2.1 §26 — the inference layer's output for this cycle. */
    val inference: InferenceBundle? = null
)

/**
 * Orchestrates the corrected pipeline across all six required horizons.
 *
 * It composes the existing single-shot [GoldIntelligenceEngine] (unchanged)
 * with §D1 horizon gating and the §D4 formula set, so the v1 behaviour stays
 * available and the corrected behaviour is additive.
 */
class MultiHorizonEngine(
    private val core: GoldIntelligenceEngine = GoldIntelligenceEngine(),
    private val dominanceEngine: RollingR2DominanceEngine = RollingR2DominanceEngine(),
    private val confidenceEngine: SpecConfidenceEngine = SpecConfidenceEngine(),
    private val weightEngine: DynamicWeightEngine = NormalizingDynamicWeightEngine(),
    private val interactionEngine: FactorInteractionEngine = ClusterFactorInteractionEngine(),
    private val contradictionEngine: ContradictionAnalysisEngine = RuleBasedContradictionAnalysisEngine(),
    private val attributionEngine: AttributionAnalysisEngine = LeaveOneOutAttributionEngine(),
    private val specVersion: String = SPEC_VERSION
) {
    fun evaluate(
        input: InputSnapshot,
        context: MarketContext = MarketContext(),
        now: Instant = Instant.now()
    ): IntelligenceReport {
        val state = core.evaluate(input, now)
        val scores = input.factorScores
        val dominance = dominanceEngine.dominance(context.factorHistory, context.goldHistory)
        val dataQuality = if (scores.isEmpty()) 0.0 else DataQuality.aggregate(scores.map { it.quality })

        val thesis = state.direction
        val cmc = CrossMarketConfirmation.compute(context.witnesses, thesis)

        val horizons = Horizon.ordered.map { h ->
            evaluateHorizon(h, input, scores, state, dominance, cmc, dataQuality, context, now)
        }

        val daily = horizons.firstOrNull { it.horizon == Horizon.D1 }
        val enriched = state.copy(
            crossMarketConfirmation = cmc.value,
            confidence = daily?.confidence ?: state.confidence,
            expectedMove = daily?.expectedMove?.point
        )

        return IntelligenceReport(
            generatedAt = now,
            specVersion = specVersion,
            state = enriched,
            horizons = horizons,
            crossMarketConfirmation = cmc,
            dominance = dominance,
            factorScores = scores,
            features = input.features.values,
            dataQuality = dataQuality,
            spotPrice = context.spotPrice,
            inference = context.inference
        )
    }

    private fun evaluateHorizon(
        horizon: Horizon,
        input: InputSnapshot,
        scores: List<FactorScore>,
        state: GoldIntelligenceState,
        dominance: Map<String, Double>,
        cmc: CrossMarketConfirmation.Result,
        dataQuality: Double,
        context: MarketContext,
        now: Instant
    ): HorizonState {
        val available = scores.map { it.factorId }
        val gate = HorizonGate.gate(horizon, available)
        val active = scores.filter { it.factorId in gate.activeFactorIds.toSet() }

        if (active.isEmpty() || gate.renormalizedBaseWeights.isEmpty()) {
            return HorizonState(
                horizon = horizon,
                mode = gate.mode,
                coverage = gate.coverage,
                direction = Direction.NEUTRAL,
                probability = null,
                probabilityStatus = "NO_ACTIVE_FACTORS",
                confidence = null,
                confidenceBreakdown = null,
                goldBias = null,
                expectedMove = null,
                activeFactorIds = gate.activeFactorIds,
                gatedFactorIds = gate.gatedFactorIds,
                attribution = emptyList(),
                weights = emptyList(),
                conflict = ConflictLevel.UNKNOWN,
                conflictRatio = 0.0,
                killSwitch = KillSwitchResult(true, listOf("NO_ACTIVE_FACTORS")),
                signalState = SignalState.UNKNOWN,
                uncertainty = Uncertainty.INSUFFICIENT_INFORMATION
            )
        }

        val quality = active.associate { it.factorId to it.quality.coerceIn(0.0, 1.0) }
        val decay = active.associate { it.factorId to TimeDecay.factor(it.asOf, now) }
        val weights = weightEngine.calculate(
            baseWeights = gate.renormalizedBaseWeights,
            regime = state.regime,
            dominance = dominance,
            quality = quality,
            timeDecay = decay
        )
        val weightById = weights.associate { it.factorId to it.weight }
        val weighted = active.map { it to (weightById[it.factorId] ?: 0.0) }

        val direct = weighted.sumOf { (f, w) -> f.score * w }
        val interaction = interactionEngine.adjust(active)
        val goldBias = (direct + interaction).coerceIn(-100.0, 100.0)

        val (conflictLevel, _) = contradictionEngine.analyze(weighted)
        val conflictRatio = conflictRatioOf(weighted)
        val attribution = attributionEngine.attribute(weighted, interaction)

        val horizonQuality = DataQuality.aggregate(active.map { it.quality })

        // SPEC v2.1 §26. Every read below degrades to the pre-§26 behaviour
        // when the inference layer produced nothing for this horizon.
        val inference = context.inference
        val fit = inference?.calibration?.get(horizon)
        val record = inference?.brier?.get(horizon)
        // SPEC v2.1 §27 — the volatility-conditional band when the replay
        // could support one, the pooled band otherwise.
        val band = inference?.bandFor(horizon)
        val runLength = inference?.runLength

        val kill = KillSwitch.evaluate(
            dataQuality = horizonQuality,
            regime = state.regime,
            conflictRatio = conflictRatio,
            brierDriftRatio = context.brierDriftRatio,
            coverage = gate.coverage
        )

        val direction = when {
            goldBias > CalibrationDefaults.BIAS_NEUTRAL_BAND -> Direction.BULLISH
            goldBias < -CalibrationDefaults.BIAS_NEUTRAL_BAND -> Direction.BEARISH
            else -> Direction.NEUTRAL
        }

        val rawProbability = logistic(goldBias)

        // The published probability is the empirically observed frequency for
        // this score, not the logistic link, whenever a walk-forward mapping
        // exists for the horizon.
        // SPEC v2.1 §27 — the published probability is the combination the
        // replay scored, not a single model. The isotonic map reads the
        // normative composite; the panel model reads the factor panel; the
        // weights are the ones measured out of sample, and a member with no
        // positive skill carries none of them.
        val isotonicProbability = fit?.probability(goldBias)
        val combination = inference?.combination?.get(horizon)
        val calibratedProbability = when {
            combination == null -> isotonicProbability
            else -> {
                val weights = combination.members.filter { it.used }.associate { it.name to it.weight }
                val live = buildMap {
                    isotonicProbability?.let { put(MEMBER_ISOTONIC, it) }
                    inference.liveRidgeProbability?.let { put(MEMBER_RIDGE, it) }
                }
                ForecastCombination.pool(weights, live) ?: isotonicProbability
            }
        }
        val calibrationQuality = inference?.qualityFor(horizon)?.takeIf { it > 0.0 }
            ?: context.calibrationQuality
        val publishable = gate.mode == HorizonMode.CALIBRATED &&
            !kill.engaged &&
            calibrationQuality != null &&
            calibrationQuality > 0.0

        val probabilityStatus = when {
            kill.engaged -> "SUPPRESSED_KILL_SWITCH"
            gate.mode == HorizonMode.DIRECTIONAL_ONLY -> "DIRECTIONAL_ONLY/UNCALIBRATED"
            // Measured and found worthless is a different answer from never
            // measured, and the screen is told which one it is.
            (calibrationQuality == null || calibrationQuality <= 0.0) &&
                inference?.measured(horizon) == true -> "UNCALIBRATED_NO_SKILL"
            calibrationQuality == null || calibrationQuality <= 0.0 -> "UNCALIBRATED_NO_SAMPLE"
            calibratedProbability != null -> "CALIBRATED_WALK_FORWARD"
            else -> "CALIBRATED"
        }
        val effectiveProbability = calibratedProbability ?: rawProbability
        val probability = if (publishable) effectiveProbability else null

        // §26. Agreement between factors that move together is one witness,
        // not many: the majority share is shrunk toward neutral in proportion
        // to how little of the evidence is independent (Meucci 2009).
        val agreement = modelAgreement(weighted)
        val correctedAgreement = inference?.breadthRatio
            ?.let { 0.5 + (agreement - 0.5) * it.coerceIn(0.0, 1.0) }
            ?: agreement

        // §26. The regime label is replaced by the posterior age of the run
        // when the changepoint detector has an opinion (Adams & MacKay 2007).
        val stability = runLength?.stability ?: state.regimeStability
        val transition = state.regime == Regime.REGIME_TRANSITION ||
            (runLength != null && runLength.changeProbability >= CHANGEPOINT_TRANSITION)

        val confidence = confidenceEngine.evaluate(
            ConfidenceInputs(
                dataQuality = horizonQuality,
                crossMarketConfirmation = cmc.value,
                modelAgreement = correctedAgreement,
                regimeStability = stability,
                calibrationQuality = calibrationQuality,
                conflictRatio = conflictRatio,
                regimeTransition = transition,
                coverage = gate.coverage
            )
        )

        val expectedMove = if (kill.engaged) {
            null
        } else {
            ExpectedMoveEngine.compute(
                horizon = horizon,
                sigma = context.sigmaByHorizon[horizon],
                probability = effectiveProbability,
                conformalHalfWidth = band?.halfWidth,
                momentumCredibility = inference?.trend?.momentumCredibility
            )
        }

        val uncertainty = when {
            kill.engaged -> Uncertainty.INSUFFICIENT_INFORMATION
            confidence.value >= 0.75 -> Uncertainty.HIGH_CONFIDENCE
            else -> Uncertainty.LOW_CONFIDENCE
        }

        val signalState = when {
            kill.engaged -> SignalState.INVALID
            conflictLevel == ConflictLevel.HIGH -> SignalState.WEAK
            uncertainty == Uncertainty.HIGH_CONFIDENCE -> SignalState.VALID
            else -> SignalState.WEAK
        }

        return HorizonState(
            horizon = horizon,
            mode = gate.mode,
            coverage = gate.coverage,
            direction = direction,
            probability = probability,
            probabilityStatus = probabilityStatus,
            confidence = confidence.value,
            confidenceBreakdown = confidence,
            goldBias = goldBias,
            expectedMove = expectedMove,
            activeFactorIds = gate.activeFactorIds,
            gatedFactorIds = gate.gatedFactorIds,
            attribution = attribution,
            weights = weights,
            conflict = conflictLevel,
            conflictRatio = conflictRatio,
            killSwitch = kill,
            signalState = signalState,
            uncertainty = uncertainty,
            rawProbability = rawProbability,
            calibrationRecord = record,
            conformal = band
        )
    }

    /** Weighted share of evidence sitting on the minority side of the thesis. */
    private fun conflictRatioOf(weighted: List<Pair<FactorScore, Double>>): Double {
        val bull = weighted.filter { it.first.score > 0.0 }.sumOf { abs(it.first.score) * it.second }
        val bear = weighted.filter { it.first.score < 0.0 }.sumOf { abs(it.first.score) * it.second }
        val total = bull + bear
        return if (total <= 0.0) 0.0 else (min(bull, bear) / total).coerceIn(0.0, 1.0)
    }

    /** Agreement = 1 − conflict, i.e. the majority share of weighted evidence. */
    private fun modelAgreement(weighted: List<Pair<FactorScore, Double>>): Double =
        1.0 - conflictRatioOf(weighted)

    private fun logistic(bias: Double): Double = 1.0 / (1.0 + exp(-bias / 25.0))

    companion object {
        const val SPEC_VERSION = "SPEC_GOLD_INTELLIGENCE_V2.1"

        /**
         * Posterior mass on "the regime reset at this observation" above which
         * the transition penalty applies (SPEC v2.1 §26.4).
         */
        const val CHANGEPOINT_TRANSITION = 0.20

        /** Member names the replay publishes; the engine pools by these. */
        const val MEMBER_ISOTONIC = "ISOTONIC_COMPOSITE"
        const val MEMBER_RIDGE = "RIDGE_PANEL"
    }
}
