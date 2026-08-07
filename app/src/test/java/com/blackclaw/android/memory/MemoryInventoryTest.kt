package com.blackclaw.android.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of the privacy inventory.
 *
 * [MemoryInventory.snapshot] and [MemoryInventory.forget] touch MMKV, so they are not
 * covered here; what is covered is the display parsing and the prompt-cost estimate,
 * which are what the user actually reads off the screen.
 */
class MemoryInventoryTest {

    // ── Profile line parsing ──────────────────────────────────────────────────

    @Test fun `a labelled line splits into label and value`() {
        val item = MemoryInventory.parseProfileLine("- Ciudad: Monterrey")
        assertEquals("Ciudad", item.label)
        assertEquals("Monterrey", item.detail)
    }

    @Test fun `a value containing a colon is kept whole`() {
        // This is the sleep-schedule line, the field most likely to contain a colon.
        // Splitting on the last colon would have shown "23" as the value.
        val item = MemoryInventory.parseProfileLine("- Horario: duerme 23:00, despierta 07:00")
        assertEquals("Horario", item.label)
        assertEquals("duerme 23:00, despierta 07:00", item.detail)
    }

    @Test fun `a line without a colon becomes an all-label item`() {
        val item = MemoryInventory.parseProfileLine("- Usa el teléfono de noche")
        assertEquals("Usa el teléfono de noche", item.label)
        assertEquals("", item.detail)
    }

    @Test fun `the bullet prefix is stripped`() {
        assertTrue(!MemoryInventory.parseProfileLine("- Nombre: Ana").label.startsWith("-"))
    }

    @Test fun `a line with no bullet still parses`() {
        val item = MemoryInventory.parseProfileLine("Nombre: Ana")
        assertEquals("Nombre", item.label)
        assertEquals("Ana", item.detail)
    }

    @Test fun `surrounding whitespace is trimmed from both sides`() {
        val item = MemoryInventory.parseProfileLine("-   Ciudad  :   Monterrey  ")
        assertEquals("Ciudad", item.label)
        assertEquals("Monterrey", item.detail)
    }

    @Test fun `an empty line does not crash`() {
        val item = MemoryInventory.parseProfileLine("")
        assertEquals("", item.label)
        assertEquals("", item.detail)
    }

    @Test fun `a trailing colon yields an empty value rather than throwing`() {
        val item = MemoryInventory.parseProfileLine("- Ciudad:")
        assertEquals("Ciudad", item.label)
        assertEquals("", item.detail)
    }

    @Test fun `real snippet lines all produce a non-blank label`() {
        // Guards against a formatting change in snippetLines producing blank rows.
        val profile = UserProfile.Profile(
            name = "Ana",
            city = "Monterrey",
            wakeUpHour = 7,
            sleepHour = 23,
            topContacts = listOf("Luis"),
            topApps = listOf("whatsapp"),
            interests = listOf("ciclismo"),
        )
        val lines = UserProfile.snippetLines(profile)
        assertTrue("snippetLines produced nothing", lines.isNotEmpty())
        lines.forEach {
            assertTrue("blank label for: $it", MemoryInventory.parseProfileLine(it).label.isNotBlank())
        }
    }

    // ── Prompt cost estimate ──────────────────────────────────────────────────

    @Test fun `token estimate rounds up so a small block never reads as zero`() {
        // Reporting "0 tokens" for a block that does get sent would understate the cost.
        assertEquals(1, MemoryInventory.approxTokens(1))
        assertEquals(1, MemoryInventory.approxTokens(4))
        assertEquals(2, MemoryInventory.approxTokens(5))
    }

    @Test fun `an empty block costs nothing`() {
        assertEquals(0, MemoryInventory.approxTokens(0))
    }

    @Test fun `the estimate scales at roughly four characters per token`() {
        assertEquals(150, MemoryInventory.approxTokens(600))
        assertEquals(350, MemoryInventory.approxTokens(1400))
    }

    @Test fun `the estimate is monotonic`() {
        var previous = 0
        listOf(0, 10, 100, 599, 600, 1400, 4000).forEach { chars ->
            val tokens = MemoryInventory.approxTokens(chars)
            assertTrue("not monotonic at $chars", tokens >= previous)
            previous = tokens
        }
    }
}
