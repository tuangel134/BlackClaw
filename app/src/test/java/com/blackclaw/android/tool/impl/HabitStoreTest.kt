package com.blackclaw.android.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*

/**
 * Tests for the pure streak-calculation and icon logic in HabitStore.
 * Storage paths need MMKV, but calculateStreak() and autoIcon() are pure.
 */
class HabitStoreTest {

    private fun dateNDaysAgo(n: Int): String {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -n) }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    private fun log(habit: String, daysAgo: Int) = HabitStore.HabitLog(
        habit = habit,
        date = dateNDaysAgo(daysAgo),
        value = "",
        note = "",
        timestamp = System.currentTimeMillis() - daysAgo * 86_400_000L,
    )

    @Test
    fun noLogsMeansZeroStreak() {
        assertEquals(0, HabitStore.calculateStreak("agua", emptyList()))
    }

    @Test
    fun singleLogTodayIsStreakOne() {
        val logs = listOf(log("agua", 0))
        assertEquals(1, HabitStore.calculateStreak("agua", logs))
    }

    @Test
    fun consecutiveDaysBuildStreak() {
        val logs = listOf(log("agua", 0), log("agua", 1), log("agua", 2))
        assertEquals(3, HabitStore.calculateStreak("agua", logs))
    }

    @Test
    fun gapBreaksStreak() {
        // today, yesterday, then a gap (3 days ago missing the day before)
        val logs = listOf(log("agua", 0), log("agua", 1), log("agua", 4))
        assertEquals(2, HabitStore.calculateStreak("agua", logs))
    }

    @Test
    fun streakFromYesterdayStillCounts() {
        // last log was yesterday (user hasn't logged today yet) — streak preserved
        val logs = listOf(log("agua", 1), log("agua", 2))
        assertEquals(2, HabitStore.calculateStreak("agua", logs))
    }

    @Test
    fun oldStreakWithNoRecentActivityIsZero() {
        // last log was 5 days ago — streak is broken
        val logs = listOf(log("agua", 5), log("agua", 6))
        assertEquals(0, HabitStore.calculateStreak("agua", logs))
    }

    @Test
    fun multipleLogsSameDayCountAsOneStreakDay() {
        val logs = listOf(log("agua", 0), log("agua", 0), log("agua", 1))
        assertEquals(2, HabitStore.calculateStreak("agua", logs))
    }

    @Test
    fun ignoresOtherHabits() {
        val logs = listOf(log("agua", 0), log("ejercicio", 1), log("agua", 1))
        assertEquals(2, HabitStore.calculateStreak("agua", logs))
    }

    @Test
    fun autoIconMatchesKnownHabits() {
        assertEquals("💧", HabitStore.autoIcon("beber agua"))
        assertEquals("🏋️", HabitStore.autoIcon("ir al gym"))
        assertEquals("🧘", HabitStore.autoIcon("meditar"))
        assertEquals("📖", HabitStore.autoIcon("leer un libro"))
        assertEquals("🚭", HabitStore.autoIcon("no fumar"))
    }

    @Test
    fun autoIconFallsBackToCheckmark() {
        assertEquals("✅", HabitStore.autoIcon("algo random sin keyword"))
    }
}
