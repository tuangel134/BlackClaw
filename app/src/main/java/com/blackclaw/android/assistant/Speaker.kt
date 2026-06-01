package com.blackclaw.android.assistant

import android.speech.tts.TextToSpeech
import com.blackclaw.android.ClawApplication
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

    @Volatile private var engine: TextToSpeech? = null
    private val ready = AtomicBoolean(false)

    @Synchronized
    private fun engine(): TextToSpeech? {
        engine?.let { return it }
        return try {
            val tts = TextToSpeech(ClawApplication.instance) { status ->
                ready.set(status == TextToSpeech.SUCCESS)
                if (status == TextToSpeech.SUCCESS) {
                    runCatching {
                        val es = Locale("es", "ES")
                        val r = engine?.setLanguage(es)
                        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            engine?.setLanguage(Locale.getDefault())
                        }
                    }
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

    /** Speak [text] aloud. [flush] true interrupts current speech. */
    fun speak(text: String, flush: Boolean = true) {
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
            tts.speak(clean.take(4000), mode, null, "blackclaw-${UUID.randomUUID()}")
        }.onFailure { XLog.w(TAG, "speak failed: ${it.message}") }
    }

    fun stop() {
        runCatching { engine?.stop() }
    }

    fun isSpeaking(): Boolean = runCatching { engine?.isSpeaking == true }.getOrDefault(false)
}
