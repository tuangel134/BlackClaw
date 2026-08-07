package com.blackclaw.android.ui.assistant

import com.blackclaw.android.assistant.AssistantItem
import com.blackclaw.android.assistant.AssistantItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wording and progress rules for the hero card.
 *
 * Only the counting/shopping paths are covered here: the finance path reads
 * aggregates from AssistantStore, which needs MMKV and therefore a device.
 */
class AssistantSummaryTest {

    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L

    private fun item(
        title: String = "algo",
        triggerAtMs: Long = 0L,
        done: Boolean = false,
    ) = AssistantItem(
        id = title, type = AssistantItemType.REMINDER, title = title,
        triggerAtMs = triggerAtMs, done = done,
    )

    // ── Counting categories ───────────────────────────────────────────────────

    @Test fun `an empty category says so and hides the progress bar`() {
        val s = AssistantSummary.timedSummary(emptyList(), now)
        assertEquals("Nada aún", s.headline)
        assertEquals(AssistantSummary.NO_PROGRESS, s.progress)
    }

    @Test fun `all done reports being up to date with a full bar`() {
        val s = AssistantSummary.timedSummary(listOf(item(done = true), item("b", done = true)), now)
        assertEquals("Todo al día", s.headline)
        assertEquals(1f, s.progress, 0.001f)
    }

    @Test fun `pending count is pluralised`() {
        assertEquals("1 pendiente", AssistantSummary.timedSummary(listOf(item()), now).headline)
        assertEquals("2 pendientes",
            AssistantSummary.timedSummary(listOf(item("a"), item("b")), now).headline)
    }

    @Test fun `progress is the completed fraction`() {
        val items = listOf(item("a", done = true), item("b"), item("c"), item("d"))
        assertEquals(0.25f, AssistantSummary.timedSummary(items, now).progress, 0.001f)
    }

    @Test fun `overdue takes the subtitle even when something is scheduled next`() {
        // Missing something already is the only fact here worth acting on, so it must
        // not be buried under a generic "next up".
        val items = listOf(
            item("vencido", triggerAtMs = now - hour),
            item("futuro", triggerAtMs = now + hour),
        )
        val s = AssistantSummary.timedSummary(items, now)
        assertTrue(s.subtitle, s.subtitle.contains("vencido"))
        assertTrue(s.subtitle, s.subtitle.contains("atención"))
    }

    @Test fun `overdue count is pluralised`() {
        val one = AssistantSummary.timedSummary(listOf(item("a", triggerAtMs = now - hour)), now)
        assertTrue(one.subtitle, one.subtitle.startsWith("1 vencido "))
        val two = AssistantSummary.timedSummary(
            listOf(item("a", triggerAtMs = now - hour), item("b", triggerAtMs = now - 2 * hour)), now)
        assertTrue(two.subtitle, two.subtitle.startsWith("2 vencidos "))
    }

    @Test fun `with nothing overdue the subtitle previews the next item`() {
        val items = listOf(item("Dentista", triggerAtMs = now + 2 * hour))
        val s = AssistantSummary.timedSummary(items, now)
        assertTrue(s.subtitle, s.subtitle.contains("Próximo"))
        assertTrue(s.subtitle, s.subtitle.contains("Dentista"))
    }

    @Test fun `a done item in the past is not counted as overdue`() {
        val s = AssistantSummary.timedSummary(listOf(item(triggerAtMs = now - hour, done = true)), now)
        assertEquals("Todo al día", s.headline)
        assertTrue(s.subtitle, !s.subtitle.contains("vencido"))
    }

    @Test fun `pending items without a time say so instead of inventing one`() {
        val s = AssistantSummary.timedSummary(listOf(item(triggerAtMs = 0L)), now)
        assertEquals("Sin hora asignada", s.subtitle)
    }

    @Test fun `a very long next title is truncated so the hero cannot overflow`() {
        val long = "x".repeat(200)
        val s = AssistantSummary.timedSummary(listOf(item(long, triggerAtMs = now + hour)), now)
        assertTrue(s.subtitle.length < 120)
    }

    // ── Shopping ──────────────────────────────────────────────────────────────

    @Test fun `an empty shopping list is described as empty`() {
        val s = AssistantSummary.shoppingSummary(emptyList())
        assertEquals("Lista vacía", s.headline)
        assertEquals(AssistantSummary.NO_PROGRESS, s.progress)
    }

    @Test fun `a fully bought list is described as complete`() {
        val s = AssistantSummary.shoppingSummary(listOf(item(done = true)))
        assertEquals("Lista completa", s.headline)
        assertEquals(1f, s.progress, 0.001f)
    }

    @Test fun `a partial list counts what is left and what is in the cart`() {
        val items = listOf(item("a", done = true), item("b"), item("c"))
        val s = AssistantSummary.shoppingSummary(items)
        assertEquals("2 por comprar", s.headline)
        assertEquals("1 de 3 en el carrito", s.subtitle)
        assertEquals(1f / 3f, s.progress, 0.001f)
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    @Test fun `of dispatches shopping to the shopping rules`() {
        val s = AssistantSummary.of(AssistantItemType.SHOPPING, emptyList(), now)
        assertEquals("Lista vacía", s.headline)
    }

    @Test fun `of dispatches every timed type to the counting rules`() {
        listOf(
            AssistantItemType.REMINDER, AssistantItemType.ALARM, AssistantItemType.NOTE,
            AssistantItemType.EVENT, AssistantItemType.ALERT,
        ).forEach { type ->
            assertEquals("$type", "Nada aún", AssistantSummary.of(type, emptyList(), now).headline)
        }
    }

    @Test fun `money formatting keeps two decimals`() {
        assertTrue(AssistantSummary.formatMoney(1234.5).endsWith("1,234.50") ||
            AssistantSummary.formatMoney(1234.5).endsWith("1.234,50"))
    }
}
