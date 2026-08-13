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
    fun `does not hijack normal conversation`() {
        val messages = listOf(
            ChatMessage(ChatMessage.Role.USER, "¿Qué significa esta palabra?"),
            ChatMessage(ChatMessage.Role.ASSISTANT, "Es una explicación normal."),
        )

        assertFalse(TaskContinuationDetector.isContinuationRequest("continua", messages))
        assertFalse(TaskContinuationDetector.isContinuationRequest("continua mañana", emptyList()))
    }
}
