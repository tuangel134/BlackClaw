package com.blackclaw.android.agent

import com.blackclaw.android.memory.JsonListStore
import org.json.JSONObject

/**
 * Short-term cross-task memory.
 *
 * After each completed task we keep a compact record (what was asked, a short
 * outcome, when). On the next task we feed a small snippet of recent tasks into
 * the system prompt, so the agent can resolve back-references like "send another
 * one to the same person" or "do that again" without the user repeating
 * themselves — and can avoid redoing work it just did.
 *
 * Deliberately tiny and capped: it's *context*, not a transcript. The full chat
 * history already lives in ConversationStore.
 */
object TaskHistoryStore {

    private const val KEY = "agent_task_history_v1"
    private const val MAX_ENTRIES = 12
    /** Entries older than this aren't worth surfacing as "recent". */
    private const val FRESH_WINDOW_MS = 6L * 60 * 60 * 1000   // 6 hours

    data class Entry(val task: String, val outcome: String, val t: Long)

    /**
     * Storage mechanics come from [JsonListStore]: one lock around the whole
     * read-modify-write, a log line when a record or the whole blob is unreadable, and
     * no `sync()` on the write path. The old hand-rolled version fsync'd on every
     * completed task and marked only its reader `@Synchronized`, so two tasks finishing
     * together could lose an entry.
     *
     * The store keeps entries **oldest first**, which is [JsonListStore]'s convention
     * and what makes its recency cap behave. This object's own API is newest-first —
     * see [all] — so the two views are reversed exactly at this boundary rather than
     * leaving each caller to guess.
     */
    private val store = object : JsonListStore<Entry>(KEY, MAX_ENTRIES, encrypted = true) {
        override val logTag = "TaskHistoryStore"
        override fun toJson(item: Entry): JSONObject = JSONObject().apply {
            put("task", item.task)
            put("outcome", item.outcome)
            put("t", item.t)
        }

        // A record with no task text cannot resolve a back-reference and cannot be
        // printed in the snippet, so it is rejected here and logged rather than
        // filtered silently at every read.
        override fun fromJson(json: JSONObject): Entry? {
            val task = json.optString("task").trim()
            if (task.isEmpty()) return null
            return Entry(task, json.optString("outcome"), json.optLong("t"))
        }

        override fun timestampOf(item: Entry): Long = item.t
    }

    /** Recent tasks, **newest first**. */
    fun all(): List<Entry> = store.all().asReversed()

    fun record(task: String, outcome: String) {
        val cleanTask = task.trim().take(160)
        if (cleanTask.isBlank()) return
        val entry = Entry(cleanTask, sanitizeOutcome(outcome), System.currentTimeMillis())
        // Deduping is this store's own policy, not the base's: a repeated task is not an
        // old entry to evict but the same entry happening again, and only the latest
        // occurrence is worth prompt space. Truncating here too means the list handed
        // over is already within the cap, so the base's cap never has to break a tie.
        val merged = dedupe(listOf(entry) + all()).take(MAX_ENTRIES)
        store.replaceAll(merged.asReversed())
    }

    /**
     * Collapse repeated tasks: if the same task text shows up more than once
     * (case-insensitive), keep only the most recent occurrence. Keeps the recent
     * list meaningful instead of five copies of "manda hola a Ana". Pure.
     */
    fun dedupe(entries: List<Entry>): List<Entry> {
        val seen = HashSet<String>()
        val out = ArrayList<Entry>(entries.size)
        // entries are newest-first; first occurrence wins (most recent).
        for (e in entries) {
            val k = e.task.trim().lowercase()
            if (seen.add(k)) out.add(e)
        }
        return out
    }

    /** Wipes the store. Returns how many entries were removed. */
    fun clear(): Int = store.clear()

    /** Prompt snippet of recent tasks within the freshness window. */
    fun asPromptSnippet(now: Long = System.currentTimeMillis(), max: Int = 5): String =
        formatSnippet(all(), now, max)

    // ── Pure helpers (unit-tested) ──

    /** Trim an outcome to one short line so it stays cheap in the prompt. */
    fun sanitizeOutcome(outcome: String): String =
        outcome.trim().replace(Regex("\\s+"), " ").take(120)

    /**
     * Build the prompt snippet from entries, keeping only those inside the fresh
     * window and at most [max]. Pure → unit-testable without MMKV.
     */
    fun formatSnippet(entries: List<Entry>, now: Long, max: Int = 5, windowMs: Long = FRESH_WINDOW_MS): String {
        val recent = entries.asSequence()
            .filter { it.task.isNotBlank() && now - it.t <= windowMs }
            .take(max)
            .toList()
        if (recent.isEmpty()) return ""
        val df = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return buildString {
            append("\n\n## Tareas recientes (contexto; resuelve referencias como \"otra vez\" o \"a la misma persona\")\n")
            recent.forEach {
                append("- [${df.format(java.util.Date(it.t))}] ${it.task}")
                if (it.outcome.isNotBlank()) append(" → ${it.outcome}")
                append("\n")
            }
        }
    }
}
