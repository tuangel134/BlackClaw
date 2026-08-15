package com.blackclaw.android.conversation

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationQuickRepliesTest {
    @Test fun `answers broad automation capability immediately`() {
        val reply = ConversationQuickReplies.replyFor("¿puedes programar tareas cierto?")
        assertTrue(reply?.startsWith("Sí.") == true)
    }

    @Test fun `does not intercept an actionable request`() {
        assertNull(ConversationQuickReplies.replyFor("¿puedes programar una tarea para mañana a las 9?"))
        assertNull(ConversationQuickReplies.replyFor("¿puedes apagar la linterna?"))
    }

    @Test fun `answers general capability question`() {
        assertTrue(ConversationQuickReplies.replyFor("¿qué puedes hacer?")?.isNotBlank() == true)
    }
}
