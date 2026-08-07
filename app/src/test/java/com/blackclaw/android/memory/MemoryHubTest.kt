package com.blackclaw.android.memory

import com.blackclaw.android.memory.MemoryHub.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Budget packing for the memory block injected into every prompt.
 *
 * This is now on the chat path as well as the agent loop, so a regression here shows
 * up as the assistant quietly losing the user's profile — or as a prompt that
 * overruns a local model's context window. Neither failure is visible in a diff.
 */
class MemoryHubTest {

    private fun section(priority: Int, length: Int) =
        Section(priority, "x".repeat(length))

    @Test fun `everything fits when the budget is generous`() {
        val result = MemoryHub.packByPriority(
            listOf(section(1, 100), section(2, 100), section(3, 100)),
            budgetChars = 1_000,
        )
        assertEquals(300, result.length)
    }

    @Test fun `output never exceeds the budget`() {
        val result = MemoryHub.packByPriority(
            listOf(section(1, 400), section(2, 400), section(3, 400)),
            budgetChars = 900,
        )
        assertTrue("was ${result.length}", result.length <= 900)
    }

    @Test fun `highest priority survives when the budget is tight`() {
        val profile = Section(1, "PROFILE")
        val summaries = Section(5, "SUMMARIES")
        val result = MemoryHub.packByPriority(listOf(summaries, profile), budgetChars = 8)
        // Input order is irrelevant; priority decides.
        assertTrue(result.contains("PROFILE"))
        assertFalse(result.contains("SUMMARIES"))
    }

    @Test fun `packing stops at the first section that does not fit`() {
        // The 300-char priority-2 section does not fit, and the tiny priority-3 one
        // would. It must still be dropped: sections are ordered by value, so letting a
        // shorter lower-value section jump the queue trades importance for brevity.
        val result = MemoryHub.packByPriority(
            listOf(section(1, 100), section(2, 300), Section(3, "TINY")),
            budgetChars = 200,
        )
        assertEquals(100, result.length)
        assertFalse(result.contains("TINY"))
    }

    @Test fun `sections are never truncated mid-sentence`() {
        // A half-sentence of profile is worse than none — it invites the model to
        // invent the missing half.
        val result = MemoryHub.packByPriority(
            listOf(Section(1, "El usuario se llama Ana y prefiere respuestas breves.")),
            budgetChars = 20,
        )
        assertEquals("", result)
    }

    @Test fun `blank sections do not consume budget`() {
        val result = MemoryHub.packByPriority(
            listOf(Section(1, "   "), Section(2, "REAL")),
            budgetChars = 6,
        )
        assertEquals("REAL", result)
    }

    @Test fun `a zero or negative budget yields nothing`() {
        val sections = listOf(Section(1, "PROFILE"))
        assertEquals("", MemoryHub.packByPriority(sections, budgetChars = 0))
        assertEquals("", MemoryHub.packByPriority(sections, budgetChars = -50))
    }

    @Test fun `an empty section list yields nothing`() {
        assertEquals("", MemoryHub.packByPriority(emptyList(), budgetChars = 1_000))
    }

    @Test fun `equal priorities are all included when they fit`() {
        val result = MemoryHub.packByPriority(
            listOf(Section(1, "AAA"), Section(1, "BBB")),
            budgetChars = 100,
        )
        assertTrue(result.contains("AAA"))
        assertTrue(result.contains("BBB"))
    }

    @Test fun `the local budget is tighter than the cloud budget`() {
        // The chat path relies on this: it is the reason long-term memory can be added
        // to on-device conversations without overrunning the context window.
        assertTrue(MemoryHub.LOCAL_BUDGET_CHARS < MemoryHub.DEFAULT_BUDGET_CHARS)
        assertTrue(MemoryHub.LOCAL_BUDGET_CHARS > 0)
    }

    @Test fun `a single oversized top-priority section is dropped rather than overrunning`() {
        // Guards the local path: a user profile that grew unbounded must not be able to
        // blow the context window just because it is priority 1.
        val result = MemoryHub.packByPriority(
            listOf(section(1, MemoryHub.LOCAL_BUDGET_CHARS + 1)),
            budgetChars = MemoryHub.LOCAL_BUDGET_CHARS,
        )
        assertEquals("", result)
    }
}
