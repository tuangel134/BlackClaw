package com.blackclaw.android.automation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog

/**
 * Activity entrypoint for external automation.
 *
 * This is the one automation entrypoint where the framework may tell us who the
 * caller is, so it is where third-party authorization is enforced. Unlike the
 * broadcast receiver, this Activity intentionally cannot require BlackClaw's
 * signature permission because Tasker/MacroDroid are signed by somebody else.
 * Therefore this in-process check is the security boundary: self-package, an
 * identified allowlisted caller, or possession of the Automation Token.
 */
class ExternalAutomationActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (!isCallerAuthorized(intent)) {
            finish()
            return
        }
        ExternalAutomationEntrypoint.handle(
            context = this,
            intent = intent,
            launchFlags = Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        finish()
    }

    /**
     * Origin check. [getCallingPackage] is the framework's own answer, not something
     * the caller can set — unlike the intent contents, which are entirely
     * attacker-controlled. It is null unless we were started with
     * `startActivityForResult`, and [AutomationCallerPolicy] decides what that means.
     */
    private fun isCallerAuthorized(intent: Intent?): Boolean {
        val caller = callingPackage
        // A valid token is how a third-party app proves consent. It has to work
        // without caller identity, because getCallingPackage() is null for the plain
        // startActivity that Tasker and `am start` use.
        val tokenPresented = AutomationToken.matches(
            intent?.getStringExtra(AutomationToken.EXTRA_TOKEN)
        )
        val decision = AutomationCallerPolicy.decide(
            callingPackage = caller,
            selfPackage = packageName,
            allowlist = KVUtils.getExternalAutomationAllowedCallers(),
            tokenPresented = tokenPresented,
        )
        if (decision == AutomationCallerPolicy.Decision.ALLOW) return true

        val message = AutomationCallerPolicy.denialMessage(decision, caller)
        XLog.w(TAG, "Rejected automation request from '${caller ?: "<unknown>"}': $decision")
        // Tell the caller why, so a legitimate-but-unlisted automation app surfaces a
        // usable error instead of silently doing nothing. The return action/package
        // come from the caller's own intent, so this leaks nothing it did not supply.
        ExternalAutomationContract.sendCallback(
            context = this,
            returnAction = intent?.getStringExtra(ExternalAutomationContract.EXTRA_RETURN_ACTION),
            requestId = intent?.getStringExtra(ExternalAutomationContract.EXTRA_REQUEST_ID),
            status = ExternalAutomationContract.STATUS_REJECTED,
            error = message,
            returnPackage = intent?.getStringExtra(ExternalAutomationContract.EXTRA_RETURN_PACKAGE),
        )
        return false
    }

    private companion object {
        const val TAG = "ExternalAutomation"
    }
}
