package com.blackclaw.android.ui.chat

/**
 * Formats the visible chat transcript into the subset that Cloud task handoff should inherit.
 *
 * Operational shell noise (monitor status, permission prompts, progress logs) must stay
 * isolated from the conversational context that gets handed to the Cloud task agent.
 */
object CloudContextHandoffFormatter {

    fun conversationLines(messages: List<ChatMessage>): List<String> {
        return messages.flatMap { message ->
            val content = message.content.trim()
            // The pending placeholder must never reach the cloud model — it would read
            // as the assistant having literally answered with an ellipsis.
            if (message.role != ChatMessage.Role.TOOL_GROUP &&
                (content.isEmpty() || content == ChatMessage.PENDING)
            ) {
                return@flatMap emptyList()
            }

            when (message.role) {
                ChatMessage.Role.USER -> listOf("User: $content")
                ChatMessage.Role.ASSISTANT -> listOf("Assistant: $content")
                ChatMessage.Role.TOOL_GROUP -> {
                    val steps = message.toolSteps.orEmpty()
                    if (steps.isEmpty()) {
                        // Compatibility for transcripts saved by older builds that
                        // serialized the tool row as plain markdown text.
                        listOf("Tool result (authoritative): $content")
                    } else {
                        steps.map { step ->
                            // Tool results are authoritative execution evidence.
                            // Keeping them separate from assistant prose prevents a
                            // hallucinated "listo" from being mistaken for success.
                            "Tool result (authoritative): ${step.toolName}: " +
                                "${if (step.success) "SUCCESS" else "FAILED"} — ${step.summary}"
                        }
                    }
                }
                ChatMessage.Role.SYSTEM -> if (isExecutionStatus(content)) {
                    listOf("Execution status (authoritative): $content")
                } else {
                    emptyList()
                }
                // CARDS holds a JSON payload, not something anyone said. Handing it to
                // the cloud model would spend tokens restating facts the transcript
                // already carries in prose.
                ChatMessage.Role.CARDS -> emptyList()
            }
        }
    }

    private fun isExecutionStatus(content: String): Boolean {
        val normalized = content.lowercase()
        return listOf(
            "cargar herramienta", "herramienta", "tool", "create contacts", "create_contacts",
            "failed", "falló", "fallo", "error", "intentando", "ejecutando",
            "guardad", "agregad", "permiso", "tarea", "task",
        ).any { it in normalized }
    }
}
