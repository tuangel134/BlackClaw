package com.blackclaw.android.agent.llm

import com.blackclaw.android.agent.AgentConfig
import com.blackclaw.android.agent.LlmProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AutomaticModelManagerTest {

    @Test
    fun `custom endpoint remains in AUTO candidate list`() {
        val candidate = AutomaticModelManager.candidateForConfig(
            AgentConfig(
                apiKey = "key",
                baseUrl = "https://gateway.example/v1/",
                modelName = "my-model",
                provider = LlmProvider.OPENAI,
            )
        )

        assertNotNull(candidate)
        assertEquals("CLOUD:CUSTOM:my-model", candidate!!.key)
        assertEquals("https://gateway.example/v1/", candidate.baseUrl)
    }

    @Test
    fun `local candidate uses a stable canonical path`() {
        val candidate = AutomaticModelManager.candidateForConfig(
            AgentConfig(
                apiKey = "",
                baseUrl = "/tmp/../tmp/blackclaw-model.litertlm",
                modelName = "gemma4-e2b",
                provider = LlmProvider.LOCAL,
            )
        )

        assertNotNull(candidate)
        assertEquals(AutomaticModelManager.Kind.LOCAL, candidate!!.kind)
        assertEquals("LOCAL:/tmp/blackclaw-model.litertlm", candidate.key)
    }
}
