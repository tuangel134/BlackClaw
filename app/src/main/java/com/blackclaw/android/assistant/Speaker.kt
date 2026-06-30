package com.blackclaw.android.assistant

import android.speech.tts.TextToSpeech
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central text-to-speech helper. Any part of the app can call [Speaker.speak]
 * to read text aloud — used by spoken briefings, the challenge alarm, and the
 * speak_text tool.
 *
 * A single process-wide TextToSpeech engine is created lazily and kept warm.
 * Prefers a Spanish voice (the app UI is Spanish) but falls back to the device
 * default if Spanish isn't installed. Fails silently if no TTS engine exists.
 */
object Speaker {
    private const val TAG = "Speaker"
    private const val KEY_VOICE = "tts_voice_name"
    private const val KEY_RATE = "tts_rate"
    private const val KEY_PITCH = "tts_pitch"

    @Volatile private var engine: TextToSpeech? = null
    private val ready = AtomicBoolean(false)

    var rate: Float
        get() = KVUtils.getFloat(KEY_RATE, 1.0f)
        set(v) { KVUtils.putFloat(KEY_RATE, v); KVUtils.sync(); applyPreferences() }
    var pitch: Float
        get() = KVUtils.getFloat(KEY_PITCH, 1.0f)
        set(v) { KVUtils.putFloat(KEY_PITCH, v); KVUtils.sync(); applyPreferences() }
    var voiceName: String
        get() = KVUtils.getString(KEY_VOICE, "")
        set(v) { KVUtils.putString(KEY_VOICE, v); KVUtils.sync(); applyPreferences() }

    @Synchronized
    private fun engine(): TextToSpeech? {
        engine?.let { return it }
        return try {
            val tts = TextToSpeech(ClawApplication.instance) { status ->
                ready.set(status == TextToSpeech.SUCCESS)
                if (status == TextToSpeech.SUCCESS) {
                    applyPreferences()
                } else {
                    XLog.w(TAG, "TTS init failed: $status")
                }
            }
            engine = tts
            tts
        } catch (e: Exception) {
            XLog.e(TAG, "TTS create failed", e)
            null
        }
    }

    /** Apply saved locale/voice/rate/pitch to the live engine. */
    fun applyPreferences() {
        val tts = engine ?: return
        runCatching {
            tts.setSpeechRate(KVUtils.getFloat(KEY_RATE, 1.0f).coerceIn(0.5f, 2.0f))
            tts.setPitch(KVUtils.getFloat(KEY_PITCH, 1.0f).coerceIn(0.5f, 2.0f))
            val voiceName = KVUtils.getString(KEY_VOICE, "")
            if (voiceName.isNotBlank()) {
                val v = tts.voices?.firstOrNull { it.name == voiceName }
                if (v != null) { tts.voice = v; return@runCatching }
            }
            // No saved voice → prefer Spanish locale.
            val es = Locale("es", "ES")
            val r = tts.setLanguage(es)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault())
            }
        }
    }

    /** Spanish (and a few common) voices available on this device's TTS engine. */
    fun availableSpanishVoices(): List<android.speech.tts.Voice> {
        val tts = engine() ?: return emptyList()
        val deadline = System.currentTimeMillis() + 2_000L
        while (!ready.get() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { return emptyList() }
        }
        return runCatching {
            tts.voices?.filter { it.locale.language == "es" }
                ?.sortedBy { it.name } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** Speak [text] aloud. [flush] true interrupts current speech. */
    fun speak(text: String, flush: Boolean = true) {
        speakInternal(text, flush, whisper = false)
    }

    /** Speak softly — quieter, lower pitch, slightly slower (whisper-back). */
    fun speakWhisper(text: String, flush: Boolean = true) {
        speakInternal(text, flush, whisper = true)
    }

    private fun speakInternal(text: String, flush: Boolean, whisper: Boolean) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val tts = engine() ?: return
        val deadline = System.currentTimeMillis() + 2_500L
        while (!ready.get() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { return }
        }
        if (!ready.get()) { XLog.w(TAG, "TTS not ready, skipping speak"); return }
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        runCatching {
            if (whisper) {
                // Softer voice: lower pitch + slightly slower. Volume is set
                // per-utterance via KEY_PARAM_VOLUME so we don't touch system volume.
                tts.setPitch((KVUtils.getFloat(KEY_PITCH, 1.0f) * 0.85f).coerceIn(0.5f, 2.0f))
                tts.setSpeechRate((KVUtils.getFloat(KEY_RATE, 1.0f) * 0.92f).coerceIn(0.5f, 2.0f))
                val params = android.os.Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.30f)
                }
                tts.speak(clean.take(4000), mode, params, "blackclaw-w-${UUID.randomUUID()}")
                // Restore normal pitch/rate for subsequent (non-whisper) speech.
                applyPreferences()
            } else {
                tts.speak(clean.take(4000), mode, null, "blackclaw-${UUID.randomUUID()}")
            }
        }.onFailure { XLog.w(TAG, "speak failed: ${it.message}") }
    }

    fun stop() {
        runCatching { engine?.stop() }
    }

    fun isSpeaking(): Boolean = runCatching { engine?.isSpeaking == true }.getOrDefault(false)
}
