package com.blackclaw.android.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blackclaw.android.automation.AutomationEngine
import com.blackclaw.android.utils.KVUtils
import org.json.JSONObject
import java.util.UUID

/** Handles actionable Sí/No buttons without requiring the user to open BlackClaw. */
class AssistantDecisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val decision = AssistantDecisionStore.take(id) ?: return
        com.blackclaw.android.service.VoiceWakeService.decisionHandled(id)
        val accepted = intent.action == ACTION_YES
        if (accepted) {
            AutomationEngine.executeTask(context, decision.task, "confirmation:$id")
            AssistantReceiver.postNotification(context, "✓ Confirmado", decision.title, false)
        } else {
            AssistantReceiver.postNotification(context, "Cancelado", decision.title, false)
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .cancel(id.hashCode())
    }

    companion object {
        const val ACTION_YES = "com.blackclaw.android.DECISION_YES"
        const val ACTION_NO = "com.blackclaw.android.DECISION_NO"
        const val EXTRA_ID = "decision_id"
    }
}

object AssistantDecisionStore {
    private const val PREFIX = "assistant_decision_"
    data class Decision(val title: String, val task: String, val expiresAt: Long)

    fun put(title: String, task: String): String {
        val id = UUID.randomUUID().toString().take(8)
        KVUtils.putString(PREFIX + id, JSONObject().apply {
            put("title", title); put("task", task); put("expires", System.currentTimeMillis() + 24 * 60 * 60_000L)
        }.toString()); KVUtils.sync()
        return id
    }

    fun take(id: String): Decision? {
        val key = PREFIX + id
        val raw = KVUtils.getString(key, "")
        KVUtils.remove(key); KVUtils.sync()
        if (raw.isBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            Decision(o.optString("title"), o.optString("task"), o.optLong("expires"))
        }.getOrNull()?.takeIf { it.expiresAt >= System.currentTimeMillis() }
    }

    fun discard(id: String) {
        KVUtils.remove(PREFIX + id); KVUtils.sync()
    }
}
