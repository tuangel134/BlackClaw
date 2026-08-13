package com.blackclaw.android.ui.chat

import java.text.Normalizer

/** Detects a short follow-up that is asking to resume a task that just stopped. */
object TaskContinuationDetector {
    private val CONTINUE_WORDS = listOf(
        "continua", "continuar", "sigue", "seguir", "reanuda", "reanudar",
        "continue", "resume", "keep going", "go on",
    )
    private val INCOMPLETE_MARKERS = listOf(
        "iteracion", "iteration", "stopped", "detuvo", "detenido", "incompleta",
        "incomplete", "error", "failed", "no pude", "no se pudo", "limite",
        "limit", "stuck", "atascad",
    )

    fun isContinuationRequest(text: String, messages: List<ChatMessage>): Boolean {
        val normalized = normalize(text.trim())
        if (normalized.isBlank() || CONTINUE_WORDS.none { normalized == it || normalized.startsWith("$it ") }) {
            return false
        }
        val lastAssistant = messages.asReversed()
            .firstOrNull { it.role == ChatMessage.Role.ASSISTANT && !it.isPending }
            ?.content
            ?.let(::normalize)
            ?: return false
        val hasPreviousUserTask = messages.any { it.role == ChatMessage.Role.USER && it.content.isNotBlank() }
        return hasPreviousUserTask && INCOMPLETE_MARKERS.any { it in lastAssistant }
    }

    fun buildPrompt(userText: String): String =
        "Continúa la tarea anterior desde el estado actual. Conserva lo que ya se completó, " +
            "no repitas pasos confirmados y termina solo lo pendiente. Instrucción del usuario: $userText"

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace("\\p{M}".toRegex(), "")
}
