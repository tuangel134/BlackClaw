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
    private const val KEY_QUIET_ONLY_IMPORTANT = "proactive_quiet_only_important"
    private const val KEY_WATCH_ALL_APPS = "proactive_watch_all_apps"
    private const val KEY_WATCHED_APPS = "proactive_watched_apps"

    /** Default guidance the user can edit — sets the assistant's judgment. */
    const val DEFAULT_INSTRUCTIONS =
        "Avísame y actúa solo cuando algo sea realmente importante o requiera una " +
        "acción con tiempo: citas, reuniones, horas a las que tengo que estar en un " +
        "lugar, vuelos, fechas límite, pagos, o recordatorios explícitos. " +
        "Si un mensaje menciona una hora a la que debo estar en algún sitio y no tengo " +
        "una alarma, ponla. Ignora promociones, spam, redes sociales y charla casual."

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
}
