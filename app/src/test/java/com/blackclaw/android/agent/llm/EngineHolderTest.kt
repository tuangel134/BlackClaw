package com.blackclaw.android.agent.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineHolderTest {

    @Test
    fun `detects exact missing LiteRT vision encoder error`() {
        val error = RuntimeException(
            "Failed to create engine: NOT_FOUND: TF_LITE_VISION_ENCODER not found in the model."
        )

        assertTrue(EngineHolder.isMissingVisionEncoder(error))
        assertFalse(LocalModelRuntime.isGpuBackendFailure(error))
    }

    @Test
    fun `detects missing vision encoder in nested native cause`() {
        val error = RuntimeException(
            "Failed to create engine",
            IllegalStateException("NOT_FOUND: TF_LITE_VISION_ADAPTER missing from bundle"),
        )

        assertTrue(EngineHolder.isMissingVisionEncoder(error))
        assertFalse(LocalModelRuntime.isGpuBackendFailure(error))
    }

    @Test
    fun `does not misclassify real GPU or corrupt model failures as missing vision`() {
        assertFalse(EngineHolder.isMissingVisionEncoder(RuntimeException("OpenCL delegate init failed")))
        assertFalse(EngineHolder.isMissingVisionEncoder(RuntimeException("Invalid flatbuffer model")))
        assertTrue(LocalModelRuntime.isGpuBackendFailure(RuntimeException("OpenCL delegate init failed")))
    }
}
