package com.blackclaw.android.assistant

import com.blackclaw.android.utils.XLog
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Offline wake-word + command engine backed by Vosk.
 *
 * Design: a SINGLE free-form recognizer runs continuously (no phase switching,
 * no recognizer restarts mid-utterance — that was dropping commands). On each
 * recognized phrase we look for the wake word "garra" (BlackClaw = "garra
 * negra"):
 *
 *  - "garra pon una alarma a las siete"  → wake + command in one breath → act.
 *  - "garra"  → acknowledge ("Dígame, jefe") and treat the NEXT phrase as the
 *    command.
 *
 * Wake detection is token-exact (not fuzzy) because Vosk emits clean dictionary
 * words, so we avoid false triggers on near-words like "gorra"/"barra".
 *
 * 100% offline, no key, no system beep. Requires [VoskModelManager.isReady].
 */
class VoskWakeWordEngine {

    companion object {
        private const val TAG = "VoskWake"
        private const val SAMPLE_RATE = 16000.0f
        // Accept these as the wake token (all real words Vosk knows).
        private val WAKE_TOKENS = setOf("garra", "guerra", "gara")  // tolerate close hits
    }

    private var model: Model? = null
    private var speech: SpeechService? = null
    @Volatile private var active = false
    @Volatile private var awaitingCommand = false
    @Volatile private var ttsUntilMs = 0L          // ignore mic echo while TTS plays
    @Volatile private var lastAckWords: Set<String> = emptySet()
    private var onCommand: ((String) -> Unit)? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun isRunning(): Boolean = active

    fun start(onCommand: (String) -> Unit): Boolean {
        if (active) return true
        if (!VoskModelManager.isReady()) return false
        this.onCommand = onCommand
        return try {
            model = Model(VoskModelManager.modelPath())
            // Single free-form recognizer, kept running for the whole session.
            speech = SpeechService(Recognizer(model, SAMPLE_RATE), SAMPLE_RATE)
            speech?.startListening(listener)
            active = true
            awaitingCommand = false
            XLog.i(TAG, "Vosk wake engine started")
            true
        } catch (e: Throwable) {
            XLog.w(TAG, "Vosk start failed: ${e.message}")
            stop()
            false
        }
    }

    fun stop() {
        active = false
        awaitingCommand = false
        runCatching { speech?.stop() }
        runCatching { speech?.shutdown() }
        speech = null
        runCatching { model?.close() }
        model = null
        XLog.i(TAG, "Vosk wake engine stopped")
    }

    private val listener = object : RecognitionListener {
        override fun onResult(hypothesis: String?) = handlePhrase(extractText(hypothesis))
        override fun onFinalResult(hypothesis: String?) = handlePhrase(extractText(hypothesis))
        override fun onPartialResult(hypothesis: String?) { /* wait for full phrase */ }
        override fun onError(e: Exception?) { XLog.d(TAG, "error: ${e?.message}") }
        override fun onTimeout() {}
    }

    private fun handlePhrase(textRaw: String) {
        if (!active) return
        val text = textRaw.trim().lowercase()
        if (text.isBlank()) return
        // Ignore the mic picking up our own spoken acknowledgement.
        if (System.currentTimeMillis() < ttsUntilMs) return
        if (text.contains("digame") || text.contains("dígame")) return
        // If the phrase is mostly words from the ack we just spoke, it's an echo.
        if (lastAckWords.isNotEmpty()) {
            val words = text.split(" ").filter { it.length > 2 }.toSet()
            if (words.isNotEmpty() && words.all { it in lastAckWords }) return
        }

        if (awaitingCommand) {
            awaitingCommand = false
            XLog.i(TAG, "Command (2nd phrase): '$text'")
            fire(text)
            return
        }

        val tokens = text.split(" ").filter { it.isNotBlank() }
        // Find the LAST wake token, command = everything after it.
        val wakeIdx = tokens.indexOfLast { it in WAKE_TOKENS }
        if (wakeIdx < 0) return  // no wake word in this phrase

        var rest = tokens.drop(wakeIdx + 1)
        // Drop a leading "negra" (from "garra negra").
        if (rest.firstOrNull() == "negra") rest = rest.drop(1)
        val command = rest.joinToString(" ").trim()

        if (command.isNotEmpty()) {
            XLog.i(TAG, "Wake + command (1 breath): '$command'")
            fire(command)
        } else {
            XLog.i(TAG, "Wake only → awaiting command")
            awaitingCommand = true
            // Speak a varied JARVIS ack and mute mic-echo for its duration.
            val ack = JarvisVoice.wakeAck()
            lastAckWords = ack.lowercase().replace(Regex("[^a-záéíóúñ ]"), "")
                .split(" ").filter { it.length > 2 }.toSet()
            ttsUntilMs = System.currentTimeMillis() + 2800
            Speaker.speak(ack)
        }
    }

    private fun fire(command: String) {
        onCommand?.let { cb -> main.post { cb(command) } }
    }

    private fun extractText(hypothesis: String?): String =
        runCatching {
            val o = JSONObject(hypothesis ?: "{}")
            o.optString("text", o.optString("partial", "")).trim()
        }.getOrDefault("")
}
