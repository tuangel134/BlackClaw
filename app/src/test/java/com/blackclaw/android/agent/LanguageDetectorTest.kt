package com.blackclaw.android.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the pure language detection logic.
 */
class LanguageDetectorTest {

    @Test
    fun detectsSpanishFromCommonWords() {
        assertEquals(LanguageDetector.Language.SPANISH,
            LanguageDetector.detect("abre whatsapp y manda un mensaje"))
    }

    @Test
    fun detectsSpanishFromAccents() {
        assertEquals(LanguageDetector.Language.SPANISH,
            LanguageDetector.detect("pon la alarma para mañana"))
    }

    @Test
    fun detectsEnglishFromCommonWords() {
        assertEquals(LanguageDetector.Language.ENGLISH,
            LanguageDetector.detect("open whatsapp and send a message"))
    }

    @Test
    fun detectsEnglishQuestion() {
        assertEquals(LanguageDetector.Language.ENGLISH,
            LanguageDetector.detect("what is the weather today"))
    }

    @Test
    fun shortInputIsUnknown() {
        assertEquals(LanguageDetector.Language.UNKNOWN,
            LanguageDetector.detect("ok"))
    }

    @Test
    fun accentCharsForceSpanish() {
        // Even with few words, accents are a strong Spanish signal
        val result = LanguageDetector.detect("qué día es")
        assertEquals(LanguageDetector.Language.SPANISH, result)
    }

    @Test
    fun spanishInstructionContainsRespondeEnEspanol() {
        val instruction = LanguageDetector.getLanguageInstruction("abre la cámara y toma una foto")
        assert(instruction.contains("español"))
    }

    @Test
    fun englishInstructionContainsRespondInEnglish() {
        val instruction = LanguageDetector.getLanguageInstruction("open the camera and take a photo")
        assert(instruction.contains("English"))
    }

    @Test
    fun unknownLanguageGivesEmptyInstruction() {
        assertEquals("", LanguageDetector.getLanguageInstruction("ok"))
    }

    @Test
    fun spanishCommandVerbs() {
        // Common phone task verbs in Spanish
        listOf(
            "busca videos de gatos",
            "envía un mensaje a mamá",
            "llama a Juan",
            "pon una alarma",
        ).forEach {
            assertEquals("Failed for: $it", LanguageDetector.Language.SPANISH,
                LanguageDetector.detect(it))
        }
    }
}
