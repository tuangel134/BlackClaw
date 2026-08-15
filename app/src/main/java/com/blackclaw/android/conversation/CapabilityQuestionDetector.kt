package com.blackclaw.android.conversation

/**
 * Detects broad capability questions before they reach the device-action router.
 *
 * A phrase such as "¿puedes programar tareas?" mentions an action verb, but it
 * is not an instruction: there is no task, schedule, target, or payload to
 * execute. Keeping this distinction in one small, pure detector prevents the
 * task classifier and the fast conversational replies from drifting apart.
 */
object CapabilityQuestionDetector {

    /** Returns true only for a capability check without concrete task details. */
    fun isCapabilityQuestion(prompt: String): Boolean {
        val t = normalize(prompt)
        if (t.isEmpty() || !CAPABILITY_FRAME.containsMatchIn(t)) return false
        if (hasConcreteRequestDetails(t)) return false

        // These are intentionally narrow. "¿Puedes poner una alarma?" is still
        // a valid request (and has an existing regression test), while a broad
        // question about scheduling/automation has nothing to execute yet.
        if (BROAD_TASK_CAPABILITY.containsMatchIn(t)) return true

        // Other capability checks (for example "¿puedes usar la terminal?")
        // often have an infinitive that the task classifier would otherwise
        // treat as an order. Keep concrete phone targets actionable.
        return GENERIC_CAPABILITY.containsMatchIn(t) &&
            !CONCRETE_ACTION_TARGET.containsMatchIn(t)
    }

    /** General questions such as "¿qué puedes hacer?" can be answered locally. */
    fun isGeneralCapabilityQuestion(prompt: String): Boolean {
        val t = normalize(prompt)
        return t == "que puedes hacer" ||
            t == "que sabes hacer" ||
            t == "what can you do" ||
            t == "what do you do" ||
            t == "para que sirves"
    }

    private val CAPABILITY_FRAME = Regex(
        """^(me\s+)?(puedes|podrias|puede|podria|sabes|eres capaz de|can you|could you)\b"""
    )

    private val BROAD_TASK_CAPABILITY = Regex(
        """\b(programa(?:r)?|crea(?:r)?|hace(?:r)?|automatiza(?:r)?|ejecuta(?:r)?|agenda(?:r)?|schedule|create|make|automate|run)\s+""" +
            """(?:una?\s+|las?\s+|mis\s+|varias\s+)?""" +
            """(tareas?|automatizaciones?|rutinas?)\b"""
    )

    private val GENERIC_CAPABILITY = Regex(
        """\b(usar|utilizar|controlar|acceder|manejar|hacer|crear|programar|automatizar|""" +
            """ejecutar|analizar|ayudar|use|utilize|control|access|handle|do|create|schedule|""" +
            """automate|run|analyze)\b"""
    )

    private val CONCRETE_ACTION_TARGET = Regex(
        """\b(alarma|alarmas|alarm|recordatorio|recordatorios|reminder|temporizador|timer|""" +
            """cronometro|nota|notas|note|mensaje|mensajes|message|evento|event|reunion|""" +
            """meeting|cita|gasto|rutina|whatsapp|telegram|instagram|facebook|messenger|""" +
            """youtube|spotify|tiktok|gmail|maps|waze|uber|netflix|chrome|discord|""" +
            """bateria|pila|battery|wifi|bluetooth|datos|almacenamiento|memoria|ram|brillo|""" +
            """volumen|linterna|portapapeles|pantalla|camara|microfono|ubicacion|gps|""" +
            """hotspot|nfc|notificacion|notificaciones|notification|captura|screenshot)\b"""
    )

    private fun hasConcreteRequestDetails(t: String): Boolean {
        // A time, trigger, destination, or payload makes the question actionable:
        // "¿puedes programar una tarea para mañana a las 9?".
        return listOf(
            "manana", "hoy", "esta noche", "a las ", "en ", "cada ",
            "cuando ", "al ", "para ", "con ", "que diga ", "que haga ",
            "tomorrow", "today", "at ", "in ", "every ", "when ", "for ",
        ).any { marker -> t.contains(marker) }
    }

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
