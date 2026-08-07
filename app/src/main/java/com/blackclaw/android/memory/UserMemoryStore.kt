package com.blackclaw.android.memory

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

    /**
     * Storage mechanics come from [JsonListStore], including the recency-aware cap.
     * Supplying [timestampOf] is what stops an actively-maintained fact from being
     * evicted in favour of an untouched one that merely sits later in the list.
     */
    private val store = object : JsonListStore<Fact>(KEY, MAX_FACTS) {
        override val logTag = "UserMemoryStore"
        override fun toJson(item: Fact): JSONObject = item.toJson()
        override fun fromJson(json: JSONObject): Fact? = runCatching { Fact.fromJson(json) }
            .getOrNull()?.takeIf { it.key.isNotBlank() }
        override fun timestampOf(item: Fact): Long = item.addedAtMs
    }

    fun all(): List<Fact> = store.all()

    private fun saveAll(facts: List<Fact>) {
        store.replaceAll(facts)
    }

    /**
     * Keep the [max] most recently touched facts.
     *
     * WHY NOT `takeLast`: [remember] replaces an existing fact **at its original
     * index** while refreshing `addedAtMs`, so list position reflects when a key was
     * first seen, not when it was last updated. Capping by position therefore evicted
     * facts the user actively maintains in favour of stale ones that merely happened to
     * be added later. Ordering by `addedAtMs` is what makes "I keep correcting this"
     * count for something.
     *
     * Insertion order is preserved among survivors so the stored file stays stable.
     */
    fun capByRecency(facts: List<Fact>, max: Int): List<Fact> {
        if (max <= 0) return emptyList()
        if (facts.size <= max) return facts
        val keep = facts.sortedByDescending { it.addedAtMs }.take(max).map { it.id }.toHashSet()
        return facts.filter { it.id in keep }
    }

    /** The [max] most recently touched facts, newest last (reads naturally in a prompt). */
    fun mostRecent(facts: List<Fact>, max: Int): List<Fact> =
        capByRecency(facts, max).sortedBy { it.addedAtMs }

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
        val facts = all()
        // 1. Substring match (fast, exact)
        val q = query.lowercase()
        val exact = facts.filter {
            it.key.lowercase().contains(q) || it.value.lowercase().contains(q)
        }
        if (exact.isNotEmpty()) return exact
        // 2. Semantic fallback — match by meaning when wording differs
        //    ("¿qué me dijo el jefe?" finds a fact about "mi superior").
        val docs = facts.map { "${it.key} ${it.value}" }
        val ranked = SemanticSearch.rank(query, docs, minScore = 0.08)
        return ranked.map { facts[it.first] }
    }

    /**
     * Build a compact text block to inject into the system prompt.
     *
     * Selects by `addedAtMs`, not by list position — see [capByRecency] for why those
     * differ and why the difference was losing the user's most-maintained facts.
     */
    fun asPromptSnippet(maxFacts: Int = 30): String {
        val facts = mostRecent(all(), maxFacts)
        if (facts.isEmpty()) return ""
        return buildString {
            append("\n\n## What the user has told you to remember\n")
            facts.forEach { append("- ${it.key}: ${it.value}\n") }
        }
    }
}
