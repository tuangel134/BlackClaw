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
    }

    @Test fun emptyIsChat() {
        chat("")
        chat("   ")
    }

    // ── Tricky: questions that ARE requests ──
    @Test fun trickyRequests() {
        task("¿puedes apagar la linterna?")
        task("¿me pones una alarma?")
        task("oye, abre la cámara")
    }

    // ── Tricky: chat that mentions verbs-as-topics should stay chat ──
    @Test fun verbsAsTopicsStayChat() {
        chat("me gusta saber de historia")
        chat("quiero pensar en mis metas")
        chat("podrías creer lo que pasó")
    }
}
