package com.blackclaw.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProviderTest {

    @Test
    fun `paid provider catalogs expose current text models`() {
        assertEquals(
            listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"),
            CloudProvider.OPENAI.models.map { it.id },
        )
        assertTrue(CloudProvider.ANTHROPIC.models.map { it.id }.containsAll(
            listOf("claude-fable-5", "claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5")
        ))
        assertTrue(CloudProvider.GOOGLE.models.map { it.id }.containsAll(
            listOf("gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
        ))
        assertFalse(CloudProvider.OPENAI.models.any { it.id.startsWith("gpt-4") })
        assertFalse(CloudProvider.ANTHROPIC.models.any { it.id == "claude-sonnet-4-6" })
    }

    @Test
    fun `all paid catalog entries have valid metadata and tracked pricing`() {
        CloudProvider.entries
            .filter { it != CloudProvider.OPENCODE_ZEN && it != CloudProvider.CUSTOM }
            .flatMap { it.models }
            .forEach { model ->
                assertTrue("context for ${model.id}", model.contextSize > 0)
                assertTrue("input price for ${model.id}", model.inputPricePerM >= 0.0)
                assertTrue("output price for ${model.id}", model.outputPricePerM >= 0.0)
                assertEquals(
                    ModelPricing.Price(model.inputPricePerM, model.outputPricePerM),
                    ModelPricing.findPrice(model.id),
                )
            }
    }

    @Test
    fun `Google uses the OpenAI compatibility endpoint`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/",
            CloudProvider.GOOGLE.defaultBaseUrl,
        )
    }
}
