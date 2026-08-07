package com.blackclaw.android.ui.assistant

import com.blackclaw.android.assistant.AssistantItem
import com.blackclaw.android.assistant.AssistantItemType
import com.blackclaw.android.ui.assistant.AssistantCardModel.Urgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Presentation rules for an assistant card. These used to be built inline inside the
 * Composable, where the only way to check them was to look at the screen.
 */
class AssistantCardModelTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun item(
        type: AssistantItemType = AssistantItemType.REMINDER,
        title: String = "Llamar al dentista",
        body: String = "",
        triggerAtMs: Long = 0L,
        done: Boolean = false,
        repeat: String = "none",
        amount: Double = 0.0,
        category: String = "",
        challenge: String = "none",
        ring: Boolean = false,
        radiusM: Int = 0,
        lat: Double = 0.0,
        source: String = "user",
    ) = AssistantItem(
        id = "x", type = type, title = title, body = body, triggerAtMs = triggerAtMs,
        repeat = repeat, done = done, amount = amount, category = category,
        challenge = challenge, ring = ring, lat = lat, radiusM = radiusM, source = source,
    )

    // ── Urgency ───────────────────────────────────────────────────────────────

    @Test fun `a past trigger is overdue`() {
        assertEquals(Urgency.OVERDUE, AssistantCardModel.urgencyOf(item(triggerAtMs = now - hour), now))
    }

    @Test fun `within the hour is imminent`() {
        assertEquals(Urgency.IMMINENT, AssistantCardModel.urgencyOf(item(triggerAtMs = now + 20 * minute), now))
    }

    @Test fun `beyond the hour is merely scheduled`() {
        assertEquals(Urgency.SCHEDULED, AssistantCardModel.urgencyOf(item(triggerAtMs = now + 5 * hour), now))
    }

    @Test fun `no trigger time means no urgency`() {
        assertEquals(Urgency.NONE, AssistantCardModel.urgencyOf(item(triggerAtMs = 0L), now))
    }

    @Test fun `done outranks overdue so completed items stop shouting`() {
        val overdueButDone = item(triggerAtMs = now - day, done = true)
        assertEquals(Urgency.DONE, AssistantCardModel.urgencyOf(overdueButDone, now))
    }

    // ── Relative time ─────────────────────────────────────────────────────────

    @Test fun `future times read as en`() {
        assertEquals("en 20 min", AssistantCardModel.relativeTime(now + 20 * minute, now))
        assertEquals("en 3 h", AssistantCardModel.relativeTime(now + 3 * hour, now))
        assertEquals("en 2 d", AssistantCardModel.relativeTime(now + 2 * day, now))
        assertEquals("en 2 sem", AssistantCardModel.relativeTime(now + 14 * day, now))
    }

    @Test fun `past times read as hace`() {
        assertEquals("hace 5 min", AssistantCardModel.relativeTime(now - 5 * minute, now))
        assertEquals("hace 2 h", AssistantCardModel.relativeTime(now - 2 * hour, now))
    }

    @Test fun `sub-minute collapses to ahora in both directions`() {
        assertEquals("ahora", AssistantCardModel.relativeTime(now + 5_000L, now))
        assertEquals("ahora", AssistantCardModel.relativeTime(now - 5_000L, now))
    }

    @Test fun `no trigger yields no relative time`() {
        assertEquals("", AssistantCardModel.relativeTime(0L, now))
    }

    // ── Money ─────────────────────────────────────────────────────────────────

    @Test fun `amount is always rendered positive because sign is carried by the label`() {
        // The store keeps expenses negative; showing "-45" beside a red "gasto" label
        // is redundant, so the number itself is unsigned.
        assertFalse(AssistantCardModel.amountLabel(-45.5).contains("-"))
        assertFalse(AssistantCardModel.amountLabel(-45.5).contains("−"))
    }

    @Test fun `zero amount produces no label`() {
        assertEquals("", AssistantCardModel.amountLabel(0.0))
    }

    @Test fun `income and expense are distinguished on the card data`() {
        assertTrue(AssistantCardModel.of(item(amount = 100.0), now).isIncome)
        assertFalse(AssistantCardModel.of(item(amount = -100.0), now).isIncome)
    }

    // ── Repeat ────────────────────────────────────────────────────────────────

    @Test fun `known repeats get a label and none does not`() {
        assertEquals("Cada día", AssistantCardModel.repeatLabel("daily"))
        assertEquals("Cada semana", AssistantCardModel.repeatLabel("weekly"))
        assertEquals("", AssistantCardModel.repeatLabel("none"))
        assertEquals("", AssistantCardModel.repeatLabel("gibberish"))
    }

    @Test fun `repeats flag tracks the label`() {
        assertTrue(AssistantCardModel.of(item(repeat = "daily"), now).repeats)
        assertFalse(AssistantCardModel.of(item(repeat = "none"), now).repeats)
    }

    // ── Capability markers ────────────────────────────────────────────────────

    @Test fun `geofence needs both a radius and a coordinate`() {
        assertTrue(AssistantCardModel.of(item(radiusM = 100, lat = 19.4), now).hasGeofence)
        // A radius with no coordinate is not a usable geofence and must not claim to be.
        assertFalse(AssistantCardModel.of(item(radiusM = 100), now).hasGeofence)
        assertFalse(AssistantCardModel.of(item(lat = 19.4), now).hasGeofence)
    }

    @Test fun `challenge marker ignores none and blank`() {
        assertTrue(AssistantCardModel.of(item(challenge = "math"), now).hasChallenge)
        assertFalse(AssistantCardModel.of(item(challenge = "none"), now).hasChallenge)
        assertFalse(AssistantCardModel.of(item(challenge = ""), now).hasChallenge)
    }

    @Test fun `rings loudly only matters for non-alarm types`() {
        // An alarm ringing is not news; a calendar event that also rings is.
        assertTrue(AssistantCardModel.of(item(type = AssistantItemType.EVENT, ring = true), now).ringsLoudly)
        assertFalse(AssistantCardModel.of(item(type = AssistantItemType.ALARM, ring = true), now).ringsLoudly)
    }

    @Test fun `ai authorship is detected case-insensitively`() {
        assertTrue(AssistantCardModel.of(item(source = "ai"), now).fromAi)
        assertTrue(AssistantCardModel.of(item(source = "AI"), now).fromAi)
        assertFalse(AssistantCardModel.of(item(source = "user"), now).fromAi)
    }

    @Test fun `suggestions are recognised by category or lightbulb prefix`() {
        assertTrue(AssistantCardModel.of(item(category = "habit"), now).isSuggestion)
        assertTrue(AssistantCardModel.of(item(title = "💡 Bebe agua"), now).isSuggestion)
        assertFalse(AssistantCardModel.of(item(), now).isSuggestion)
    }

    // ── Checkability ──────────────────────────────────────────────────────────

    @Test fun `only actionable types get a checkbox`() {
        listOf(AssistantItemType.REMINDER, AssistantItemType.NOTE, AssistantItemType.SHOPPING)
            .forEach { assertTrue("$it", AssistantCardModel.of(item(type = it), now).checkable) }
        listOf(AssistantItemType.ALARM, AssistantItemType.EVENT, AssistantItemType.ALERT,
            AssistantItemType.FINANCE)
            .forEach { assertFalse("$it", AssistantCardModel.of(item(type = it), now).checkable) }
    }

    // ── Titles ────────────────────────────────────────────────────────────────

    @Test fun `a blank title falls back rather than rendering an empty card`() {
        assertEquals("(sin título)", AssistantCardModel.of(item(title = "   "), now).title)
    }

    @Test fun `every type maps to an accent, an emoji and a label`() {
        AssistantItemType.entries.forEach { type ->
            assertTrue("$type accent", AssistantCardModel.accentName(type).isNotBlank())
            assertTrue("$type emoji", AssistantCardModel.emoji(type).isNotBlank())
            assertTrue("$type label", AssistantCardModel.label(type).isNotBlank())
        }
    }

    @Test fun `accent names all resolve to a real palette entry`() {
        AssistantItemType.entries.forEach { type ->
            val name = AssistantCardModel.accentName(type)
            val accent = com.blackclaw.android.ui.design.ClawPalette.forCategory(name)
            // forCategory falls back to Signature for anything unknown, so a mismatch
            // between the two files would silently make every card the same colour.
            assertEquals("$type should not fall back", name, accent.name)
        }
    }
}
