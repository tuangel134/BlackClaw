package com.blackclaw.android.ui.assist

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import com.blackclaw.android.utils.XLog

/**
 * Receives Android's pre-overlay AssistStructure/screenshot, then opens the
 * existing QuickAssist conversation surface.  The native session keeps no UI of
 * its own, so it never replaces the app snapshot with a blank assistant window.
 */
class BlackClawVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    // A session can receive assist data very early, so initialize immediately.
    // onPrepareShow refreshes it for subsequent shows of the same session.
    private var invocationId = AssistInvocationContext.beginInvocation()
    private var launchedPanel = false

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onPrepareShow(args: Bundle?, flags: Int) {
        invocationId = AssistInvocationContext.beginInvocation()
        launchedPanel = false
        super.onPrepareShow(args, flags)
    }

    override fun onShow(args: Bundle?, flags: Int) {
        super.onShow(args, flags)
        // Give the system a brief turn to deliver onHandleAssist/onHandleScreenshot.
        // QuickAssist can still open immediately and its screen question consumes any
        // screenshot OCR that finishes a moment later.
        mainHandler.postDelayed(::launchPanel, PANEL_LAUNCH_DELAY_MS)
    }

    @Deprecated("The platform dispatches this callback for assist invocations on supported Android versions.")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        AssistInvocationContext.recordStructure(invocationId, structure)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        AssistInvocationContext.recordScreenshot(invocationId, screenshot)
    }

    override fun onHide() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onHide()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun launchPanel() {
        if (launchedPanel) return
        launchedPanel = true
        val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
            component = ComponentName(getContext(), QuickAssistActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching {
            startVoiceActivity(intent)
            hide()
        }.onFailure { error ->
            XLog.w(TAG, "Could not launch QuickAssist from voice session: ${error.message}")
        }
    }

    private companion object {
        const val TAG = "VoiceInteraction"
        const val PANEL_LAUNCH_DELAY_MS = 140L
    }
}
