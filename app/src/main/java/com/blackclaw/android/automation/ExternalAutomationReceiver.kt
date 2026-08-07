package com.blackclaw.android.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Production entrypoint for external automation apps.
 *
 * Example:
 * adb shell am broadcast \
 *   -a com.blackclaw.android.RUN_TASK \
 *   -p com.blackclaw.android \
 *   --es task "Summarize my notifications"
 *
 * ## No caller identity is available here
 *
 * A BroadcastReceiver is never told who sent the broadcast. [onReceive] gets the
 * intent and nothing else: there is no `getCallingPackage()`, no sender uid, and
 * `Intent.getPackage()` / `getComponent()` describe the DESTINATION the sender chose,
 * which the sender can set to anything. So the per-caller allowlist that
 * [ExternalAutomationActivity] enforces via [AutomationCallerPolicy] cannot be
 * applied on this path — there is simply nothing to compare against.
 *
 * That makes the manifest's `android:permission="com.blackclaw.android.permission.
 * AUTOMATION"` (protectionLevel `signature`) the entire access control for this
 * component, not the first of two layers. It is enforced by the system before this
 * process is woken, so it holds regardless of what the intent contains — but the
 * consequence is that authorisation here is all-or-nothing per signing key. If you
 * ever need to distinguish callers on the broadcast path, the shape that works is a
 * bound Service (where `Binder.getCallingUid()` is trustworthy), not a receiver.
 */
class ExternalAutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        ExternalAutomationEntrypoint.handle(
            context = context,
            intent = intent,
            launchFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
    }
}
