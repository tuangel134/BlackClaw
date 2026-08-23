package com.blackclaw.android.ui.assist

import android.content.Intent
import android.service.voice.VoiceInteractionService
import com.blackclaw.android.utils.XLog

/** Android's default-assistant entry point. The actual UI belongs to its session. */
class BlackClawVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        XLog.i(TAG, "BlackClaw is ready as the system assistant")
    }

    override fun onShowSessionFailed(args: android.os.Bundle) {
        super.onShowSessionFailed(args)
        XLog.w(TAG, "System could not show the assistant session")
        // Some OEM builds fail to bind the session service even though the
        // VoiceInteractionService is active. The assistant must still be
        // usable: launch the same lock-screen-safe panel directly instead of
        // leaving the power-button gesture with no visible result.
        launchQuickAssist("session-failed")
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        launchQuickAssist("keyguard")
    }

    private fun launchQuickAssist(source: String) {
        val intent = Intent(this, QuickAssistActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        runCatching { startActivity(intent) }
            .onFailure { XLog.w(TAG, "Could not launch assistant ($source): ${it.message}") }
    }

    private companion object {
        const val TAG = "VoiceInteraction"
    }
}
