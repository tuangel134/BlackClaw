package com.blackclaw.android.assistant

import java.util.Calendar

/**
 * JARVIS-style spoken personality for the voice assistant.
 *
 * Generates VARIED acknowledgements so BlackClaw never sounds canned. Lines are
 * pooled by intent (wake ack vs command ack) and flavoured by time of day, with
 * the occasional dry-wit line — channeling the JARVIS-from-Iron-Man tone:
 * composed, courteous, quietly witty, always loyal ("jefe").
 *
 * Avoids repeating the immediately-previous line so it feels fresh.
 */
object JarvisVoice {

    @Volatile private var lastWake: String = ""
    @Volatile private var lastCmd: String = ""

    private fun hour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    private fun timeGreeting(): List<String> = when (hour()) {
        in 5..11 -> listOf(
            "Buenos días, jefe.",
            "Buenos días. Listo para empezar el día.",
            "Buenos días, jefe. ¿Arrancamos?",
            "Buen día. A sus órdenes.",
        )
        in 12..19 -> listOf(
            "Buenas tardes, jefe.",
            "Buenas tardes. ¿En qué le ayudo?",
            "Buenas tardes, a su disposición.",
        )
        else -> listOf(
            "Buenas noches, jefe.",
            "Buenas noches. Aún despierto, por usted.",
            "A estas horas y al pie del cañón, jefe.",
        )
    }

    /** Pool of wake acknowledgements (said when the wake word comes with no command). */
    private val WAKE_BASE = listOf(
        "Dígame, jefe.",
        "¿Qué necesita, jefe?",
        "A sus órdenes.",
        "Aquí estoy, jefe.",
        "Siempre activo para usted.",
        "¿En qué le ayudo?",
        "Lo escucho.",
        "Usted dirá, jefe.",
        "A su disposición.",
        "¿Qué tiene en mente?",
        "Listo cuando usted quiera.",
        "Cómo no, jefe.",
        "Por supuesto, dígame.",
        "Para eso estoy.",
        "¿Qué pasó, jefe?",
        "¿Cómo está, jefe?",
        "Diga usted.",
        "Atento, jefe.",
        "¿Manos a la obra?",
        "Presente, jefe.",
        "¿Qué se le ofrece?",
        "Cuente conmigo.",
        "Aquí su asistente, dígame.",
        "Todo bajo control. ¿Qué necesita?",
        "Encantado de ayudar, jefe.",
    )

    /** Occasional dry-wit lines (used sparingly). */
    private val WAKE_WIT = listOf(
        "¿Otra genialidad en camino, jefe?",
        "Espero que esta vez no sea para apagar una alarma… de nuevo.",
        "Sus deseos son, literalmente, mis instrucciones.",
        "Intentaré contener mi entusiasmo. Dígame.",
        "Funcionando al 100%, como de costumbre. ¿Qué hacemos?",
        "Si fuera humano, ya tendría el café listo. Dígame.",
    )

    /** Pool of command acknowledgements (said right before executing a task). */
    private val CMD_BASE = listOf(
        "Enseguida.",
        "Marchando, jefe.",
        "Ahora mismo.",
        "Voy a ello.",
        "Hecho, jefe.",
        "Por supuesto.",
        "En seguida me encargo.",
        "Considérelo hecho.",
        "Dalo por resuelto.",
        "Me pongo con ello.",
        "Trabajando en ello, jefe.",
        "Como ordene.",
        "De inmediato.",
        "En camino.",
        "Manos a la obra.",
        "Entendido, jefe.",
        "Procesando su petición.",
        "Cuente con ello.",
    )

    private val CMD_WIT = listOf(
        "Otra misión imposible. Veamos qué puedo hacer.",
        "Más fácil que reiniciar el reactor. Voy.",
        "Con un poco de magia digital… enseguida.",
        "No prometo milagros, pero haré algo parecido.",
    )

    /** Acknowledgement when only the wake word was heard. */
    fun wakeAck(): String {
        // ~15% chance of a witty line, ~20% a time-aware greeting, else a base line.
        val roll = Math.random()
        val pool = when {
            roll < 0.15 -> WAKE_WIT
            roll < 0.35 -> timeGreeting()
            else -> WAKE_BASE
        }
        return pick(pool, lastWake).also { lastWake = it }
    }

    /** Acknowledgement said right before running a command. */
    fun commandAck(): String {
        val pool = if (Math.random() < 0.12) CMD_WIT else CMD_BASE
        return pick(pool, lastCmd).also { lastCmd = it }
    }

    private fun pick(pool: List<String>, avoid: String): String {
        if (pool.size <= 1) return pool.firstOrNull() ?: ""
        var choice = pool.random()
        var guard = 0
        while (choice == avoid && guard < 5) { choice = pool.random(); guard++ }
        return choice
    }
}
