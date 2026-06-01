package com.blackclaw.android.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.Calendar
import org.junit.Test

/**
 * Pure-JVM tests for [AssistantTime.parse] — the natural-ish datetime parser the
 * LLM and UI both rely on. No Android APIs are touched, so these run on the JVM.
 */
class AssistantTimeTest {

    @Test
    fun blankInputReturnsZero() {
        assertEquals(0L, AssistantTime.parse(null))
        assertEquals(0L, AssistantTime.parse(""))
        assertEquals(0L, AssistantTime.parse("   "))
    }

    @Test
    fun garbageInputReturnsZero() {
        assertEquals(0L, AssistantTime.parse("not a date at all"))
    }

    @Test
    fun relativeMinutes() {
        val before = System.currentTimeMillis()
        val r = AssistantTime.parse("in 30m")
        val after = System.currentTimeMillis()
        // Should be ~30 min in the future, within the call window.
        assertTrue(r >= before + 30 * 60_000)
        assertTrue(r <= after + 30 * 60_000 + 1000)
    }

    @Test
    fun relativeHours() {
        val before = System.currentTimeMillis()
        val r = AssistantTime.parse("in 2h")
        assertTrue(r >= before + 2 * 3_600_000)
    }

    @Test
    fun relativeSeconds() {
        val before = System.currentTimeMillis()
        val r = AssistantTime.parse("in 90s")
        assertTrue(r >= before + 90_000)
    }

    @Test
    fun absoluteIsoDateTime() {
        val r = AssistantTime.parse("2030-12-31 23:59")
        val cal = Calendar.getInstance().apply { timeInMillis = r }
        assertEquals(2030, cal.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, cal.get(Calendar.MONTH))
        assertEquals(31, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, cal.get(Calendar.MINUTE))
    }

    @Test
    fun bareTimeRollsToNextOccurrence() {
        val r = AssistantTime.parse("07:30")
        assertTrue("bare time should resolve to a future instant", r > System.currentTimeMillis())
        val cal = Calendar.getInstance().apply { timeInMillis = r }
        assertEquals(7, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun tomorrowKeyword() {
        val r = AssistantTime.parse("tomorrow 09:00")
        val today = Calendar.getInstance()
        val cal = Calendar.getInstance().apply { timeInMillis = r }
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertTrue("tomorrow should be in the future", r > System.currentTimeMillis())
        // Day-of-year should differ from today (handles year wrap via > check above).
        assertTrue(cal.get(Calendar.DAY_OF_YEAR) != today.get(Calendar.DAY_OF_YEAR) ||
            cal.get(Calendar.YEAR) != today.get(Calendar.YEAR))
    }

    @Test
    fun spanishMananaKeyword() {
        val r = AssistantTime.parse("mañana 08:15")
        val cal = Calendar.getInstance().apply { timeInMillis = r }
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, cal.get(Calendar.MINUTE))
        assertTrue(r > System.currentTimeMillis())
    }

    @Test
    fun formatRendersDashForEmpty() {
        assertEquals("—", AssistantTime.format(0L))
        assertEquals("—", AssistantTime.format(-5L))
    }

    @Test
    fun formatRendersSomethingForValidMs() {
        val out = AssistantTime.format(System.currentTimeMillis())
        assertTrue(out.isNotBlank())
        assertTrue(out != "—")
    }
}
