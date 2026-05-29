package com.blackclaw.android.agent.skill

import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * User-defined skill — a reusable named prompt + trigger phrase the user can
 * fire from chat or the skills screen.
 *
 * Distinct from the built-in skills (BuiltInSkills) which are hardcoded.
 */
data class UserSkill(
    val id: String,
    val name: String,
    val description: String,
    val trigger: String,         // optional — "morning routine", "pay the bills"
    val prompt: String,          // the actual instructions sent to the agent
    val emoji: String = "✨",
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("trigger", trigger)
        put("prompt", prompt)
        put("emoji", emoji)
        put("createdAtMs", createdAtMs)
        put("updatedAtMs", updatedAtMs)
    }

    companion object {
        fun fromJson(o: JSONObject) = UserSkill(
            id = o.optString("id", UUID.randomUUID().toString().take(8)),
            name = o.getString("name"),
            description = o.optString("description", ""),
            trigger = o.optString("trigger", ""),
            prompt = o.getString("prompt"),
            emoji = o.optString("emoji", "✨"),
            createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis()),
            updatedAtMs = o.optLong("updatedAtMs", System.currentTimeMillis()),
        )
    }
}

object UserSkillStore {

    private const val TAG = "UserSkillStore"
    private const val KEY = "KEY_USER_SKILLS_V1"

    @Synchronized
    fun all(): List<UserSkill> {
        val raw = KVUtils.getString(KEY, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { UserSkill.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun find(id: String): UserSkill? = all().firstOrNull { it.id == id }

    @Synchronized
    private fun saveAll(skills: List<UserSkill>) {
        val arr = JSONArray()
        skills.forEach { arr.put(it.toJson()) }
        KVUtils.putString(KEY, arr.toString())
        KVUtils.sync()
    }

    @Synchronized
    fun upsert(skill: UserSkill): UserSkill {
        val now = System.currentTimeMillis()
        val current = all().toMutableList()
        val idx = current.indexOfFirst { it.id == skill.id }
        val finalSkill = if (idx >= 0) {
            skill.copy(updatedAtMs = now)
        } else {
            skill.copy(createdAtMs = now, updatedAtMs = now)
        }
        if (idx >= 0) current[idx] = finalSkill else current.add(finalSkill)
        saveAll(current)
        XLog.i(TAG, "Saved skill ${finalSkill.id}: ${finalSkill.name}")
        return finalSkill
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val before = all()
        val after = before.filterNot { it.id == id }
        if (after.size == before.size) return false
        saveAll(after)
        return true
    }

    /** Try to match a free-form user input to a skill by trigger phrase. */
    fun matchTrigger(input: String): UserSkill? {
        val needle = input.trim().lowercase()
        if (needle.isEmpty()) return null
        return all().firstOrNull { skill ->
            val t = skill.trigger.trim().lowercase()
            t.isNotEmpty() && (needle == t || needle.contains(t))
        }
    }
}
