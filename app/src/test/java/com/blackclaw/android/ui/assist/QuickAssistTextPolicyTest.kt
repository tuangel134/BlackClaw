package com.blackclaw.android.ui.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickAssistTextPolicyTest {
    @Test fun `farewells and greetings stay conversational`() {
        assertTrue(QuickAssistTextPolicy.isFarewell("muchas gracias"))
        assertTrue(QuickAssistTextPolicy.isGreeting("Hola BlackClaw!"))
        assertFalse(QuickAssistTextPolicy.isFarewell("gracias por buscar el clima de mañana"))
    }

    @Test fun `screen questions are recognized in spanish and english`() {
        assertTrue(QuickAssistTextPolicy.isScreenQuery("¿Qué hay en mi pantalla?"))
        assertTrue(QuickAssistTextPolicy.isScreenQuery("read my screen"))
        assertFalse(QuickAssistTextPolicy.isScreenQuery("cuéntame un chiste"))
    }

    @Test fun `failure classification returns useful recovery`() {
        assertEquals(QuickAssistRecovery.ACCESSIBILITY, QuickAssistTextPolicy.recoveryFor("Falta permiso de accesibilidad"))
        assertEquals(QuickAssistRecovery.CONNECTION, QuickAssistTextPolicy.recoveryFor("network timeout"))
        assertEquals(QuickAssistRecovery.RETRY, QuickAssistTextPolicy.recoveryFor("element not found"))
        assertEquals(null, QuickAssistTextPolicy.recoveryFor(""))
    }

    @Test fun `reasoning lines are removed without losing answer`() {
        val cleaned = QuickAssistTextPolicy.stripReasoning("Debo responder al usuario con brevedad.\nListo, jefe.")
        assertEquals("Listo, jefe.", cleaned)
    }

    @Test fun `sentence boundary waits for a useful chunk`() {
        assertEquals(0, QuickAssistTextPolicy.sentenceBoundary("Hola.", 0))
        assertEquals(17, QuickAssistTextPolicy.sentenceBoundary("Esta frase sirve. Otra", 0))
    }

    @Test fun `app launch tools are detected`() {
        assertTrue(QuickAssistTextPolicy.isAppLaunchTool("open_app_action"))
        assertTrue(QuickAssistTextPolicy.isAppLaunchTool("send_message"))
        assertFalse(QuickAssistTextPolicy.isAppLaunchTool("get_device_info"))
    }
}
