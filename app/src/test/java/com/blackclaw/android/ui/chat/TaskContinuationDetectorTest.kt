package com.blackclaw.android.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskContinuationDetectorTest {
    @Test
    fun `resumes a task that stopped at the iteration limit`() {
        val messages = listOf(
            ChatMessage(ChatMessage.Role.USER, "Añade 30 contactos inventados"),
            ChatMessage(ChatMessage.Role.ASSISTANT, "Error: se alcanzó el número máximo de iteraciones (60)"),
        )

        assertTrue(TaskContinuationDetector.isContinuationRequest("continúa", messages))
        assertTrue(TaskContinuationDetector.buildPrompt("continua").contains("estado actual"))
    }

    @Test
    fun `routes short replies after a tool task back to the agent`() {
        val messages = listOf(
            ChatMessage(ChatMessage.Role.USER, "Añade contactos inventados"),
            ChatMessage(ChatMessage.Role.SYSTEM, "Create Contacts..."),
            ChatMessage(ChatMessage.Role.TOOL_GROUP, "", toolSteps = listOf(
                ToolStep("Create Contacts", "FAILED — permiso denegado", success = false),
            )),
            ChatMessage(ChatMessage.Role.ASSISTANT, "No se pudieron guardar los contactos."),
        )

        assertTrue(TaskContinuationDetector.isContinuationRequest("ya", messages))
        assertTrue(TaskContinuationDetector.isContinuationRequest("pudiste?", messages))
        assertTrue(TaskContinuationDetector.isContinuationRequest("otros 30", messages))
        assertTrue(TaskContinuationDetector.isContinuationRequest("sí", messages))
    }

    @Test
    fun `does not route acknowledgements after normal chat`() {
        val messages = listOf(
            ChatMessage(ChatMessage.Role.USER, "¿Qué significa esta palabra?"),
            ChatMessage(ChatMessage.Role.ASSISTANT, "Es una explicación normal."),
        )

        assertFalse(TaskContinuationDetector.isContinuationRequest("sí", messages))
        assertFalse(TaskContinuationDetector.isContinuationRequest("ok", messages))
        assertFalse(TaskContinuationDetector.isContinuationRequest("otros 30", messages))
    }

    @Test
    fun `does not hijack normal conversation`() {
        val messages = listOf(
            ChatMessage(ChatMessage.Role.USER, "¿Qué significa esta palabra?"),
            ChatMessage(ChatMessage.Role.ASSISTANT, "Es una explicación normal."),
        )

        assertFalse(TaskContinuationDetector.isContinuationRequest("continua", messages))
        assertFalse(TaskContinuationDetector.isContinuationRequest("continua mañana", emptyList()))
    }
}
