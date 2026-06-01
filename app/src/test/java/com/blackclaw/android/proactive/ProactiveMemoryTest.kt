package com.blackclaw.android.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure correction-learning scoring in [ProactiveMemory].
 * Storage paths need MMKV (Android), but the scoring math is extracted and
 * tested directly here.
 */
class ProactiveMemoryTest {

    @Test
    fun noRejectsMeansNoConservatism() {
        assertEquals(0.0, ProactiveMemory.conservatismScore(0, 0), 0.0001)
    }

    @Test
    fun singleRejectIsMild() {
        // 1 reject, no quick → 1/4 = 0.25
        assertEquals(0.25, ProactiveMemory.conservatismScore(1, 0), 0.0001)
    }

    @Test
    fun quickRejectsWeighDouble() {
        // 1 reject that was also quick → weighted 2 → 0.5
        assertEquals(0.5, ProactiveMemory.conservatismScore(1, 1), 0.0001)
    }

    @Test
    fun saturatesAtOne() {
        assertEquals(1.0, ProactiveMemory.conservatismScore(10, 10), 0.0001)
    }

    @Test
    fun reachesCautionThresholdAfterRepeatedRejects() {
        // Two rejects, both quick → weighted 4 → 1.0 (well past the 0.5 caution line)
        val score = ProactiveMemory.conservatismScore(2, 2)
        assertTrue("expected strong caution, got $score", score >= 0.5)
    }

    @Test
    fun confidenceThresholdRisesWithConservatism() {
        // Mirror the formula used in ProactiveAssistantManager:
        // threshold = (0.55 + conservatism*0.35), capped at 0.92.
        fun threshold(c: Double) = (0.55 + c * 0.35).coerceAtMost(0.92)
        assertEquals(0.55, threshold(0.0), 0.0001)
        assertTrue(threshold(1.0) > threshold(0.0))
        assertTrue(threshold(1.0) <= 0.92)
    }
}
