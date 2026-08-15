package com.blackclaw.android.conversation

/**
 * Zero-latency replies for questions whose answer is already known by the app.
 *
 * These are deliberately limited to capability questions. Normal conversation
 * still goes to the selected model, and an actionable request still goes to the
 * task router. The goal is to avoid waking a local model or starting a network
 * round-trip just to answer "sí, puedo hacerlo".
 */
object ConversationQuickReplies {

    fun replyFor(prompt: String): String? {
        if (CapabilityQuestionDetector.isGeneralCapabilityQuestion(prompt)) {
            return GENERAL_ES
        }
        if (!CapabilityQuestionDetector.isCapabilityQuestion(prompt)) return null

        val normalized = normalize(prompt)
        val isAutomationQuestion = Regex(
            """\b(programar|tarea|tareas|automatizacion|automatizaciones|rutina|rutinas|""" +
                """schedule|task|tasks|automation|automations|routine|routines)\b"""
        ).containsMatchIn(normalized)
        val isEnglish = normalized.contains("can you") ||
            normalized.contains("could you") ||
            normalized.contains("schedule") ||
            normalized.contains("tasks")
        return when {
            isAutomationQuestion && isEnglish -> AUTOMATION_EN
            isAutomationQuestion -> AUTOMATION_ES
            else -> GENERAL_ES
        }
    }

    private const val GENERAL_ES =
        "Sí. Puedo conversar, consultar información del teléfono, analizar imágenes, " +
            "usar la terminal segura y crear o ejecutar tareas y automatizaciones. Dime qué quieres hacer."

    private const val AUTOMATION_ES =
        "Sí. Puedo crear y ejecutar tareas y automatizaciones en Android. " +
            "Dime el disparador y lo que debe hacer; por ejemplo: «cuando llegue a casa, activa el Wi‑Fi»."

    private const val AUTOMATION_EN =
        "Yes. I can create and run Android tasks and automations. Tell me the trigger " +
            "and what it should do, for example: “when I get home, turn on Wi‑Fi.”"

    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) {
            sb.append(
                when (c) {
                    'á' -> 'a'; 'é' -> 'e'; 'í' -> 'i'; 'ó' -> 'o'; 'ú' -> 'u'; 'ü' -> 'u'; 'ñ' -> 'n'
                    else -> if (c.isLetterOrDigit() || c == ' ') c else ' '
                }
            )
        }
        return sb.toString().trim().replace(Regex(" +"), " ")
    }
}
