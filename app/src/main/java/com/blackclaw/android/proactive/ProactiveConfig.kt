package com.blackclaw.android.proactive

import com.blackclaw.android.utils.KVUtils

/**
 * Settings for the Proactive Assistant.
 *
 * When enabled, every incoming notification wakes the AI for a cheap one-shot
 * "is this important, and should I act?" classification. If the AI decides
 * something is actionable (a meeting time with no alarm set, a deadline, a
 * reminder), it can autonomously set an alarm, create a reminder/scheduled
 * task, take a note, or surface a heads-up notification — based on the user's
 * natural-language instructions.
 *
 * This is intentionally separate from Auto-Replies: auto-reply *answers* a
 * contact; the proactive assistant *helps the user* with their own
 * notifications.
 */
object ProactiveConfig {

    private const val KEY_ENABLED = "proactive_enabled"
    private const val KEY_INSTRUCTIONS = "proactive_instructions"
    private const val KEY_ALLOW_ALARMS = "proactive_allow_alarms"
    private const val KEY_ALLOW_REMINDERS = "proactive_allow_reminders"
    private const val KEY_ALLOW_NOTES = "proactive_allow_notes"
    private const val KEY_ALLOW_CALENDAR = "proactive_allow_calendar"
    private const val KEY_ALLOW_FINANCE = "proactive_allow_finance"
    private const val KEY_QUIET_ONLY_IMPORTANT = "proactive_quiet_only_important"
    private const val KEY_WATCH_ALL_APPS = "proactive_watch_all_apps"
    private const val KEY_WATCHED_APPS = "proactive_watched_apps"
    // ── Gating ──
    private const val KEY_QUIET_START = "proactive_quiet_start_hour"   // e.g. 23
    private const val KEY_QUIET_END = "proactive_quiet_end_hour"       // e.g. 7
    private const val KEY_MAX_ACTIONS_HOUR = "proactive_max_actions_hour"
    private const val KEY_ASK_WHEN_UNSURE = "proactive_ask_when_unsure"
    private const val KEY_DEEP_READ = "proactive_deep_read"
    // ── Briefings ──
    private const val KEY_MORNING_ENABLED = "proactive_morning_enabled"
    private const val KEY_MORNING_HOUR = "proactive_morning_hour"
    private const val KEY_MORNING_MIN = "proactive_morning_min"
    private const val KEY_NIGHT_ENABLED = "proactive_night_enabled"
    private const val KEY_NIGHT_HOUR = "proactive_night_hour"
    private const val KEY_NIGHT_MIN = "proactive_night_min"
    private const val KEY_SPEAK_BRIEFINGS = "proactive_speak_briefings"

    /** Default guidance the user can edit — sets the assistant's judgment. */
    const val DEFAULT_INSTRUCTIONS =
        "Avísame y actúa solo cuando algo sea realmente importante o requiera una " +
        "acción con tiempo: citas, reuniones, horas a las que tengo que estar en un " +
        "lugar, vuelos, fechas límite, pagos, o recordatorios explícitos. " +
        "Si un mensaje menciona una hora a la que debo estar en algún sitio y no tengo " +
        "una alarma, ponla. Si yo prometo algo con tiempo (\"te llamo mañana\", \"el lunes " +
        "te paso eso\"), crea un recordatorio de seguimiento. Si detectas un cargo, factura " +
        "o pago, regístralo en finanzas. Ignora promociones, spam, redes sociales y charla casual."

    var enabled: Boolean
        get() = KVUtils.getBoolean(KEY_ENABLED, false)
        set(v) { KVUtils.putBoolean(KEY_ENABLED, v); KVUtils.sync() }

    var instructions: String
        get() = KVUtils.getString(KEY_INSTRUCTIONS, DEFAULT_INSTRUCTIONS)
        set(v) { KVUtils.putString(KEY_INSTRUCTIONS, v); KVUtils.sync() }

    var allowAlarms: Boolean
        get() = KVUtils.getBoolean(KEY_ALLOW_ALARMS, true)
        set(v) { KVUtils.putBoolean(KEY_ALLOW_ALARMS, v); KVUtils.sync() }

    var allowReminders: Boolean
        get() = KVUtils.getBoolean(KEY_ALLOW_REMINDERS, true)
        set(v) { KVUtils.putBoolean(KEY_ALLOW_REMINDERS, v); KVUtils.sync() }

    var allowNotes: Boolean
        get() = KVUtils.getBoolean(KEY_ALLOW_NOTES, true)
        set(v) { KVUtils.putBoolean(KEY_ALLOW_NOTES, v); KVUtils.sync() }

    var allowCalendar: Boolean
        get() = KVUtils.getBoolean(KEY_ALLOW_CALENDAR, false)
        set(v) { KVUtils.putBoolean(KEY_ALLOW_CALENDAR, v); KVUtils.sync() }

    var allowFinance: Boolean
        get() = KVUtils.getBoolean(KEY_ALLOW_FINANCE, false)
        set(v) { KVUtils.putBoolean(KEY_ALLOW_FINANCE, v); KVUtils.sync() }

    /** When true, the assistant acts silently and only notifies for important things. */
    var quietUnlessImportant: Boolean
        get() = KVUtils.getBoolean(KEY_QUIET_ONLY_IMPORTANT, true)
        set(v) { KVUtils.putBoolean(KEY_QUIET_ONLY_IMPORTANT, v); KVUtils.sync() }

    /** Watch every app's notifications, or only a chosen set. */
    var watchAllApps: Boolean
        get() = KVUtils.getBoolean(KEY_WATCH_ALL_APPS, true)
        set(v) { KVUtils.putBoolean(KEY_WATCH_ALL_APPS, v); KVUtils.sync() }

    /** Comma-separated package names to watch when watchAllApps is false. */
    var watchedApps: String
        get() = KVUtils.getString(KEY_WATCHED_APPS,
            "com.whatsapp,org.telegram.messenger,com.google.android.apps.messaging")
        set(v) { KVUtils.putString(KEY_WATCHED_APPS, v); KVUtils.sync() }

    fun isAppWatched(pkg: String): Boolean {
        if (watchAllApps) return true
        return watchedApps.split(",").map { it.trim() }.any { it.isNotEmpty() && it == pkg }
    }

    // ──────────────────────── Gating ────────────────────────

    /** Quiet-hours start hour (0-23). Default 23 (11pm). */
    var quietStartHour: Int
        get() = KVUtils.getInt(KEY_QUIET_START, 23)
        set(v) { KVUtils.putInt(KEY_QUIET_START, v.coerceIn(0, 23)); KVUtils.sync() }

    /** Quiet-hours end hour (0-23). Default 7 (7am). */
    var quietEndHour: Int
        get() = KVUtils.getInt(KEY_QUIET_END, 7)
        set(v) { KVUtils.putInt(KEY_QUIET_END, v.coerceIn(0, 23)); KVUtils.sync() }

    /** Whether quiet hours are active right now (handles wrap past midnight). */
    fun inQuietHours(hour: Int): Boolean {
        val s = quietStartHour; val e = quietEndHour
        if (s == e) return false
        return if (s < e) hour in s until e else (hour >= s || hour < e)
    }

    /** Max autonomous actions per rolling hour (anti-runaway). Default 8. */
    var maxActionsPerHour: Int
        get() = KVUtils.getInt(KEY_MAX_ACTIONS_HOUR, 8)
        set(v) { KVUtils.putInt(KEY_MAX_ACTIONS_HOUR, v.coerceIn(1, 50)); KVUtils.sync() }

    /** When unsure, ask the user (notification) instead of acting silently. */
    var askWhenUnsure: Boolean
        get() = KVUtils.getBoolean(KEY_ASK_WHEN_UNSURE, true)
        set(v) { KVUtils.putBoolean(KEY_ASK_WHEN_UNSURE, v); KVUtils.sync() }

    /** Allow opening the chat to read a truncated/redacted message via a11y. */
    var deepRead: Boolean
        get() = KVUtils.getBoolean(KEY_DEEP_READ, false)
        set(v) { KVUtils.putBoolean(KEY_DEEP_READ, v); KVUtils.sync() }

    // ──────────────────────── Briefings ────────────────────────

    var morningBriefingEnabled: Boolean
        get() = KVUtils.getBoolean(KEY_MORNING_ENABLED, false)
        set(v) { KVUtils.putBoolean(KEY_MORNING_ENABLED, v); KVUtils.sync() }
    var morningHour: Int
        get() = KVUtils.getInt(KEY_MORNING_HOUR, 8)
        set(v) { KVUtils.putInt(KEY_MORNING_HOUR, v.coerceIn(0, 23)); KVUtils.sync() }
    var morningMinute: Int
        get() = KVUtils.getInt(KEY_MORNING_MIN, 0)
        set(v) { KVUtils.putInt(KEY_MORNING_MIN, v.coerceIn(0, 59)); KVUtils.sync() }

    var nightBriefingEnabled: Boolean
        get() = KVUtils.getBoolean(KEY_NIGHT_ENABLED, false)
        set(v) { KVUtils.putBoolean(KEY_NIGHT_ENABLED, v); KVUtils.sync() }
    var nightHour: Int
        get() = KVUtils.getInt(KEY_NIGHT_HOUR, 22)
        set(v) { KVUtils.putInt(KEY_NIGHT_HOUR, v.coerceIn(0, 23)); KVUtils.sync() }
    var nightMinute: Int
        get() = KVUtils.getInt(KEY_NIGHT_MIN, 0)
        set(v) { KVUtils.putInt(KEY_NIGHT_MIN, v.coerceIn(0, 59)); KVUtils.sync() }

    /** Read the briefing aloud via TTS when it fires. */
    var speakBriefings: Boolean
        get() = KVUtils.getBoolean(KEY_SPEAK_BRIEFINGS, false)
        set(v) { KVUtils.putBoolean(KEY_SPEAK_BRIEFINGS, v); KVUtils.sync() }
}
