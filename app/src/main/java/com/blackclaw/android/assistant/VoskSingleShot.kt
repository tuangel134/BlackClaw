package com.blackclaw.android.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.blackclaw.android.utils.XLog
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.concurrent.thread

/**
 * Offline single-shot speech recognition backed by Vosk — the fallback for
 * push-to-talk (the assist panel) when the system SpeechRecognizer is unusable.
 *
 * Why this exists: some OEM ROMs (HonorOS/MagicOS observed) either point the
 * DEFAULT recognizer at a component that fails to bind, or ship Google's
 * on-device "Soda" recognizer WITHOUT the Spanish language pack downloaded
 * ("Returning no LP, as MDD has not downloaded this pack") — so both online and
 * offline Google paths fail with error 12/13. Vosk needs none of that: the
 * Spanish model ships bundled in the app, works fully offline, no Google
 * Speech Services, no language-pack download, no network.
 *
 * Listens until it hears a complete utterance, then a short trailing silence,
 * and returns the transcript. Overall + post-speech-silence timeouts guard it.
 */
object VoskSingleShot {

    private const val TAG = "VoskOneShot"
    private const val SAMPLE_RATE = 16000

    fun isReady(): Boolean = VoskModelManager.isReady()

    @Volatile private var running = false
    @Volatile private var audioRecord: AudioRecord? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Listen once and return the transcript. Callbacks fire on the main thread.
     * [timeoutMs] = max wait for ANY speech; [silenceMs] = trailing silence that
     * ends an utterance once speech has started.
     */
    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO is granted
    fun listen(
        onResult: (String) -> Unit,
        onError: (String) -> Unit = {},
        onRms: (Float) -> Unit = {},
        onPartial: (String) -> Unit = {},
        timeoutMs: Long = 12_000L,
        silenceMs: Long = 1800L,
    ) {
        if (running) { onError("Ya estoy escuchando."); return }
        if (!isReady()) { onError("El modelo de voz offline no está listo."); return }
        running = true
        thread(name = "vosk-oneshot", isDaemon = true) {
            var model: Model? = null
            var rec: Recognizer? = null
            var ar: AudioRecord? = null
            var finished = false
            fun finish(result: String?, err: String?) {
                if (finished) return
                finished = true
                if (result != null) main.post { onResult(result) }
                else if (err != null) main.post { onError(err) }
            }
            try {
                model = Model(VoskModelManager.modelPath())
                rec = Recognizer(model, SAMPLE_RATE.toFloat())
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) { finish(null, "No pude abrir el micrófono."); return@thread }
                ar = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
                if (ar.state != AudioRecord.STATE_INITIALIZED) { finish(null, "No pude abrir el micrófono."); return@thread }
                audioRecord = ar
                ar.startRecording()

                val buf = ShortArray(SAMPLE_RATE / 4)  // ~250ms
                val startedAt = System.currentTimeMillis()
                var lastVoiceAt = 0L
                var heardSpeech = false
                var ambient = 200.0

                while (running && !finished) {
                    val n = try { ar.read(buf, 0, buf.size) } catch (e: Exception) { break }
                    if (n <= 0) continue
                    val rms = WhisperMode.rmsOf(buf, n)
                    main.post { onRms(((20 * Math.log10(rms.coerceAtLeast(1.0)) - 40) / 10).toFloat().coerceIn(0f, 1f)) }
                    if (rms < ambient * 1.5) ambient = (ambient * 0.95 + rms * 0.05).coerceIn(80.0, 4000.0)
                    val hasVoice = rms > ambient * 1.9 + 120.0
                    val now = System.currentTimeMillis()
                    if (hasVoice) { heardSpeech = true; lastVoiceAt = now }

                    val end = try { rec.acceptWaveForm(buf, n) } catch (e: Exception) { false }
                    if (end) {
                        val text = extractText(rec.result)
                        if (text.isNotBlank()) { finish(text, null); break }
                    } else {
                        val partial = extractText(rec.partialResult)
                        if (partial.isNotBlank()) main.post { onPartial(partial) }
                    }

                    // End conditions.
                    if (heardSpeech && now - lastVoiceAt > silenceMs) {
                        val text = extractText(rec.finalResult).ifBlank { extractText(rec.partialResult) }
                        if (text.isNotBlank()) finish(text, null) else finish(null, "No entendí nada.")
                        break
                    }
                    if (!heardSpeech && now - startedAt > timeoutMs) { finish(null, "No escuché nada."); break }
                    if (now - startedAt > timeoutMs + 8000) { finish(null, "Se agotó el tiempo."); break }
                }
                if (!finished) {
                    val text = extractText(rec.finalResult)
                    if (text.isNotBlank()) finish(text, null) else finish(null, "No entendí nada.")
                }
            } catch (e: Throwable) {
                XLog.w(TAG, "Vosk one-shot failed: ${e.message}")
                finish(null, "Fallo el reconocimiento offline: ${e.message}")
            } finally {
                running = false
                runCatching { ar?.stop() }
                runCatching { ar?.release() }
                audioRecord = null
                runCatching { rec?.close() }
                runCatching { model?.close() }
            }
        }
    }

    fun cancel() {
        running = false
        runCatching { audioRecord?.stop() }
    }

    private fun extractText(hypothesis: String?): String = runCatching {
        val o = JSONObject(hypothesis ?: "{}")
        o.optString("text", o.optString("partial", "")).trim()
    }.getOrDefault("")
}
