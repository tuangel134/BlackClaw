package com.blackclaw.android.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for fuzzy wake-word detection — the key to "Hey BlackClaw" working
 * despite the speech recognizer mishearing a non-dictionary word.
 */
class WakeWordMatcherTest {

    @Test
    fun exactMatchExtractsCommand() {
        val m = WakeWordMatcher.match("blackclaw manda un mensaje a mama", "blackclaw")
        assertNotNull(m)
        assertEquals("manda un mensaje a mama", m!!.command)
    }

    @Test
    fun spacedMishearingMatches() {
        // STT often hears "black claw" as two words
        val m = WakeWordMatcher.match("black claw pon una alarma", "blackclaw")
        assertNotNull(m)
        assertEquals("pon una alarma", m!!.command)
    }

    @Test
    fun commonMishearingBlackLaw() {
        val m = WakeWordMatcher.match("black law abre whatsapp", "blackclaw")
        assertNotNull(m)
        assertEquals("abre whatsapp", m!!.command)
    }

    @Test
    fun fuzzyMishearingBlaclo() {
        val m = WakeWordMatcher.match("blaclo enciende la luz", "blackclaw")
        assertNotNull(m)
        assertEquals("enciende la luz", m!!.command)
    }

    @Test
    fun spanishGarraTriggers() {
        // "garra" = Spanish for "claw" — a recognizer-friendly trigger
        val m = WakeWordMatcher.match("garra qué hora es", "blackclaw")
        assertNotNull(m)
        assertEquals("que hora es", m!!.command)
    }

    @Test
    fun wakeWordOnlyGivesEmptyCommand() {
        val m = WakeWordMatcher.match("blackclaw", "blackclaw")
        assertNotNull(m)
        assertEquals("", m!!.command)
    }

    @Test
    fun absentWakeWordReturnsNull() {
        assertNull(WakeWordMatcher.match("hola qué tal el día", "blackclaw"))
    }

    @Test
    fun commaAfterWakeWordIsStripped() {
        val m = WakeWordMatcher.match("blackclaw, llama a Juan", "blackclaw")
        assertNotNull(m)
        assertEquals("llama a juan", m!!.command)
    }

    @Test
    fun customWakeWordWorks() {
        val m = WakeWordMatcher.match("jarvis apaga la tele", "jarvis")
        assertNotNull(m)
        assertEquals("apaga la tele", m!!.command)
    }

    @Test
    fun customWakeWordFuzzy() {
        // slight mishearing of a custom word
        val m = WakeWordMatcher.match("jarbis baja el volumen", "jarvis")
        assertNotNull(m)
        assertEquals("baja el volumen", m!!.command)
    }

    @Test
    fun levenshteinBasics() {
        assertEquals(0, WakeWordMatcher.levenshtein("abc", "abc"))
        assertEquals(1, WakeWordMatcher.levenshtein("abc", "abd"))
        assertEquals(3, WakeWordMatcher.levenshtein("abc", "xyz"))
    }

    @Test
    fun unrelatedWordNotFalseTriggered() {
        // "blanco" should NOT trigger "blackclaw" (too different / different meaning)
        val m = WakeWordMatcher.match("el coche blanco es bonito", "blackclaw")
        assertNull(m)
    }
}
