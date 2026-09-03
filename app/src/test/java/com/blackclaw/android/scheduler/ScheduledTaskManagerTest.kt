package com.blackclaw.android.scheduler

import com.blackclaw.android.scheduler.ScheduledTaskManager.Mode
import com.blackclaw.android.scheduler.ScheduledTaskManager.Recurrence
import com.blackclaw.android.scheduler.ScheduledTaskManager.ScheduledTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTaskManagerTest {
    private val now = 1_800_000_000_000L

    private fun task(
        id: String,
        triggerAtMs: Long,
        recurrence: Recurrence,
        intervalMs: Long = 0L,
    ) = ScheduledTask(
        id = id,
        mode = Mode.TASK,
        text = "private user task",
        triggerAtMs = triggerAtMs,
        recurrence = recurrence,
        intervalMs = intervalMs,
        createdAtMs = now - 10_000L,
    )

    @Test
    fun `rearm drops expired one shot instead of firing it after reboot`() {
        val stale = task("stale", now - 1_000L, Recurrence.ONCE)

        assertTrue(ScheduledTaskManager.normalizeForRearm(listOf(stale), now).isEmpty())
    }

    @Test
    fun `rearm preserves a future one shot unchanged`() {
        val future = task("future", now + 60_000L, Recurrence.ONCE)

        assertEquals(listOf(future), ScheduledTaskManager.normalizeForRearm(listOf(future), now))
    }

    @Test
    fun `rearm advances recurring task past every missed slot`() {
        val hourly = task("hourly", now - 3 * 60 * 60_000L, Recurrence.HOURLY)

        val normalized = ScheduledTaskManager.normalizeForRearm(listOf(hourly), now).single()

        assertTrue(normalized.triggerAtMs > now)
        assertEquals(now + 60 * 60_000L, normalized.triggerAtMs)
    }

    @Test
    fun `interval rearm enforces one minute minimum`() {
        val interval = task("interval", now - 1L, Recurrence.INTERVAL, intervalMs = 1L)

        val normalized = ScheduledTaskManager.normalizeForRearm(listOf(interval), now).single()

        assertEquals(now + 59_999L, normalized.triggerAtMs)
    }
}
