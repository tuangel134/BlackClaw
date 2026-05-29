package com.blackclaw.android.memory

import com.blackclaw.android.utils.KVUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Long-term memory of facts the user has told the agent.
 * Each fact is a (id, key, value, addedAt) tuple stored as JSON in MMKV.
 *
 * Distinct from the chat ConversationStore (per-session) and the KB tools
 * (markdown vault). This is small, structured, persistent across all sessions.
 */
object UserMemoryStore {

    private const val KEY = "KEY_USER_MEMORY_FACTS_V1"
    private const val MAX_FACTS = 200

    data class Fact(
        val id: String,
        val key: String,
        val value: String,
        val addedAtMs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("key", key)
            put("value", value)
            put("addedAtMs", addedAtMs)
        }

        companion object {
            fun fromJson(o: JSONObject) = Fact(
                id = o.getString("id"),
                key = o.getString("key"),
                value = o.getString("value"),
                addedAtMs = o.optLong("addedAtMs", 0L),
            )
        }
    }

    @Synchronized
    fun all(): List<Fact> {
        val raw = KVUtils.getString(KEY, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { Fact.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun saveAll(facts: List<Fact>) {
        val capped = if (facts.size > MAX_FACTS) facts.takeLast(MAX_FACTS) else facts
        val arr = JSONArray()
        capped.forEach { arr.put(it.toJson()) }
        KVUtils.putString(KEY, arr.toString())
        KVUtils.sync()
    }

    /** Add or update a fact. If a fact with the same key already exists, replace it. */
    @Synchronized
    fun remember(key: String, value: String): Fact {
        val now = System.currentTimeMillis()
        val current = all().toMutableList()
        val idx = current.indexOfFirst { it.key.equals(key, ignoreCase = true) }
        val fact = Fact(
            id = if (idx >= 0) current[idx].id else UUID.randomUUID().toString().take(8),
            key = key.trim(),
            value = value.trim(),
            addedAtMs = now,
        )
        if (idx >= 0) current[idx] = fact else current.add(fact)
        saveAll(current)
        return fact
    }

    @Synchronized
    fun forget(idOrKey: String): Boolean {
        val current = all().toMutableList()
        val before = current.size
        current.removeAll {
            it.id.equals(idOrKey, ignoreCase = true) ||
            it.key.equals(idOrKey, ignoreCase = true)
        }
        if (current.size == before) return false
        saveAll(current)
        return true
    }

    @Synchronized
    fun forgetAll(): Int {
        val n = all().size
        saveAll(emptyList())
        return n
    }

    @Synchronized
    fun search(query: String): List<Fact> {
        if (query.isBlank()) return all()
        val q = query.lowercase()
        return all().filter {
            it.key.lowercase().contains(q) || it.value.lowercase().contains(q)
        }
    }

    /** Build a compact text block to inject into the system prompt. */
    fun asPromptSnippet(maxFacts: Int = 30): String {
        val facts = all().takeLast(maxFacts)
        if (facts.isEmpty()) return ""
        return buildString {
            append("\n\n## What the user has told you to remember\n")
            facts.forEach { append("- ${it.key}: ${it.value}\n") }
        }
    }
}
