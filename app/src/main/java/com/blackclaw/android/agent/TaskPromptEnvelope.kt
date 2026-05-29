package com.blackclaw.android.agent

data class ParsedTaskPrompt(
    val currentRequest: String,
    val chatHistory: String? = null,
    val backgroundState: String? = null,
) {
    val hasChatHistory: Boolean
        get() = !chatHistory.isNullOrBlank()

    val hasBackgroundState: Boolean
        get() = !backgroundState.isNullOrBlank()
}

/**
 * Wraps a chatroom transcript and the user's latest request into a stable prompt format.
 *
 * Routing must still use the raw task text. This envelope is only for the agent loop so Cloud
 * tasks can inherit the current chatroom context without breaking deterministic/skill matching.
 */
object TaskPromptEnvelope {

    private const val HISTORY_START = "<<<BLACKCLAW_CHAT_HISTORY>>>"
    private const val HISTORY_END = "<<<END_BLACKCLAW_CHAT_HISTORY>>>"
    private const val BACKGROUND_START = "<<<BLACKCLAW_BACKGROUND_STATE>>>"
    private const val BACKGROUND_END = "<<<END_BLACKCLAW_BACKGROUND_STATE>>>"
    private const val REQUEST_START = "<<<BLACKCLAW_CURRENT_REQUEST>>>"
    private const val REQUEST_END = "<<<END_BLACKCLAW_CURRENT_REQUEST>>>"

    fun build(
        chatHistoryLines: List<String>,
        currentRequest: String,
        backgroundState: String? = null,
    ): String {
        val transcript = chatHistoryLines.joinToString("\n").trim()
        val background = backgroundState?.trim().orEmpty()
        if (transcript.isBlank() && background.isBlank()) return currentRequest.trim()

        return buildString {
            if (transcript.isNotBlank()) {
                append(HISTORY_START).append('\n')
                append(transcript).append('\n')
                append(HISTORY_END).append('\n')
            }
            if (background.isNotBlank()) {
                append(BACKGROUND_START).append('\n')
                append(background).append('\n')
                append(BACKGROUND_END).append('\n')
            }
            append(REQUEST_START).append('\n')
            append(currentRequest.trim()).append('\n')
            append(REQUEST_END)
        }
    }

    fun parse(prompt: String): ParsedTaskPrompt {
        val historyStart = prompt.indexOf(HISTORY_START)
        val historyEnd = prompt.indexOf(HISTORY_END)
        val backgroundStart = prompt.indexOf(BACKGROUND_START)
        val backgroundEnd = prompt.indexOf(BACKGROUND_END)
        val requestStart = prompt.indexOf(REQUEST_START)
        val requestEnd = prompt.indexOf(REQUEST_END)

        if (requestStart < 0 || requestEnd < 0) {
            return ParsedTaskPrompt(currentRequest = prompt.trim())
        }

        val history = if (historyStart >= 0 && historyEnd >= 0) {
            prompt.substring(historyStart + HISTORY_START.length, historyEnd).trim()
        } else {
            ""
        }
        val backgroundState = if (backgroundStart >= 0 && backgroundEnd >= 0) {
            prompt.substring(backgroundStart + BACKGROUND_START.length, backgroundEnd).trim()
        } else {
            ""
        }
        val request = prompt.substring(requestStart + REQUEST_START.length, requestEnd).trim()
        return ParsedTaskPrompt(
            currentRequest = request.ifBlank { prompt.trim() },
            chatHistory = history.ifBlank { null },
            backgroundState = backgroundState.ifBlank { null },
        )
    }
}
