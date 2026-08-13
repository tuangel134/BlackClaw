package com.blackclaw.android.ui.chat

import com.blackclaw.android.agent.TaskClassifier
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

    /**
     * Short replies are common after a tool result: "ya", "pudiste?", "sí", or
     * "otros 30". They do not look like tasks to the classifier on their own, so
     * keep them here instead of sending them to the text-only chat model.
     */
    private val SHORT_FOLLOW_UPS = setOf(
        "ya", "si", "sí", "ok", "okay", "dale", "va",
        "pudiste", "hazlo", "hazla", "hazlos", "hazlas",
        "manualmente", "otros", "otra", "reintenta", "reintentar",
        "intenta", "intentarlo", "ahora", "continua", "continuar",
        "sigue", "seguir", "reanuda", "reanudar", "continue", "resume",
    )

    private val FOLLOW_UP_PREFIXES = listOf(
        "otros ", "otra ", "hazlo ", "hazla ", "hazlos ", "hazlas ",
        "reintenta ", "reintentar ", "intenta ", "intentarlo ",
        "cuantos ", "cuantas ", "porque ", "por que ", "que paso ",
        "agrega ", "agregalos ", "agregalas ", "anade ", "anadelos ",
        "continua ", "continuar ", "sigue ", "seguir ",
    )

    private val TASK_EVIDENCE_MARKERS = listOf(
        "cargar herramienta", "herramienta", "tool", "create contacts", "create_contacts",
        "failed", "fallo", "error", "intentando", "ejecutando", "guardad", "agreg",
        "contactos", "permiso", "accesibilidad", "abrir la app", "tarea", "task",
    )

    fun isContinuationRequest(text: String, messages: List<ChatMessage>): Boolean {
        val normalized = normalize(text.trim())
        if (normalized.isBlank() || !looksLikeFollowUp(normalized)) return false

        // Do not hijack a normal "sí" or "ok". A continuation is only valid when
        // the visible transcript contains a real task and an execution/failure
        // signal immediately before the follow-up.
        return hasRecentTaskContext(messages)
    }

    fun buildPrompt(userText: String): String =
        "Continúa la tarea anterior desde el estado actual. Conserva lo que ya se completó, " +
            "no repitas pasos confirmados y termina solo lo pendiente. Los resultados de las " +
            "herramientas son la única prueba de que algo ocurrió: no conviertas una respuesta " +
            "anterior del modelo en éxito y verifica antes de afirmarlo. Instrucción del usuario: $userText"

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace("\\p{M}".toRegex(), "")
            .replace("[^a-z0-9\\s]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

    private fun looksLikeFollowUp(normalized: String): Boolean {
        if (normalized in SHORT_FOLLOW_UPS) return true
        if (CONTINUE_WORDS.any { normalized == it || normalized.startsWith("$it ") }) return true
        if (FOLLOW_UP_PREFIXES.any { normalized.startsWith(it) }) return true
        return normalized.startsWith("cuantos") || normalized.startsWith("cuantas") ||
            normalized.contains(" no los veo") || normalized.contains(" no aparecen")
    }

    private fun hasRecentTaskContext(messages: List<ChatMessage>): Boolean {
        if (messages.isEmpty()) return false

        val recent = messages.takeLast(24)
        val hasTaskRequest = recent.any {
            it.role == ChatMessage.Role.USER && it.content.isNotBlank() && TaskClassifier.isTask(it.content)
        }
        if (!hasTaskRequest) return false

        val lastAssistant = recent.asReversed()
            .firstOrNull { it.role == ChatMessage.Role.ASSISTANT && !it.isPending }
            ?.let { normalize(it.content) }
            ?: ""
        val lastExecutionSignal = recent.asReversed().any { message ->
            when (message.role) {
                ChatMessage.Role.TOOL_GROUP -> true
                ChatMessage.Role.SYSTEM,
                ChatMessage.Role.ASSISTANT -> TASK_EVIDENCE_MARKERS.any { marker ->
                    normalize(message.content).contains(marker)
                }
                else -> false
            }
        }

        // A tool group/system status is authoritative. For old conversations that
        // predate tool groups, retain compatibility with the assistant's task
        // summary, but only when the previous user message was itself actionable.
        return lastExecutionSignal || INCOMPLETE_MARKERS.any { it in lastAssistant }
    }
}
