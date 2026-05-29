package com.blackclaw.android.agent

import android.content.Context
import com.blackclaw.android.utils.XLog

/**
 * Manages externalized playbooks stored as structured MD files in assets/playbooks/.
 *
 * Each playbook has:
 * - YAML frontmatter: id, name, triggers
 * - MD body: step-by-step instructions injected into system prompt
 *
 * Playbooks are composited tool sequences for local LLM (Gemma 4).
 * Cloud LLMs don't need playbooks — they figure out tools on their own.
 */
object PlaybookManager {

    private const val TAG = "PlaybookManager"
    private const val PLAYBOOK_DIR = "playbooks"

    data class Playbook(
        val id: String,
        val name: String,
        val triggers: List<String>,
        val body: String  // The MD body, injected directly into system prompt
    )

    private val playbooks = mutableListOf<Playbook>()

    /**
     * Load all playbook MD files from assets/playbooks/.
     * Called once at app startup.
     */
    fun loadAll(context: Context) {
        playbooks.clear()
        try {
            val files = context.assets.list(PLAYBOOK_DIR) ?: return
            for (file in files) {
                if (!file.endsWith(".md")) continue
                try {
                    val content = context.assets.open("$PLAYBOOK_DIR/$file")
                        .bufferedReader().use { it.readText() }
                    val playbook = parse(content)
                    if (playbook != null) {
                        playbooks.add(playbook)
                        XLog.d(TAG, "Loaded playbook: ${playbook.id} (${playbook.triggers.size} triggers)")
                    }
                } catch (e: Exception) {
                    XLog.w(TAG, "Failed to load playbook: $file", e)
                }
            }
            XLog.i(TAG, "Loaded ${playbooks.size} playbooks")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to list playbooks", e)
        }
    }

    /**
     * Find the best matching playbook for a task.
     * Returns null if no playbook matches.
     */
    fun match(task: String): Playbook? {
        val lower = task.lowercase()
        // Find playbook with the most specific trigger match
        var bestMatch: Playbook? = null
        var bestScore = 0

        for (playbook in playbooks) {
            for (trigger in playbook.triggers) {
                if (lower.contains(trigger.lowercase())) {
                    val score = trigger.length  // Longer trigger = more specific
                    if (score > bestScore) {
                        bestScore = score
                        bestMatch = playbook
                    }
                }
            }
        }

        if (bestMatch != null) {
            XLog.i(TAG, "Matched playbook: ${bestMatch.id} for task: $task")
        }
        return bestMatch
    }

    /**
     * Get all loaded playbooks.
     */
    fun getAll(): List<Playbook> = playbooks.toList()

    /**
     * Parse a playbook MD file with YAML frontmatter.
     *
     * Format:
     * ```
     * ---
     * id: send_message
     * name: Send Message
     * triggers:
     *   - "send"
     *   - "tell"
     * ---
     *
     * Body text here...
     * ```
     */
    private fun parse(content: String): Playbook? {
        // Split frontmatter from body
        if (!content.startsWith("---")) return null
        val endIndex = content.indexOf("---", 3)
        if (endIndex < 0) return null

        val frontmatter = content.substring(3, endIndex).trim()
        val body = content.substring(endIndex + 3).trim()

        // Parse frontmatter (simple YAML — no library needed)
        var id = ""
        var name = ""
        val triggers = mutableListOf<String>()
        var inTriggers = false

        for (line in frontmatter.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("id:") -> {
                    id = trimmed.substringAfter("id:").trim()
                    inTriggers = false
                }
                trimmed.startsWith("name:") -> {
                    name = trimmed.substringAfter("name:").trim()
                    inTriggers = false
                }
                trimmed.startsWith("triggers:") -> {
                    inTriggers = true
                }
                inTriggers && trimmed.startsWith("- ") -> {
                    val trigger = trimmed.substringAfter("- ").trim()
                        .removeSurrounding("\"").removeSurrounding("'")
                    if (trigger.isNotEmpty()) triggers.add(trigger)
                }
                else -> inTriggers = false
            }
        }

        if (id.isEmpty() || body.isEmpty()) return null
        return Playbook(id, name, triggers, body)
    }
}
