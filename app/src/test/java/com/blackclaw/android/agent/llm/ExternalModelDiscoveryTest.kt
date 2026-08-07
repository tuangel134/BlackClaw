package com.blackclaw.android.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalModelDiscoveryTest {

    @Test
    fun classifiesLitertModelAsCompatible() {
        val format = ExternalModelDiscovery.classify("Gemma-4-E2B-it.litertlm")

        assertEquals(ExternalModelDiscovery.Format.LITERT_LM, format)
        assertTrue(format.canRunInBlackClaw)
    }

    @Test
    fun recognizesCommonExternalModelFormatsWithoutClaimingTheyCanRun() {
        assertEquals(ExternalModelDiscovery.Format.GGUF, ExternalModelDiscovery.classify("qwen2.5.gguf"))
        assertEquals(ExternalModelDiscovery.Format.MEDIAPIPE_TASK, ExternalModelDiscovery.classify("gemma.task"))
        assertEquals(ExternalModelDiscovery.Format.TFLITE, ExternalModelDiscovery.classify("gemma.tflite"))
        assertFalse(ExternalModelDiscovery.classify("qwen2.5.gguf").canRunInBlackClaw)
    }

    @Test
    fun ignoresOrdinaryBinFilesButFindsNamedModelBinaries() {
        assertFalse(ExternalModelDiscovery.isModelCandidate("movie.bin"))
        assertTrue(ExternalModelDiscovery.isModelCandidate("gemma-2b.bin"))
    }
}
