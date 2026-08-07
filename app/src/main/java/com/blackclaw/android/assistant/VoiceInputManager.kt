package com.blackclaw.android.assistant

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
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
        get() {
            val stored = KVUtils.getString(KEY_LANG, "")
            if (stored.isNotEmpty()) return stored
            return detectSpanishVariant()
        }
        set(v) { KVUtils.putString(KEY_LANG, v); KVUtils.sync() }

    private fun detectSpanishVariant(): String {
        val loc = Locale.getDefault()
        if (!loc.language.equals("es", ignoreCase = true)) return loc.toLanguageTag()
        return when (loc.country.uppercase()) {
            "MX" -> "es-MX"
            "US" -> "es-US"
            "AR" -> "es-AR"
            "CO" -> "es-CO"
            "CL" -> "es-CL"
            "PE" -> "es-PE"
            "VE" -> "es-VE"
            "EC" -> "es-EC"
            "GT" -> "es-GT"
            "BO" -> "es-BO"
            "DO" -> "es-DO"
            "HN" -> "es-HN"
            "NI" -> "es-NI"
            "CR" -> "es-CR"
            "PA" -> "es-PA"
            "SV" -> "es-SV"
            "PR" -> "es-PR"
            "UY" -> "es-UY"
            "PY" -> "es-PY"
            else -> "es-ES"
        }
    }

    /** Show the full-screen assist panel when the wake word fires (vs background-only). */
    var panelOnWake: Boolean
        get() = KVUtils.getBoolean("voice_panel_on_wake", true)
        set(v) { KVUtils.putBoolean("voice_panel_on_wake", v); KVUtils.sync() }

    fun isAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(ClawApplication.instance) ||
            pickRecognizerComponent() != null

    // Some OEM ROMs (e.g. HonorOS/MagicOS) point the DEFAULT recognition service
    // at a component that fails to bind ("Bind to system recognition service
    // failed with error 10"), even though Google's recognizer IS installed. So
    // instead of relying on the system default, we resolve a WORKING recognizer
    // component ourselves — preferring Google — and bind to it explicitly.
    @Volatile private var compResolved = false
    @Volatile private var cachedComp: ComponentName? = null

    private fun pickRecognizerComponent(): ComponentName? {
        if (compResolved) return cachedComp
        compResolved = true
        cachedComp = runCatching {
            val pm = ClawApplication.instance.packageManager
            val services = pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            if (services.isEmpty()) return@runCatching null
            val preferred = services.firstOrNull {
                it.serviceInfo?.packageName == "com.google.android.googlequicksearchbox"
            } ?: services.firstOrNull {
                it.serviceInfo?.packageName?.contains("google") == true
            } ?: services.first()
            preferred.serviceInfo?.let { ComponentName(it.packageName, it.name) }
        }.getOrNull()
        XLog.i(TAG, "Recognizer component resolved: ${cachedComp ?: "(system default)"}")
        return cachedComp
    }

    /** Create a recognizer bound to a working component (Google) when possible. */
    private fun createRecognizer(): SpeechRecognizer {
        val ctx = ClawApplication.instance
        val comp = pickRecognizerComponent()
        return if (comp != null)
            SpeechRecognizer.createSpeechRecognizer(ctx, comp)
        else
            SpeechRecognizer.createSpeechRecognizer(ctx)
    }

    private fun buildIntent(preferOffline: Boolean = true, lang: String = language): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ClawApplication.instance.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
        }

    private fun bestTranscript(candidates: List<String>): String {
        if (candidates.isEmpty()) return ""
        if (candidates.size == 1) return candidates[0].trim()
        return candidates
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .maxByOrNull { c ->
                var score = c.length.toDouble()
                if (c.any { it.isUpperCase() }) score += 5
                if (c.contains(' ')) score += 3
                if (c.first().isUpperCase()) score += 2
                score
            } ?: candidates[0].trim()
    }

    /**
     * Listen once and return the transcript via [onResult]. [onError] gets a
     * human-readable reason. [onRms] streams mic loudness (dB) for UI animation,
     * [onPartial] streams interim transcripts. Must be called from the main thread.
     *
     * Robust against the Google recognizer's flaky transient errors (CLIENT/BUSY/
     * SERVER) by retrying a couple of times, and falls back to the device's
     * default language if es-ES isn't available on-device.
     */
    fun listenOnce(
        onResult: (String) -> Unit,
        onError: (String) -> Unit = {},
        onRms: (Float) -> Unit = {},
        onPartial: (String) -> Unit = {},
    ) {
        // Try the system recognizer (Google) first. If it's fundamentally
        // unusable on this device (broken default component, or missing language
        // pack → error 12/13), fall back to the bundled offline Vosk engine so
        // voice input works regardless of the OEM's speech stack.
        val fallback = {
            if (VoskSingleShot.isReady()) {
                XLog.i(TAG, "System recognizer unusable → falling back to offline Vosk")
                VoskSingleShot.listen(onResult, onError, onRms, onPartial)
            } else onError("El reconocimiento de voz no está disponible en este dispositivo.")
        }
        main.post {
            startSingle(onResult, onError, onRms, onPartial,
                attempt = 0, preferOffline = false, lang = language, onUnusable = fallback)
        }
    }

    private fun startSingle(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onRms: (Float) -> Unit,
        onPartial: (String) -> Unit,
        attempt: Int,
        preferOffline: Boolean,
        lang: String,
        onUnusable: (() -> Unit)? = null,
    ) {
        if (!isAvailable()) { onError("Reconocimiento de voz no disponible en este dispositivo."); return }
        runCatching { recognizer?.destroy() }
        val sr = createRecognizer()
        recognizer = sr
        var done = false
        sr.setRecognitionListener(object : SimpleRecognition() {
            override fun onRmsChanged(rmsdB: Float) { onRms(rmsdB) }
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (t.isNotEmpty()) onPartial(t)
            }
            override fun onResults(results: Bundle) {
                if (done) return
                done = true
                val candidates = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val text = bestTranscript(candidates)
                cleanup()
                if (text.isNotEmpty()) onResult(text) else onError("No entendí nada.")
            }
            override fun onError(error: Int) {
                if (done) return
                cleanup()
                // Transient errors: retry up to 3 attempts with a short backoff.
                val transient = error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == 11 /* ERROR_SERVER_DISCONNECTED */ ||
                    error == SpeechRecognizer.ERROR_NETWORK ||
                    error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                // Language errors (12/13): retry once with the device default language.
                val langErr = error == 12 || error == 13
                when {
                    transient && attempt < 3 -> {
                        done = true
                        // If the first attempt was offline and failed, try online next.
                        val nextOffline = if (preferOffline && attempt == 0) false else preferOffline
                        main.postDelayed({
                            startSingle(onResult, onError, onRms, onPartial, attempt + 1, nextOffline, lang, onUnusable)
                        }, 450L)
                    }
                    langErr && lang.isNotEmpty() -> {
                        done = true
                        main.postDelayed({
                            startSingle(onResult, onError, onRms, onPartial, attempt + 1, false, "", onUnusable)
                        }, 200L)
                    }
                    else -> {
                        // "Unusable" = the recognizer itself can't serve us (client/bind
                        // failure, or no language pack) rather than a genuine no-speech.
                        // In that case, hand off to the Vosk fallback if provided.
                        val unusable = error == SpeechRecognizer.ERROR_CLIENT ||
                            error == 10 /* bind failed */ ||
                            error == 12 /* language unavailable */ ||
                            error == 13 /* language not supported */ ||
                            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                        if (unusable && onUnusable != null) onUnusable() else onError(errorText(error))
                    }
                }
            }
        })
        runCatching { sr.startListening(buildIntent(preferOffline, lang)) }
            .onFailure {
                sr.destroy(); recognizer = null
                if (attempt < 3) {
                    main.postDelayed({
                        startSingle(onResult, onError, onRms, onPartial, attempt + 1, preferOffline, lang, onUnusable)
                    }, 450L)
                } else if (onUnusable != null) onUnusable()
                else onError("No pude iniciar el micrófono: ${it.message}")
            }
    }

    /**
     * Start the hands-free wake-word loop. [onCommand] receives the phrase that
     * followed the wake word. Keeps re-listening until [stopWakeLoop].
     */
    fun startWakeLoop(onCommand: (String, Boolean) -> Unit, onError: (String) -> Unit = {}) {
        if (wakeLoopActive) return
        wakeLoopActive = true
        // Prefer the fully-offline Vosk engine (no beep, continuous, + whisper
        // detection). Falls back to SpeechRecognizer (no whisper detection there).
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
        // SpeechRecognizer path can't measure amplitude → whisper=false.
        main.post { listenForWake { cmd -> onCommand(cmd, false) } }
    }

    fun stopWakeLoop() {
        wakeLoopActive = false
        runCatching { voskEngine.stop() }
        // SpeechRecognizer must be touched on the main thread.
        main.post { cleanup() }
        BeepSuppressor.restore()
        XLog.i(TAG, "Wake loop stopped")
    }

    /** True when the offline (Vosk) wake engine is the active backend. */
    fun isOfflineWakeReady(): Boolean = VoskModelManager.isReady()

    /** Arm a follow-up window (continuous conversation) on the offline engine. */
    fun armFollowUp(durationMs: Long = 7000L) {
        runCatching { voskEngine.armFollowUp(durationMs) }
    }

    /** Observe listening state changes (idle/listening/heard/listening_followup). */
    fun setStateListener(cb: ((String) -> Unit)?) {
        voskEngine.onState = cb
    }

    /**
     * Calibrate the whisper detector: pause listening, record ~2.5s of the user
     * speaking normally, set that as the "normal level". Runs on a background
     * thread; [onDone] gets the measured RMS (or 0 on failure). The caller is
     * responsible for restarting the wake service afterwards if it was on.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun calibrateWhisper(onDone: (Double) -> Unit) {
        stopWakeLoop()  // release the mic for the calibration capture
        Thread({
            var rms = 0.0
            runCatching {
                Thread.sleep(400)  // let the mic free up
                val rate = 16000
                val minBuf = android.media.AudioRecord.getMinBufferSize(
                    rate, android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf > 0) {
                    val ar = android.media.AudioRecord(
                        android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        rate, android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
                    if (ar.state == android.media.AudioRecord.STATE_INITIALIZED) {
                        ar.startRecording()
                        val buf = ShortArray(rate / 4)
                        var sumSq = 0.0; var count = 0L
                        val deadline = System.currentTimeMillis() + 2500
                        while (System.currentTimeMillis() < deadline) {
                            val n = ar.read(buf, 0, buf.size)
                            if (n > 0) {
                                for (i in 0 until n) { val s = buf[i].toDouble(); sumSq += s * s }
                                count += n
                            }
                        }
                        ar.stop(); ar.release()
                        if (count > 0) rms = kotlin.math.sqrt(sumSq / count)
                    }
                }
            }.onFailure { XLog.w(TAG, "Calibration failed: ${it.message}") }
            if (rms > 0) WhisperMode.calibrateNormalLevel(rms)
            main.post { onDone(rms) }
        }, "whisper-calibrate").apply { isDaemon = true }.start()
    }

    private fun listenForWake(onCommand: (String) -> Unit) {
        if (!wakeLoopActive) return
        BeepSuppressor.mute()  // keep streams muted while we (re)start listening
        val sr = createRecognizer()
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

    /**
     * Cancel any single-shot listening in progress (system recognizer or the
     * Vosk fallback), without touching the hands-free wake loop. Used when the
     * user switches to typing instead of talking.
     */
    fun cancelListenOnce() {
        main.post { cleanup() }
        runCatching { VoskSingleShot.cancel() }
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
