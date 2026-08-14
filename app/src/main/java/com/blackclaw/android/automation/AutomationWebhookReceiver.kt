package com.blackclaw.android.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blackclaw.android.utils.XLog

/**
 * External entry point for Tasker-like apps.
 *
 * Tasker can send an explicit broadcast to this package with [EXTRA_TOKEN]. The
 * profile token is the shared secret; the event engine still applies enabled state,
 * conditions, cooldown, concurrency and daily/runtime limits. No payload is logged.
 */
class AutomationWebhookReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val token = intent.getStringExtra(EXTRA_TOKEN)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val payload = intent.getStringExtra(EXTRA_PAYLOAD).orEmpty().take(4_000)
        val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty().take(120)
        runCatching {
            AutomationProfileEngine.emitSystemEvent(
                context.applicationContext,
                AutomationProfileStore.TriggerType.WEBHOOK,
                buildMap {
                    put("token", token)
                    if (payload.isNotBlank()) put("payload", payload)
                    if (source.isNotBlank()) put("source", source)
                },
            )
        }.onFailure { XLog.w(TAG, "Webhook event could not be dispatched", it) }
    }

    companion object {
        const val ACTION = "com.blackclaw.android.AUTOMATION_WEBHOOK"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_SOURCE = "source"
        private const val TAG = "AutomationWebhookReceiver"
    }
}
