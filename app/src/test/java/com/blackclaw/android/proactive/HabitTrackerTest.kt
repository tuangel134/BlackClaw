package com.blackclaw.android.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Tests for [HabitTracker.detect] — the pure habit-detection function that finds
 * recurring (kind, hour, weekday) patterns. No Android needed.
 */
class HabitTrackerTest {

    private val now = System.currentTimeMillis()
    private val day = 24L * 60 * 60 * 1000

    private fun sig(kind: String, hour: Int, dow: Int, daysAgo: Long) =
        HabitTracker.Signal(kind, hour, dow, now - daysAgo * day)

    @Test
    fun detectsRepeatingPattern() {
        val signals = listOf(
            sig("alarm", 7, Calendar.MONDAY, 21),
            sig("alarm", 7, Calendar.MONDAY, 14),
            sig("alarm", 7, Calendar.MONDAY, 7),
        )
        val habits = HabitTracker.detect(signals, now)
        assertEquals(1, habits.size)
        assertEquals("alarm", habits[0].kind)
        assertEquals(7, habits[0].hour)
        assertEquals(Calendar.MONDAY, habits[0].dayOfWeek)
        assertEquals(3, habits[0].count)
    }

    @Test
    fun belowThresholdIsNotAHabit() {
        val signals = listOf(
            sig("alarm", 7, Calendar.MONDAY, 14),
            sig("alarm", 7, Calendar.MONDAY, 7),
        )
        assertTrue(HabitTracker.detect(signals, now).isEmpty())
    }

    @Test
    fun differentHoursDoNotGroup() {
        val signals = listOf(
            sig("alarm", 7, Calendar.MONDAY, 21),
            sig("alarm", 8, Calendar.MONDAY, 14),
            sig("alarm", 9, Calendar.MONDAY, 7),
        )
        assertTrue(HabitTracker.detect(signals, now).isEmpty())
    }

    @Test
    fun oldSignalsOutsideWindowAreIgnored() {
        val signals = listOf(
            sig("alarm", 7, Calendar.MONDAY, 200),
            sig("alarm", 7, Calendar.MONDAY, 190),
            sig("alarm", 7, Calendar.MONDAY, 180),
        )
        // Default window is ~60 days, so 180+ days ago shouldn't count.
        assertTrue(HabitTracker.detect(signals, now).isEmpty())
    }

    @Test
    fun multipleHabitsSortedByCount() {
        val signals = mutableListOf<HabitTracker.Signal>()
        // 5x reminder at 9 on Friday
        repeat(5) { signals.add(sig("reminder", 9, Calendar.FRIDAY, (it * 2 + 1).toLong())) }
        // 3x alarm at 7 on Monday
        repeat(3) { signals.add(sig("alarm", 7, Calendar.MONDAY, (it * 2 + 1).toLong())) }
        val habits = HabitTracker.detect(signals, now)
        assertEquals(2, habits.size)
        // Most frequent first.
        assertEquals("reminder", habits[0].kind)
        assertEquals(5, habits[0].count)
        assertEquals("alarm", habits[1].kind)
    }

    @Test
    fun habitIdIsStable() {
        val h = HabitTracker.Habit("alarm", 7, Calendar.MONDAY, 3)
        assertEquals("alarm|7|${Calendar.MONDAY}", h.id)
    }

    @Test
    fun blankKindIgnored() {
        val signals = listOf(
            sig("", 7, Calendar.MONDAY, 3),
            sig("", 7, Calendar.MONDAY, 2),
            sig("", 7, Calendar.MONDAY, 1),
        )
        assertTrue(HabitTracker.detect(signals, now).isEmpty())
    }

    @Test
    fun describeMentionsTimeAndCount() {
        val h = HabitTracker.Habit("alarm", 7, Calendar.MONDAY, 4)
        val text = HabitTracker.describe(h)
        assertTrue(text.contains("07:00"))
        assertTrue(text.contains("lunes"))
        assertTrue(text.contains("4"))
    }
}
