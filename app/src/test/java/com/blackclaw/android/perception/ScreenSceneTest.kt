package com.blackclaw.android.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSceneTest {

    @Test
    fun `combines accessibility text and actions without duplicate labels`() {
        val scene = ScreenScene.compose(
            foregroundApp = "Foreground: Spotify (com.spotify.music)",
            accessibilityTree = """
                [n1] "Now Playing" (100,100)
                [n2] "Play" tap (200,300)
                [n3] "Play" tap (200,300)
            """.trimIndent(),
            ocrLines = emptyList(),
        )

        assertEquals("Spotify", scene.foregroundApp)
        assertEquals(listOf("Now Playing", "Play"), scene.visibleText)
        assertEquals(listOf("Play"), scene.actions)
        assertTrue(scene.describeForQuickAssist().contains("Puedes interactuar con: Play."))
    }
}
