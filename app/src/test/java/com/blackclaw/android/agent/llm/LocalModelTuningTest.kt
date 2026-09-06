package com.blackclaw.android.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelTuningTest {

    @Test
    fun `normalization clamps unsafe values`() {
        val value = LocalModelTuning(
            temperature = 9.0,
            topP = 2.0,
            topK = 500,
            maxOutputTokens = 9000,
            contextWindowTokens = 1000,
        ).normalized()

        assertEquals(2.0, value.temperature, 0.0)
        assertEquals(1.0, value.topP, 0.0)
        assertEquals(64, value.topK)
        assertEquals(2048, value.contextWindowTokens)
        assertTrue(value.maxOutputTokens < value.contextWindowTokens)
    }

    @Test
    fun `balanced preset reserves output and safety budget`() {
        val tuning = LocalModelTuning.preset(LocalModelPreset.BALANCED, 8192)
        assertEquals(1536, tuning.maxOutputTokens)
        assertEquals(6272, LocalContextBudget.inputBudgetTokens(tuning))
    }

    @Test
    fun `context guard recreates before 8192 token overflow`() {
        val tuning = LocalModelTuning(
            contextWindowTokens = 8192,
            maxOutputTokens = 1536,
            autoCompactContext = true,
        )
        // Conservative estimate: 3 chars/token. 19k chars are already beyond the
        // 6272-token input budget once the next turn is included.
        assertTrue(LocalContextBudget.shouldRecreate(18_500, 1_000, tuning))
        assertFalse(LocalContextBudget.shouldRecreate(4_000, 500, tuning))
    }

    @Test
    fun `detects native token overflow error from screenshot`() {
        val error = RuntimeException(
            "Failed to call nativeSendMessage: INVALID_ARGUMENT: Input token ids are too long. " +
                "Exceeding the maximum number of tokens allowed: 9838 >= 8192"
        )
        assertTrue(LocalContextBudget.isTokenOverflow(error))
    }

    @Test
    fun `auto compact can be explicitly disabled`() {
        val tuning = LocalModelTuning(autoCompactContext = false)
        assertFalse(LocalContextBudget.shouldRecreate(100_000, 10_000, tuning))
    }
}
