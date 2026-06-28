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
        val raw = KVUtils.getString(KEY_PROFILE, "")
        if (raw.isBlank()) return Profile()
        return runCatching { Profile.fromJson(JSONObject(raw)) }.getOrDefault(Profile())
    }

    @Synchronized
    fun save(profile: Profile) {
        KVUtils.putString(KEY_PROFILE, profile.toJson().toString())
        KVUtils.sync()
    }

    /** Update a single trait without replacing the whole profile. */
    fun setTrait(key: String, value: String) {
        val p = get()
        val traits = p.traits.toMutableMap()
        traits[key] = value
        save(p.copy(traits = traits))
    }

    fun getTrait(key: String): String = get().traits[key] ?: ""

    /** Record an interaction for pattern learning. */
    fun recordInteraction(type: String, detail: String) {
        val cal = java.util.Calendar.getInstance()
        val interaction = Interaction(
            timestamp = cal.timeInMillis,
            type = type,
            detail = detail.take(100),
            hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
            dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK),
        )
        val list = interactions().toMutableList()
        list.add(interaction)
        val capped = if (list.size > MAX_INTERACTIONS) list.takeLast(MAX_INTERACTIONS) else list
        val arr = JSONArray()
        capped.forEach { arr.put(it.toJson()) }
        KVUtils.putString(KEY_INTERACTIONS, arr.toString())
        KVUtils.sync()
    }

    @Synchronized
    fun interactions(): List<Interaction> {
        val raw = KVUtils.getString(KEY_INTERACTIONS, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { Interaction.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    // ── Pattern Detection (self-learning) ──

    /**
     * Analyze interactions and update the profile with detected patterns.
     * Called periodically (e.g., during night briefing or after N interactions).
     */
    fun learnFromInteractions() {
        val ints = interactions()
        if (ints.size < 10) return  // Not enough data
        val profile = get()
        var updated = profile

        // Detect wake-up time (most common hour for first interaction of the day)
        val morningHours = ints.filter { it.hour in 5..11 }
            .groupBy { it.hour }
            .maxByOrNull { it.value.size }
        if (morningHours != null && morningHours.value.size >= 3) {
            updated = updated.copy(wakeUpHour = morningHours.key)
        }

        // Detect sleep time (latest interactions)
        val nightHours = ints.filter { it.hour in 21..23 || it.hour in 0..3 }
            .groupBy { it.hour }
            .maxByOrNull { it.value.size }
        if (nightHours != null && nightHours.value.size >= 3) {
            updated = updated.copy(sleepHour = nightHours.key)
        }

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

        if (updated != profile) {
            save(updated)
            XLog.i(TAG, "Profile updated from interactions: wake=${updated.wakeUpHour}, sleep=${updated.sleepHour}")
        }
    }

    /**
     * Build a prompt snippet describing what we know about the user.
     * Injected into the system prompt so the AI personalizes responses.
     */
    fun asPromptSnippet(): String {
        val p = get()
        if (p.name.isBlank() && p.wakeUpHour < 0 && p.topContacts.isEmpty() && p.traits.isEmpty()) {
            return ""
        }
        return buildString {
            append("\n\n## Lo que sé de ti (perfil aprendido)\n")
            if (p.name.isNotBlank()) append("- Nombre: ${p.name}\n")
            if (p.city.isNotBlank()) append("- Ciudad: ${p.city}\n")
            if (p.wakeUpHour >= 0) append("- Sueles despertar ~${p.wakeUpHour}:00\n")
            if (p.sleepHour >= 0) append("- Sueles dormir ~${p.sleepHour}:00\n")
            if (p.workStartHour >= 0) append("- Trabajo: ${p.workStartHour}:00–${p.workEndHour}:00\n")
            if (p.topContacts.isNotEmpty()) append("- Contactos frecuentes: ${p.topContacts.joinToString()}\n")
            if (p.topApps.isNotEmpty()) append("- Apps favoritas: ${p.topApps.joinToString()}\n")
            if (p.interests.isNotEmpty()) append("- Intereses: ${p.interests.joinToString()}\n")
            if (p.routineNotes.isNotBlank()) append("- Rutina: ${p.routineNotes}\n")
            if (p.preferredAlarmLead != 30) append("- Prefiere alarmas ${p.preferredAlarmLead} min antes del evento\n")
            p.traits.forEach { (k, v) -> if (v.isNotBlank()) append("- $k: $v\n") }
            append("Usa esta info para personalizar tu respuesta y anticipar lo que necesita.\n")
        }
    }
}
