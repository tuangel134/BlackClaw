package com.blackclaw.android.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIntentRegressionTest {

    @Test
    fun `notification mentions do not read device notifications`() {
        val contextualMentions = listOf(
            "notificación",
            "qué es una notificación",
            "la palabra notificación aparece mucho",
            "hablemos de las notificaciones de Android",
            "mis notificaciones son molestas",
            "resume este artículo sobre las notificaciones",
            "muéstrame cómo crear las notificaciones",
            "muestra las notificaciones de este artículo",
            "quiero aprender a leer mis notificaciones desde Kotlin",
        )

        contextualMentions.forEach { prompt ->
            assertNull(
                "Must not execute get_notifications for: $prompt",
                DirectDeviceDataGuard.deterministicToolCall(prompt),
            )
        }
    }

    @Test
    fun `bare and conceptual notification mentions stay in chat`() {
        listOf(
            "notificación",
            "qué es una notificación",
            "la palabra notificación aparece mucho",
            "hablemos de las notificaciones de Android",
            "mis notificaciones son molestas",
        ).forEach { prompt ->
            assertFalse("Expected CHAT: $prompt", TaskClassifier.isTask(prompt))
        }
    }

    @Test
    fun `explicit notification data requests still use the fast path`() {
        listOf(
            "lee mis notificaciones",
            "revisa las notificaciones",
            "qué notificaciones tengo",
            "hay notificaciones nuevas",
            "yo whats on my notifs",
            "check my notifications",
            "show me current notifications",
            "how many notifications",
            "what notifications do i have",
            "puedes leer mis notificaciones",
            "quiero ver mis notificaciones",
            "enséñame mis notificaciones",
        ).forEach { prompt ->
            val call = DirectDeviceDataGuard.deterministicToolCall(prompt)
            assertNotNull("Expected notification tool call for: $prompt", call)
            assertTrue(call?.toolName == "get_notifications")
            assertTrue("Expected TASK: $prompt", TaskClassifier.isTask(prompt))
        }
    }

    @Test
    fun `negated notification requests never execute the tool`() {
        listOf(
            "no leas mis notificaciones",
            "nunca revises mis notificaciones",
            "don't read my notifications",
            "do not check my notifications",
        ).forEach { prompt ->
            assertNull(
                "Must not execute a notification tool for negated request: $prompt",
                DirectDeviceDataGuard.deterministicToolCall(prompt),
            )
        }
    }
}
