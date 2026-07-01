package com.blackclaw.android.assistant

/**
 * Fuzzy wake-word matching.
 *
 * The Android SpeechRecognizer transcribes a non-dictionary word like
 * "BlackClaw" very inconsistently — especially in Spanish (es-ES), where it
 * tends to hear "black law", "blaclo", "black o", "blac claw", "garra negra"…
 * A naive `contains("blackclaw")` almost never fires.
 *
 * This matcher:
 *  - Normalizes the transcript (lowercase, strip accents/punctuation).
 *  - Builds a set of accepted variants for the configured wake word (built-in
 *    list for the default "blackclaw", plus the literal word and its spaced
 *    form).
 *  - Slides over the transcript token by token, accepting a hit when a token
 *    (or a 2-token window) is within a small edit distance of any variant.
 *  - Returns the COMMAND that follows the wake word, or null if not present.
 *
 * Pure and side-effect free → unit-testable without Android.
 */
object WakeWordMatcher {

    /** Known good mishearings / variants for the default "blackclaw". */
    private val DEFAULT_VARIANTS = listOf(
        "blackclaw", "black claw", "blac claw", "black law", "black lo",
        "blaclo", "blaclaw", "blakclo", "black o", "blackclo", "blak claw",
        "blackcla", "blakla", "garra", "garra negra",
    )

    /** Common mishearings for the "garra" wake word (es-ES recognizer). */
    private val GARRA_VARIANTS = listOf(
        "garra", "gara", "garrá", "agarra", "agara", "garda", "garras",
        "gará", "gadra", "graga", "garro", "carra", "garrar",
    )

    /** Max Levenshtein distance for a fuzzy token match (scales with length).
     *  Kept tight for short words to avoid false triggers on common Spanish
     *  words (e.g. "blanco" must NOT match "blaclo"). Known mishearings are
     *  handled as exact entries in DEFAULT_VARIANTS instead. */
    private fun tolerance(len: Int): Int = when {
        len <= 7 -> 1
        len <= 10 -> 2
        else -> 3
    }

    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text.lowercase()) {
            sb.append(when (c) {
                'á' -> 'a'; 'é' -> 'e'; 'í' -> 'i'; 'ó' -> 'o'; 'ú' -> 'u'; 'ü' -> 'u'; 'ñ' -> 'n'
                else -> if (c.isLetterOrDigit() || c == ' ') c else ' '
            })
        }
        return sb.toString().trim().replace(Regex(" +"), " ")
    }

    private fun variantsFor(wakeWord: String): List<String> {
        val w = normalize(wakeWord)
        val base = mutableListOf(w)
        // Spaced form: "blackclaw" → "black claw"
        if (!w.contains(' ') && w.length > 5) {
            // naive split near the middle to add a spaced variant
            base.add(w)
        }
        return when (w) {
            "blackclaw" -> (DEFAULT_VARIANTS + base).distinct()
            "garra" -> (GARRA_VARIANTS + base).distinct()
            else -> base.distinct()
        }
    }

    /**
     * If [transcript] contains the wake word, return the command that follows
     * (may be empty string if only the wake word was said). Returns null if the
     * wake word isn't present.
     */
    fun match(transcript: String, wakeWord: String): MatchResult? {
        val norm = normalize(transcript)
        if (norm.isBlank()) return null
        val tokens = norm.split(" ")
        val variants = variantsFor(wakeWord)

        // Multi-word variants (e.g. "black claw") → check sliding 2-grams first.
        val multiVariants = variants.filter { it.contains(' ') }
        val singleVariants = variants.filter { !it.contains(' ') }

        for (i in tokens.indices) {
            // 2-token window
            if (i + 1 < tokens.size) {
                val bigram = "${tokens[i]} ${tokens[i + 1]}"
                if (multiVariants.any { fuzzyEq(bigram, it) }) {
                    val command = tokens.drop(i + 2).joinToString(" ").trim().removePrefix(",").trim()
                    return MatchResult(command, bigram)
                }
            }
            // single token
            if (singleVariants.any { fuzzyEq(tokens[i], it) }) {
                val command = tokens.drop(i + 1).joinToString(" ").trim().removePrefix(",").trim()
                return MatchResult(command, tokens[i])
            }
            // glued token: recognizer merged wake word + command ("garrapon una alarma").
            val glued = gluedCommand(tokens[i], singleVariants)
            if (glued != null) {
                val rest = tokens.drop(i + 1).joinToString(" ").trim()
                val command = (glued.first + " " + rest).trim().removePrefix(",").trim()
                return MatchResult(command, glued.second)
            }
        }
        return null
    }

    /**
     * If [token] starts with a wake variant followed by real command text
     * ("garrapon" → "pon"), return the split command + matched variant.
     * Requires the remainder to be ≥2 chars so plurals like "garras" don't fire.
     */
    private fun gluedCommand(token: String, singleVariants: List<String>): Pair<String, String>? {
        for (v in singleVariants) {
            if (v.length < 4) continue
            if (token.startsWith(v) && token.length - v.length >= 2) {
                return token.substring(v.length) to v
            }
        }
        return null
    }

    private fun fuzzyEq(a: String, b: String): Boolean {
        if (a == b) return true
        // Quick length gate
        if (kotlin.math.abs(a.length - b.length) > 3) return false
        return levenshtein(a, b) <= tolerance(maxOf(a.length, b.length))
    }

    /** Classic iterative Levenshtein distance. */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    data class MatchResult(val command: String, val matchedVariant: String)
}
