package com.blackclaw.android.memory

import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Evolving user profile — BlackClaw's understanding of who the user is.
 *
 * Unlike UserMemoryStore (explicit facts the user asked to save), UserProfile
 * is AUTOMATICALLY built over time by observing patterns:
 * - What time they wake up / go to sleep
 * - Which apps they use most
 * - Who they message frequently
 * - Their work schedule patterns
 * - Preferred alarm times
 * - Spending patterns
 * - Interests and preferences inferred from tasks
 *
 * The profile is injected into the system prompt so the AI "knows" the user
 * better over time — like JARVIS learning Tony's habits.
 */
object UserProfile {

    private const val TAG = "UserProfile"
    private const val KEY_PROFILE = "user_profile_v1"
    private const val KEY_PATTERNS = "user_patterns_v1"
    private const val KEY_INTERACTIONS = "user_interaction_log_v1"
    private const val MAX_INTERACTIONS = 100

    /**
     * Hard cap on the prompt snippet.
     *
     * WHY THIS EXISTS: this section is priority 1 in [MemoryHub], and
     * `MemoryHub.packByPriority` drops a section whole rather than truncating it
     * mid-sentence. Unbounded, a profile that grew past the budget would take every
     * other memory section down with it — so the app would remember *less* the more
     * it learned about you, which is the exact opposite of the intent. 600 chars
     * leaves room for facts, routines and task history inside the 1400-char on-device
     * budget.
     */
    const val MAX_SNIPPET_CHARS = 600

    /**
     * Minimum time between writes of the interaction log.
     *
     * `recordInteraction` used to serialise a 100-entry JSON array and call
     * `KVUtils.sync()` — an explicit fsync — on every single chat message.
     * [com.blackclaw.android.proactive.SmartQuietDetector] already solved this exact
     * problem in this codebase with a write throttle; this mirrors it.
     */
    private const val WRITE_THROTTLE_MS = 30_000L

    /** How many samples an hour bucket needs before it counts as a pattern. */
    private const val MIN_SAMPLES_PER_HOUR = 3

    // ── Profile fields ──

    data class Profile(
        val name: String = "",
        val wakeUpHour: Int = -1,        // -1 = unknown
        val sleepHour: Int = -1,
        val workStartHour: Int = -1,
        val workEndHour: Int = -1,
        val topContacts: List<String> = emptyList(),
        val topApps: List<String> = emptyList(),
        val interests: List<String> = emptyList(),
        val personality: String = "",     // how the user likes to be spoken to
        val routineNotes: String = "",    // free-form notes about their routine
        val preferredAlarmLead: Int = 30, // minutes before event to alarm
        val city: String = "",
        val language: String = "es",
        val traits: Map<String, String> = emptyMap(),  // flexible key-value traits
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("wakeUpHour", wakeUpHour)
            put("sleepHour", sleepHour)
            put("workStartHour", workStartHour)
            put("workEndHour", workEndHour)
            put("topContacts", JSONArray(topContacts))
            put("topApps", JSONArray(topApps))
            put("interests", JSONArray(interests))
            put("personality", personality)
            put("routineNotes", routineNotes)
            put("preferredAlarmLead", preferredAlarmLead)
            put("city", city)
            put("language", language)
            put("traits", JSONObject(traits))
        }

        companion object {
            fun fromJson(o: JSONObject) = Profile(
                name = o.optString("name", ""),
                wakeUpHour = o.optInt("wakeUpHour", -1),
                sleepHour = o.optInt("sleepHour", -1),
                workStartHour = o.optInt("workStartHour", -1),
                workEndHour = o.optInt("workEndHour", -1),
                topContacts = jsonArrayToList(o.optJSONArray("topContacts")),
                topApps = jsonArrayToList(o.optJSONArray("topApps")),
                interests = jsonArrayToList(o.optJSONArray("interests")),
                personality = o.optString("personality", ""),
                routineNotes = o.optString("routineNotes", ""),
                preferredAlarmLead = o.optInt("preferredAlarmLead", 30),
                city = o.optString("city", ""),
                language = o.optString("language", "es"),
                traits = jsonObjToMap(o.optJSONObject("traits")),
            )

            private fun jsonArrayToList(arr: JSONArray?): List<String> {
                if (arr == null) return emptyList()
                return (0 until arr.length()).map { arr.getString(it) }
            }

            private fun jsonObjToMap(obj: JSONObject?): Map<String, String> {
                if (obj == null) return emptyMap()
                return obj.keys().asSequence().associate { it to obj.optString(it, "") }
            }
        }
    }

    // ── Interaction logging (for pattern detection) ──

    data class Interaction(
        val timestamp: Long,
        val type: String,       // "task" | "chat" | "alarm_set" | "app_opened" | "message_sent"
        val detail: String,     // short description
        val hour: Int,
        val dayOfWeek: Int,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("t", timestamp); put("type", type); put("detail", detail)
            put("hour", hour); put("dow", dayOfWeek)
        }

        companion object {
            fun fromJson(o: JSONObject) = Interaction(
                timestamp = o.optLong("t"),
                type = o.optString("type"),
                detail = o.optString("detail"),
                hour = o.optInt("hour"),
                dayOfWeek = o.optInt("dow"),
            )
        }
    }

    // ── Public API ──

    @Synchronized
    fun get(): Profile {
        val raw = KVUtils.getEncryptedString(KEY_PROFILE, "")
        if (raw.isBlank()) return Profile()
        return runCatching { Profile.fromJson(JSONObject(raw)) }.getOrDefault(Profile())
    }

    @Synchronized
    fun save(profile: Profile): Boolean {
        val saved = KVUtils.putEncryptedString(KEY_PROFILE, profile.toJson().toString())
        if (!saved) XLog.e(TAG, "Could not persist learned profile securely")
        return saved
    }

    /**
     * Erase the learned profile. Returns how many profile lines were removed.
     *
     * WHY this also clears the interaction log and the pending buffer: the profile is
     * *derived* data. [learnFromInteractions] rebuilds it from the raw interaction log,
     * so wiping only [KEY_PROFILE] would let the very next chat message resurrect the
     * name, city, sleep hours and frequent contacts the user just deleted — a delete
     * button that lies. The raw log is the actual personal data; the profile is a
     * summary of it. Both go.
     *
     * [KEY_PATTERNS] goes too: it is the same inference cached under another key.
     */
    @Synchronized
    fun forgetEverything(): Int {
        val removed = runCatching { snippetLines(get()).size }.getOrDefault(0)
        if (!KVUtils.removeEncrypted(KEY_PROFILE, KEY_PATTERNS, KEY_INTERACTIONS)) {
            XLog.e(TAG, "Could not erase learned profile securely")
            return 0
        }
        pending.clear()
        lastWriteMs = 0L
        return removed
    }

    /** Update a single trait without replacing the whole profile. */
    fun setTrait(key: String, value: String): Boolean {
        val p = get()
        val traits = p.traits.toMutableMap()
        traits[key] = value
        return save(p.copy(traits = traits))
    }

    fun getTrait(key: String): String = get().traits[key] ?: ""

    /** Buffered until the throttle window elapses. Guarded by the lock on this object. */
    private val pending = mutableListOf<Interaction>()

    @Volatile
    private var lastWriteMs = 0L

    /**
     * Record an interaction for pattern learning.
     *
     * `@Synchronized` covers the whole read-modify-write. Previously only
     * [interactions] was synchronized, so two threads recording at once both read the
     * same list, both appended, and one append was lost.
     *
     * Writes are coalesced: entries land in [pending] immediately (so a reader never
     * misses them) and are only serialised once per [WRITE_THROTTLE_MS]. Nothing is
     * dropped — this trades write frequency, not data.
     */
    @Synchronized
    fun recordInteraction(type: String, detail: String) {
        val cal = java.util.Calendar.getInstance()
        pending.add(
            Interaction(
                timestamp = cal.timeInMillis,
                type = type,
                detail = detail.take(100),
                hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
                dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK),
            )
        )
        val now = System.currentTimeMillis()
        if (now - lastWriteMs >= WRITE_THROTTLE_MS) flushPending(now)
    }

    /** Force the buffer to disk. Called before anything that reads patterns. */
    @Synchronized
    fun flush() {
        if (pending.isNotEmpty()) flushPending(System.currentTimeMillis())
    }

    /** Caller must hold the monitor. */
    private fun flushPending(now: Long) {
        val merged = (storedInteractions() + pending).takeLast(MAX_INTERACTIONS)
        val arr = JSONArray()
        merged.forEach { arr.put(it.toJson()) }
        if (KVUtils.putEncryptedString(KEY_INTERACTIONS, arr.toString())) {
            // Clear only after the encrypted commit succeeds. A Keystore failure must
            // not turn a transient persistence problem into lost learning data.
            pending.clear()
            lastWriteMs = now
        } else {
            XLog.w(TAG, "Interaction flush deferred; pending data retained")
        }
    }

    /**
     * Map a tool invocation onto an interaction worth learning from, or null.
     *
     * WHY: [learnFromInteractions] derives `topApps` from `app_opened` events and
     * `topContacts` from `message_sent` events, but nothing in the app ever recorded
     * either type — the single caller passed `"chat"`. Both branches were therefore
     * unreachable and those two fields were permanently empty. Deriving them from tool
     * calls fixes that at the one choke point every execution path goes through, and
     * keeps the mapping pure so it can be tested.
     */
    fun interactionForTool(toolName: String, params: Map<String, Any>): Pair<String, String>? {
        fun param(vararg keys: String): String =
            keys.firstNotNullOfOrNull { params[it]?.toString()?.trim()?.ifBlank { null } }.orEmpty()
        return when (toolName) {
            "open_app" -> param("app", "package", "name")
                .takeIf { it.isNotEmpty() }?.let { "app_opened" to it }
            "send_message", "send_sms" -> param("contact", "to", "recipient", "name")
                .takeIf { it.isNotEmpty() }?.let { "message_sent" to it }
            "make_call" -> param("contact", "to", "number", "name")
                .takeIf { it.isNotEmpty() }?.let { "message_sent" to it }
            else -> null
        }
    }

    /** Convenience for the tool layer: records only when the tool is relevant. */
    fun recordToolUse(toolName: String, params: Map<String, Any>) {
        val (type, detail) = interactionForTool(toolName, params) ?: return
        recordInteraction(type, detail)
    }

    /**
     * All interactions, including any still buffered by the write throttle, so a
     * reader never sees a stale view just because the flush window has not elapsed.
     */
    @Synchronized
    fun interactions(): List<Interaction> =
        (storedInteractions() + pending).takeLast(MAX_INTERACTIONS)

    /** Caller must hold the monitor. */
    private fun storedInteractions(): List<Interaction> {
        val raw = KVUtils.getEncryptedString(KEY_INTERACTIONS, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { Interaction.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    // ── Pattern Detection (self-learning) — pure helpers, unit-tested ──

    /**
     * Earliest morning hour the user is reliably active.
     *
     * "Earliest with enough samples", not the modal hour: the modal hour is when phone
     * use *peaks*, which is typically well after waking. The first hour that clears
     * [MIN_SAMPLES_PER_HOUR] is a much closer proxy for actually being up.
     */
    fun detectWakeHour(hours: List<Int>, minSamples: Int = MIN_SAMPLES_PER_HOUR): Int? =
        hours.filter { it in 5..11 }
            .groupingBy { it }.eachCount()
            .filterValues { it >= minSamples }
            .keys.minOrNull()

    /**
     * Latest hour the user is reliably active before sleeping.
     *
     * WHY THIS WAS WRONG: the previous version took the *most frequent* hour out of
     * {21,22,23,0,1,2,3}. Two problems. Activity at 22:00 means the user is **awake**,
     * not asleep, so the modal hour answers the wrong question. And comparing raw hour
     * numbers across midnight is meaningless — 01:00 sorts below 21:00 even though it
     * is later in the same night.
     *
     * Both are fixed by projecting post-midnight hours onto 24..27 so the night is
     * monotonic, then taking the latest hour with enough samples and mapping back.
     */
    fun detectSleepHour(hours: List<Int>, minSamples: Int = MIN_SAMPLES_PER_HOUR): Int? {
        val nightly = hours.filter { it in 21..23 || it in 0..3 }
            .map { if (it <= 3) it + 24 else it }
        val latest = nightly.groupingBy { it }.eachCount()
            .filterValues { it >= minSamples }
            .keys.maxOrNull() ?: return null
        return if (latest >= 24) latest - 24 else latest
    }

    /**
     * Analyze interactions and update the profile with detected patterns.
     * Called periodically (e.g., during night briefing or after N interactions).
     */
    fun learnFromInteractions() {
        flush() // buffered entries must be visible before deriving patterns
        val ints = interactions()
        if (ints.size < 10) return  // Not enough data
        val profile = get()
        var updated = profile

        detectWakeHour(ints.map { it.hour })?.let { updated = updated.copy(wakeUpHour = it) }
        detectSleepHour(ints.map { it.hour })?.let { updated = updated.copy(sleepHour = it) }

        // Detect top apps from "app_opened" interactions
        val appCounts = ints.filter { it.type == "app_opened" }
            .groupBy { it.detail }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }
            .take(5).map { it.key }
        if (appCounts.isNotEmpty()) {
            updated = updated.copy(topApps = appCounts)
        }

        // Detect top contacts from "message_sent" interactions
        val contactCounts = ints.filter { it.type == "message_sent" }
            .groupBy { it.detail }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }
            .take(5).map { it.key }
        if (contactCounts.isNotEmpty()) {
            updated = updated.copy(topContacts = contactCounts)
        }

        if (updated != profile && save(updated)) {
            XLog.i(TAG, "Profile updated from interactions: wake=${updated.wakeUpHour}, sleep=${updated.sleepHour}")
        }
    }

    /**
     * Build a prompt snippet describing what we know about the user.
     * Injected into the system prompt so the AI personalizes responses.
     */
    fun asPromptSnippet(budgetChars: Int = MAX_SNIPPET_CHARS): String =
        asPromptSnippetOf(get(), budgetChars)

    /**
     * Pure variant taking an explicit [Profile], so the budgeting can be tested
     * without MMKV. [asPromptSnippet] is this plus a storage read.
     */
    fun asPromptSnippetOf(p: Profile, budgetChars: Int = MAX_SNIPPET_CHARS): String =
        renderSnippet(snippetLines(p), budgetChars)

    /**
     * Profile facts as individual lines, **ordered most to least valuable**.
     *
     * The order is the drop order when the budget is tight, so it matters: a name is
     * worth more than a list of favourite apps, and arbitrary [Profile.traits] go last
     * because they are the only unbounded field — `setTrait` can add keys forever, and
     * without this they would be what pushes the section over the limit.
     */
    fun snippetLines(p: Profile): List<String> = buildList {
        if (p.name.isNotBlank()) add("- Nombre: ${p.name}")
        if (p.city.isNotBlank()) add("- Ciudad: ${p.city}")
        if (p.wakeUpHour >= 0) add("- Sueles despertar ~${p.wakeUpHour}:00")
        if (p.sleepHour >= 0) add("- Sueles dormir ~${p.sleepHour}:00")
        if (p.workStartHour >= 0) add("- Trabajo: ${p.workStartHour}:00–${p.workEndHour}:00")
        if (p.routineNotes.isNotBlank()) add("- Rutina: ${p.routineNotes}")
        if (p.topContacts.isNotEmpty()) add("- Contactos frecuentes: ${p.topContacts.joinToString()}")
        if (p.topApps.isNotEmpty()) add("- Apps favoritas: ${p.topApps.joinToString()}")
        if (p.interests.isNotEmpty()) add("- Intereses: ${p.interests.joinToString()}")
        if (p.preferredAlarmLead != 30) {
            add("- Prefiere alarmas ${p.preferredAlarmLead} min antes del evento")
        }
        p.traits.forEach { (k, v) -> if (v.isNotBlank()) add("- $k: $v") }
    }

    /**
     * Render [lines] under [budgetChars], dropping whole lines from the end.
     *
     * Returns empty when nothing fits, rather than a header with no content — a bare
     * heading would spend budget telling the model a section exists and then say
     * nothing, which is worse than omitting it.
     */
    fun renderSnippet(lines: List<String>, budgetChars: Int = MAX_SNIPPET_CHARS): String {
        if (lines.isEmpty() || budgetChars <= 0) return ""
        val header = "\n\n## Lo que sé de ti (perfil aprendido)\n"
        val footer = "Usa esta info para personalizar tu respuesta y anticipar lo que necesita.\n"
        var used = header.length + footer.length
        val kept = ArrayList<String>(lines.size)
        for (line in lines) {
            val cost = line.length + 1 // newline
            if (used + cost > budgetChars) break
            kept.add(line)
            used += cost
        }
        if (kept.isEmpty()) return ""
        return buildString {
            append(header)
            kept.forEach { append(it).append('\n') }
            append(footer)
        }
    }
}
