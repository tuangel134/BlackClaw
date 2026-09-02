package com.blackclaw.android.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLatencyPolicyTest {
    private fun config(
        provider: LlmProvider = LlmProvider.OPENAI,
        model: String = "gemini-fast",
        key: String = "key",
        baseUrl: String = "https://example.invalid/v1",
        temperature: Double = 0.1,
        systemPrompt: String = "prompt-a",
        maxIterations: Int = 20,
    ) = AgentConfig(
        apiKey = key,
        baseUrl = baseUrl,
        modelName = model,
        systemPrompt = systemPrompt,
        maxIterations = maxIterations,
        temperature = temperature,
        provider = provider,
        streaming = true,
    )

    @Test fun `same direct cloud transport reuses warm client`() {
        val current = config(systemPrompt = "old prompt", maxIterations = 20)
        val incoming = config(systemPrompt = "new memory and prompt", maxIterations = 60)

        assertTrue(
            DefaultAgentService.canReuseCloudClient(
                current,
                incoming,
                currentAutomatic = false,
                incomingAutomatic = false,
            )
        )
    }

    @Test fun `model provider endpoint key or temperature change rebuilds client`() {
        val current = config()
        assertFalse(DefaultAgentService.canReuseCloudClient(current, config(model = "other"), false, false))
        assertFalse(DefaultAgentService.canReuseCloudClient(current, config(provider = LlmProvider.ANTHROPIC), false, false))
        assertFalse(DefaultAgentService.canReuseCloudClient(current, config(baseUrl = "https://other.invalid/v1"), false, false))
        assertFalse(DefaultAgentService.canReuseCloudClient(current, config(key = "other-key"), false, false))
        assertFalse(DefaultAgentService.canReuseCloudClient(current, config(temperature = 0.3), false, false))
    }

    @Test fun `local and automatic modes never reuse task client`() {
        assertFalse(DefaultAgentService.canReuseCloudClient(config(provider = LlmProvider.LOCAL), config(provider = LlmProvider.LOCAL), false, false))
        assertFalse(DefaultAgentService.canReuseCloudClient(config(), config(), true, true))
        assertFalse(DefaultAgentService.canReuseCloudClient(config(), config(), false, true))
    }

    @Test fun `fast chat only accepts unambiguous conversation`() {
        assertTrue(DefaultAgentService.shouldUseFastChat("hola qué tal"))
        assertTrue(DefaultAgentService.shouldUseFastChat("quién fue Einstein"))
        assertFalse(DefaultAgentService.shouldUseFastChat("abre WhatsApp"))
        assertFalse(DefaultAgentService.shouldUseFastChat("lee mis notificaciones"))
        assertFalse(DefaultAgentService.shouldUseFastChat("pon una alarma mañana a las 7"))
    }

    @Test fun `fast chat prompt stays compact and tool free`() {
        assertTrue("Fast chat prompt grew unexpectedly", AgentPrompts.FAST_CHAT.length < 1_200)
        assertFalse(AgentPrompts.FAST_CHAT.contains("get_screen_info"))
        assertFalse(AgentPrompts.FAST_CHAT.contains("request_tool"))
    }
}
