package com.blackclaw.android.tool.impl

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.service.ClawNotificationListener
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Reply to a notification directly through its inline reply action (RemoteInput)
 * — the same mechanism the system uses for quick-reply. No need to open the app
 * or drive the UI. Works for WhatsApp, Telegram, Messages, etc. when the
 * notification exposes a reply action.
 */
class ReplyNotificationTool : BaseTool() {
    override fun getName() = "reply_notification"
    override fun getDisplayName() = "Responder notificación"
    override fun getDescriptionEN() =
        "Reply to an active notification inline via its quick-reply action, without opening the app. " +
        "Match the notification by 'from' (sender or app name shown in the notification) and provide " +
        "'message'. Best for replying to a chat message you just received. Requires Notification Access."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "responde una notificación directo (quick-reply) sin abrir la app"
    override fun getParameters() = listOf(
        ToolParameter("from", "string", "Sender/app text to match the notification (e.g. 'Mamá', 'WhatsApp').", true),
        ToolParameter("message", "string", "The reply text to send.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        if (!ClawNotificationListener.isConnected()) {
            return ToolResult.error("Acceso a notificaciones desactivado. Actívalo en Ajustes → Acceso a notificaciones.")
        }
        val from = requireString(params, "from").trim().lowercase()
        val message = requireString(params, "message").trim()
        if (message.isEmpty()) return ToolResult.error("El mensaje está vacío.")

        val notes = ClawNotificationListener.getActiveNotificationList()
            ?: return ToolResult.error("No pude leer las notificaciones.")
        val ctx = ClawApplication.instance
        val pm = ctx.packageManager

        // Score notifications by how well they match `from` (title, text, app label).
        var bestAction: android.app.Notification.Action? = null
        var bestLabel = ""
        var bestScore = -1
        for (sbn in notes) {
            val n = sbn.notification ?: continue
            val extras = n.extras ?: continue
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
            val appLabel = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
            }.getOrDefault(sbn.packageName)
            val hay = "$title $appLabel $text".lowercase()
            if (!hay.contains(from)) continue
            // Find a reply action (has RemoteInput).
            val replyAction = findReplyAction(n) ?: continue
            // Prefer title match over app-only match.
            val score = (if (title.lowercase().contains(from)) 2 else 0) +
                (if (appLabel.lowercase().contains(from)) 1 else 0)
            if (score > bestScore) {
                bestScore = score; bestAction = replyAction; bestLabel = title.ifBlank { appLabel }
            }
        }

        val action = bestAction
            ?: return ToolResult.error(
                "No encontré una notificación de '$from' con opción de responder. " +
                "Puede que no tenga respuesta rápida; abre la app con open_app_action y responde ahí.")

        return try {
            val remoteInputs = action.remoteInputs ?: emptyArray()
            val intent = Intent()
            val results = Bundle()
            for (ri in remoteInputs) results.putCharSequence(ri.resultKey, message)
            RemoteInput.addResultsToIntent(remoteInputs, intent, results)
            action.actionIntent.send(ctx, 0, intent)
            ToolResult.success("Respondí a '$bestLabel': \"$message\".")
        } catch (e: Exception) {
            ToolResult.error("No pude enviar la respuesta: ${e.message}")
        }
    }

    /** First action that carries a RemoteInput (i.e. an inline reply). */
    private fun findReplyAction(n: android.app.Notification): android.app.Notification.Action? {
        val actions = n.actions ?: return null
        return actions.firstOrNull { a ->
            val ris = a.remoteInputs
            ris != null && ris.isNotEmpty()
        }
    }
}
