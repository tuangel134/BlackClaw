package com.blackclaw.android.assistant

import com.blackclaw.android.utils.XLog
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Offline wake-word engine backed by Vosk.
 *
 * Why this over the SpeechRecognizer loop:
 *  - 100% on-device, no system "beep" on each cycle, no API key.
 *  - Truly continuous listening (no gaps), low-ish CPU with a restricted grammar.
 *
 * How it works:
 *  - Phase 1 (wake): a Recognizer with a small GRAMMAR limited to the wake
 *    phrase variants + "[unk]". Vosk only needs to decide "did they say the
 *    wake word or not", which is cheap and accurate. Wake phrase is "garra" /
 *    "garra negra" — real Spanish words Vosk knows (BlackClaw = "garra negra").
 *  - Phase 2 (command): once woken, we swap to a FREE-FORM recognizer to
 *    capture the arbitrary command, then hand it to the callback and return to
 *    phase 1.
 *
 * Requires [VoskModelManager.isReady]. Falls back gracefully (returns false from
 * [start]) so the caller can use the SpeechRecognizer path instead.
 */
class VoskWakeWordEngine {

    companion object {
        private const val TAG = "VoskWake"
        private const val SAMPLE_RATE = 16000.0f
        // Wake variants must be words present in the Spanish model's vocabulary.
        private const val WAKE_GRAMMAR = "[\"garra\", \"garra negra\", \"oye garra\", \"[unk]\"]"
        private val WAKE_TOKENS = listOf("garra")
        // How long to listen for the command after waking before giving up.
        private const val COMMAND_WINDOW_MS = 6000L
    }

    private var model: Model? = null
    private var speech: SpeechService? = null
    @Volatile private var active = false
    @Volatile private var capturingCommand = false
    private var onCommand: ((String) -> Unit)? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun isRunning(): Boolean = active

    /** Returns true if it started; false if the model isn't ready / failed. */
    fun start(onCommand: (String) -> Unit): Boolean {
        if (active) return true
        if (!VoskModelManager.isReady()) return false
        this.onCommand = onCommand
        return try {
            model = Model(VoskModelManager.modelPath())
            startWakePhase()
            active = true
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
        capturingCommand = false
        runCatching { speech?.stop() }
        runCatching { speech?.shutdown() }
        speech = null
        runCatching { model?.close() }
        model = null
        XLog.i(TAG, "Vosk wake engine stopped")
    }

    private fun startWakePhase() {
        capturingCommand = false
        val m = model ?: return
        runCatching { speech?.stop() }
        speech = SpeechService(Recognizer(m, SAMPLE_RATE, WAKE_GRAMMAR), SAMPLE_RATE)
        speech?.startListening(wakeListener)
    }

    private fun startCommandPhase() {
        capturingCommand = true
        val m = model ?: return
        runCatching { speech?.stop() }
        // Free-form recognizer to capture an arbitrary command.
        speech = SpeechService(Recognizer(m, SAMPLE_RATE), SAMPLE_RATE)
        speech?.startListening(commandListener)
        // Safety timeout: if no command is captured, return to wake phase.
        main.postDelayed({
            if (capturingCommand && active) {
                XLog.d(TAG, "Command window timed out, back to wake")
                startWakePhase()
            }
        }, COMMAND_WINDOW_MS)
    }

    private val wakeListener = object : RecognitionListener {
        override fun onResult(hypothesis: String?) {
            val text = extractText(hypothesis)
            if (text.isNotBlank() && WAKE_TOKENS.any { text.contains(it) }) {
                XLog.i(TAG, "Wake detected: '$text'")
                if (active) {
                    Speaker.speak("Dígame, jefe.")
                    startCommandPhase()
                }
            }
        }
        override fun onPartialResult(hypothesis: String?) {
            // React fast on the partial too.
            val text = extractPartial(hypothesis)
            if (text.isNotBlank() && WAKE_TOKENS.any { text.contains(it) } && !capturingCommand && active) {
                XLog.i(TAG, "Wake (partial): '$text'")
                Speaker.speak("Dígame, jefe.")
                startCommandPhase()
            }
        }
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(e: Exception?) { XLog.d(TAG, "wake error: ${e?.message}") }
        override fun onTimeout() {}
    }

    private val commandListener = object : RecognitionListener {
        override fun onResult(hypothesis: String?) {
            val text = extractText(hypothesis)
            if (text.isNotBlank()) {
                XLog.i(TAG, "Command captured: '$text'")
                capturingCommand = false
                onCommand?.let { cb -> main.post { cb(text) } }
                // Brief pause then resume wake listening.
                main.postDelayed({ if (active) startWakePhase() }, 500)
            }
        }
        override fun onPartialResult(hypothesis: String?) {}
        override fun onFinalResult(hypothesis: String?) {
            // If onResult didn't fire with content, recover to wake phase.
            if (capturingCommand && active) {
                val text = extractText(hypothesis)
                if (text.isNotBlank()) {
                    capturingCommand = false
                    onCommand?.let { cb -> main.post { cb(text) } }
                }
                main.postDelayed({ if (active) startWakePhase() }, 300)
            }
        }
        override fun onError(e: Exception?) {
            XLog.d(TAG, "command error: ${e?.message}")
            if (active) startWakePhase()
        }
        override fun onTimeout() { if (active) startWakePhase() }
    }

    private fun extractText(hypothesis: String?): String =
        runCatching { JSONObject(hypothesis ?: "{}").optString("text", "").trim() }.getOrDefault("")

    private fun extractPartial(hypothesis: String?): String =
        runCatching { JSONObject(hypothesis ?: "{}").optString("partial", "").trim() }.getOrDefault("")
}
