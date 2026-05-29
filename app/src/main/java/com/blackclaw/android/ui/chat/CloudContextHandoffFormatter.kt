package com.blackclaw.android.ui.chat

/**
 * Formats the visible chat transcript into the subset that Cloud task handoff should inherit.
 *
 * Operational shell noise (monitor status, permission prompts, progress logs) must stay
 * isolated from the conversational context that gets handed to the Cloud task agent.
 */
object CloudContextHandoffFormatter {

    fun conversationLines(messages: List<ChatMessage>): List<String> {
        return messages.mapNotNull { message ->
            val content = message.content.trim()
            if (content.isEmpty() || content == "...") {
                return@mapNotNull null
            }

            when (message.role) {
                ChatMessage.Role.USER -> "User: $content"
                ChatMessage.Role.ASSISTANT -> "Assistant: $content"
                ChatMessage.Role.SYSTEM, ChatMessage.Role.TOOL_GROUP -> null
            }
        }
    }
}
