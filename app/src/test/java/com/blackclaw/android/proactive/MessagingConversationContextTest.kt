package com.blackclaw.android.proactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingConversationContextTest {

    @Test
    fun exactContactTitleMatchesCapturedHeader() {
        assertTrue(MessagingConversationContext.threadMatches("Carlos", listOf("Carlos", "en línea")))
    }

    @Test
    fun notificationSubtitleStillMatchesContactHeader() {
        assertTrue(MessagingConversationContext.threadMatches("Carlos Hernández", listOf("Carlos Hernández")))
    }

    @Test
    fun tokenOverlapHandlesDecoratedGroupName() {
        assertTrue(
            MessagingConversationContext.threadMatches(
                "Familia Hernández",
                listOf("Familia Hernández (8)"),
            )
        )
    }

    @Test
    fun unrelatedChatDoesNotLeakContext() {
        assertFalse(MessagingConversationContext.threadMatches("Carlos", listOf("Yara", "en línea")))
    }
}
