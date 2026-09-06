package com.blackclaw.android.agent.llm

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiMessageNormalizationTest {

    @Test
    fun `merges late system messages into the first system message`() {
        val messages = listOf<ChatMessage>(
            SystemMessage.from("base instructions"),
            UserMessage.from("hello"),
            AiMessage.from("hi"),
            SystemMessage.from("model switch notice"),
            UserMessage.from("continue"),
        )

        val normalized = normalizeMessagesForOpenAi(messages)

        assertTrue(normalized.first() is SystemMessage)
        assertEquals(1, normalized.count { it is SystemMessage })
        assertEquals(
            "base instructions\n\nmodel switch notice",
            (normalized.first() as SystemMessage).text(),
        )
        assertEquals(
            listOf("hello", "hi", "continue"),
            normalized.drop(1).map { message ->
                when (message) {
                    is UserMessage -> message.singleText()
                    is AiMessage -> message.text()
                    else -> message.toString()
                }
            },
        )
    }
}
