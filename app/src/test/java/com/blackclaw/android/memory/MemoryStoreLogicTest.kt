package com.blackclaw.android.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection and summarisation rules for the fact store and conversation memory.
 *
 * Both were losing user data in ways nothing would surface: facts evicted by insertion
 * order instead of recency, and conversation summaries that dropped everything between
 * the first request and the last reply.
 */
class MemoryStoreLogicTest {

    private fun fact(key: String, addedAtMs: Long) =
        UserMemoryStore.Fact(id = key, key = key, value = "v-$key", addedAtMs = addedAtMs)

    // ── Fact eviction by recency, not position ────────────────────────────────

    @Test fun `under the cap nothing is dropped`() {
        val facts = listOf(fact("a", 1), fact("b", 2))
        assertEquals(facts, UserMemoryStore.capByRecency(facts, 5))
    }

    @Test fun `a maintained old fact survives over a newer untouched one`() {
        // "mamá" was added first so it sits at index 0, but the user keeps updating it,
        // so remember() refreshed addedAtMs in place. Capping by position evicted it;
        // capping by recency keeps it.
        val facts = listOf(
            fact("mamá", addedAtMs = 9_000),   // first inserted, most recently updated
            fact("trivia1", addedAtMs = 1_000),
            fact("trivia2", addedAtMs = 2_000),
        )
        val kept = UserMemoryStore.capByRecency(facts, 2)
        assertTrue(kept.any { it.key == "mamá" })
        assertFalse(kept.any { it.key == "trivia1" })
    }

    @Test fun `capping preserves insertion order among survivors`() {
        val facts = listOf(fact("a", 5), fact("b", 1), fact("c", 9))
        val kept = UserMemoryStore.capByRecency(facts, 2)
        assertEquals(listOf("a", "c"), kept.map { it.key })
    }

    @Test fun `a zero cap keeps nothing`() {
        assertEquals(emptyList<UserMemoryStore.Fact>(),
            UserMemoryStore.capByRecency(listOf(fact("a", 1)), 0))
    }

    @Test fun `mostRecent returns oldest-first so a prompt reads chronologically`() {
        val facts = listOf(fact("a", 3), fact("b", 1), fact("c", 2))
        assertEquals(listOf("b", "c", "a"), UserMemoryStore.mostRecent(facts, 3).map { it.key })
    }

    @Test fun `mostRecent selects by recency then orders chronologically`() {
        val facts = listOf(fact("old", 1), fact("mid", 5), fact("new", 9))
        assertEquals(listOf("mid", "new"), UserMemoryStore.mostRecent(facts, 2).map { it.key })
    }

    // ── Conversation summary ──────────────────────────────────────────────────

    private fun turns(vararg pairs: Pair<String, String>) = pairs.toList()

    @Test fun `a summary keeps several distinct requests, not just the first`() {
        val summary = ConversationMemory.extractSummary(
            turns(
                "USER" to "pon una alarma a las 7",
                "ASSISTANT" to "Hecho",
                "USER" to "cómo está el clima",
                "ASSISTANT" to "Soleado",
                "USER" to "manda un mensaje a Ana",
                "ASSISTANT" to "Enviado",
            )
        )
        // The old version kept only "pon una alarma" and the last reply.
        assertTrue(summary, summary.contains("alarma"))
        assertTrue(summary, summary.contains("clima"))
        assertTrue(summary, summary.contains("Ana"))
    }

    @Test fun `the last outcome is retained`() {
        val summary = ConversationMemory.extractSummary(
            turns("USER" to "pon una alarma", "ASSISTANT" to "Alarma puesta a las 7")
        )
        assertTrue(summary, summary.contains("Resultado"))
        assertTrue(summary, summary.contains("Alarma puesta"))
    }

    @Test fun `summaries never exceed the storage limit`() {
        val many = (1..40).flatMap {
            listOf("USER" to "peticion muy larga numero $it ".repeat(6), "ASSISTANT" to "ok $it")
        }
        val summary = ConversationMemory.extractSummary(many)
        assertTrue("was ${summary.length}", summary.length <= 300)
    }

    @Test fun `dropped requests are counted rather than silently lost`() {
        val many = (1..30).map { "USER" to "peticion distinta numero $it que es bastante larga" }
        val summary = ConversationMemory.extractSummary(many)
        assertTrue(summary, summary.contains("más)"))
    }

    @Test fun `at least one request survives even with a long outcome`() {
        val summary = ConversationMemory.extractSummary(
            turns("USER" to "x".repeat(500), "ASSISTANT" to "y".repeat(500))
        )
        assertTrue(summary, summary.startsWith("Usuario pidió: "))
        assertTrue(summary.length in 1..300)
    }

    @Test fun `no user turns means no summary`() {
        assertEquals("", ConversationMemory.extractSummary(turns("ASSISTANT" to "hola")))
        assertEquals("", ConversationMemory.extractSummary(emptyList()))
        assertEquals("", ConversationMemory.extractSummary(turns("USER" to "   ")))
    }

    @Test fun `whitespace is normalised so summaries stay single-line`() {
        val summary = ConversationMemory.extractSummary(
            turns("USER" to "pon\n\n  una   alarma", "ASSISTANT" to "ok")
        )
        assertFalse(summary.contains("\n"))
        assertTrue(summary, summary.contains("pon una alarma"))
    }

    // ── Request dedup (voice sessions repeat constantly) ──────────────────────

    @Test fun `near-duplicate requests collapse to the first occurrence`() {
        val deduped = ConversationMemory.dedupeRequests(
            listOf("pon una alarma", "Pon una alarma!", "pon una alarma?", "cómo está el clima")
        )
        assertEquals(2, deduped.size)
        assertEquals("pon una alarma", deduped.first())
    }

    @Test fun `genuinely different requests are all kept`() {
        val input = listOf("pon una alarma", "cómo está el clima", "manda mensaje a Ana")
        assertEquals(input, ConversationMemory.dedupeRequests(input))
    }

    @Test fun `dedup does not merge requests that only share a prefix`() {
        val deduped = ConversationMemory.dedupeRequests(
            listOf("manda un mensaje a Ana", "manda un mensaje a Pedro")
        )
        assertEquals(2, deduped.size)
    }

    // ── Topics are optional (the silent-drop landmine) ────────────────────────

    @Test fun `an entry without topics parses instead of being silently discarded`() {
        val json = org.json.JSONObject()
            .put("id", "c1").put("summary", "algo").put("timestamp", 1_700_000_000_000L)
        val entry = ConversationMemory.Entry.fromJson(json)
        assertEquals("c1", entry.id)
        assertEquals(emptyList<String>(), entry.topics)
    }

    @Test fun `topics parse when present and blanks are skipped`() {
        val json = org.json.JSONObject()
            .put("id", "c2").put("summary", "s").put("timestamp", 1L)
            .put("topics", org.json.JSONArray(listOf("alarmas", "", "clima")))
        assertEquals(listOf("alarmas", "clima"), ConversationMemory.Entry.fromJson(json).topics)
    }
}
