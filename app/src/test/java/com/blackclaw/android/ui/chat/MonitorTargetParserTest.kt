package com.blackclaw.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitorTargetParserTest {

    @Test
    fun `parses telegram monitor target`() {
        val result = MonitorTargetParser.fromTaskText("monitor Mom on Telegram")

        requireNotNull(result)
        assertEquals("Mom", result.label)
        assertEquals("Telegram", result.app)
    }

    @Test
    fun `defaults to whatsapp when app missing`() {
        val result = MonitorTargetParser.fromTaskText("monitor girlfriend")

        requireNotNull(result)
        assertEquals("Girlfriend", result.label)
        assertEquals("WhatsApp", result.app)
    }

    @Test
    fun `does not mistake caroline for line app`() {
        val result = MonitorTargetParser.fromTaskText("monitor Caroline")

        requireNotNull(result)
        assertEquals("Caroline", result.label)
        assertEquals("WhatsApp", result.app)
    }

    @Test
    fun `parses messages aliases`() {
        val result = MonitorTargetParser.fromTaskText("watch Alex on sms")

        requireNotNull(result)
        assertEquals("Alex", result.label)
        assertEquals("Messages", result.app)
    }

    @Test
    fun `returns null when no target remains`() {
        assertNull(MonitorTargetParser.fromTaskText("monitor"))
    }
}
