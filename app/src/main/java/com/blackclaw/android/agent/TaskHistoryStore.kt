package com.blackclaw.android.agent

import com.blackclaw.android.utils.KVUtils
import org.json.JSONArray
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

    @Synchronized
    fun all(): List<Entry> {
        val raw = KVUtils.getString(KEY, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Entry(o.optString("task"), o.optString("outcome"), o.optLong("t"))
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun record(task: String, outcome: String) {
        val cleanTask = task.trim().take(160)
        if (cleanTask.isBlank()) return
        val entry = Entry(cleanTask, sanitizeOutcome(outcome), System.currentTimeMillis())
        val list = all().toMutableList()
        list.add(0, entry)
        val deduped = dedupe(list)
        val capped = if (deduped.size > MAX_ENTRIES) deduped.take(MAX_ENTRIES) else deduped
        val arr = JSONArray()
        capped.forEach {
            arr.put(JSONObject().apply {
                put("task", it.task); put("outcome", it.outcome); put("t", it.t)
            })
        }
        KVUtils.putString(KEY, arr.toString()); KVUtils.sync()
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

    @Synchronized
    fun clear() {
        KVUtils.putString(KEY, ""); KVUtils.sync()
    }

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
