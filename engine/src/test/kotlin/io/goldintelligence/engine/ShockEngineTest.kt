package io.goldintelligence.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShockEngineTest {

    private val engine = RuleBasedShockDetectionEngine()
    private val snapshot = InputSnapshot.empty()

    @Test
    fun `single trigger below minimum does not activate shock`() {
        val features = FeatureSet(mapOf(FeatureKeys.DXY_MOVE_SIGMA to 5.2))
        val detail = engine.detect(snapshot, features)
        assertFalse(detail.active)
        assertEquals(1, detail.triggeredConditions.size)
    }

    @Test
    fun `two simultaneous sigma breaches activate shock`() {
        val features = FeatureSet(
            mapOf(
                FeatureKeys.DXY_MOVE_SIGMA to 5.5,
                FeatureKeys.US10Y_MOVE_SIGMA to 4.5
            )
        )
        val detail = engine.detect(snapshot, features)
        assertTrue(detail.active)
        assertEquals(2, detail.triggeredConditions.size)
    }

    @Test
    fun `no features means no shock`() {
        val detail = engine.detect(snapshot, FeatureSet())
        assertFalse(detail.active)
        assertTrue(detail.triggeredConditions.isEmpty())
    }
}
