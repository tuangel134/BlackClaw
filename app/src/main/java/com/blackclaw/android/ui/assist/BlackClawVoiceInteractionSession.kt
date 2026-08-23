package com.blackclaw.android.ui.assist

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.blackclaw.android.utils.XLog

/**
 * Receives Android's pre-overlay AssistStructure/screenshot, then opens the
 * existing QuickAssist conversation surface.  The native session keeps no UI of
 * its own, so it never replaces the app snapshot with a blank assistant window.
 */
class BlackClawVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    // A session can receive assist data very early, so initialize immediately.
    // onPrepareShow refreshes it for subsequent shows of the same session.
    private var invocationId = AssistInvocationContext.beginInvocation()
    private var launchedPanel = false

    override fun onCreate() {
        super.onCreate()
        // onPrepareShow() was added after the app's minSdk. Keep the same
        // contract on older Android releases where only onShow() is delivered.
        setUiEnabled(false)
    }

    override fun onPrepareShow(args: Bundle?, flags: Int) {
        // QuickAssist is the assistant activity, not a voice-activity child
        // rendered underneath a session window. Disabling the session window at
        // this point is the platform-supported contract for that arrangement.
        setUiEnabled(false)
        invocationId = AssistInvocationContext.beginInvocation()
        launchedPanel = false
        super.onPrepareShow(args, flags)
    }

    override fun onShow(args: Bundle?, flags: Int) {
        super.onShow(args, flags)
        // AssistStructure/screenshot callbacks are independent of the activity
        // launch. Open immediately so a power-button invocation cannot be lost
        // while the session is waiting on a timer or gets hidden by the system.
        launchPanel()
    }

    @Deprecated("The platform dispatches this callback for assist invocations on supported Android versions.")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        AssistInvocationContext.recordStructure(invocationId, structure)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        AssistInvocationContext.recordScreenshot(invocationId, screenshot)
    }

    override fun onHide() {
        super.onHide()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun launchPanel() {
        if (launchedPanel) return
        launchedPanel = true
        val intent = Intent(Intent.ACTION_ASSIST).apply {
            component = ComponentName(getContext(), QuickAssistActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching {
            // QuickAssist owns the full-screen UI. startVoiceActivity() places
            // the activity under a voice-interaction layer and is intended for
            // voice child activities, which can leave an assistant panel blank
            // or hidden on newer Android/OEM builds.
            startAssistantActivity(intent)
            hide()
        }.onFailure { error ->
            XLog.w(TAG, "Assistant activity launch failed: ${error.message}; trying direct fallback")
            runCatching {
                getContext().startActivity(intent.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                hide()
            }.onFailure { fallback ->
                XLog.w(TAG, "Direct assistant fallback failed: ${fallback.message}")
            }
        }
    }

    private companion object { const val TAG = "VoiceInteraction" }
}
