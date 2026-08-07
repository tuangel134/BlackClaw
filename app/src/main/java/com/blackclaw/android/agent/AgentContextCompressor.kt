package com.blackclaw.android.agent

import com.blackclaw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage

class AgentContextCompressor(private val provider: () -> LlmProvider) {

    companion object {
        private const val TAG = "ContextCompressor"
        private val GSON = Gson()

        private val OBSERVATION_PLACEHOLDERS = mapOf(
            "get_screen_info" to "[screen info omitted]",
            "take_screenshot" to "[screenshot result omitted]",
            "find_node_info" to "[node find result omitted]",
            "get_installed_apps" to "[app list omitted]",
            "scroll_to_find" to "[scroll find result omitted]",
        )
    }

    private val keepRecentRounds: Int
        get() = if (provider() == LlmProvider.LOCAL) 2 else 3

    fun compressHistoryForSend(messages: MutableList<ChatMessage>) {
        val charsBefore = totalChars(messages)
        val msgCountBefore = messages.size

        val screenPlaceholder = OBSERVATION_PLACEHOLDERS["get_screen_info"]!!
        val lastScreenIdx = messages.indexOfLast {
            it is ToolExecutionResultMessage && it.toolName() == "get_screen_info"
        }
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is ToolExecutionResultMessage
                && msg.toolName() == "get_screen_info"
                && i != lastScreenIdx
                && msg.text() != screenPlaceholder
            ) {
                messages[i] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), screenPlaceholder)
            }
        }

        val aiIndices = messages.indices.filter { messages[it] is AiMessage }
        if (aiIndices.size <= keepRecentRounds) return

        val totalRounds = aiIndices.size
        for (roundIdx in aiIndices.indices) {
            val roundFromEnd = totalRounds - roundIdx
            if (roundFromEnd <= keepRecentRounds) break

            val aiIndex = aiIndices[roundIdx]
            var j = aiIndex + 1
            while (j < messages.size && messages[j] is ToolExecutionResultMessage) {
                compressToolResultMessage(messages, j)
                j++
            }
        }

        val charsAfter = totalChars(messages)
        val saved = charsBefore - charsAfter
        if (saved > 0) {
            XLog.i(TAG, "Context compressed: ${charsBefore}→${charsAfter} chars, saved ${saved} chars (${saved * 100 / charsBefore}%), rounds=${aiIndices.size}")
        }
    }

    private fun compressToolResultMessage(messages: MutableList<ChatMessage>, index: Int) {
        val msg = messages[index] as ToolExecutionResultMessage
        val text = msg.text()
        if (text.length <= 100) return

        val placeholder = OBSERVATION_PLACEHOLDERS[msg.toolName()]
        if (placeholder != null) {
            messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), placeholder)
            return
        }

        val compressed = summarizeToolResult(text)
        messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), compressed)
    }

    private fun summarizeToolResult(resultJson: String): String {
        return try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            val map: Map<String, Any?> = GSON.fromJson(resultJson, mapType)
            val isSuccess = map["isSuccess"] as? Boolean ?: false
            if (isSuccess) {
                val data = map["data"]?.toString() ?: "ok"
                "✓ " + if (data.length > 80) data.take(80) + "..." else data
            } else {
                val error = map["error"]?.toString() ?: "failed"
                "✗ " + if (error.length > 80) error.take(80) + "..." else error
            }
        } catch (_: Exception) {
            if (resultJson.length > 80) resultJson.take(80) + "..." else resultJson
        }
    }

    private fun totalChars(messages: List<ChatMessage>): Int = messages.sumOf { msg ->
        when (msg) {
            is AiMessage -> (msg.text()?.length ?: 0) +
                (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
            is ToolExecutionResultMessage -> msg.text().length
            is UserMessage -> msg.singleText().length
            is SystemMessage -> msg.text().length
            else -> 0
        }
    }
}
