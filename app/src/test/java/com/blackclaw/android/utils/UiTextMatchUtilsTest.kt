package com.blackclaw.android.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTextMatchUtilsTest {

    @Test
    fun `matches exact text ignoring punctuation and case`() {
        assertTrue(UiTextMatchUtils.matchesExactOrNormalized("Allow!", "allow"))
        assertTrue(UiTextMatchUtils.matchesExactOrNormalized("Monica (Work)", "monica work"))
    }

    @Test
    fun `relaxed matching allows extra words around query`() {
        assertTrue(UiTextMatchUtils.matchesRelaxed("Allow to open", "allow"))
        assertTrue(UiTextMatchUtils.matchesRelaxed("Send message to Monica", "monica"))
    }

    @Test
    fun `relaxed matching handles digit formatting differences`() {
        assertTrue(UiTextMatchUtils.matchesRelaxed("+1 (604) 555-1234", "6045551234"))
    }

    @Test
    fun `exact matching does not overmatch partial short text`() {
        assertFalse(UiTextMatchUtils.matchesExactOrNormalized("Open", "op"))
        assertFalse(UiTextMatchUtils.matchesExactOrNormalized("Allow to open", "allow"))
    }

    @Test
    fun `relaxed matching still rejects unrelated text`() {
        assertFalse(UiTextMatchUtils.matchesRelaxed("Telegram", "whatsapp"))
    }
}
