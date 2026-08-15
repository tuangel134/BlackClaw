package com.blackclaw.android.agent

/**
 * Robust bilingual (ES/EN) task-vs-chat classifier.
 *
 * Decides whether a user message is an ACTIONABLE task (→ agent loop with tools)
 * or just conversation/question (→ chat). The previous single-regex approach
 * missed Spanish conjugations and polite/indirect requests, so action requests
 * were sent to chat and the model only *described* doing them.
 *
 * This uses layered signals (all normalized, accent-insensitive):
 *   0. Capability-question guard — broad questions stay in conversation even
 *      when they contain an action verb ("¿puedes programar tareas?").
 *   1. Action verbs — explicit imperative + infinitive forms, ES & EN.
 *   2. Indirect/polite requests — "puedes…", "podrías…", "necesito que…",
 *      "me gustaría que…", "can you…", "could you…".
 *   3. Action objects — nouns that strongly imply an action even with a weak
 *      verb ("una alarma", "recordatorio", "mensaje a…").
 *   4. App names — "whatsapp a mamá", "abre spotify".
 *   5. Device state — battery/wifi/notifications/etc.
 *
 * Pure & side-effect free → fully unit-testable.
 */
object TaskClassifier {

    fun isTask(prompt: String): Boolean {
        val raw = prompt.trim()
        if (raw.isEmpty()) return false
        val t = normalize(raw)
        val words = t.split(' ').filter { it.isNotBlank() }

        // "¿Puedes programar tareas?" is a capability question, not an
        // executable task. Check this before action verbs ("programar" is one)
        // so it does not enter the slow agent loop. Concrete requests such as
        // "¿puedes programar una tarea para mañana?" remain tasks.
        if (com.blackclaw.android.conversation.CapabilityQuestionDetector.isCapabilityQuestion(raw) ||
            com.blackclaw.android.conversation.CapabilityQuestionDetector.isGeneralCapabilityQuestion(raw) ||
            com.blackclaw.android.conversation.CapabilityQuestionDetector.isInformationalQuestion(raw) ||
            com.blackclaw.android.conversation.CapabilityQuestionDetector.isTopicStatement(raw) ||
            com.blackclaw.android.conversation.CapabilityQuestionDetector.isContentGenerationRequest(raw)
        ) {
            return false
        }

        // 1. Strong signals — any hit ⇒ task.
        if (hasActionVerb(words) || hasStopCommand(words) || hasExplicitEnglishCommand(words, t)) return true
        if (hasIndirectRequest(t)) return true
        if (hasActionObject(t)) return true
        if (hasAppName(t)) return true
        if (hasDeviceState(t)) return true

        return false
    }

    // ── normalization ──
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

    // ── 1. Action verbs (normalized, no accents). Imperatives + infinitives + a
    //       few conjugations the user commonly types/says. ──
    private val ACTION_VERBS = setOf(
        // open / launch
        "abre", "abreme", "abrir", "abra", "abran", "open", "launch", "lanza", "inicia", "iniciar", "arranca",
        // send / message
        "manda", "mandame", "mandar", "envia", "enviame", "enviar", "envie", "mensajea", "escribele",
        "send", "message", "text", "dile", "contesta", "responde", "responder", "reply", "forward", "reenvia",
        // search / web
        "busca", "buscame", "buscar", "investiga", "investigar", "googlea", "search", "find", "look",
        "averigua", "averiguar", "consulta", "consultar",
        // play / media
        "reproduce", "reproducir", "pon", "ponme", "poner", "play", "pausa", "pause", "reanuda",
        "siguiente", "anterior", "salta",
        // call
        "llama", "llamame", "llamar", "marca", "marcar", "call", "dial", "telefonea", "videollamada",
        // set / create / schedule
        "crea", "crear", "agrega", "agregar", "anade", "anadir", "programa", "programar", "agenda",
        "agendar", "configura", "configurar", "create", "add", "schedule",
        // remind / alarm
        "recuerda", "recuerdame", "recordar", "avisa", "avisame", "avisar", "despiertame", "remind",
        // notes
        "anota", "anotame", "anotar", "apunta", "apuntame", "apuntar", "guarda", "guardame", "guardar",
        "note", "save", "store", "registra", "registrar",
        // toggle / device
        "enciende", "encender", "prende", "prender", "apaga", "apagar", "activa", "activar",
        "desactiva", "desactivar", "sube", "subir", "baja", "bajar", "ajusta", "ajustar",
        "silencia", "silenciar", "vibra", "toggle", "enable", "disable", "mute",
        // navigate / tap
        "toca", "tocar", "tap", "click", "pulsa", "presiona", "desliza", "swipe", "scroll", "navega",
        "ve", "vete", "regresa", "vuelve", "cierra", "cerrar", "close", "go",
        // download / install / delete / share
        "descarga", "descargar", "instala", "instalar", "install", "download", "borra", "borrar",
        "elimina", "eliminar", "delete", "comparte", "compartir", "share",
        // copy / paste
        "copia", "copiar", "pega", "pegar", "copy", "paste",
        // translate / calc / convert
        "traduce", "traducir", "translate", "calcula", "calcular", "convierte", "convertir", "convert",
        // capture
        "captura", "capturar", "graba", "grabar", "record", "escanea", "escanear", "scan", "screenshot",
        // generic do/help (with object)
        "haz", "hazme", "hacer", "ejecuta", "ejecutar", "ponme", "quitame", "quita", "quitar",
        // check / read device data
        "checa", "chequea", "revisa", "revisar", "lee", "leeme", "leer", "muestra", "muestrame", "ensename",
        "dime", "check", "read", "show", "list", "lista", "listar",
        // stop / cancel
        "deten", "detener", "parar", "cancela", "cancelar", "stop", "cancel", "termina",
        // move / rename
        "mueve", "mover", "renombra", "renombrar", "move", "rename",
        // alarms specific
        "despierta", "suena", "temporiza",
        // ── subjunctive / 2nd-person forms (after "que", "puedes", etc.) ──
        "pones", "pongas", "pongan", "ponga",
        "reproduzcas", "reproduzca",
        "envies", "envien", "envie",
        "mandes", "manden",
        "abras", "abran",
        "busques", "busquen",
        "llames", "llamen",
        "recuerdes", "recuerden",
        "actives", "activen", "desactives", "desactiven",
        "apagues", "apaguen", "enciendas", "enciendan",
        "subas", "bajes", "ajustes",
        "agregues", "anadas", "programes", "agendes",
        "anotes", "apuntes", "grabes", "guardes",
        "traduzcas", "calcules", "conviertas",
        "descargues", "instales", "borres", "elimines",
        "compartas", "copies", "pegues", "silencies",
        "marques", "toques", "cierres", "muestres", "leas", "revises",
    )

    private fun hasActionVerb(words: List<String>): Boolean {
        // Match as whole words (any position) — covers "pon una alarma",
        // "quiero abrir x" (abrir is a verb), etc.
        return words.any { it in ACTION_VERBS }
    }

    /** "para" is also one of the most common Spanish prepositions. */
    private fun hasStopCommand(words: List<String>): Boolean {
        if (words.firstOrNull() != "para") return false
        val next = words.getOrNull(1)
        return next == null || next in setOf(
            "la", "el", "las", "los", "esto", "eso", "todo", "ahora", "ya",
            "musica", "video", "cancion", "proceso", "descarga", "tarea",
        )
    }

    /** English words such as set/make/run/do are too common to be global signals. */
    private fun hasExplicitEnglishCommand(words: List<String>, text: String): Boolean {
        return when (words.firstOrNull()) {
            "do" -> words.getOrNull(1) !in setOf(null, "you", "i", "we", "they")
            "run" -> words.size > 1 && words.getOrNull(1) !in setOf("out", "late", "away")
            "set" -> hasActionObject(text) || hasDeviceState(text)
            "make" -> hasActionObject(text) || hasDeviceState(text) ||
                words.drop(1).any { it in setOf("call", "appointment", "task", "automation") }
            else -> false
        }
    }

    // ── 2. Indirect / polite request patterns ──
    private val INDIRECT_PATTERNS = listOf(
        Regex("""\b(puedes|podrias|puede|podria|me puedes|me podrias)\s+\w+"""),
        Regex("""\b(necesito|quiero|quisiera|me gustaria|deseo)\s+(que\s+)?\w+"""),
        Regex("""\b(ayudame|ayudar|ayuda)\s+(a\s+)?\w+"""),
        Regex("""\bhazme el favor de\b"""),
        Regex("""\bpor favor\b"""),
        Regex("""\b(porfa|porfis|porfavor)\b"""),
        Regex("""\b(tienes|hay)\s+que\b"""),
        // English
        Regex("""\b(can|could|would|will)\s+you\s+\w+"""),
        Regex("""\b(i\s+(need|want|would like))\s+(you\s+to\s+)?\w+"""),
        Regex("""\bplease\b"""),
        Regex("""\bhelp me\s+\w+"""),
    )

    private fun hasIndirectRequest(t: String): Boolean {
        // A polite/indirect frame alone is weak; require it to co-occur with a
        // verb-ish or action object so "¿puedes creer?" / "please tell me a joke"
        // don't misfire. But "puedes poner una alarma" / "necesito que abras x"
        // should fire. We approximate: indirect frame + (action object OR a
        // second meaningful verb-like token).
        val frame = INDIRECT_PATTERNS.any { it.containsMatchIn(t) }
        if (!frame) return false
        // If there's any action object or app or device noun, it's a task.
        if (hasActionObject(t) || hasAppName(t) || hasDeviceState(t)) return true
        // Or if an action verb appears anywhere (e.g. "puedes ponerme...").
        val words = t.split(' ')
        if (words.any { it in ACTION_VERBS }) return true
        // Infinitive after the frame: "...que abrir/poner/enviar..." (-ar/-er/-ir)
        return Regex("""\b\w{3,}(ar|er|ir)\b""").containsMatchIn(t) &&
            // avoid pure chat infinitives like "saber", "ser", "creer", "pensar"
            !Regex("""\b(saber|ser|creer|pensar|opinar|hablar|charlar|platicar|conversar)\b""").containsMatchIn(t)
    }

    // ── 3. Action objects — strongly imply a task ──
    private val ACTION_OBJECTS = listOf(
        Regex("""\b(una?\s+)?alarmas?\b"""),
        Regex("""\b(un\s+)?recordatorios?\b"""),
        Regex("""\b(un\s+)?temporizador\b"""),
        Regex("""\b(un\s+)?cronometro\b"""),
        Regex("""\b(una?\s+)?notas?\b"""),
        Regex("""\blista de (compras|la compra|super)\b"""),
        Regex("""\b(un\s+)?mensajes?\b"""),
        Regex("""\b(un\s+)?evento\b"""),
        Regex("""\b(una?\s+)?reunion\b"""),
        Regex("""\b(una?\s+)?cita\b"""),
        Regex("""\b(un\s+)?gasto\b"""),
        Regex("""\b(una?\s+)?rutina\b"""),
        Regex("""\b(una?\s+)?tareas?\b"""),
        Regex("""\b(una?\s+)?automatizaciones?\b"""),
        // English
        Regex("""\ban?\s+(alarm|reminder|timer|note|event|meeting|message|task|automation|routine)\b"""),
    )

    private fun hasActionObject(t: String): Boolean = ACTION_OBJECTS.any { it.containsMatchIn(t) }

    // ── 4. App names ──
    private val APP_NAMES = setOf(
        "whatsapp", "telegram", "instagram", "facebook", "messenger", "youtube", "spotify",
        "tiktok", "twitter", "gmail", "maps", "waze", "uber", "netflix", "chrome", "discord",
        "snapchat", "reddit", "twitch", "amazon", "mercadolibre", "shazam",
    )

    private fun hasAppName(t: String): Boolean {
        val words = t.split(' ')
        return words.any { it in APP_NAMES }
    }

    // ── 5. Device state nouns ──
    // Notifications are context-sensitive: mentioning the word is not enough to
    // read private device data. Other device-state nouns keep their legacy routing.
    private val NOTIFICATION_STATE =
        Regex("""\b(notificacion|notificaciones|notification|notifications|notif|notifs)\b""")

    private val DEVICE_STATE = Regex(
        """\b(bateria|pila|wifi|bluetooth|datos|almacenamiento|memoria|ram|brillo|volumen|""" +
        """linterna|portapapeles|pantalla|camara|microfono|ubicacion|gps|hotspot|nfc|avion|""" +
        """battery|wifi|bluetooth|storage|brightness|volume|flashlight|clipboard|screen|""" +
        """camera|microphone|location|airplane)\b"""
    )

    private fun hasDeviceState(t: String): Boolean {
        if (NOTIFICATION_STATE.containsMatchIn(t)) {
            return DirectDeviceDataGuard.matchesNotificationDataRequest(t)
        }
        return DEVICE_STATE.containsMatchIn(t)
    }
}
