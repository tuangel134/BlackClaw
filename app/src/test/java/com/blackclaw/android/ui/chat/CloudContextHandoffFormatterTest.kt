package com.blackclaw.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudContextHandoffFormatterTest {

    @Test
    fun `conversation handoff keeps user assistant and authoritative tool evidence`() {
        val lines = CloudContextHandoffFormatter.conversationLines(
            listOf(
                ChatMessage(ChatMessage.Role.SYSTEM, "Auto-reply active for Mom on Telegram."),
                ChatMessage(ChatMessage.Role.USER, "The codeword is zulu731."),
                ChatMessage(ChatMessage.Role.ASSISTANT, "ok", modelName = "gpt-4.1"),
                ChatMessage(ChatMessage.Role.TOOL_GROUP, "", toolSteps = listOf(ToolStep("search", "done", success = true))),
                ChatMessage(ChatMessage.Role.SYSTEM, "Accessibility service connecting, please wait..."),
            )
        )

        assertEquals(
            listOf(
                "User: The codeword is zulu731.",
                "Assistant: ok",
                "Tool result (authoritative): search: SUCCESS — done",
            ),
            lines
        )
    }
}
