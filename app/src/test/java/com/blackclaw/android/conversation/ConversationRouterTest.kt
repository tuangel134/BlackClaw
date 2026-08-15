package com.blackclaw.android.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationRouterTest {
    @Test fun `routes ordinary conversation without tools`() {
        assertEquals(ConversationRouter.Mode.CONVERSE, ConversationRouter.decide("¿Qué opinas de esta idea?").mode)
    }

    @Test fun `routes device reads separately from actions`() {
        assertEquals(ConversationRouter.Mode.READ, ConversationRouter.decide("Lee mis notificaciones").mode)
        assertEquals(ConversationRouter.Mode.ACT, ConversationRouter.decide("Enciende la linterna").mode)
    }

    @Test fun `destructive acts require confirmation`() {
        val decision = ConversationRouter.decide("Desinstala WhatsApp")
        assertEquals(ConversationRouter.Mode.ACT, decision.mode)
        assertEquals(ConversationRouter.Confirmation.REQUIRED, decision.confirmation)
    }

    @Test fun `destructive verb with destructive object requires confirmation`() {
        val decision = ConversationRouter.decide("Elimina todo del teléfono")
        assertEquals(ConversationRouter.Mode.ACT, decision.mode)
        assertEquals(ConversationRouter.Confirmation.REQUIRED, decision.confirmation)
    }

    @Test fun `send money requires confirmation`() {
        val decision = ConversationRouter.decide("Envía dinero a Carlos")
        assertEquals(ConversationRouter.Confirmation.REQUIRED, decision.confirmation)
    }

    @Test fun `normal actions do not require confirmation`() {
        val decision = ConversationRouter.decide("Abre Spotify")
        assertEquals(ConversationRouter.Mode.ACT, decision.mode)
        assertEquals(ConversationRouter.Confirmation.NONE, decision.confirmation)
    }

    @Test fun `battery query routes as READ`() {
        assertEquals(ConversationRouter.Mode.READ, ConversationRouter.decide("¿Cuánta batería tengo?").mode)
    }

    @Test fun `english commands route correctly`() {
        assertEquals(ConversationRouter.Mode.ACT, ConversationRouter.decide("Turn on the flashlight").mode)
        assertEquals(ConversationRouter.Mode.READ, ConversationRouter.decide("Check my battery").mode)
    }

    @Test fun `greeting is conversation not task`() {
        assertEquals(ConversationRouter.Mode.CONVERSE, ConversationRouter.decide("Hola, ¿cómo estás?").mode)
    }

    @Test fun `capability question is conversation not an action`() {
        assertEquals(ConversationRouter.Mode.CONVERSE, ConversationRouter.decide("¿puedes programar tareas cierto?").mode)
        assertEquals(ConversationRouter.Mode.ACT, ConversationRouter.decide("¿puedes programar una tarea para mañana a las 9?").mode)
    }

    @Test fun `explanations stay conversation while device reads remain actions`() {
        assertEquals(ConversationRouter.Mode.CONVERSE, ConversationRouter.decide("¿cómo puedo programar tareas?").mode)
        assertEquals(ConversationRouter.Mode.CONVERSE, ConversationRouter.decide("¿para qué sirve abrir WhatsApp?").mode)
        assertEquals(ConversationRouter.Mode.ACT, ConversationRouter.decide("¿qué batería tengo?").mode)
    }

    @Test fun `delete all requires confirmation`() {
        val decision = ConversationRouter.decide("Borra todo")
        assertEquals(ConversationRouter.Confirmation.REQUIRED, decision.confirmation)
    }
}
