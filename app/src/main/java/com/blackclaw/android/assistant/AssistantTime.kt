package com.blackclaw.android.assistant

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Lenient natural-ish datetime parsing shared by the assistant tools and UI.
 * Accepts what the LLM tends to emit: "2026-12-31 23:59", "tomorrow 09:00",
 * "today 14:30", "in 30m", "in 2h", or a bare "07:30" (next occurrence).
 *
 * V2: Extended with Spanish day names, "el lunes", "pasado mañana",
 * relative expressions in Spanish ("en 30 min", "dentro de 2h"),
 * and better weekday resolution.
 */
object AssistantTime {

    private val WEEKDAY_MAP = mapOf(
        "lunes" to Calendar.MONDAY, "monday" to Calendar.MONDAY,
        "martes" to Calendar.TUESDAY, "tuesday" to Calendar.TUESDAY,
        "miercoles" to Calendar.WEDNESDAY, "miércoles" to Calendar.WEDNESDAY, "wednesday" to Calendar.WEDNESDAY,
        "jueves" to Calendar.THURSDAY, "thursday" to Calendar.THURSDAY,
        "viernes" to Calendar.FRIDAY, "friday" to Calendar.FRIDAY,
        "sabado" to Calendar.SATURDAY, "sábado" to Calendar.SATURDAY, "saturday" to Calendar.SATURDAY,
        "domingo" to Calendar.SUNDAY, "sunday" to Calendar.SUNDAY,
    )

    /** Returns epoch ms, or 0 if unparseable. */
    fun parse(input: String?): Long {
        if (input.isNullOrBlank()) return 0L
        val s = input.trim().lowercase()
        val now = System.currentTimeMillis()

        // Relative: "in 30m", "in 2h", "in 90s", "en 30 min", "dentro de 2h"
        Regex("""(?:in|en|dentro\s+de)\s+(\d+)\s*(s|sec|seconds?|m|min|minutes?|minutos?|h|hours?|horas?|d|days?|dias?|días?)""")
            .find(s)?.let { m ->
                val n = m.groupValues[1].toLong()
                val unit = m.groupValues[2]
                val ms = when {
                    unit.startsWith("s") -> n * 1000
                    unit.startsWith("m") -> n * 60_000
                    unit.startsWith("h") -> n * 3_600_000
                    unit.startsWith("d") -> n * 86_400_000
                    else -> 0
                }
                return now + ms
            }

        // Absolute formats.
        val patterns = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy/MM/dd HH:mm", "dd/MM/yyyy HH:mm")
        for (p in patterns) {
            runCatching {
                SimpleDateFormat(p, Locale.getDefault()).parse(input.trim())?.let { return it.time }
            }
        }

        // Extract time component (HH:mm) — used in combination with day resolution below
        val timeMatch = Regex("""(\d{1,2}):(\d{2})""").find(s)
        val h: Int
        val min: Int
        if (timeMatch != null) {
            h = timeMatch.groupValues[1].toInt().coerceIn(0, 23)
            min = timeMatch.groupValues[2].toInt().coerceIn(0, 59)
        } else {
            // No time found — if we can identify a day, default to 09:00
            h = 9; min = 0
        }

        // Resolve the day
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }

        // "pasado mañana" / "day after tomorrow"
        if (s.contains("pasado mañana") || s.contains("day after tomorrow")) {
            cal.add(Calendar.DAY_OF_YEAR, 2)
            return cal.timeInMillis
        }

        // "tomorrow" / "mañana"
        if (s.contains("tomorrow") || s.contains("mañana")) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }

        // "today" / "hoy"
        if (s.contains("today") || s.contains("hoy")) {
            // If the time already passed today, still keep today (user explicitly said "hoy")
            return cal.timeInMillis
        }

        // Weekday: "el lunes", "on monday", "el próximo viernes"
        for ((name, dow) in WEEKDAY_MAP) {
            if (s.contains(name)) {
                val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
                var daysAhead = (dow - today + 7) % 7
                if (daysAhead == 0) daysAhead = 7  // "el lunes" when today is Monday → next week
                // But if time hasn't passed and they just say the day name, could mean today
                // Only advance if time already passed today for same-day references
                cal.add(Calendar.DAY_OF_YEAR, daysAhead)
                return cal.timeInMillis
            }
        }

        // Bare "HH:mm" — next occurrence (today if future, tomorrow if past)
        if (timeMatch != null) {
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }

        // date only "yyyy-MM-dd" (add default time 09:00 if no time component)
        runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(input.trim())?.let {
                val dateCal = Calendar.getInstance().apply {
                    time = it
                    set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                return dateCal.timeInMillis
            }
        }
        return 0L
    }

    fun format(ms: Long): String {
        if (ms <= 0) return "—"
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = ms }
        val df = if (now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)) {
            SimpleDateFormat("'hoy' HH:mm", Locale.getDefault())
        } else if (now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) + 1 == target.get(Calendar.DAY_OF_YEAR)) {
            SimpleDateFormat("'mañana' HH:mm", Locale.getDefault())
        } else {
            SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
        }
        return df.format(Date(ms))
    }
}
