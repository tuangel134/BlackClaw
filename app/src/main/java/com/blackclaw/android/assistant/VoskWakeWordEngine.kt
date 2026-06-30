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
 * Offline wake-word + command engine backed by Vosk, with a self-managed
 * [AudioRecord] loop (instead of Vosk's SpeechService) so we can ALSO measure
 * input amplitude per buffer — that's what enables whisper detection.
 *
 * Per buffer we: (1) feed the samples to the Vosk recognizer for transcription,
 * and (2) feed their RMS to [WhisperMode]. When an utterance completes we know
 * both the text AND whether it was whispered, so the reply can whisper back.
 *
 * Single continuous recognizer; wake word "garra" (BlackClaw = "garra negra").
 * 100% offline, no key, no system beep. Falls back (start returns false) if the
 * model isn't ready or the mic can't be opened.
 */
class VoskWakeWordEngine {

    companion object {
        private const val TAG = "VoskWake"
        private const val SAMPLE_RATE = 16000
        // Wake tokens per language (Vosk emits clean dictionary words).
        private val WAKE_TOKENS_ES = setOf("garra", "guerra", "gara")
        private val WAKE_TOKENS_EN = setOf("claw", "flaw", "clause", "black claw")
        private const val AWAIT_TIMEOUT_MS = 12_000L
    }

    private fun wakeTokens(): Set<String> =
        if (VoskModelManager.activeLang == VoskModelManager.Lang.EN) WAKE_TOKENS_EN else WAKE_TOKENS_ES

    @Volatile private var model: Model? = null
    @Volatile private var recognizer: Recognizer? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var active = false
    @Volatile private var awaitingCommand = false
    @Volatile private var awaitingSince = 0L
    @Volatile private var followUpUntil = 0L          // continuous-conversation window
    @Volatile private var ttsUntilMs = 0L
    @Volatile private var lastAckWords: Set<String> = emptySet()
    private var onCommand: ((String, Boolean) -> Unit)? = null
    /** Optional listening-state callback for visual feedback: idle/listening/heard. */
    var onState: ((String) -> Unit)? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun isRunning(): Boolean = active

    /** Open a follow-up window so the next phrase needs no wake word (Alexa-style). */
    fun armFollowUp(durationMs: Long = 7000L) {
        if (active) {
            followUpUntil = System.currentTimeMillis() + durationMs
            onState?.invoke("listening_followup")
            XLog.d(TAG, "Follow-up window armed ${durationMs}ms")
        }
    }

    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO is granted
    @Synchronized
    fun start(onCommand: (String, Boolean) -> Unit): Boolean {
        if (active) return true
        if (!VoskModelManager.isReady()) return false
        this.onCommand = onCommand
        return try {
            model = Model(VoskModelManager.modelPath())
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) { stop(); return false }
            val bufSize = minBuf * 2
            val ar = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); stop(); return false }
            audioRecord = ar
            active = true
            awaitingCommand = false
            WhisperMode.beginUtterance()
            ar.startRecording()
            captureThread = thread(name = "vosk-capture", isDaemon = true) { captureLoop() }
            XLog.i(TAG, "Vosk wake engine started (self-managed AudioRecord)")
            true
        } catch (e: Throwable) {
            XLog.w(TAG, "Vosk start failed: ${e.message}")
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        active = false
        awaitingCommand = false
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        // Let the capture thread observe active=false and exit.
        runCatching { captureThread?.join(500) }
        captureThread = null
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { model?.close() }
        model = null
        XLog.i(TAG, "Vosk wake engine stopped")
    }

    private fun captureLoop() {
        val buf = ShortArray(SAMPLE_RATE / 4)  // ~250ms chunks
        val rec = recognizer ?: return
        // ── VAD: only feed the recognizer when there's voice energy, with a
        // short hangover after speech so we don't clip word endings. Saves CPU
        // and avoids transcribing background noise. ──
        var ambient = 200.0          // adaptive noise floor
        var voiceHangover = 0        // buffers to keep processing after voice
        while (active) {
            val ar = audioRecord ?: break
            val n = try { ar.read(buf, 0, buf.size) } catch (e: Exception) { break }
            if (n <= 0) continue
            val rms = WhisperMode.rmsOf(buf, n)
            // Update ambient noise floor slowly when it's quiet.
            if (rms < ambient * 1.5) ambient = (ambient * 0.95 + rms * 0.05).coerceIn(80.0, 4000.0)
            val voiceThreshold = ambient * 2.2 + 150.0
            val hasVoice = rms > voiceThreshold

            if (hasVoice) {
                voiceHangover = 4   // ~1s hangover
                WhisperMode.feed(buf, n)
            } else if (voiceHangover > 0) {
                voiceHangover--
                WhisperMode.feed(buf, n)
            } else {
                // Silence — skip recognizer work entirely (the VAD win).
                continue
            }

            val end = try { rec.acceptWaveForm(buf, n) } catch (e: Exception) { false }
            if (end) {
                val text = extractText(rec.result)
                val whisper = WhisperMode.endUtteranceWasWhisper()
                WhisperMode.beginUtterance()
                if (text.isNotBlank()) main.post { handlePhrase(text, whisper) }
            }
        }
    }

    private fun handlePhrase(textRaw: String, whisper: Boolean) {
        if (!active) return
        val text = textRaw.trim().lowercase()
        if (text.isBlank()) return
        if (System.currentTimeMillis() < ttsUntilMs) return
        if (text.contains("digame") || text.contains("dígame")) return
        if (lastAckWords.isNotEmpty()) {
            val words = text.split(" ").filter { it.length > 2 }.toSet()
            if (words.isNotEmpty() && words.all { it in lastAckWords }) return
        }

        if (awaitingCommand) {
            if (System.currentTimeMillis() - awaitingSince > AWAIT_TIMEOUT_MS) {
                awaitingCommand = false
            } else {
                awaitingCommand = false
                XLog.i(TAG, "Command (2nd phrase): '$text' whisper=$whisper")
                fire(text, whisper)
                return
            }
        }

        // Follow-up window: accept a command WITHOUT the wake word right after a
        // reply (continuous conversation, Alexa-style).
        if (System.currentTimeMillis() < followUpUntil) {
            followUpUntil = 0
            XLog.i(TAG, "Follow-up command: '$text' whisper=$whisper")
            fire(text, whisper)
            return
        }

        val tokens = text.split(" ").filter { it.isNotBlank() }
        val wt = wakeTokens()
        val wakeIdx = tokens.indexOfLast { it in wt }
        if (wakeIdx < 0) return
        onState?.invoke("heard")

        var rest = tokens.drop(wakeIdx + 1)
        // Drop a trailing wake-word modifier ("garra negra" / "black claw").
        if (rest.firstOrNull() == "negra") rest = rest.drop(1)
        val command = rest.joinToString(" ").trim()

        if (command.isNotEmpty()) {
            XLog.i(TAG, "Wake + command (1 breath): '$command' whisper=$whisper")
            fire(command, whisper)
        } else {
            XLog.i(TAG, "Wake only → awaiting command")
            awaitingCommand = true
            awaitingSince = System.currentTimeMillis()
            val ack = JarvisVoice.wakeAck()
            lastAckWords = ack.lowercase().replace(Regex("[^a-záéíóúñ ]"), "")
                .split(" ").filter { it.length > 2 }.toSet()
            ttsUntilMs = System.currentTimeMillis() + 2800
            if (whisper) Speaker.speakWhisper(ack) else Speaker.speak(ack)
        }
    }

    private fun fire(command: String, whisper: Boolean) {
        onCommand?.let { cb -> main.post { cb(command, whisper) } }
    }

    private fun extractText(hypothesis: String?): String =
        runCatching {
            val o = JSONObject(hypothesis ?: "{}")
            o.optString("text", o.optString("partial", "")).trim()
        }.getOrDefault("")
}
