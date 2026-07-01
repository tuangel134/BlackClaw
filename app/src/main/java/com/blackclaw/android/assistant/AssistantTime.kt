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

        // Relative: "in 30m", "en 2h", "en 3 dias", "en 3 semanas", "dentro de 2 meses"
        Regex("""(?:in|en|dentro\s+de)\s+(\d+)\s*(semanas?|sem|w|meses|mes|segundos?|seg|s|minutos?|min|m|horas?|hrs?|h|d[ií]as?|d)\b""")
            .find(s)?.let { m ->
                val n = m.groupValues[1].toInt()
                val unit = m.groupValues[2]
                val cal = Calendar.getInstance()
                val isDateUnit = when {
                    unit == "w" || unit.startsWith("sem") -> { cal.add(Calendar.WEEK_OF_YEAR, n); true }
                    unit == "mes" || unit == "meses" -> { cal.add(Calendar.MONTH, n); true }
                    unit.startsWith("seg") || unit == "s" -> { cal.add(Calendar.SECOND, n); false }
                    unit.startsWith("min") || unit == "m" -> { cal.add(Calendar.MINUTE, n); false }
                    unit.startsWith("h") -> { cal.add(Calendar.HOUR_OF_DAY, n); false }
                    unit.startsWith("d") -> { cal.add(Calendar.DAY_OF_YEAR, n); true }
                    else -> { return@let }
                }
                // For day/week/month offsets, honor an explicit clock time if the
                // user gave one ("en 3 semanas a las 5"), else default to 09:00.
                if (isDateUnit) {
                    val hm = Regex("""(\d{1,2}):(\d{2})""").find(s)
                    val spoken = if (hm == null) parseSpokenHour(
                        s.substringAfter(m.value).ifBlank { s }) else null
                    when {
                        hm != null -> {
                            cal.set(Calendar.HOUR_OF_DAY, hm.groupValues[1].toInt().coerceIn(0, 23))
                            cal.set(Calendar.MINUTE, hm.groupValues[2].toInt().coerceIn(0, 59))
                        }
                        spoken != null -> {
                            cal.set(Calendar.HOUR_OF_DAY, spoken.first); cal.set(Calendar.MINUTE, spoken.second)
                        }
                        else -> { cal.set(Calendar.HOUR_OF_DAY, 9); cal.set(Calendar.MINUTE, 0) }
                    }
                    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                }
                return cal.timeInMillis
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
        var h: Int
        var min: Int
        var haveTime = true
        if (timeMatch != null) {
            h = timeMatch.groupValues[1].toInt().coerceIn(0, 23)
            min = timeMatch.groupValues[2].toInt().coerceIn(0, 59)
        } else {
            val spoken = parseSpokenHour(s)
            if (spoken != null) {
                h = spoken.first; min = spoken.second
            } else {
                // No time found — if we can identify a day, default to 09:00
                h = 9; min = 0; haveTime = false
            }
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
        if (haveTime) {
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

    /**
     * Parse a spoken-style hour without a colon, common in voice/chat:
     * "a las 7", "las 7 y media", "7 de la tarde", "7pm", "7 y cuarto",
     * "7 menos cuarto", "mediodia", "medianoche". Returns (hour24, minute) or
     * null if no hour is found. Applies AM/PM heuristics for Spanish:
     *  - "de la tarde"/"de la noche"/"pm" → +12 (unless already >=12)
     *  - "de la mañana"/"am"/"madrugada" → keep as-is
     *  - bare small hours (1..7) with no qualifier are left as stated; callers
     *    treat them as the next occurrence.
     */
    private fun parseSpokenHour(s: String): Pair<Int, Int>? {
        if (s.contains("mediodia") || s.contains("mediodía")) return 12 to 0
        if (s.contains("medianoche")) return 0 to 0

        // Find an hour number, ideally near "las"/"la"/"a las" or a qualifier.
        val m = Regex("""(?:a\s+)?las?\s+(\d{1,2})\b""").find(s)
            ?: Regex("""\b(?:a\s+)?(\d{1,2})\b""").find(s) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        if (hour !in 0..23) return null

        var minute = 0
        when {
            s.contains("y media") -> minute = 30
            s.contains("y cuarto") -> minute = 15
            s.contains("menos cuarto") -> { minute = 45; hour = (hour + 23) % 24 }
            else -> Regex("""y\s+(\d{1,2})""").find(s)?.let {
                val mm = it.groupValues[1].toIntOrNull()
                if (mm != null && mm in 1..59) minute = mm
            }
        }

        // AM/PM heuristics.
        val pm = s.contains("tarde") || s.contains("noche") ||
            Regex("""\d\s*pm""").containsMatchIn(s) || s.contains(" pm")
        val am = s.contains("mañana") || s.contains("madrugada") ||
            Regex("""\d\s*am""").containsMatchIn(s) || s.contains(" am")
        if (pm && hour < 12) hour += 12
        if (am && hour == 12) hour = 0
        return hour.coerceIn(0, 23) to minute.coerceIn(0, 59)
    }

    fun format(ms: Long): String {        if (ms <= 0) return "—"
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
