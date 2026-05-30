package com.blackclaw.android.assistant

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Lenient natural-ish datetime parsing shared by the assistant tools and UI.
 * Accepts what the LLM tends to emit: "2026-12-31 23:59", "tomorrow 09:00",
 * "today 14:30", "in 30m", "in 2h", or a bare "07:30" (next occurrence).
 */
object AssistantTime {

    /** Returns epoch ms, or 0 if unparseable. */
    fun parse(input: String?): Long {
        if (input.isNullOrBlank()) return 0L
        val s = input.trim().lowercase()
        val now = System.currentTimeMillis()

        // Relative: "in 30m", "in 2h", "in 90s"
        Regex("""in\s+(\d+)\s*(s|sec|seconds?|m|min|minutes?|h|hours?|d|days?)""")
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

        // "today HH:mm" / "tomorrow HH:mm" / bare "HH:mm"
        val timeMatch = Regex("""(\d{1,2}):(\d{2})""").find(s)
        if (timeMatch != null) {
            val h = timeMatch.groupValues[1].toInt().coerceIn(0, 23)
            val min = timeMatch.groupValues[2].toInt().coerceIn(0, 59)
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            when {
                s.contains("tomorrow") || s.contains("mañana") -> cal.add(Calendar.DAY_OF_YEAR, 1)
                s.contains("today") || s.contains("hoy") -> { /* keep today */ }
                else -> if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1) // next occurrence
            }
            return cal.timeInMillis
        }
        // date only "yyyy-MM-dd"
        runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(input.trim())?.let { return it.time }
        }
        return 0L
    }

    fun format(ms: Long): String {
        if (ms <= 0) return "—"
        return SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(ms))
    }
}
