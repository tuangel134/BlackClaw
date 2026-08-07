package com.blackclaw.android.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeZenModelsTest {

    @Test
    fun `selects every free catalog model even when paid models come first`() {
        val catalog = List(55) { "paid-model-$it" } + listOf(
            "deepseek-v4-flash-free",
            "big-pickle",
            "north-mini-code-free",
        )

        assertEquals(
            listOf("big-pickle", "deepseek-v4-flash-free", "north-mini-code-free"),
            OpenCodeZenModels.selectFreeCandidates(catalog),
        )
    }

    @Test
    fun `normalizes duplicates and ignores non free catalog entries`() {
        assertEquals(
            listOf("big-pickle", "mimo-v2.5-free"),
            OpenCodeZenModels.selectFreeCandidates(
                listOf(" BIG-PICKLE ", "mimo-v2.5-free", "mimo-v2.5-free", "glm-5.2"),
            ),
        )
    }
}
