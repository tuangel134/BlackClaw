package com.blackclaw.android.automation

import android.content.Context
import android.content.Intent
import com.blackclaw.android.appViewModel
import com.blackclaw.android.ui.chat.ComposeChatActivity
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog

object ExternalAutomationEntrypoint {
    private const val TAG = "ExternalAutomation"

    fun handle(context: Context, intent: Intent?, launchFlags: Int): Boolean {
        if (intent == null) return false
        if (!isTargetedToBlackClaw(context, intent)) {
            XLog.w(TAG, "Rejected non-targeted external automation request: ${intent.action}")
            return false
        }

        val request = ExternalAutomationContract.parse(intent.action) { key ->
            intent.getStringExtra(key)
        }
        if (request == null) {
            rejectFromIntent(context, intent, "Missing task/chat payload")
            return false
        }

        if (!KVUtils.isExternalAutomationEnabled()) {
            XLog.w(TAG, "External automation rejected because the user setting is disabled")
            ExternalAutomationContract.sendCallback(
                context = context,
                returnAction = request.returnAction,
                requestId = request.requestId,
                status = ExternalAutomationContract.STATUS_REJECTED,
                error = "External Automation is disabled in BlackClaw Settings.",
                returnPackage = request.returnPackage,
                mode = request.mode,
            )
            return false
        }

        if (appViewModel.isTaskRunning()) {
            ExternalAutomationContract.sendCallback(
                context = context,
                returnAction = request.returnAction,
                requestId = request.requestId,
                status = ExternalAutomationContract.STATUS_REJECTED,
                error = "Another BlackClaw task is already running.",
                returnPackage = request.returnPackage,
                mode = request.mode,
            )
            return false
        }

        XLog.i(TAG, "Accepted external automation mode=${request.mode} chars=${request.text.length}")
        ExternalAutomationContract.sendCallback(
            context = context,
            returnAction = request.returnAction,
            requestId = request.requestId,
            status = ExternalAutomationContract.STATUS_ACCEPTED,
            returnPackage = request.returnPackage,
            mode = request.mode,
        )

        val launch = Intent(context, ComposeChatActivity::class.java).apply {
            when (request.mode) {
                ExternalAutomationContract.Mode.TASK -> putExtra(EXTRA_TASK, request.text)
                ExternalAutomationContract.Mode.CHAT -> putExtra(EXTRA_CHAT, request.text)
            }
            putExtra(EXTRA_EXTERNAL_REQUEST_ID, request.requestId)
            putExtra(EXTRA_EXTERNAL_RETURN_ACTION, request.returnAction)
            putExtra(EXTRA_EXTERNAL_RETURN_PACKAGE, request.returnPackage)
            flags = launchFlags
        }
        context.startActivity(launch)
        return true
    }

    /**
     * Whether the intent was addressed to us explicitly, i.e. it names BlackClaw as
     * its DESTINATION rather than relying on implicit action matching.
     *
     * ## This is NOT an access control
     *
     * It validates destination, never origin. Both fields it reads — `component` and
     * `package` — are set by whoever built the intent, so any app on the device
     * satisfies this check with a single line:
     *
     *     intent.setPackage("com.blackclaw.android")
     *
     * The only thing it buys is filtering out intents that merely happened to match
     * our action filter, which is hygiene, not security. Authorisation lives in the
     * signature permission used by the broadcast receiver and in
     * [AutomationCallerPolicy]/[AutomationToken] for the exported activity. Do not add trust to
     * this function; there is nothing trustworthy in an intent's own description of
     * where it is headed.
     */
    private fun isTargetedToBlackClaw(context: Context, intent: Intent): Boolean {
        val packageName = context.packageName
        val component = intent.component
        return component?.packageName == packageName || intent.`package` == packageName
    }

    private fun rejectFromIntent(context: Context, intent: Intent, error: String) {
        ExternalAutomationContract.sendCallback(
            context = context,
            returnAction = intent.getStringExtra(ExternalAutomationContract.EXTRA_RETURN_ACTION),
            requestId = intent.getStringExtra(ExternalAutomationContract.EXTRA_REQUEST_ID),
            status = ExternalAutomationContract.STATUS_REJECTED,
            error = error,
            returnPackage = intent.getStringExtra(ExternalAutomationContract.EXTRA_RETURN_PACKAGE),
        )
    }

    const val EXTRA_TASK = "task"
    const val EXTRA_CHAT = "chat"
    const val EXTRA_EXTERNAL_REQUEST_ID = "external_request_id"
    const val EXTRA_EXTERNAL_RETURN_ACTION = "external_return_action"
    const val EXTRA_EXTERNAL_RETURN_PACKAGE = "external_return_package"
}
