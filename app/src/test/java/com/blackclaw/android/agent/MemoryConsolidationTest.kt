package com.blackclaw.android.agent

import com.blackclaw.android.proactive.HabitTracker
import com.blackclaw.android.proactive.ProactiveMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the memory-consolidation/cleanup logic added in oleada 16:
 *  - TaskHistoryStore.dedupe — collapse repeated tasks
 *  - ProactiveMemory.conservatismScore decay — old corrections fade
 *  - HabitTracker.prune — drop stale signals
 * All pure functions.
 */
class MemoryConsolidationTest {

    private val now = System.currentTimeMillis()
    private val day = 24L * 60 * 60 * 1000

    // ── TaskHistoryStore.dedupe ──

    @Test
    fun dedupeKeepsMostRecentOfRepeatedTask() {
        val entries = listOf(
            TaskHistoryStore.Entry("manda hola a Ana", "enviado", now),
            TaskHistoryStore.Entry("otra cosa", "ok", now - 1000),
            TaskHistoryStore.Entry("Manda Hola A Ana", "enviado antes", now - 5000),
        )
        val out = TaskHistoryStore.dedupe(entries)
        assertEquals(2, out.size)
        // The kept "manda hola a Ana" is the most recent (first) one.
        assertEquals("enviado", out.first { it.task.equals("manda hola a Ana", true) }.outcome)
    }

    @Test
    fun dedupeLeavesDistinctTasksUntouched() {
        val entries = listOf(
            TaskHistoryStore.Entry("a", "", now),
            TaskHistoryStore.Entry("b", "", now - 1),
            TaskHistoryStore.Entry("c", "", now - 2),
        )
        assertEquals(3, TaskHistoryStore.dedupe(entries).size)
    }

    // ── ProactiveMemory decay ──

    @Test
    fun freshCorrectionFullStrength() {
        val score = ProactiveMemory.conservatismScore(2, 2, ageMs = 0L)
        assertEquals(1.0, score, 0.0001)
    }

    @Test
    fun oldCorrectionDecaysToZero() {
        // 40 days old, default window 30 days → fully decayed.
        val score = ProactiveMemory.conservatismScore(4, 4, ageMs = 40 * day)
        assertEquals(0.0, score, 0.0001)
    }

    @Test
    fun halfwayThroughWindowIsRoughlyHalf() {
        // 15 of 30 days → ~0.5 decay factor applied to a saturated base (1.0).
        val score = ProactiveMemory.conservatismScore(4, 4, ageMs = 15 * day)
        assertTrue("expected ~0.5, got $score", score in 0.4..0.6)
    }

    // ── HabitTracker.prune ──

    @Test
    fun pruneDropsStaleSignals() {
        val signals = listOf(
            HabitTracker.Signal("alarm", 7, 2, now - 100 * day),  // stale (>60d)
            HabitTracker.Signal("alarm", 7, 2, now - 1 * day),    // fresh
        )
        val out = HabitTracker.prune(signals, now)
        assertEquals(1, out.size)
        assertTrue(out.all { now - it.t <= 60L * day })
    }

    @Test
    fun pruneCapsTotal() {
        val signals = (1..300).map { HabitTracker.Signal("alarm", 7, 2, now - it.toLong()) }
        val out = HabitTracker.prune(signals, now, max = 200)
        assertEquals(200, out.size)
    }
}
