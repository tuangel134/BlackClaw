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
    private const val KEY_MUTED_APPS = "proactive_muted_apps"
    private const val KEY_NEVER_AUTO_MUTE_MIGRATED = "proactive_never_auto_mute_v1"
    // ── Efficiency ──
    private const val KEY_PREFILTER = "proactive_prefilter"
    private const val KEY_MAX_CLASSIFY_HOUR = "proactive_max_classify_hour"
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
    private const val KEY_AUTO_MORNING_ALARMS = "proactive_auto_morning_alarms"
    // ── Weekly finance summary ──
    private const val KEY_WEEKLY_ENABLED = "proactive_weekly_finance_enabled"
    private const val KEY_WEEKLY_DAY = "proactive_weekly_finance_day"   // Calendar.DAY_OF_WEEK (1=Sun..7=Sat)
    private const val KEY_WEEKLY_HOUR = "proactive_weekly_finance_hour"
    private const val KEY_WEEKLY_MIN = "proactive_weekly_finance_min"

    /** Default guidance the user can edit — conservative about intrusive timed actions. */
    const val DEFAULT_INSTRUCTIONS =
        "Una hora mencionada NO significa que yo acepté un plan. Trata invitaciones, propuestas y " +
        "'podríamos vernos' como PENDIENTES hasta tener evidencia de que YO acepté o confirmé. " +
        "Si mi respuesta dice 'no puedo', 'no voy', 'tal vez', 'te confirmo', 'siempre no' o cancela el plan, " +
        "NO crees alarma ni evento; cancela o reprograma cualquier elemento proactivo enlazado si corresponde. " +
        "Crea alarmas automáticamente solo para compromisos realmente confirmados y con alta certeza. " +
        "Para notificaciones autoritativas como un vuelo ya reservado, una cita confirmada o calendario del usuario, " +
        "puedes tratarlas como confirmadas si el contenido lo demuestra. " +
        "Si yo prometo algo ('te llamo mañana', 'el lunes te paso eso') y el contexto muestra que fui yo, " +
        "puedes crear un recordatorio. Detecta cargos/pagos reales para finanzas y evita duplicados. " +
        "Cuando falte evidencia de aceptación, espera o sugiere; no inventes un compromiso."

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
        get() = KVUtils.getBoolean(KEY_ALLOW_CALENDAR, true)
        set(v) { KVUtils.putBoolean(KEY_ALLOW_CALENDAR, v); KVUtils.sync() }

    var allowFinance: Boolean
        get() = KVUtils.getBoolean(KEY_ALLOW_FINANCE, true)
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

    fun watchedAppSet(): Set<String> = watchedApps.split(",")
        .map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun isAppWatched(pkg: String): Boolean {
        restoreLegacyAutoMutedApps()
        if (isAppMuted(pkg)) return false
        if (watchAllApps) return true
        return pkg in watchedAppSet()
    }

    fun setAppWatched(pkg: String, watched: Boolean) {
        if (pkg.isBlank()) return
        val set = watchedAppSet().toMutableSet()
        if (watched) set.add(pkg) else set.remove(pkg)
        watchedApps = set.sorted().joinToString(",")
    }

    fun replaceWatchedApps(packages: Collection<String>) {
        watchedApps = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString(",")
    }

    /** Comma-separated packages the assistant auto-muted after learning they're
     *  almost always ignored. Excluded from watching regardless of watchAllApps. */
    var mutedApps: String
        get() = KVUtils.getString(KEY_MUTED_APPS, "")
        set(v) { KVUtils.putString(KEY_MUTED_APPS, v); KVUtils.sync() }

    fun isAppMuted(pkg: String): Boolean =
        pkg.isNotBlank() && mutedApps.split(",").map { it.trim() }.any { it.isNotEmpty() && it == pkg }

    fun mutedAppList(): List<String> =
        mutedApps.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun muteApp(pkg: String) {
        if (pkg.isBlank() || isAppMuted(pkg)) return
        val set = mutedAppList().toMutableSet().apply { add(pkg) }
        mutedApps = set.joinToString(",")
    }

    fun clearMutedApps() { mutedApps = "" }

    /** One-time recovery from the removed auto-mute policy. */
    @Synchronized
    fun restoreLegacyAutoMutedApps() {
        if (KVUtils.getBoolean(KEY_NEVER_AUTO_MUTE_MIGRATED, false)) return
        clearMutedApps()
        KVUtils.putBoolean(KEY_NEVER_AUTO_MUTE_MIGRATED, true)
        KVUtils.sync()
    }

    // ──────────────────────── Efficiency ────────────────────────

    /** Cheap local pre-filter: skip the LLM entirely for notifications with no
     *  actionable cue (time/money/commitment). Saves tokens, battery and (in
     *  local mode) RAM. Default on. */
    var prefilterEnabled: Boolean
        get() = KVUtils.getBoolean(KEY_PREFILTER, true)
        set(v) { KVUtils.putBoolean(KEY_PREFILTER, v); KVUtils.sync() }

    /** Max LLM classification calls per rolling hour (separate from action cap).
     *  Guards against notification storms hammering the model. Default 40. */
    var maxClassificationsPerHour: Int
        get() = KVUtils.getInt(KEY_MAX_CLASSIFY_HOUR, 40)
        set(v) { KVUtils.putInt(KEY_MAX_CLASSIFY_HOUR, v.coerceIn(1, 500)); KVUtils.sync() }

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

    /** Max autonomous actions per rolling hour (anti-runaway). Default 20. */
    var maxActionsPerHour: Int
        get() = KVUtils.getInt(KEY_MAX_ACTIONS_HOUR, 20)
        set(v) { KVUtils.putInt(KEY_MAX_ACTIONS_HOUR, v.coerceIn(1, 100)); KVUtils.sync() }

    /** When unsure, suggest/ask instead of acting silently.
     *  Default true: a false-positive alarm is more disruptive than a missed suggestion. */
    var askWhenUnsure: Boolean
        get() = KVUtils.getBoolean(KEY_ASK_WHEN_UNSURE, true)
        set(v) { KVUtils.putBoolean(KEY_ASK_WHEN_UNSURE, v); KVUtils.sync() }

    /** Allow opening the chat to read a truncated/redacted message via a11y.
     *  Default true — the assistant reads the full message for better decisions. */
    var deepRead: Boolean
        get() = KVUtils.getBoolean(KEY_DEEP_READ, true)
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

    /** Night briefing may prepare alarms only for confirmed commitments. Off by default to avoid surprise alarms. */
    var autoMorningAlarms: Boolean
        get() = KVUtils.getBoolean(KEY_AUTO_MORNING_ALARMS, false)
        set(v) { KVUtils.putBoolean(KEY_AUTO_MORNING_ALARMS, v); KVUtils.sync() }

    /** Read important proactive alerts aloud when voice mode is on. */
    var speakAlerts: Boolean
        get() = KVUtils.getBoolean("proactive_speak_alerts", true)
        set(v) { KVUtils.putBoolean("proactive_speak_alerts", v); KVUtils.sync() }

    /** Auto-create detected habits. Explicitly detected, reversible habits are low-risk. */
    var autoCreateHabits: Boolean
        get() = KVUtils.getBoolean("proactive_auto_create_habits", true)
        set(v) { KVUtils.putBoolean("proactive_auto_create_habits", v); KVUtils.sync() }

    // ──────────────────────── Weekly finance summary ────────────────────────

    /** Weekly recap of spending/income vs budget. Off by default. */
    var weeklyFinanceEnabled: Boolean
        get() = KVUtils.getBoolean(KEY_WEEKLY_ENABLED, false)
        set(v) { KVUtils.putBoolean(KEY_WEEKLY_ENABLED, v); KVUtils.sync() }

    /** Day of week to deliver it (Calendar.DAY_OF_WEEK; 1=Sun..7=Sat). Default Sunday. */
    var weeklyFinanceDay: Int
        get() = KVUtils.getInt(KEY_WEEKLY_DAY, java.util.Calendar.SUNDAY)
        set(v) { KVUtils.putInt(KEY_WEEKLY_DAY, v.coerceIn(1, 7)); KVUtils.sync() }

    var weeklyFinanceHour: Int
        get() = KVUtils.getInt(KEY_WEEKLY_HOUR, 20)
        set(v) { KVUtils.putInt(KEY_WEEKLY_HOUR, v.coerceIn(0, 23)); KVUtils.sync() }

    var weeklyFinanceMinute: Int
        get() = KVUtils.getInt(KEY_WEEKLY_MIN, 0)
        set(v) { KVUtils.putInt(KEY_WEEKLY_MIN, v.coerceIn(0, 59)); KVUtils.sync() }
}
