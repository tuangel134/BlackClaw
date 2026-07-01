package com.blackclaw.android.assistant

import kotlin.random.Random

/**
 * Wake-up challenges for "important" alarms that can't be dismissed by a single
 * tap — the user must actually engage their brain so they truly wake up.
 *
 * Kept deliberately simple (no nightmare puzzles): a small arithmetic problem,
 * a short memory sequence to repeat, or a phrase to type. Enough friction to
 * wake up, not enough to enrage.
 */
sealed class AlarmChallenge {

    /** Prompt shown to the user during the answer phase. */
    abstract val prompt: String

    /** Returns true if [answer] solves the challenge. */
    abstract fun check(answer: String): Boolean

    /**
     * Optional content the user must memorise. When non-null the UI shows
     * [memorizeText] for [memorizeSeconds] seconds, then HIDES it and asks the
     * user to reproduce it from memory. Null for challenges with nothing to
     * hide (math/type, where the prompt stays visible).
     */
    open val memorizeText: String? = null
    open val memorizeSeconds: Int = 0

    /** Math: solve a × b + c style problem. */
    data class Math(val a: Int, val b: Int, val c: Int) : AlarmChallenge() {
        private val solution = a * b + c
        override val prompt = "¿Cuánto es $a × $b + $c?"
        override fun check(answer: String) = answer.trim().toIntOrNull() == solution
    }

    /**
     * Memory: a digit sequence is shown briefly, then hidden. The user must
     * type it back from memory — a genuine recall test, not a copy task.
     */
    data class Memory(val digits: String) : AlarmChallenge() {
        override val prompt = "¿Qué dígitos viste? Escríbelos de memoria"
        override val memorizeText = digits
        override val memorizeSeconds = 4
        override fun check(answer: String) = answer.trim().filter { it.isDigit() } == digits
    }

    /** Type: copy a short phrase exactly (case-insensitive, trimmed). */
    data class Type(val phrase: String) : AlarmChallenge() {
        override val prompt = "Escribe exactamente: \"$phrase\""
        override fun check(answer: String) =
            answer.trim().equals(phrase.trim(), ignoreCase = true)
    }

    companion object {
        private val PHRASES = listOf(
            "ya estoy despierto", "buenos dias mundo", "hoy sera un buen dia",
            "arriba y a brillar", "vamos con todo hoy",
        )

        /** Build a challenge of the given kind (math|memory|type|random). */
        fun create(kind: String): AlarmChallenge = when (kind.lowercase()) {
            "math" -> Math(Random.nextInt(3, 13), Random.nextInt(3, 13), Random.nextInt(1, 20))
            "memory" -> Memory((1..5).map { Random.nextInt(0, 10) }.joinToString(""))
            "type" -> Type(PHRASES.random())
            else -> when (Random.nextInt(3)) {
                0 -> create("math")
                1 -> create("memory")
                else -> create("type")
            }
        }
    }
}
