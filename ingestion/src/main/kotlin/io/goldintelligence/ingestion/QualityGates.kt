package io.goldintelligence.ingestion

import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * SPEC v2 §12.4 — the eleven mandatory quality gates.
 *
 * v1 validated only that a value existed. The three gates that actually
 * caused silent corruption in live probing are G08 (HTTP 200 with an empty
 * payload), G09 (provider silently returning a coarser granularity than the
 * one requested) and G03 (timestamps in the future). All eleven run on every
 * record before it can reach the feature layer.
 */
object QualityGates {
    data class Spec(
        val maxAge: Duration,
        val min: Double? = null,
        val max: Double? = null,
        val maxJump: Double? = null,
        val requestedGranularity: String? = null
    )

    data class Verdict(
        val status: ValidationStatus,
        val failed: List<String>,
        val warnings: List<String>
    ) {
        val accepted: Boolean get() = status != ValidationStatus.REJECTED
    }

    const val G01 = "G01_VALUE_PRESENT"
    const val G02 = "G02_VALUE_FINITE"
    const val G03 = "G03_TIMESTAMP_NOT_FUTURE"
    const val G04 = "G04_TIMESTAMP_SANE"
    const val G05 = "G05_NOT_EXPIRED"
    const val G06 = "G06_RANGE_PLAUSIBLE"
    const val G07 = "G07_JUMP_PLAUSIBLE"
    const val G08 = "G08_NON_EMPTY_DATASET"
    const val G09 = "G09_GRANULARITY_MATCHES"
    const val G10 = "G10_NO_DUPLICATE_TIMESTAMP"
    const val G11 = "G11_MONOTONIC_SERIES"

    private val EPOCH_FLOOR: Instant = Instant.parse("1970-01-02T00:00:00Z")

    fun check(
        value: Double?,
        observationTimestamp: Instant?,
        now: Instant,
        spec: Spec,
        datasetSize: Int = 1,
        reportedGranularity: String? = null,
        previousValue: Double? = null,
        seenTimestamps: Set<Instant> = emptySet(),
        seriesIsMonotonic: Boolean = true
    ): Verdict {
        val failed = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (value == null) failed += G01
        if (value != null && (value.isNaN() || value.isInfinite())) failed += G02

        if (observationTimestamp == null) {
            failed += G04
        } else {
            if (observationTimestamp.isAfter(now.plusSeconds(60))) failed += G03
            if (observationTimestamp.isBefore(EPOCH_FLOOR)) failed += G04
            if (Duration.between(observationTimestamp, now) > spec.maxAge) warnings += G05
        }

        if (value != null) {
            val belowMin = spec.min != null && value < spec.min
            val aboveMax = spec.max != null && value > spec.max
            if (belowMin || aboveMax) failed += G06
            if (spec.maxJump != null && previousValue != null && abs(value - previousValue) > spec.maxJump) {
                warnings += G07
            }
        }

        if (datasetSize <= 0) failed += G08

        if (spec.requestedGranularity != null && reportedGranularity != null &&
            !spec.requestedGranularity.equals(reportedGranularity, ignoreCase = true)
        ) {
            failed += G09
        }

        if (observationTimestamp != null && observationTimestamp in seenTimestamps) failed += G10
        if (!seriesIsMonotonic) warnings += G11

        val status = when {
            failed.isNotEmpty() -> ValidationStatus.REJECTED
            warnings.isNotEmpty() -> ValidationStatus.DEGRADED
            else -> ValidationStatus.PASSED
        }
        return Verdict(status, failed, warnings)
    }
}
