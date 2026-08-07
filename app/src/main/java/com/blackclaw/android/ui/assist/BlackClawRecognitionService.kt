package com.blackclaw.android.ui.assist

import android.content.Intent
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Compatibility declaration required by the VoiceInteractionService contract on
 * Android 11 and older. QuickAssist owns interactive speech recognition through
 * VoiceInputManager, so framework requests are explicitly ended instead of
 * competing for the microphone with the visible assistant.
 */
class BlackClawRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, callback: Callback?) {
        reportUnavailable(callback)
    }

    override fun onStopListening(callback: Callback?) = Unit

    override fun onCancel(callback: Callback?) = Unit

    private fun reportUnavailable(callback: Callback?) {
        try {
            callback?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (_: RemoteException) {
            // Caller left before receiving the terminal result.
        }
    }
}
