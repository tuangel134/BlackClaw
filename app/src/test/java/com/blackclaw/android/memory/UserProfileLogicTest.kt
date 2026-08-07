package com.blackclaw.android.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic of the learned profile: snippet budgeting, hour inference, and the
 * tool-to-interaction mapping.
 *
 * The storage paths need MMKV, so they are not covered here — but these three are the
 * parts that were actually wrong, and all three fail silently in production.
 */
class UserProfileLogicTest {

    // ── Snippet budget (the defect that could wipe all memory) ────────────────

    private fun manyTraits(n: Int) = (1..n).associate { "rasgo$it" to "valor$it" }

    @Test fun `snippet stays within budget`() {
        val p = UserProfile.Profile(
            name = "Ana", city = "Madrid", wakeUpHour = 7, sleepHour = 23,
            routineNotes = "x".repeat(400),
            traits = manyTraits(50),
        )
        val out = UserProfile.asPromptSnippetOf(p, budgetChars = 600)
        assertTrue("was ${out.length}", out.length <= 600)
    }

    @Test fun `the most valuable lines survive a tight budget`() {
        val p = UserProfile.Profile(name = "Ana", city = "Madrid", traits = manyTraits(40))
        val out = UserProfile.asPromptSnippetOf(p, budgetChars = 200)
        assertTrue(out.contains("Nombre: Ana"))
        // Arbitrary traits are the unbounded field, so they are the first to go.
        assertTrue(out, !out.contains("rasgo40"))
    }

    @Test fun `an unbounded profile can no longer take the whole memory block down`() {
        // This is the regression that mattered: MemoryHub drops a section whole rather
        // than truncating, so before the cap an oversized profile removed every other
        // memory section along with itself.
        val p = UserProfile.Profile(name = "Ana", traits = manyTraits(500))
        val out = UserProfile.asPromptSnippetOf(p, budgetChars = UserProfile.MAX_SNIPPET_CHARS)
        assertTrue(out.isNotEmpty())
        assertTrue(out.length <= UserProfile.MAX_SNIPPET_CHARS)
        assertTrue(out.contains("Nombre: Ana"))
    }

    @Test fun `an empty profile yields nothing`() {
        assertEquals("", UserProfile.renderSnippet(emptyList()))
        assertEquals("", UserProfile.asPromptSnippetOf(UserProfile.Profile()))
    }

    @Test fun `a budget too small for even one line yields nothing, not a bare header`() {
        // A heading with no content would spend budget announcing a section and then
        // say nothing.
        assertEquals("", UserProfile.renderSnippet(listOf("- Nombre: Ana"), budgetChars = 20))
    }

    @Test fun `line order is the drop order`() {
        val p = UserProfile.Profile(
            name = "Ana", city = "Madrid", wakeUpHour = 7,
            topApps = listOf("whatsapp"), traits = mapOf("z" to "1"),
        )
        val lines = UserProfile.snippetLines(p)
        val nameIdx = lines.indexOfFirst { it.contains("Nombre") }
        val appsIdx = lines.indexOfFirst { it.contains("Apps") }
        val traitIdx = lines.indexOfFirst { it.startsWith("- z:") }
        assertTrue(nameIdx < appsIdx)
        assertTrue(appsIdx < traitIdx)
    }

    // ── Wake hour ─────────────────────────────────────────────────────────────

    @Test fun `wake hour is the earliest reliably active morning hour`() {
        // Peak use is 09:00, but the user is already up at 07:00 every day. The old
        // modal-hour approach reported 09:00.
        val hours = List(4) { 7 } + List(20) { 9 }
        assertEquals(7, UserProfile.detectWakeHour(hours))
    }

    @Test fun `a one-off early hour does not count as a pattern`() {
        val hours = listOf(5) + List(10) { 8 }
        assertEquals(8, UserProfile.detectWakeHour(hours))
    }

    @Test fun `no morning data yields null rather than a fabricated hour`() {
        assertNull(UserProfile.detectWakeHour(List(10) { 15 }))
        assertNull(UserProfile.detectWakeHour(emptyList()))
    }

    // ── Sleep hour (was conceptually wrong) ───────────────────────────────────

    @Test fun `sleep hour is the latest active hour, not the most frequent one`() {
        // Heavy use at 22:00 plus a consistent 01:00 habit. Activity at 22:00 means
        // AWAKE, so reporting 22 as the sleep hour was answering the wrong question.
        val hours = List(20) { 22 } + List(3) { 1 }
        assertEquals(1, UserProfile.detectSleepHour(hours))
    }

    @Test fun `post-midnight hours sort after late-evening hours`() {
        // Comparing raw hour numbers put 01:00 below 21:00 even though it is later in
        // the same night.
        assertEquals(0, UserProfile.detectSleepHour(List(3) { 23 } + List(3) { 0 }))
        assertEquals(3, UserProfile.detectSleepHour(List(3) { 21 } + List(3) { 3 }))
    }

    @Test fun `sleep hour ignores daytime activity`() {
        assertNull(UserProfile.detectSleepHour(List(50) { 14 }))
    }

    @Test fun `sleep hour needs enough samples`() {
        val hours = List(10) { 22 } + listOf(2)
        assertEquals(22, UserProfile.detectSleepHour(hours))
    }

    @Test fun `detected hours are always valid clock hours`() {
        listOf(0, 1, 2, 3, 21, 22, 23).forEach { h ->
            val detected = UserProfile.detectSleepHour(List(5) { h })
            assertEquals(h, detected)
            assertTrue("$detected", detected in 0..23)
        }
    }

    // ── Tool to interaction mapping (was entirely missing) ────────────────────

    @Test fun `opening an app records an app_opened interaction`() {
        val result = UserProfile.interactionForTool("open_app", mapOf("app" to "WhatsApp"))
        assertEquals("app_opened" to "WhatsApp", result)
    }

    @Test fun `messaging records a message_sent interaction against the contact`() {
        assertEquals(
            "message_sent" to "Ana",
            UserProfile.interactionForTool("send_message", mapOf("contact" to "Ana", "text" to "hola")),
        )
        assertEquals(
            "message_sent" to "Ana",
            UserProfile.interactionForTool("send_sms", mapOf("to" to "Ana")),
        )
    }

    @Test fun `calling counts toward frequent contacts`() {
        assertEquals(
            "message_sent" to "Mamá",
            UserProfile.interactionForTool("make_call", mapOf("contact" to "Mamá")),
        )
    }

    @Test fun `tools that say nothing about habits are ignored`() {
        listOf("tap", "get_screen_info", "shell_exec", "finish").forEach {
            assertNull(it, UserProfile.interactionForTool(it, mapOf("x" to "y")))
        }
    }

    @Test fun `a relevant tool with no usable parameter is ignored`() {
        // Better to learn nothing than to record an empty contact name.
        assertNull(UserProfile.interactionForTool("open_app", emptyMap()))
        assertNull(UserProfile.interactionForTool("send_message", mapOf("contact" to "   ")))
    }

    @Test fun `alternative parameter names are accepted`() {
        // The model does not always use the canonical key name.
        assertEquals(
            "app_opened" to "spotify",
            UserProfile.interactionForTool("open_app", mapOf("package" to "spotify")),
        )
    }
}
