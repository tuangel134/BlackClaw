package com.blackclaw.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TaskParserTest {

    @Test
    fun `opens floating autoclicker without treating it as an app name`() {
        val parsed = TaskParser.parse("abre el autoclicker")
        assertEquals("game_autoclicker", parsed?.toolName)
        assertEquals("open", parsed?.toolParams?.get("operation"))
    }

    @Test
    fun `compound game phrase directly starts named looping macro`() {
        val parsed = TaskParser.parse("abre Clash of Clans y inicia el autoclicker farmeo en bucle")
        assertEquals("game_macro", parsed?.toolName)
        assertEquals("play", parsed?.toolParams?.get("operation"))
        assertEquals("farmeo", parsed?.toolParams?.get("name"))
        assertEquals(true, parsed?.toolParams?.get("loop"))
    }

    @Test
    fun `controls active autoclicker locally`() {
        assertEquals("pause", TaskParser.parse("pausa el autoclicker")?.toolParams?.get("operation"))
        assertEquals("resume", TaskParser.parse("reanuda el autoclicker")?.toolParams?.get("operation"))
        assertEquals("restart", TaskParser.parse("reinicia el autoclicker")?.toolParams?.get("operation"))
        assertEquals("stop", TaskParser.parse("deten el autoclicker")?.toolParams?.get("operation"))
    }

    @Test
    fun `send message command routes to direct send message tool`() {
        val parsed = TaskParser.parse("send hi to Girlfriend on WhatsApp")

        assertNotNull(parsed)
        assertEquals("send_message", parsed!!.action)
        assertEquals("send_message", parsed.toolName)
        assertEquals("hi", parsed.toolParams!!["message"])
        assertEquals("Girlfriend", parsed.toolParams!!["contact"])
        assertEquals("WhatsApp", parsed.toolParams!!["app"])
    }

    @Test
    fun `send contextual message still falls through to agent`() {
        assertNull(TaskParser.parse("send that to Girlfriend on WhatsApp"))
    }

    @Test
    fun `email commands do not route to messaging app tool`() {
        assertNull(TaskParser.parse("send email to user@example.com"))
    }

    // ── Android Auto fast-path: the car tiles prefix the spoken text and rely on
    // these deterministic (0-LLM) routes. ──────────────────────────────────────

    @Test
    fun `car music tile command routes to play_music`() {
        // "Música" tile => "pon " + spoken text.
        val parsed = TaskParser.parse("pon bad bunny")

        assertNotNull(parsed)
        assertEquals("play_music", parsed!!.action)
        assertEquals("play_music", parsed.toolName)
        assertEquals("bad bunny", parsed.toolParams!!["query"])
    }

    @Test
    fun `generic play music has empty query so it resumes instead of searching`() {
        // "reproduce música" / "pon música" must NOT search literally for "música".
        for (cmd in listOf("reproduce música", "pon música", "pon musica", "reproduce musica")) {
            val parsed = TaskParser.parse(cmd)
            assertNotNull("expected fast-path for '$cmd'", parsed)
            assertEquals("play_music", parsed!!.toolName)
            assertEquals("empty query for '$cmd'", "", parsed.toolParams!!["query"])
        }
    }

    @Test
    fun `car navigate tile command routes to open_app_action maps`() {
        // "Navegar" tile => "navégame a " + spoken text.
        val parsed = TaskParser.parse("navégame a walmart")

        assertNotNull(parsed)
        assertEquals("navigate", parsed!!.action)
        assertEquals("open_app_action", parsed.toolName)
        assertEquals("maps", parsed.toolParams!!["app"])
        assertEquals("walmart", parsed.toolParams!!["query"])
    }

    @Test
    fun `nearest place navigation keeps the cercano hint for distance-sorted search`() {
        val parsed = TaskParser.parse("llévame al walmart más cercano")

        assertNotNull(parsed)
        assertEquals("open_app_action", parsed!!.toolName)
        assertEquals("maps", parsed.toolParams!!["app"])
        // The parser is accent-insensitive ("más" → "mas"); the "cercano" hint
        // still survives so OpenAppActionTool opens the distance-sorted Maps
        // search list (its regex matches "cercan") instead of auto-routing.
        assertEquals("walmart mas cercano", parsed.toolParams!!["query"])
    }
}
