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
            // The pending placeholder must never reach the cloud model — it would read
            // as the assistant having literally answered with an ellipsis.
            if (content.isEmpty() || content == ChatMessage.PENDING) {
                return@mapNotNull null
            }

            when (message.role) {
                ChatMessage.Role.USER -> "User: $content"
                ChatMessage.Role.ASSISTANT -> "Assistant: $content"
                // CARDS holds a JSON payload, not something anyone said. Handing it to
                // the cloud model would spend tokens restating facts the transcript
                // already carries in prose.
                ChatMessage.Role.SYSTEM, ChatMessage.Role.TOOL_GROUP,
                ChatMessage.Role.CARDS -> null
            }
        }
    }
}
