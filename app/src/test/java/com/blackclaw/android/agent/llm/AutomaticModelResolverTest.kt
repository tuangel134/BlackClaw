package com.blackclaw.android.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticModelResolverTest {

    @Test
    fun `automatic prefers cloud when validated internet is available`() {
        assertEquals(
            ActiveModelMode.CLOUD,
            AutomaticModelResolver.effectiveMode(
                selectedMode = ActiveModelMode.AUTOMATIC,
                internetValidated = true,
                hasLocalModel = true,
                hasCloudModel = true,
            )
        )
    }

    @Test
    fun `automatic falls back to local when internet is unavailable`() {
        assertEquals(
            ActiveModelMode.LOCAL,
            AutomaticModelResolver.effectiveMode(
                selectedMode = ActiveModelMode.AUTOMATIC,
                internetValidated = false,
                hasLocalModel = true,
                hasCloudModel = true,
            )
        )
    }

    @Test
    fun `automatic uses the only configured model when one side is missing`() {
        assertEquals(
            ActiveModelMode.LOCAL,
            AutomaticModelResolver.effectiveMode(
                selectedMode = ActiveModelMode.AUTOMATIC,
                internetValidated = true,
                hasLocalModel = true,
                hasCloudModel = false,
            )
        )
        assertEquals(
            ActiveModelMode.CLOUD,
            AutomaticModelResolver.effectiveMode(
                selectedMode = ActiveModelMode.AUTOMATIC,
                internetValidated = false,
                hasLocalModel = false,
                hasCloudModel = true,
            )
        )
    }

    @Test
    fun `explicit modes are never changed by automatic resolver`() {
        assertEquals(
            ActiveModelMode.LOCAL,
            AutomaticModelResolver.effectiveMode(
                selectedMode = ActiveModelMode.LOCAL,
                internetValidated = true,
                hasLocalModel = false,
                hasCloudModel = true,
            )
        )
        assertEquals(
            ActiveModelMode.CLOUD,
            AutomaticModelResolver.effectiveMode(
                selectedMode = ActiveModelMode.CLOUD,
                internetValidated = false,
                hasLocalModel = true,
                hasCloudModel = false,
            )
        )
    }
}
