package com.blackclaw.android.conversation

import com.blackclaw.android.agent.TaskClassifier

/** Typed Conversation Engine decision used consistently by every surface. */
object ConversationRouter {
    enum class Mode { CONVERSE, READ, ACT }
    enum class Confirmation { NONE, REQUIRED }
    data class Decision(val mode: Mode, val confidence: Double, val confirmation: Confirmation,
                        val reason: String)

    private val readSignals = listOf(
        "lee ", "léeme", "dime mi", "muestra", "consulta", "revisa", "cuánta batería", "cuanta bateria",
        "notificaciones", "qué tengo", "que tengo", "agenda", "ubicación", "ubicacion", "clipboard",
        "read ", "show ", "check ", "what notifications", "battery",
    )
    private val destructive = listOf(
        "borra todo", "elimina todo", "desinstala", "formatea", "restablecer de fábrica",
        "envía dinero", "envia dinero", "transfiere", "paga ", "factory reset", "delete all", "send money",
    )

    fun decide(text: String): Decision {
        val normalized = text.lowercase().trim()
        if (!TaskClassifier.isTask(text)) return Decision(Mode.CONVERSE, .92, Confirmation.NONE, "conversación")
        val mode = if (readSignals.any { it in normalized }) Mode.READ else Mode.ACT
        val confirmation = if (mode == Mode.ACT && destructive.any { it in normalized })
            Confirmation.REQUIRED else Confirmation.NONE
        return Decision(mode, if (mode == Mode.READ) .86 else .88, confirmation,
            if (mode == Mode.READ) "lectura sin cambios" else "acción solicitada")
    }
}
