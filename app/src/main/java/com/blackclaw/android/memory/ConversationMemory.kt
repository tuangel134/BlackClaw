package com.blackclaw.android.memory

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
                topics = readTopics(o),
            )

            /**
             * Topics are optional.
             *
             * The previous version was `(0 until o.optJSONArray("topics")?.length()!!)`,
             * which throws NPE the moment the key is absent — and because [all] wraps
             * each entry in `runCatching`, the failure was invisible: the entry was
             * dropped permanently and silently. Any record written by an older build,
             * a migration, or a hand edit would vanish. It also called `optJSONArray`
             * once per element.
             */
            private fun readTopics(o: JSONObject): List<String> {
                val arr = o.optJSONArray("topics") ?: return emptyList()
                return (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i, "").takeIf { it.isNotBlank() }
                }
            }
        }
    }

    /**
     * Storage mechanics live in [JsonListStore]: locking around the whole
     * read-modify-write, timestamp-aware capping, no fsync per write, and a log line
     * when a record fails to parse instead of it disappearing silently.
     */
    private val store = object : JsonListStore<Entry>(KEY, MAX_ENTRIES, encrypted = true) {
        override val logTag = TAG
        override fun toJson(item: Entry): JSONObject = item.toJson()
        override fun fromJson(json: JSONObject): Entry? =
            Entry.fromJson(json).takeIf { it.summary.isNotBlank() }
        override fun timestampOf(item: Entry): Long = item.timestamp
    }

    fun all(): List<Entry> = store.all()

    /**
     * Record a conversation summary. Called when the user starts a new chat or the
     * conversation is saved/closed.
     *
     * Keyed on [conversationId], so re-recording the same conversation replaces its
     * entry rather than accumulating near-duplicates.
     */
    fun record(conversationId: String, summary: String, topics: List<String> = emptyList()) {
        if (summary.isBlank()) return
        val entry = Entry(
            id = conversationId,
            summary = summary.take(MAX_SUMMARY_LENGTH),
            timestamp = System.currentTimeMillis(),
            topics = topics.take(5),
        )
        val persisted = store.upsert(entry) { it.id == conversationId }
        if (persisted.any { it.id == entry.id && it == entry }) {
            XLog.d(TAG, "Recorded conversation memory: summaryChars=${entry.summary.length} topicCount=${entry.topics.size}")
        } else {
            XLog.w(TAG, "Conversation memory write failed; previous data retained")
        }
    }

    /** Drop every stored summary. Returns how many were removed. */
    fun forgetAll(): Int = store.clear()

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
        if (messages.isEmpty()) return ""
        val userMsgs = messages.filter { it.first == "USER" }
            .map { it.second.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
        if (userMsgs.isEmpty()) return ""
        val lastAssistant = messages.lastOrNull { it.first == "ASSISTANT" }
            ?.second?.trim()?.replace(Regex("\\s+"), " ").orEmpty()

        // WHY THIS IS NOT JUST first + last: these entries are the ONLY memory of a
        // conversation once it ends, and the previous version kept the first request and
        // the last reply and discarded everything in between — for a twenty-turn voice
        // session that is almost the whole conversation. Distinct requests are what a
        // later session needs in order to resolve a back-reference, so we keep as many
        // as fit and say plainly how many were dropped instead of silently losing them.
        val distinct = dedupeRequests(userMsgs)
        val budget = MAX_SUMMARY_LENGTH - lastAssistantCost(lastAssistant)
        val kept = ArrayList<String>()
        var used = "Usuario pidió: ".length
        for (request in distinct) {
            val piece = request.take(PER_REQUEST_CHARS)
            val cost = piece.length + if (kept.isEmpty()) 0 else SEPARATOR.length
            if (used + cost > budget) break
            kept.add(piece)
            used += cost
        }
        if (kept.isEmpty()) kept.add(distinct.first().take(PER_REQUEST_CHARS))

        return buildString {
            append("Usuario pidió: ").append(kept.joinToString(SEPARATOR))
            val dropped = distinct.size - kept.size
            if (dropped > 0) append(" (+$dropped más)")
            if (lastAssistant.isNotBlank()) {
                append(". Resultado: ").append(lastAssistant.take(LAST_REPLY_CHARS))
            }
        }.take(MAX_SUMMARY_LENGTH)
    }

    private const val PER_REQUEST_CHARS = 70
    private const val LAST_REPLY_CHARS = 90
    private const val SEPARATOR = " · "

    private fun lastAssistantCost(lastAssistant: String): Int =
        if (lastAssistant.isBlank()) 0 else ". Resultado: ".length + LAST_REPLY_CHARS

    /**
     * Collapse near-duplicate requests, keeping first occurrence.
     *
     * Voice sessions repeat themselves constantly — the recogniser mishears, the user
     * rephrases. Five variants of the same request would otherwise crowd out the four
     * genuinely different things that were asked.
     */
    fun dedupeRequests(requests: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>(requests.size)
        for (r in requests) {
            val fingerprint = r.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.take(40)
            if (seen.add(fingerprint)) out.add(r)
        }
        return out
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
