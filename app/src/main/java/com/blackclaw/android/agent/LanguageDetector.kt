package com.blackclaw.android.agent

/**
 * Simple language detector based on character/word patterns.
 * Used to ensure the agent responds in the same language the user writes in.
 *
 * Not a full NLP solution — just enough to tell Spanish from English from
 * other Latin-script languages based on common word frequencies.
 */
object LanguageDetector {

    enum class Language(val code: String, val instruction: String) {
        SPANISH("es", "Responde SIEMPRE en español."),
        ENGLISH("en", "Always respond in English."),
        UNKNOWN("", ""),
    }

    private val SPANISH_MARKERS = setOf(
        "abre", "abre", "manda", "envia", "envía", "busca", "pon", "dime",
        "quiero", "necesito", "puedes", "haz", "hazme", "muestra", "cierra",
        "llama", "escribe", "mañana", "hoy", "alarma", "recordatorio",
        "cómo", "qué", "cuál", "dónde", "cuándo", "por qué", "cuánto",
        "el", "la", "los", "las", "un", "una", "del", "al", "es", "está",
        "tiene", "para", "con", "por", "que", "como", "pero", "más",
        "también", "ahora", "después", "antes", "aquí", "todo", "nada",
        "bien", "mal", "sí", "no", "yo", "tú", "él", "ella", "nosotros",
    )

    private val ENGLISH_MARKERS = setOf(
        "open", "send", "search", "set", "tell", "show", "close",
        "call", "write", "tomorrow", "today", "alarm", "reminder",
        "how", "what", "which", "where", "when", "why", "how much",
        "the", "a", "an", "is", "are", "was", "were", "have", "has",
        "for", "with", "that", "this", "from", "but", "not", "can",
        "will", "would", "could", "should", "also", "now", "then",
        "here", "there", "all", "nothing", "good", "bad", "yes", "no",
    )

    /**
     * Detect the most likely language of a user prompt.
     * Returns UNKNOWN if confidence is too low.
     */
    fun detect(text: String): Language {
        val words = text.lowercase().split(Regex("[\\s,.!?;:]+")).filter { it.length > 1 }
        if (words.size < 2) return Language.UNKNOWN

        var esScore = 0
        var enScore = 0
        for (word in words) {
            if (word in SPANISH_MARKERS) esScore++
            if (word in ENGLISH_MARKERS) enScore++
        }

        // Spanish accent characters are a strong signal
        val accents = text.count { it in "áéíóúñ¿¡" }
        esScore += accents * 2

        return when {
            esScore > enScore && esScore >= 2 -> Language.SPANISH
            enScore > esScore && enScore >= 2 -> Language.ENGLISH
            accents > 0 -> Language.SPANISH  // Accents alone → Spanish
            else -> Language.UNKNOWN
        }
    }

    /**
     * Get a language instruction to inject into the system prompt.
     * Returns empty string if language is unclear (let the model decide).
     */
    fun getLanguageInstruction(text: String): String {
        val lang = detect(text)
        return if (lang != Language.UNKNOWN) "\n\n## Language\n${lang.instruction}\n" else ""
    }
}
