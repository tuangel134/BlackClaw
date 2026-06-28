package com.blackclaw.android.proactive

import com.blackclaw.android.utils.KVUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Habit learning — the first real step toward an assistant that *anticipates*
 * instead of only reacting.
 *
 * Every time a timed item (alarm / reminder) is created, we record a compact
 * signal: its kind, the hour-of-day, and the day-of-week. When the same
 * (kind, hour, weekday) shows up repeatedly, that's a habit, and the assistant
 * can proactively offer to set it up — e.g. "You always set a 7:00 alarm on
 * Mondays; want me to schedule it automatically?".
 *
 * The detection itself is a pure function over a list of signals so it can be
 * unit-tested without Android. Storage is a small capped JSON ring buffer.
 */
object HabitTracker {

    private const val KEY_SIGNALS = "habit_signals_v1"          // JSON array of {kind,hour,dow,t}
    private const val KEY_SUGGESTED = "habit_suggested_v1"      // JSON array of habit ids already offered
    private const val MAX_SIGNALS = 200
    private const val MIN_OCCURRENCES = 3
    /** Only consider signals from roughly the last 60 days. */
    private const val WINDOW_MS = 60L * 24 * 60 * 60 * 1000

    data class Signal(val kind: String, val hour: Int, val dayOfWeek: Int, val t: Long)

    /** A detected recurring pattern. [id] is stable so we don't re-offer it. */
    data class Habit(val kind: String, val hour: Int, val dayOfWeek: Int, val count: Int) {
        val id: String get() = "$kind|$hour|$dayOfWeek"
    }

    // ── Recording ──

    fun record(kind: String, triggerAtMs: Long) {
        if (triggerAtMs <= 0) return
        val cal = Calendar.getInstance().apply { timeInMillis = triggerAtMs }
        val sig = Signal(
            kind = kind.lowercase().trim(),
            hour = cal.get(Calendar.HOUR_OF_DAY),
            dayOfWeek = cal.get(Calendar.DAY_OF_WEEK),
            t = System.currentTimeMillis(),
        )
        synchronized(this) {
            val list = signals().toMutableList()
            list.add(sig)
            val pruned = prune(list, sig.t)
            saveSignals(pruned)
        }
    }

    /**
     * Keep the signal buffer healthy: drop anything older than the detection
     * window (they can never form a habit anymore) and cap the total. Pure.
     */
    fun prune(signals: List<Signal>, now: Long, windowMs: Long = WINDOW_MS, max: Int = MAX_SIGNALS): List<Signal> {
        val cutoff = now - windowMs
        val fresh = signals.filter { it.t >= cutoff }
        return if (fresh.size > max) fresh.takeLast(max) else fresh
    }

    @Synchronized
    fun signals(): List<Signal> {
        val raw = KVUtils.getString(KEY_SIGNALS, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Signal(o.optString("kind"), o.optInt("hour"), o.optInt("dow"), o.optLong("t"))
            }
        }.getOrDefault(emptyList())
    }

    private fun saveSignals(list: List<Signal>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("kind", it.kind); put("hour", it.hour); put("dow", it.dayOfWeek); put("t", it.t)
            })
        }
        KVUtils.putString(KEY_SIGNALS, arr.toString()); KVUtils.sync()
    }

    // ── Detection (pure) ──

    /**
     * Find recurring (kind, hour, dayOfWeek) groups with at least [minOccurrences]
     * signals inside [windowMs] before [now]. Pure — unit-tested directly.
     */
    fun detect(
        signals: List<Signal>,
        now: Long = System.currentTimeMillis(),
        windowMs: Long = WINDOW_MS,
        minOccurrences: Int = MIN_OCCURRENCES,
    ): List<Habit> {
        val cutoff = now - windowMs
        return signals.asSequence()
            .filter { it.t >= cutoff && it.kind.isNotBlank() }
            .groupBy { Triple(it.kind, it.hour, it.dayOfWeek) }
            .filter { it.value.size >= minOccurrences }
            .map { (k, v) -> Habit(k.first, k.second, k.third, v.size) }
            .sortedByDescending { it.count }
            .toList()
    }

    /** Habits not yet offered to the user. */
    fun newHabits(): List<Habit> {
        val offered = suggestedIds()
        return detect(signals()).filter { it.id !in offered }
    }

    @Synchronized
    fun markSuggested(habit: Habit) {
        val list = suggestedIds().toMutableSet()
        list.add(habit.id)
        val arr = JSONArray(); list.forEach { arr.put(it) }
        KVUtils.putString(KEY_SUGGESTED, arr.toString()); KVUtils.sync()
    }

    private fun suggestedIds(): Set<String> {
        val raw = KVUtils.getString(KEY_SUGGESTED, "")
        if (raw.isBlank()) return emptySet()
        return runCatching {
            val a = JSONArray(raw); (0 until a.length()).map { a.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    // ── Human-readable phrasing (Spanish) ──

    private val DOW_ES = mapOf(
        Calendar.SUNDAY to "domingos", Calendar.MONDAY to "lunes", Calendar.TUESDAY to "martes",
        Calendar.WEDNESDAY to "miércoles", Calendar.THURSDAY to "jueves",
        Calendar.FRIDAY to "viernes", Calendar.SATURDAY to "sábados",
    )

    private fun kindEs(kind: String) = when (kind) {
        "alarm" -> "una alarma"
        "reminder" -> "un recordatorio"
        "event" -> "un evento"
        else -> kind
    }

    /** A short description of a detected habit. */
    fun describe(habit: Habit): String {
        val dow = DOW_ES[habit.dayOfWeek] ?: "ese día"
        val hh = "%02d:00".format(habit.hour)
        return "Sueles poner ${kindEs(habit.kind)} a las $hh los $dow (${habit.count} veces)."
    }
}
