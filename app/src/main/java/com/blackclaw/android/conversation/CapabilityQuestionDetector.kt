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

    /** Returns true when the prompt asks about ability instead of issuing an action. */
    fun isCapabilityQuestion(prompt: String): Boolean {
        val t = normalize(prompt)
        if (t.isEmpty()) return false

        // "¿Sabes abrir WhatsApp?", "¿eres capaz de apagar la linterna?" and
        // "¿puedo programar tareas?" ask about ability; they never contain an
        // instruction for BlackClaw to execute, even when a concrete object is
        // mentioned.
        if (KNOWLEDGE_CAPABILITY_FRAME.containsMatchIn(t) ||
            USER_ABILITY_FRAME.containsMatchIn(t)
        ) {
            return true
        }
        if (!CAPABILITY_FRAME.containsMatchIn(t)) return false

        // A confirmation tail changes the speech act for every verb. For
        // example, "¿puedes abrir WhatsApp, cierto?" asks whether the
        // assistant has that ability; it does not ask it to open the app now.
        // Keep this semantic rule independent from the action vocabulary so a
        // new verb cannot accidentally reintroduce the slow task loop.
        if (CAPABILITY_CONFIRMATION_TAIL.containsMatchIn(t)) return true

        if (hasConcreteRequestDetails(t)) return false

        // These are intentionally narrow. "¿Puedes poner una alarma?" is still
        // a valid request (and has an existing regression test), while a broad
        // question about scheduling/automation has nothing to execute yet.
        if (BROAD_TASK_CAPABILITY.containsMatchIn(t)) return true

        // Other capability checks (for example "¿puedes usar la terminal?")
        // often have an infinitive that the task classifier would otherwise
        // treat as an order. Keep concrete phone targets actionable.
        return isGenericCapabilityWithoutPayload(t)
    }

    /**
     * Questions that ask for an explanation or information, rather than an
     * action. Explicit device-data reads are excluded so "¿qué batería tengo?"
     * and "¿cómo está el Wi‑Fi?" still reach their deterministic tools.
     */
    fun isInformationalQuestion(prompt: String): Boolean {
        val t = normalize(prompt)
        if (t.isEmpty()) return false
        if (isDirectDeviceRead(t)) return false
        if (EXPLANATORY_FRAME.containsMatchIn(t) ||
            USER_ABILITY_FRAME.containsMatchIn(t) ||
            KNOWLEDGE_CAPABILITY_FRAME.containsMatchIn(t) ||
            META_QUESTION_FRAME.containsMatchIn(t) ||
            (NEGATED_FRAME.containsMatchIn(t) && !isCorrection(prompt)) ||
            INTERROGATIVE_START.containsMatchIn(t) ||
            ENGLISH_META_QUESTION.containsMatchIn(t)
        ) return true
        if (prompt.contains('?') && !ACTIONABLE_QUESTION_LEAD.containsMatchIn(t)) return true
        return EXPLANATORY_PHRASE.any { it.containsMatchIn(t) }
    }

    /** Topic statements should not be routed as actions just because they name a verb/object. */
    fun isTopicStatement(prompt: String): Boolean {
        val t = normalize(prompt)
        if (t.isEmpty() || isDirectDeviceRead(t)) return false
        return TOPIC_FRAME.any { it.containsMatchIn(t) } ||
            (t.startsWith("dime ") && !isDirectDeviceRead(t))
    }

    /** Text-generation requests belong to chat, even when phrased with "haz" or "crea". */
    fun isContentGenerationRequest(prompt: String): Boolean {
        val t = normalize(prompt)
        return CONTENT_REQUEST.containsMatchIn(t)
    }

    /** General questions such as "¿qué puedes hacer?" can be answered locally. */
    fun isGeneralCapabilityQuestion(prompt: String): Boolean {
        val t = normalize(prompt)
        return t == "que puedes hacer" ||
            t.startsWith("que puedes hacer ") ||
            t == "que sabes hacer" ||
            t == "what can you do" ||
            t.startsWith("what can you do ") ||
            t == "what do you do" ||
            t == "para que sirves" ||
            Regex("""^(que|what)\s+(puede|can)\s+(hacer|do)(\s+(blackclaw|el asistente|you))?$""").matches(t)
    }

    private val CAPABILITY_FRAME = Regex(
        """^(?:(?:me|tu|blackclaw|el asistente)\s+)*(puedes|podrias|puede|podria|sabes|eres capaz de|can you|could you)\b"""
    )

    private val KNOWLEDGE_CAPABILITY_FRAME = Regex(
        """^(?:(?:me|tu|blackclaw|el asistente)\s+)*(sabes|sabe|eres capaz de|es capaz de|do you know|are you able to)\b"""
    )

    private val USER_ABILITY_FRAME = Regex(
        """^(?:(?:yo)\s+)?puedo\b|""" +
            """^(?:tu)\s+(?:puedes|podrias)\b|""" +
            """^(?:(?:el asistente|blackclaw)\s+)(?:puede|podria|sabe)\b|""" +
            """^(se puede|es posible|tengo capacidad|tienes capacidad|tiene capacidad|can i|could i|is it possible)\b"""
    )

    private val BROAD_TASK_CAPABILITY = Regex(
        """\b(programa(?:r)?|crea(?:r)?|hace(?:r)?|automatiza(?:r)?|ejecuta(?:r)?|agenda(?:r)?|schedule|create|make|automate|run)\s+""" +
            """(?:una?\s+|las?\s+|mis\s+|varias\s+)?""" +
            """(tareas?|automatizaciones?|rutinas?|tasks?|automations?|routines?)\b"""
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

    private val CAPABILITY_ONLY_OBJECT = Regex(
        """\b(terminal|telefono|android|imagen|imagenes|image|images|""" +
            """tareas?|automatizaciones?|rutinas?|tasks?|automations?|routines?|capacidad|capacidades|esto|eso|algo|todo)\b"""
    )

    private val CAPABILITY_CONFIRMATION_TAIL = Regex(
        """\b(cierto|verdad|correcto|no|right|correct|yes or no)$"""
    )

    private val EXPLANATORY_FRAME = Regex(
        """^(?:(?:oye|hey|blackclaw|el asistente)\s+)*(?:(?:me\s+)?(?:puedes|podrias|puede|podria|can you|could you)\s+)?""" +
            """(?:decirme|explicarme|mostrarme|ensenarme|ensename|contarme|decir|explicar|mostrar|""" +
            """ensenar|contar)\b.*\b(como|que|si|why|how|what|whether)\b"""
    )

    private val EXPLANATORY_PHRASE = listOf(
        Regex("""\b(dime|decirme|explica|explicame|explicarme|ensena|ensename|muestra|muestrame|mostrarme|cuentame|contarme)\s+(como|que|si|a)\b"""),
        Regex("""\b(quiero|quisiera|me gustaria|necesito)\s+(saber|aprender|entender|conocer)\b"""),
        Regex("""\b(puedes|podrias|puede|podria|can you|could you)\s+ayudarme\s+(a|con)\s+(entender|aprender|saber|conocer|explicar)\b"""),
        Regex("""^(como|how)\s+(puedo|puede|se|hacer|do i|can i)\b"""),
    )

    private val INTERROGATIVE_START = Regex(
        """^(?:(?:oye|hey|blackclaw|el asistente|entonces|y|por favor)\s+)*""" +
            """(que|cual|cuales|quien|quienes|como|por que|para que|donde|cuando|cuanto|cuanta|""" +
            """cuantos|cuantas|how|what|which|who|why|where|when)\b"""
    )

    private val ENGLISH_META_QUESTION = Regex("""^(do|does|did)\s+you\b""")

    private val ACTIONABLE_QUESTION_LEAD = Regex(
        """^(?:(?:oye|hey|por favor|please|blackclaw|el asistente)\s+)*(?:""" +
            """(?:me\s+)?(?:puedes|podrias|puede|podria|can you|could you|me ayudas?|""" +
            """abre\w*|abrir|open|lanza\w*|inicia\w*|arranca\w*|""" +
            """apaga\w*|apagar|enciende\w*|encender|activa\w*|activar|""" +
            """pon\w*|crea\w*|agrega\w*|anad\w*|programa\w*|agenda\w*|""" +
            """configura\w*|manda\w*|envi\w*|reenvia\w*|llama\w*|marca\w*|""" +
            """busca\w*|googlea\w*|investiga\w*|consulta\w*|averigua\w*|""" +
            """recu\w*|avisa\w*|suena\w*|""" +
            """muestr\w*|lee\w*|revisa\w*|descarga\w*|instala\w*|desinstala\w*|""" +
            """borra\w*|elimina\w*|comparte\w*|copia\w*|pega\w*|""" +
            """anota\w*|apunta\w*|guarda\w*|registra\w*|lista\w*|note|save|store|list|""" +
            """traduce\w*|calcula\w*|convierte\w*|captura\w*|graba\w*|""" +
            """escanea\w*|ejecuta\w*|haz\w*|silencia\w*|vibra\w*|quita\w*|""" +
            """sube\w*|baja\w*|ajusta\w*|desactiva\w*|""" +
            """despierta\w*|temporiza\w*|reproduce\w*|reanuda\w*|pausa\w*|""" +
            """toca\w*|pulsa\w*|presiona\w*|desliza\w*|navega\w*|cierra\w*|""" +
            """deten\w*|cancela\w*|termina\w*|""" +
            """mueve\w*|renombra\w*|pausa\w*|reanuda\w*|siguiente|anterior|salta\w*|para|""" +
            """stop|cancel|check|read|show|set|create|add|schedule|make|send|message|text|""" +
            """play|pause|remind|call|dial|launch|search|find|look|reply|forward|run|do|close|go|delete|install|""" +
            """download|share|copy|paste|translate|calculate|convert|screenshot|record|""" +
            """scan|mute|enable|disable|toggle|swipe|scroll|tap|click|back|home)\b)"""
    )

    private val META_QUESTION_FRAME = Regex(
        """^(hay que|tienes que|tiene que|quieres que|te gustaria que|no sabes|no se|""" +
            """no entiendo|no comprendo|me pregunto)\b"""
    )

    private val NEGATED_FRAME = Regex(
        """^(?:(?:por favor|please)\s+)?(no|nunca|jamas|dont|do not|never)\b"""
    )

    private val TOPIC_FRAME = listOf(
        Regex("""^(hablemos|habla)\s+(de|sobre)\b"""),
        Regex("""^(me\s+)?(gusta|interesa|interesan)\b"""),
        Regex("""^(quiero|quisiera|me gustaria)\s+(saber|aprender|entender|conocer|hablar)\b"""),
        Regex("""^(estoy|estamos)\s+(aprendiendo|estudiando)\b"""),
        Regex("""^(explicame|ensename|muestrame|mostrarme)\b"""),
        Regex("""^(?:se\s+llama|(?:mi|mis|el|la|los|las|este|esta|ese|esa)\s+\w+(?:\s+\w+){0,3}\s+se\s+llama)\b"""),
        Regex("""^tengo\s+tiempo\s+para\b"""),
    )

    private val CONTENT_REQUEST = Regex(
        """^(?:(?:me\s+)?(?:puedes|podrias|can you|could you)\s+)?""" +
            """(?:hazme|haz|hacer|crea|escribe|dame|make|write|tell)\s+(?:me\s+)?(?:un\s+|una\s+|a\s+)?""" +
            """(chiste|resumen|explicacion|ejemplo|poema|historia|cuento|guion|idea|""" +
            """joke|summary|explanation|example|poem|story|script)\b"""
    )

    private val DEVICE_DATA_NOUN = Regex(
        """\b(bateria|pila|battery|wifi|bluetooth|datos|data|almacenamiento|storage|memoria|ram|""" +
            """brillo|brightness|volumen|volume|portapapeles|clipboard|pantalla|screen|""" +
            """notificacion|notificaciones|notification|notifications|notif|notifs|ubicacion|""" +
            """location|gps|hotspot|nfc|avion|airplane)\b"""
    )

    private val DIRECT_DEVICE_READ_MARKER = Regex(
        """\b(que hay|que tengo|cuales son|cuant[oa]s?|como esta|como anda|estado|status|nivel|""" +
            """conectado|hay wifi|que red|a que red|dime|muestra|muestrame|lee|revisa|ensename|""" +
            """show|read|check|my|current|level|status|do i have|how many|""" +
            """what notifications|which notifications|on my)\b"""
    )

    private val HOW_TO_FRAME = Regex(
        """^(como|how)\s+(puedo|puede|se|hacer|do i|can i)\b"""
    )

    private fun isDirectDeviceRead(t: String): Boolean {
        if (!DEVICE_DATA_NOUN.containsMatchIn(t)) return false
        // "¿Cómo puedo leer mis notificaciones?" is a how-to question, not a
        // request to read them now.
        if (HOW_TO_FRAME.containsMatchIn(t)) return false
        if (Regex("""\b(como|how)\b""").containsMatchIn(t) &&
            !Regex("""\b(como esta|como anda|how is|what is|what's)\b""").containsMatchIn(t)
        ) return false
        return DIRECT_DEVICE_READ_MARKER.containsMatchIn(t) ||
            Regex("""\b(que|what|which)\b.{0,48}\b(tengo|tiene|have|level|status)\b""").containsMatchIn(t)
    }

    private fun isGenericCapabilityWithoutPayload(t: String): Boolean {
        if (!GENERIC_CAPABILITY.containsMatchIn(t) || CONCRETE_ACTION_TARGET.containsMatchIn(t)) {
            return false
        }
        if (CAPABILITY_ONLY_OBJECT.containsMatchIn(t)) return true
        return Regex(
            """\b(usar|utilizar|controlar|acceder|manejar|hacer|crear|programar|automatizar|""" +
                """ejecutar|analizar|ayudar|use|utilize|control|access|handle|do|create|schedule|""" +
                """automate|run|analyze)\s*$"""
        ).containsMatchIn(t)
    }

    private fun isCorrection(prompt: String): Boolean {
        return Regex("""(?i)^\s*no\s*[,;:]""").containsMatchIn(prompt)
    }

    private fun hasConcreteRequestDetails(t: String): Boolean {
        // A time, trigger, destination, or payload makes the question actionable:
        // "¿puedes programar una tarea para mañana a las 9?".
        return listOf(
            "manana", "hoy", "esta noche", "a las ", "en ", "cada ",
            "cuando ", "al ", "para ", "con ", "que diga ", "que haga ",
            "tomorrow", "today", "at ", "in ", "every ", "when ", "for ",
        ).any { marker -> t.contains(marker) } ||
            Regex("""\b(a|to)\s+\w+""").containsMatchIn(t)
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
