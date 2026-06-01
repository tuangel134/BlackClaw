package com.blackclaw.android.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── Per-package mute proposal (oleada 18) ──

    @Test
    fun mostlyIgnoredAppIsMuteCandidate() {
        val stat = ProactiveMemory.PkgStat(total = 10, ignores = 9)
        assertTrue(ProactiveMemory.shouldProposeMute(stat))
    }

    @Test
    fun notEnoughHistoryIsNotMuteCandidate() {
        // Only 3 notifications, even all ignored → below MUTE_MIN_TOTAL.
        val stat = ProactiveMemory.PkgStat(total = 3, ignores = 3)
        assertFalse(ProactiveMemory.shouldProposeMute(stat))
    }

    @Test
    fun frequentlyActedAppIsNotMuteCandidate() {
        // 10 notifications but half were acted on → keep watching.
        val stat = ProactiveMemory.PkgStat(total = 10, ignores = 5)
        assertFalse(ProactiveMemory.shouldProposeMute(stat))
    }

    @Test
    fun ignoreRatioComputesCorrectly() {
        assertEquals(0.0, ProactiveMemory.PkgStat(0, 0).ignoreRatio, 0.0001)
        assertEquals(0.9, ProactiveMemory.PkgStat(10, 9).ignoreRatio, 0.0001)
    }
}
