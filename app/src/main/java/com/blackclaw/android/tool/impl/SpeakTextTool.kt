package com.blackclaw.android.tool.impl

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import com.blackclaw.android.utils.XLog
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speak text aloud via Android's TextToSpeech engine. Useful for hands-free
 * confirmation, accessibility, or driving / cooking flows where the user
 * shouldn't look at the screen.
 *
 * Uses a process-wide TTS engine that initialises lazily on first call and
 * stays warm for follow-up calls.
 */
class SpeakTextTool : BaseTool() {

    companion object {
        private const val TAG = "SpeakTextTool"

        @Volatile
        private var engine: TextToSpeech? = null
        private val engineReady = AtomicBoolean(false)

        @Synchronized
        private fun getEngine(): TextToSpeech? {
            engine?.let { return it }
            val ctx = ClawApplication.instance
            return try {
                val tts = TextToSpeech(ctx) { status ->
                    engineReady.set(status == TextToSpeech.SUCCESS)
                    if (status != TextToSpeech.SUCCESS) {
                        XLog.w(TAG, "TTS init failed: $status")
                    }
                }
                engine = tts
                tts
            } catch (e: Exception) {
                XLog.e(TAG, "TTS engine creation failed", e)
                null
            }
        }
    }

    override fun getName() = "speak_text"
    override fun getDisplayName() = "Speak"
    override fun getDescriptionEN() =
        "Speak the given text aloud through the device speaker. " +
        "Use for hands-free confirmation (driving, cooking) or when the user explicitly asks 'say X out loud'. " +
        "Optional 'language' is a BCP-47 tag like 'en-US', 'es-ES'."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("text", "string", "Text to speak.", true),
        ToolParameter("language", "string", "Optional BCP-47 language tag, e.g. en-US, es-ES.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val text = requireString(params, "text").trim()
        if (text.isEmpty()) return ToolResult.error("text cannot be empty")
        val language = optionalString(params, "language", "")

        val tts = getEngine() ?: return ToolResult.error("TTS engine not available on this device")

        // Wait briefly for init if this is the first call
        val deadline = System.currentTimeMillis() + 2_500L
        while (!engineReady.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        if (!engineReady.get()) {
            return ToolResult.error("TTS engine did not initialise in time. Try again.")
        }

        if (language.isNotBlank()) {
            try {
                val locale = Locale.forLanguageTag(language)
                val res = tts.setLanguage(locale)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    XLog.w(TAG, "TTS language $language not supported, falling back to default")
                }
            } catch (e: Exception) {
                XLog.w(TAG, "Bad language tag '$language'", e)
            }
        }

        val utteranceId = "blackclaw-${UUID.randomUUID()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {}
            @Deprecated("legacy api")
            override fun onError(utteranceId: String?) {}
        })

        val truncated = if (text.length > 4_000) text.take(4_000) else text
        return try {
            val rc = tts.speak(truncated, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (rc == TextToSpeech.SUCCESS) {
                ToolResult.success("Speaking ${truncated.length} chars.")
            } else {
                ToolResult.error("TTS rejected the request (code $rc)")
            }
        } catch (e: Exception) {
            ToolResult.error("TTS failed: ${e.message}")
        }
    }
}
