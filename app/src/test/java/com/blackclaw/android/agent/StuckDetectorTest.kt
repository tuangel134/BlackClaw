package com.blackclaw.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [StuckDetector] — the loop-breaker that keeps the agent from
 * spinning forever on the same action / unchanged screen. Pure JVM.
 */
class StuckDetectorTest {

    @Test
    fun freshDetectorDoesNotFireEarly() {
        val d = StuckDetector()
        // Two distinct, productive steps: no stuck signal.
        assertNull(d.record("tap:login", screenHash = 1, screenDiffCount = 5, error = null))
        assertNull(d.record("type:user", screenHash = 2, screenDiffCount = 4, error = null))
    }

    @Test
    fun sameActionFourTimesFiresSignal() {
        val d = StuckDetector()
        var detection: StuckDetector.Detection? = null
        repeat(4) { i ->
            // Vary screen hash so it's specifically same-action firing, not screen-unchanged.
            detection = d.record("tap:submit", screenHash = i, screenDiffCount = 1, error = null)
        }
        assertNotNull(detection)
        assertTrue(detection!!.signal is StuckDetector.Signal.SameAction)
    }

    @Test
    fun screenUnchangedThreeTimesFires() {
        val d = StuckDetector()
        var detection: StuckDetector.Detection? = null
        // Different actions but identical screen hash → screen-unchanged.
        detection = d.record("a", screenHash = 99, screenDiffCount = 1, error = null)
        detection = d.record("b", screenHash = 99, screenDiffCount = 1, error = null)
        detection = d.record("c", screenHash = 99, screenDiffCount = 1, error = null)
        assertNotNull(detection)
    }

    @Test
    fun repeatedErrorThreeTimesFires() {
        val d = StuckDetector()
        var detection: StuckDetector.Detection? = null
        detection = d.record("a", screenHash = 1, screenDiffCount = 3, error = "ELEMENT_NOT_FOUND")
        detection = d.record("b", screenHash = 2, screenDiffCount = 3, error = "ELEMENT_NOT_FOUND")
        detection = d.record("c", screenHash = 3, screenDiffCount = 3, error = "ELEMENT_NOT_FOUND")
        assertNotNull(detection)
        assertTrue(detection!!.signal is StuckDetector.Signal.RepeatedError)
    }

    @Test
    fun escalatesToAutoKillAfterPersistentStuck() {
        val d = StuckDetector()
        var detection: StuckDetector.Detection? = null
        // 8 identical stuck steps should escalate past HINT and STRATEGY_SWITCH.
        repeat(8) {
            detection = d.record("tap:x", screenHash = 7, screenDiffCount = 0, error = null)
        }
        assertNotNull(detection)
        assertEquals(StuckDetector.RecoveryLevel.AUTO_KILL, detection!!.level)
    }

    @Test
    fun firstStuckIsHintLevel() {
        val d = StuckDetector()
        var detection: StuckDetector.Detection? = null
        // Exactly 4 same actions → first stuck detection should be HINT.
        repeat(4) {
            detection = d.record("tap:x", screenHash = it, screenDiffCount = 1, error = null)
        }
        assertNotNull(detection)
        assertEquals(StuckDetector.RecoveryLevel.HINT, detection!!.level)
        assertTrue(detection!!.recoveryHint.isNotBlank())
    }

    @Test
    fun productiveStepResetsStuckCounter() {
        val d = StuckDetector()
        repeat(4) { d.record("tap:x", screenHash = it, screenDiffCount = 1, error = null) }
        // A clearly productive step (new screen, lots of diff) should clear it.
        val ok = d.record("scroll", screenHash = 1000, screenDiffCount = 20, error = null)
        assertNull(ok)
    }

    @Test
    fun resetClearsState() {
        val d = StuckDetector()
        repeat(4) { d.record("tap:x", screenHash = it, screenDiffCount = 1, error = null) }
        d.reset()
        // After reset, a single action shouldn't immediately fire.
        assertNull(d.record("tap:x", screenHash = 0, screenDiffCount = 1, error = null))
    }
}
