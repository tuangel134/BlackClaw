package com.blackclaw.android.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalModelManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `model directory uses external app storage when it can be created`() {
        val externalRoot = temporaryFolder.newFolder("external")
        val internalRoot = temporaryFolder.newFolder("internal")

        val dir = LocalModelManager.resolveUsableModelDir(externalRoot, internalRoot)

        assertEquals(externalRoot.resolve("models"), dir)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `model directory falls back to internal storage when external path is unusable`() {
        val externalRoot = temporaryFolder.newFolder("external")
        externalRoot.resolve("models").writeText("blocking file")
        val internalRoot = temporaryFolder.newFolder("internal")

        val dir = LocalModelManager.resolveUsableModelDir(externalRoot, internalRoot)

        assertEquals(internalRoot.resolve("models"), dir)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `model directory falls back to internal storage when external path is not writable`() {
        val externalRoot = temporaryFolder.newFolder("external")
        val internalRoot = temporaryFolder.newFolder("internal")
        val externalModelDir = externalRoot.resolve("models")

        val dir = LocalModelManager.resolveUsableModelDir(
            externalRoot = externalRoot,
            internalRoot = internalRoot,
            canWriteDirectory = { candidate -> candidate != externalModelDir },
        )

        assertEquals(internalRoot.resolve("models"), dir)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `model directory falls back to internal storage when external root is missing`() {
        val internalRoot = temporaryFolder.newFolder("internal")

        val dir = LocalModelManager.resolveUsableModelDir(null, internalRoot)

        assertEquals(internalRoot.resolve("models"), dir)
        assertTrue(dir.isDirectory)
    }
    @Test
    fun `catalog marks known text-only and multimodal community bundles`() {
        assertEquals(
            LocalModelManager.VisionSupport.NO,
            LocalModelManager.visionSupportForPath("/models/gemma-4-E2B-uncensored-max.litertlm"),
        )
        assertEquals(
            LocalModelManager.VisionSupport.NO,
            LocalModelManager.visionSupportForPath("/Downloads/gemma-4-E2B-it-Uncensored-MAX.litertlm"),
        )
        assertEquals(
            LocalModelManager.VisionSupport.YES,
            LocalModelManager.visionSupportForPath("/models/gemma-4-E4B-it-abliterated.litertlm"),
        )
        assertEquals(
            LocalModelManager.VisionSupport.YES,
            LocalModelManager.visionSupportForPath("/models/Huihui-gemma-4-E2B-it-abliterated.litertlm"),
        )
        assertEquals(
            LocalModelManager.VisionSupport.UNKNOWN,
            LocalModelManager.visionSupportForPath("/models/custom-user-model.litertlm"),
        )
    }

    @Test
    fun `recent uncensored catalog entries use public LiteRT bundles`() {
        val ids = LocalModelManager.AVAILABLE_MODELS.map { it.id }.toSet()
        assertTrue("gemma4-e4b-olekk-abliterated" in ids)
        assertTrue("huihui-gemma4-e2b-abliterated-vision" in ids)
        assertTrue("huihui-gemma4-e4b-abliterated-vision" in ids)
        assertFalse(LocalModelManager.AVAILABLE_MODELS.any { it.url.isBlank() || !it.fileName.endsWith(".litertlm") })
    }

}
