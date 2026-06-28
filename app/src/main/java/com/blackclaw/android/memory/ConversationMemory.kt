package com.blackclaw.android.memory

import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cross-session conversation memory.
 *
 * After each conversation ends (or the user starts a new chat), we store a
 * compact summary so the next conversation can reference what was discussed
 * before. This makes the assistant feel like it "remembers" across sessions.
 *
 * Storage: a capped ring buffer of summaries in MMKV (last N conversations).
 * Each entry is {id, summary, timestamp, topics[]}.
 */
object ConversationMemory {

    private const val TAG = "ConversationMemory"
    private const val KEY = "KEY_CONVERSATION_MEMORY_V1"
    private const val MAX_ENTRIES = 15
    private const val MAX_SUMMARY_LENGTH = 300

    data class Entry(
        val id: String,
        val summary: String,
        val timestamp: Long,
        val topics: List<String>,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("summary", summary)
            put("timestamp", timestamp)
            put("topics", JSONArray(topics))
        }

        companion object {
            fun fromJson(o: JSONObject) = Entry(
                id = o.optString("id", ""),
                summary = o.optString("summary", ""),
                timestamp = o.optLong("timestamp", 0L),
                topics = (0 until o.optJSONArray("topics")?.length()!!).map {
                    o.optJSONArray("topics")!!.getString(it)
                },
            )
        }
    }

    @Synchronized
    fun all(): List<Entry> {
        val raw = KVUtils.getString(KEY, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { Entry.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Record a conversation summary. Called when user starts a new chat or
     * the conversation is saved/closed.
     */
    @Synchronized
    fun record(conversationId: String, summary: String, topics: List<String> = emptyList()) {
        if (summary.isBlank()) return
        val entry = Entry(
            id = conversationId,
            summary = summary.take(MAX_SUMMARY_LENGTH),
            timestamp = System.currentTimeMillis(),
            topics = topics.take(5),
        )
        val list = all().toMutableList()
        // Replace if same conversation, otherwise append
        val idx = list.indexOfFirst { it.id == conversationId }
        if (idx >= 0) list[idx] = entry else list.add(entry)
        // Cap
        val capped = if (list.size > MAX_ENTRIES) list.takeLast(MAX_ENTRIES) else list
        val arr = JSONArray()
        capped.forEach { arr.put(it.toJson()) }
        KVUtils.putString(KEY, arr.toString())
        KVUtils.sync()
        XLog.d(TAG, "Recorded conversation memory: ${entry.id} (${entry.summary.take(50)}...)")
    }

    /**
     * Build a prompt snippet with recent conversation context.
     * Injected into the system prompt so the LLM knows what was discussed before.
     */
    fun asPromptSnippet(maxEntries: Int = 5): String {
        val entries = all().takeLast(maxEntries)
        if (entries.isEmpty()) return ""
        val df = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
        return buildString {
            append("\n\n## Previous conversations (what the user and you discussed recently)\n")
            entries.forEach { e ->
                append("- [${df.format(java.util.Date(e.timestamp))}] ${e.summary}\n")
            }
            append("Use this context if the user refers to something from a prior chat.\n")
        }
    }

    /**
     * Generate a summary from a list of chat messages (simplified extraction).
     * This is a lightweight local summarizer — extracts user intents and outcomes.
     * For more sophisticated summaries, the LLM can be used.
     */
    fun extractSummary(messages: List<Pair<String, String>>): String {
        // messages = list of (role, content)
        if (messages.isEmpty()) return ""
        // Extract user messages and last assistant response
        val userMsgs = messages.filter { it.first == "USER" }.map { it.second }
        val lastAssistant = messages.lastOrNull { it.first == "ASSISTANT" }?.second ?: ""

        if (userMsgs.isEmpty()) return ""

        val sb = StringBuilder()
        // First user message = main topic
        sb.append("Usuario pidió: ${userMsgs.first().take(100)}")
        if (userMsgs.size > 1) {
            sb.append(" (+${userMsgs.size - 1} más)")
        }
        if (lastAssistant.isNotBlank()) {
            sb.append(". Resultado: ${lastAssistant.take(100)}")
        }
        return sb.toString().take(MAX_SUMMARY_LENGTH)
    }

    /**
     * Extract topics from messages for quick reference.
     */
    fun extractTopics(messages: List<Pair<String, String>>): List<String> {
        val userText = messages.filter { it.first == "USER" }
            .joinToString(" ") { it.second }.lowercase()
        val topics = mutableListOf<String>()
        val keywords = mapOf(
            "whatsapp" to "mensajería", "telegram" to "mensajería",
            "alarma" to "alarmas", "recordatorio" to "recordatorios",
            "foto" to "cámara", "cámara" to "cámara",
            "música" to "música", "spotify" to "música",
            "batería" to "dispositivo", "wifi" to "dispositivo",
            "contacto" to "contactos", "llamar" to "llamadas",
            "buscar" to "búsqueda", "google" to "búsqueda",
            "nota" to "notas", "finanza" to "finanzas", "gasto" to "finanzas",
        )
        for ((key, topic) in keywords) {
            if (userText.contains(key) && topic !in topics) {
                topics.add(topic)
                if (topics.size >= 5) break
            }
        }
        return topics
    }
}
