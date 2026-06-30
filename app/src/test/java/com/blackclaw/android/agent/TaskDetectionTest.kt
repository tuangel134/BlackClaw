package com.blackclaw.android.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DefaultAgentService.isTaskLike] — the router that decides whether a
 * message is an actionable task (→ agent loop with tools) or just chat. Must
 * work in Spanish since that's the app's primary language.
 */
class TaskDetectionTest {

    @Test
    fun spanishAlarmIsTask() {
        assertTrue(DefaultAgentService.isTaskLike("pon una alarma a las 7"))
        assertTrue(DefaultAgentService.isTaskLike("ponme una alarma mañana"))
    }

    @Test
    fun spanishReminderIsTask() {
        assertTrue(DefaultAgentService.isTaskLike("recuérdame llamar al dentista"))
        assertTrue(DefaultAgentService.isTaskLike("avísame en 30 minutos"))
    }

    @Test
    fun spanishMessageIsTask() {
        assertTrue(DefaultAgentService.isTaskLike("manda un mensaje a mamá"))
        assertTrue(DefaultAgentService.isTaskLike("envía un whatsapp a Juan"))
    }

    @Test
    fun spanishOpenAppIsTask() {
        assertTrue(DefaultAgentService.isTaskLike("abre youtube"))
        assertTrue(DefaultAgentService.isTaskLike("abre la cámara"))
    }

    @Test
    fun spanishDeviceControlIsTask() {
        assertTrue(DefaultAgentService.isTaskLike("enciende la linterna"))
        assertTrue(DefaultAgentService.isTaskLike("sube el volumen"))
        assertTrue(DefaultAgentService.isTaskLike("apaga el wifi"))
    }

    @Test
    fun spanishNoteIsTask() {
        assertTrue(DefaultAgentService.isTaskLike("anota que tengo reunión el lunes"))
        assertTrue(DefaultAgentService.isTaskLike("crea una nota"))
    }

    @Test
    fun englishStillWorks() {
        assertTrue(DefaultAgentService.isTaskLike("open whatsapp"))
        assertTrue(DefaultAgentService.isTaskLike("set an alarm at 7"))
        assertTrue(DefaultAgentService.isTaskLike("send a message to mom"))
    }

    @Test
    fun deviceNounsTrigger() {
        assertTrue(DefaultAgentService.isTaskLike("cuánta batería tengo"))
        assertTrue(DefaultAgentService.isTaskLike("lee mis notificaciones"))
    }

    @Test
    fun casualChatIsNotTask() {
        assertFalse(DefaultAgentService.isTaskLike("hola qué tal"))
        assertFalse(DefaultAgentService.isTaskLike("cuéntame un chiste"))
        assertFalse(DefaultAgentService.isTaskLike("gracias"))
    }

    @Test
    fun pureQuestionIsNotTask() {
        assertFalse(DefaultAgentService.isTaskLike("quién fue Einstein"))
        assertFalse(DefaultAgentService.isTaskLike("por qué el cielo es azul"))
    }
}
