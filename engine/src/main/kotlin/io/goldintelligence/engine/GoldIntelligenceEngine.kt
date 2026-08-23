package io.goldintelligence.engine

import java.time.Instant

class GoldIntelligenceEngine(
    private val validator: DataValidator = StrictDataValidator(),
    private val featureEngineer: FeatureEngineer = IdentityFeatureEngineer(),
    private val factorEngine: FactorEngine = SuppliedFactorEngine(),
    private val regimeEngine: RegimeEngine = ThresholdRegimeEngine(),
    private val shockEngine: ShockDetectionEngine = RuleBasedShockDetectionEngine(),
    private val scoringPipeline: ScoringPipeline = ScoringPipeline(),
    private val liquidityEngine: LiquidityAssessmentEngine = RuleBasedLiquidityEngine(),
    private val probabilityModel: ProbabilityModel = HeuristicProbabilityModel(),
    private val calibrator: ProbabilityCalibrator = NoOpCalibrator(),
    private val confidenceEngine: ConfidenceEngine = EvidenceConfidenceEngine(),
    private val analogueEngine: HistoricalAnalogueEngine = NoHistoricalAnalogueEngine(),
    private val scenarioOutlookEngine: ScenarioOutlookEngine = RuleBasedScenarioOutlookEngine(),
    private val newsIntelligenceEngine: NewsIntelligenceEngine = WeightedNewsIntelligenceEngine(),
    private val invalidationEngine: InvalidationEngine = RuleBasedInvalidationEngine()
) {
    fun evaluate(input: InputSnapshot, now: Instant = Instant.now()): GoldIntelligenceState {
        if (!validator.validate(input)) {
            return unknownState(input, "DATA_VALIDATION_FAILED")
        }

        val features = featureEngineer.transform(input)
        val suppliedFactors = if (input.factorScores.isNotEmpty()) {
            input.factorScores
        } else {
            factorEngine.score(features)
        }

        val (regime, stability) = regimeEngine.detect(input.copy(features = features))
        val shockDetail = shockEngine.detect(input, features)
        val shockState = when {
            shockDetail.active -> ShockState.ACTIVE
            input.shock != ShockState.UNKNOWN -> input.shock
            else -> ShockState.NONE
        }

        val scoring = scoringPipeline.run(suppliedFactors, features, regime, now)
        val liquidity = liquidityEngine.assess(features)
            .let { if (it != Liquidity.UNKNOWN) it else input.liquidity }

        val dominantFactor = input.dominantFactor ?: scoring.dominantFactor
        val effectiveSnapshot = input.copy(
            features = features,
            factorScores = suppliedFactors,
            regime = regime,
            regimeStability = stability,
            shock = shockState,
            dominantFactor = dominantFactor,
            contradictions = input.contradictions + scoring.conflictNotes
        )

        val rawProbability = probabilityModel.predict(effectiveSnapshot)
        val calibrated = rawProbability?.let(calibrator::calibrate)
        val confidence = confidenceEngine.calculate(effectiveSnapshot, calibrated)

        val direction = when {
            calibrated == null -> biasDirection(scoring.goldBias)
            (calibrated.up ?: 0.0) > (calibrated.down ?: 0.0) &&
                (calibrated.up ?: 0.0) > (calibrated.neutral ?: 0.0) -> Direction.BULLISH
            (calibrated.down ?: 0.0) > (calibrated.up ?: 0.0) &&
                (calibrated.down ?: 0.0) > (calibrated.neutral ?: 0.0) -> Direction.BEARISH
            else -> Direction.NEUTRAL
        }

        val probability = when (direction) {
            Direction.BULLISH -> calibrated?.up
            Direction.BEARISH -> calibrated?.down
            Direction.NEUTRAL -> calibrated?.neutral
        }

        val historicalScenarios = analogueEngine.find(input)
        val scenarioOutlook = scenarioOutlookEngine.buildScenarios(suppliedFactors, scoring.goldBias)
        val invalidation = invalidationEngine.invalidate(effectiveSnapshot)

        val newsState = when {
            input.news.isEmpty() -> "NONE"
            else -> input.news
                .map { newsIntelligenceEngine.evaluate(it) }
                .maxByOrNull { it.expectedImpactScore }
                ?.label
                ?: "UNKNOWN"
        }

        val uncertainty = when {
            shockDetail.active -> Uncertainty.LOW_CONFIDENCE
            stability == Stability.LOW -> Uncertainty.LOW_CONFIDENCE
            calibrated == null && scoring.goldBias == null -> Uncertainty.INSUFFICIENT_INFORMATION
            confidence != null && confidence >= 0.75 -> Uncertainty.HIGH_CONFIDENCE
            confidence != null -> Uncertainty.LOW_CONFIDENCE
            scoring.conflict == ConflictLevel.HIGH -> Uncertainty.LOW_CONFIDENCE
            else -> Uncertainty.INSUFFICIENT_INFORMATION
        }

        val signalState = when {
            calibrated == null && scoring.goldBias == null -> SignalState.UNKNOWN
            shockDetail.active -> SignalState.WEAK
            uncertainty == Uncertainty.HIGH_CONFIDENCE && scoring.conflict != ConflictLevel.HIGH -> SignalState.VALID
            uncertainty == Uncertainty.INSUFFICIENT_INFORMATION -> SignalState.UNKNOWN
            else -> SignalState.WEAK
        }

        return GoldIntelligenceState(
            direction = direction,
            probability = probability,
            confidence = confidence,
            regime = regime,
            regimeStability = stability,
            dominantFactor = dominantFactor,
            primaryDrivers = suppliedFactors
                .sortedByDescending { kotlin.math.abs(it.score) }
                .take(3)
                .map { it.factorId },
            contradictions = effectiveSnapshot.contradictions,
            crossMarketConfirmation = null,
            newsState = newsState,
            liquidity = liquidity,
            shock = shockState,
            expectedMove = null,
            uncertainty = uncertainty,
            scenarios = historicalScenarios,
            invalidation = invalidation,
            signalState = signalState,
            goldBias = scoring.goldBias,
            attribution = scoring.attribution,
            conflict = scoring.conflict,
            conflictNotes = scoring.conflictNotes,
            shockDetail = shockDetail,
            divergences = scoring.divergences,
            scenarioOutlook = scenarioOutlook
        )
    }

    private fun biasDirection(bias: Double?): Direction = when {
        bias == null -> Direction.NEUTRAL
        bias > CalibrationDefaults.BIAS_NEUTRAL_BAND -> Direction.BULLISH
        bias < -CalibrationDefaults.BIAS_NEUTRAL_BAND -> Direction.BEARISH
        else -> Direction.NEUTRAL
    }

    private fun unknownState(input: InputSnapshot, reason: String): GoldIntelligenceState =
        GoldIntelligenceState(
            direction = Direction.NEUTRAL,
            probability = null,
            confidence = null,
            regime = input.regime,
            regimeStability = input.regimeStability,
            dominantFactor = input.dominantFactor,
            primaryDrivers = emptyList(),
            contradictions = listOf(reason) + input.contradictions,
            crossMarketConfirmation = null,
            newsState = "UNKNOWN",
            liquidity = input.liquidity,
            shock = input.shock,
            expectedMove = null,
            uncertainty = Uncertainty.INSUFFICIENT_INFORMATION,
            scenarios = emptyList(),
            invalidation = null,
            signalState = SignalState.UNKNOWN,
            goldBias = null,
            attribution = emptyList(),
            conflict = ConflictLevel.UNKNOWN,
            conflictNotes = emptyList(),
            shockDetail = null,
            divergences = emptyList(),
            scenarioOutlook = emptyList()
        )
}
