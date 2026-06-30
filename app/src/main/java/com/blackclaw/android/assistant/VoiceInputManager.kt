package com.blackclaw.android.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Handler
import android.os.Looper
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import java.util.Locale

/**
 * Native speech-to-text using Android's on-device SpeechRecognizer.
 *
 * Two modes:
 *  - Single-shot: listen once, return the transcript (push-to-talk style).
 *  - Wake-word loop: continuously listen; when the transcript starts with the
 *    wake word ("blackclaw" / "hey blackclaw"), fire the callback with the rest
 *    of the phrase, then resume listening. This gives hands-free operation —
 *    "Hey BlackClaw, manda un mensaje a mamá" — useful when the phone is docked
 *    or you're driving / working on the PC.
 *
 * Battery note: continuous recognition is opt-in. We use the system recognizer
 * (no extra model bundled) and restart it on each result/error to keep a long
 * loop alive. On devices with on-device recognition (EXTRA_PREFER_OFFLINE) this
 * stays local.
 */
object VoiceInputManager {

    private const val TAG = "VoiceInput"
    private const val KEY_WAKE_WORD = "voice_wake_word"
    private const val KEY_WAKE_ENABLED = "voice_wake_enabled"
    private const val KEY_LANG = "voice_lang"

    @Volatile private var recognizer: SpeechRecognizer? = null
    @Volatile private var wakeLoopActive = false
    private val main = Handler(Looper.getMainLooper())
    /** Offline engine (preferred when its model is downloaded). */
    private val voskEngine = VoskWakeWordEngine()

    var wakeWord: String
        get() = KVUtils.getString(KEY_WAKE_WORD, "blackclaw")
        set(v) { KVUtils.putString(KEY_WAKE_WORD, v.lowercase().trim()); KVUtils.sync() }

    var wakeEnabled: Boolean
        get() = KVUtils.getBoolean(KEY_WAKE_ENABLED, false)
        set(v) { KVUtils.putBoolean(KEY_WAKE_ENABLED, v); KVUtils.sync() }

    var language: String
        get() = KVUtils.getString(KEY_LANG, "es-ES")
        set(v) { KVUtils.putString(KEY_LANG, v); KVUtils.sync() }

    fun isAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(ClawApplication.instance)

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        // Partial results let us catch the wake word mid-utterance (faster, and
        // recovers cases where the full result is truncated).
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        // Prefer on-device recognition when the OEM supports it (privacy + offline).
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    /**
     * Listen once and return the transcript via [onResult]. [onError] gets a
     * human-readable reason. Must be called from the main thread.
     */
    fun listenOnce(onResult: (String) -> Unit, onError: (String) -> Unit = {}) {
        main.post { startSingle(onResult, onError) }
    }

    private fun startSingle(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!isAvailable()) { onError("Reconocimiento de voz no disponible en este dispositivo."); return }
        val sr = SpeechRecognizer.createSpeechRecognizer(ClawApplication.instance)
        recognizer = sr
        sr.setRecognitionListener(object : SimpleRecognition() {
            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                cleanup()
                if (text.isNotEmpty()) onResult(text) else onError("No entendí nada.")
            }
            override fun onError(error: Int) {
                cleanup()
                onError(errorText(error))
            }
        })
        runCatching { sr.startListening(buildIntent()) }
            .onFailure { onError("No pude iniciar el micrófono: ${it.message}") }
    }

    /**
     * Start the hands-free wake-word loop. [onCommand] receives the phrase that
     * followed the wake word. Keeps re-listening until [stopWakeLoop].
     */
    fun startWakeLoop(onCommand: (String) -> Unit, onError: (String) -> Unit = {}) {
        if (wakeLoopActive) return
        wakeLoopActive = true
        // Prefer the fully-offline Vosk engine (no beep, continuous). It only
        // works once its model is downloaded; otherwise fall back to the system
        // SpeechRecognizer loop (with the beep suppressed).
        if (VoskModelManager.isReady() && voskEngine.start(onCommand)) {
            XLog.i(TAG, "Wake loop: using offline Vosk engine")
            return
        }
        if (!isAvailable()) {
            wakeLoopActive = false
            onError("Reconocimiento de voz no disponible.")
            return
        }
        BeepSuppressor.mute()   // silence the per-cycle recognition beep
        XLog.i(TAG, "Wake loop: using SpeechRecognizer (word='$wakeWord')")
        main.post { listenForWake(onCommand) }
    }

    fun stopWakeLoop() {
        wakeLoopActive = false
        runCatching { voskEngine.stop() }
        cleanup()
        BeepSuppressor.restore()
        XLog.i(TAG, "Wake loop stopped")
    }

    /** True when the offline (Vosk) wake engine is the active backend. */
    fun isOfflineWakeReady(): Boolean = VoskModelManager.isReady()

    private fun listenForWake(onCommand: (String) -> Unit) {
        if (!wakeLoopActive) return
        BeepSuppressor.mute()  // keep streams muted while we (re)start listening
        val sr = SpeechRecognizer.createSpeechRecognizer(ClawApplication.instance)
        recognizer = sr
        var fired = false
        sr.setRecognitionListener(object : SimpleRecognition() {
            override fun onPartialResults(partialResults: Bundle?) {
                if (fired) return
                val candidates = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                // Check every alternative the recognizer offers — improves hit rate.
                for (c in candidates) {
                    val m = WakeWordMatcher.match(c, wakeWord)
                    if (m != null && m.command.isNotEmpty()) {
                        fired = true
                        XLog.i(TAG, "Wake (partial) '${m.matchedVariant}' → '${m.command}'")
                        BeepSuppressor.restore()  // let TTS reply be heard
                        main.post { onCommand(m.command) }
                        restartWakeAfterDelay(onCommand, delayMs = 3000)
                        return
                    }
                }
            }
            override fun onResults(results: Bundle) {
                if (fired) { restartWakeAfterDelay(onCommand); return }
                val candidates = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                handleWakeResult(candidates, onCommand)
            }
            override fun onError(error: Int) {
                // Common during silence — just restart the loop after a short delay.
                restartWakeAfterDelay(onCommand)
            }
        })
        runCatching { sr.startListening(buildIntent()) }
            .onFailure { restartWakeAfterDelay(onCommand) }
    }

    private fun handleWakeResult(candidates: List<String>, onCommand: (String) -> Unit) {
        for (text in candidates) {
            val m = WakeWordMatcher.match(text, wakeWord) ?: continue
            XLog.i(TAG, "Wake word '${m.matchedVariant}' detected, command='${m.command}'")
            BeepSuppressor.restore()  // audible TTS / command
            if (m.command.isNotEmpty()) {
                main.post { onCommand(m.command) }
                restartWakeAfterDelay(onCommand, delayMs = 3000)
            } else {
                // Just the wake word — acknowledge and listen for the command next.
                Speaker.speak("Dígame, jefe.")
                restartWakeAfterDelay(onCommand, delayMs = 2000)
            }
            return
        }
        restartWakeAfterDelay(onCommand)
    }

    private fun restartWakeAfterDelay(onCommand: (String) -> Unit, delayMs: Long = 350) {
        cleanup()
        if (!wakeLoopActive) return
        // Short gap for silence cycles (muted, no beep). Longer gap after a
        // command so any TTS reply finishes before we mute + listen again.
        main.postDelayed({ listenForWake(onCommand) }, delayMs)
    }

    private fun cleanup() {
        runCatching {
            recognizer?.stopListening()
            recognizer?.destroy()
        }
        recognizer = null
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "No entendí lo que dijiste."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No escuché nada."
        SpeechRecognizer.ERROR_AUDIO -> "Problema con el micrófono."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta permiso de micrófono."
        SpeechRecognizer.ERROR_NETWORK -> "Sin red para reconocimiento (prueba modo offline)."
        else -> "Error de reconocimiento ($code)."
    }

    /** Listener with no-op defaults so subclasses only override what they need. */
    private open class SimpleRecognition : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {}
        override fun onResults(results: Bundle) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
