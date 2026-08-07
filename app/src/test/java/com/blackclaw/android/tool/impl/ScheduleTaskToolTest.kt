package com.blackclaw.android.tool.impl

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleTaskToolTest {
    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.JULY, 18, 16, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun `parses Spanish timer`() {
        assertEquals(now + 30 * 60_000L, ScheduleTaskTool().parseWhen("en 30 minutos", now))
    }

    @Test fun `parses Spanish today and tomorrow clock`() {
        val today = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 17) }.timeInMillis
        val tomorrow = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        assertEquals(today, ScheduleTaskTool().parseWhen("hoy a las 17:00", now))
        assertEquals(tomorrow, ScheduleTaskTool().parseWhen("mañana a las 17:00", now))
    }
}
