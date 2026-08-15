package com.blackclaw.android.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive tests for [TaskClassifier] — must reliably tell actions from
 * chat in Spanish and English, including conjugations and polite/indirect forms.
 */
class TaskClassifierTest {

    private fun task(s: String) = assertTrue("Expected TASK: '$s'", TaskClassifier.isTask(s))
    private fun chat(s: String) = assertFalse("Expected CHAT: '$s'", TaskClassifier.isTask(s))

    // ── Spanish imperatives ──
    @Test fun spanishImperatives() {
        task("pon una alarma a las 7")
        task("ponme una alarma mañana")
        task("manda un mensaje a mamá")
        task("envíame el archivo")
        task("abre youtube")
        task("ábreme la cámara")
        task("recuérdame llamar al dentista")
        task("avísame en 30 minutos")
        task("enciende la linterna")
        task("apaga el wifi")
        task("sube el volumen")
        task("baja el brillo")
        task("busca videos de gatos")
        task("llama a Juan")
        task("anota que tengo reunión el lunes")
        task("crea una nota")
        task("reproduce música")
        task("traduce esto al inglés")
        task("descarga la app")
        task("comparte mi ubicación")
        task("silencia el teléfono")
        task("para la música")
        task("programa una reunión")
        task("agenda una cita el viernes")
        task("crea una tarea: comprar leche")
        task("crea una tarea cuando me conecte al wifi")
    }

    // ── Spanish infinitive / indirect / polite ──
    @Test fun spanishIndirect() {
        task("puedes poner una alarma")
        task("podrías abrir whatsapp")
        task("necesito que envíes un mensaje")
        task("quiero que pongas una alarma")
        task("me gustaría que reproduzcas música")
        task("ayúdame a buscar un restaurante")
        task("hazme el favor de apagar el wifi")
        task("por favor pon una alarma")
        task("puedes recordarme comprar pan")
        task("¿me puedes poner una alarma?")
    }

    // ── Action objects (weak verb but clear intent) ──
    @Test fun actionObjects() {
        task("una alarma para las 8")
        task("recordatorio de pagar la luz")
        task("temporizador de 10 minutos")
        task("mensaje a mamá: llego tarde")
        task("lista de compras: leche y pan")
        task("agéndame una reunión")
    }

    // ── App names ──
    @Test fun appNames() {
        task("whatsapp a mamá que llego tarde")
        task("spotify lo-fi beats")
        task("ponme algo en netflix")
    }

    // ── Device state ──
    @Test fun deviceState() {
        task("cuánta batería tengo")
        task("lee mis notificaciones")
        task("cómo está el wifi")
        task("qué hay en mi portapapeles")
    }

    // ── English ──
    @Test fun english() {
        task("open whatsapp")
        task("set an alarm at 7")
        task("run a task")
        task("do this")
        task("send a message to mom")
        task("can you turn off the wifi")
        task("could you remind me to call")
        task("please play some music")
        task("what's my battery level")
        task("remind me to buy milk")
    }

    // ── Casual chat / questions → NOT tasks ──
    @Test fun casualChat() {
        chat("hola qué tal")
        chat("buenos días")
        chat("gracias")
        chat("cuéntame un chiste")
        chat("cómo estás")
        chat("qué opinas de la vida")
        chat("jajaja qué bueno")
        chat("me siento cansado hoy")
        chat("hablemos de filosofía")
        chat("eres muy listo")
    }

    @Test fun pureQuestionsChat() {
        chat("quién fue Einstein")
        chat("por qué el cielo es azul")
        chat("qué es la fotosíntesis")
        chat("cuál es el sentido de la vida")
        chat("¿cómo se llama esta canción?")
        chat("¿para qué sirve el botón de apagar el wifi?")
        chat("¿cómo puedo programar tareas?")
        chat("¿qué significa crear una tarea?")
        chat("¿qué acciones puede ejecutar BlackClaw?")
        chat("¿acaso puedes apagar la linterna?")
        chat("tengo tiempo para leer")
        chat("mi perro se llama Max")
        chat("do you like music")
        chat("set theory is interesting")
        chat("no apagues el wifi")
        chat("no leas mis notificaciones")
        chat("please do not open WhatsApp")
        chat("por favor no apagues el wifi")
        chat("¿hay que programar tareas?")
        chat("¿tienes que crear una nota?")
        chat("¿quieres que abra WhatsApp?")
        chat("no entiendo cómo abrir WhatsApp")
        chat("hazme un chiste")
        chat("hazme un resumen")
        chat("crea un poema")
        chat("write me a joke")
        chat("¿puedes hacer un resumen?")
    }

    @Test fun emptyIsChat() {
        chat("")
        chat("   ")
    }

    // ── Tricky: questions that ARE requests ──
    @Test fun trickyRequests() {
        task("¿puedes apagar la linterna?")
        task("¿me pones una alarma?")
        task("¿puedes crear una nota?")
        task("¿puedes programar una tarea para mañana a las 9?")
        task("¿puedes ejecutar ls?")
        task("¿qué batería tengo?")
        task("¿cómo está el wifi?")
        task("¿qué hay en mi portapapeles?")
        task("¿qué notificaciones tengo?")
        task("dime mi batería")
        task("muestra mis notificaciones")
        task("no, abre la cámara")
        task("oye, abre la cámara")
    }

    @Test fun capabilityQuestionsStayInChat() {
        chat("¿puedes programar tareas cierto?")
        chat("¿puedes abrir WhatsApp, cierto?")
        chat("¿puedes apagar la linterna, verdad?")
        chat("¿puedes llamar a Juan, correcto?")
        chat("¿puedes ejecutar ls, right?")
        chat("¿puedes programar una tarea?")
        chat("¿puedes crear automatizaciones?")
        chat("¿puedes usar la terminal?")
        chat("¿puedes hacer esto?")
        chat("¿puedo programar tareas?")
        chat("¿se puede programar tareas?")
        chat("¿es posible crear automatizaciones?")
        chat("¿sabes abrir WhatsApp?")
        chat("¿eres capaz de apagar la linterna?")
        chat("¿tú puedes crear una nota?")
        chat("¿puedes decirme cómo abrir WhatsApp?")
        chat("¿puedes explicarme cómo crear una nota?")
        chat("¿puedes ayudarme a entender cómo programar tareas?")
        chat("dime cómo programar una tarea")
        chat("muestra cómo crear una tarea")
        chat("enséñame a crear una tarea")
        chat("¿qué puedes hacer?")
        chat("what can you do")
        chat("can you schedule tasks?")
    }

    // ── Tricky: chat that mentions verbs-as-topics should stay chat ──
    @Test fun verbsAsTopicsStayChat() {
        chat("me gusta saber de historia")
        chat("quiero pensar en mis metas")
        chat("podrías creer lo que pasó")
    }
}
