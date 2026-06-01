package com.blackclaw.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure formatting/freshness helpers in [TaskHistoryStore].
 * Storage uses MMKV (Android); the snippet building is extracted and tested here.
 */
class TaskHistoryStoreTest {

    private val now = System.currentTimeMillis()
    private val min = 60_000L

    private fun e(task: String, outcome: String, minutesAgo: Long) =
        TaskHistoryStore.Entry(task, outcome, now - minutesAgo * min)

    @Test
    fun emptyHistoryYieldsEmptySnippet() {
        assertEquals("", TaskHistoryStore.formatSnippet(emptyList(), now))
    }

    @Test
    fun recentTaskAppearsInSnippet() {
        val snippet = TaskHistoryStore.formatSnippet(
            listOf(e("manda hola a Ana por WhatsApp", "enviado", 5)), now)
        assertTrue(snippet.contains("manda hola a Ana"))
        assertTrue(snippet.contains("enviado"))
        assertTrue(snippet.contains("Tareas recientes"))
    }

    @Test
    fun staleTasksAreExcluded() {
        // 10 hours ago is outside the 6h fresh window.
        val snippet = TaskHistoryStore.formatSnippet(
            listOf(e("algo viejo", "ok", 600)), now)
        assertEquals("", snippet)
    }

    @Test
    fun snippetIsCappedToMax() {
        val entries = (1..10).map { e("tarea $it", "ok", it.toLong()) }
        val snippet = TaskHistoryStore.formatSnippet(entries, now, max = 3)
        val lines = snippet.lines().filter { it.startsWith("- ") }
        assertEquals(3, lines.size)
    }

    @Test
    fun blankTasksFilteredOut() {
        val snippet = TaskHistoryStore.formatSnippet(
            listOf(e("", "ok", 1), e("tarea real", "ok", 2)), now)
        val lines = snippet.lines().filter { it.startsWith("- ") }
        assertEquals(1, lines.size)
        assertTrue(snippet.contains("tarea real"))
    }

    @Test
    fun sanitizeOutcomeCollapsesWhitespaceAndTrims() {
        val out = TaskHistoryStore.sanitizeOutcome("  done\n\n  with   spaces ")
        assertEquals("done with spaces", out)
    }

    @Test
    fun sanitizeOutcomeTruncatesLongText() {
        val long = "x".repeat(500)
        assertTrue(TaskHistoryStore.sanitizeOutcome(long).length <= 120)
    }

    @Test
    fun outcomeOmittedWhenBlank() {
        val snippet = TaskHistoryStore.formatSnippet(
            listOf(e("solo tarea", "", 1)), now)
        assertTrue(snippet.contains("solo tarea"))
        assertFalse(snippet.contains("→"))
    }
}
