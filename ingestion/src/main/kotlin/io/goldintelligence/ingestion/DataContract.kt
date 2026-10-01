package io.goldintelligence.ingestion

import io.goldintelligence.engine.Horizon
import io.goldintelligence.engine.Observation
import io.goldintelligence.engine.Tier
import java.time.Duration
import java.time.Instant

/**
 * SPEC v2 §D3 — Data Contract v2.
 *
 * v1's contract carried a value, a timestamp and a source name. That is not
 * enough to decide whether a number may be used, published, or back-tested:
 * licence class, proxy status, vintage and validation outcome all change what
 * the system is allowed to do with it. Every field below is mandatory; a
 * record that cannot populate one is rejected rather than defaulted.
 */
enum class LicenseClass { PUBLIC_DOMAIN, ATTRIBUTION, RESTRICTED, UNKNOWN }

enum class ValidationStatus { PASSED, DEGRADED, REJECTED }

enum class Frequency { TICK, MINUTE, HOURLY, DAILY, WEEKLY, MONTHLY, QUARTERLY, IRREGULAR }

data class DataPoint(
    /** 1 */ val seriesId: String,
    /** 2 */ val value: Double,
    /** 3 */ val unit: String,
    /** 4 */ val observationTimestamp: Instant,
    /** 5 */ val releaseTimestamp: Instant?,
    /** 6 */ val ingestTimestamp: Instant,
    /** 7 */ val source: String,
    /** 8 */ val sourceUrl: String,
    /** 9 */ val licenseClass: LicenseClass,
    /** 10 */ val tier: Tier,
    /** 11 */ val quality: Double,
    /** 12 */ val isProxy: Boolean,
    /** 13 */ val proxyOf: String?,
    /** 14 */ val revision: Double?,
    /** 15 */ val vintage: Instant,
    /** 16 */ val frequency: Frequency,
    /** 17 */ val latencySeconds: Long,
    /** 18 */ val validationStatus: ValidationStatus,
    /** 19 */ val minHorizon: Horizon
) {
    val ageSeconds: Long get() = Duration.between(observationTimestamp, ingestTimestamp).seconds

    /**
     * SPEC v2 §15 — Publication policy. Raw values may only leave the system
     * when the licence permits it; otherwise only derived quantities
     * (z-scores, ranks, signs, factor scores) may be published.
     */
    val rawPublishable: Boolean
        get() = licenseClass == LicenseClass.PUBLIC_DOMAIN || licenseClass == LicenseClass.ATTRIBUTION

    fun toObservation(): Observation = Observation(
        value = value,
        timestamp = observationTimestamp,
        releaseTimestamp = releaseTimestamp,
        source = source,
        revision = revision,
        quality = quality,
        latencySeconds = latencySeconds
    )
}
