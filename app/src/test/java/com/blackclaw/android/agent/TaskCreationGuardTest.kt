package com.blackclaw.android.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCreationGuardTest {

    @Test
    fun `native todo request cannot finish as an app recommendation`() {
        val guard = TaskCreationGuard.fromTask("crea una tarea: comprar leche")

        assertTrue(guard.shouldBlockTextOnlyCompletion("Puedes usar Todoist para eso."))
        assertTrue(guard.buildPromptSection().contains("assistant_note"))
        assertTrue(guard.maybeBlockFinish().orEmpty().contains("BlackClaw"))

        guard.recordToolAttempt("assistant_note")
        guard.recordToolResult("assistant_note", success = true)
        assertFalse(guard.shouldBlockTextOnlyCompletion("Tarea guardada."))
        assertFalse(guard.maybeBlockFinish().orEmpty().isNotEmpty())
    }

    @Test
    fun `wifi event task selects automation path`() {
        val guard = TaskCreationGuard.fromTask("crea una tarea cuando me conecte al wifi")

        assertTrue(guard.buildPromptSection().contains("automation_profile"))
        assertTrue(guard.shouldBlockTextOnlyCompletion("Usa Tasker o MacroDroid."))
        guard.recordToolResult("automation_profile", success = true)
        assertFalse(guard.shouldBlockTextOnlyCompletion("Vista previa creada."))
    }

    @Test
    fun `generic task question is not guarded`() {
        val guard = TaskCreationGuard.fromTask("qué es una tarea programada")

        assertFalse(guard.shouldBlockTextOnlyCompletion("Es una tarea que se ejecuta más tarde."))
        assertTrue(guard.buildPromptSection().isEmpty())
    }
}
