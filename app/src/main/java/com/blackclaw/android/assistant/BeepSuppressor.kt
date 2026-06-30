package com.blackclaw.android.assistant

import android.content.Context
import android.media.AudioManager
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.utils.XLog

/**
 * Silences the system's speech-recognition "beep" that plays on every
 * SpeechRecognizer.startListening() call.
 *
 * In the wake-word loop the recognizer is restarted continuously, so the beep
 * would chirp every cycle — very annoying. There's no public API to disable the
 * tone, so the widely-used workaround is to mute the audio stream(s) the beep
 * plays on for the brief listening window, then restore the user's volume.
 *
 * We mute MUSIC + SYSTEM + NOTIFICATION (the beep lives on different streams
 * across OEMs — EMUI/Huawei uses SYSTEM, AOSP uses MUSIC). We snapshot and
 * restore exact volumes so we never leave the phone muted.
 */
object BeepSuppressor {

    private const val TAG = "BeepSuppressor"

    // The recognition beep lives on SYSTEM/NOTIFICATION on most OEMs (incl.
    // EMUI/Huawei). We deliberately DON'T mute STREAM_MUSIC, because TTS replies
    // play there — muting it would make the assistant's voice inaudible.
    private val STREAMS = intArrayOf(
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_NOTIFICATION,
    )

    @Volatile private var muted = false

    private fun am(): AudioManager? =
        ClawApplication.instance.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @Synchronized
    fun mute() {
        if (muted) return
        val audio = am() ?: return
        // Mark muted BEFORE touching streams so a partial failure still lets
        // restore() run and unmute whatever did get muted.
        muted = true
        for (s in STREAMS) {
            runCatching {
                @Suppress("DEPRECATION")
                audio.setStreamMute(s, true)
            }.onFailure { XLog.d(TAG, "mute $s failed: ${it.message}") }
        }
    }

    @Synchronized
    fun restore() {
        val audio = am()
        // Always attempt to unmute every stream, regardless of the flag, so we
        // never leave the device muted (idempotent — setStreamMute(false) is safe).
        if (audio != null) {
            for (s in STREAMS) {
                runCatching {
                    @Suppress("DEPRECATION")
                    audio.setStreamMute(s, false)
                }.onFailure { XLog.d(TAG, "restore $s failed: ${it.message}") }
            }
        }
        muted = false
    }
}
