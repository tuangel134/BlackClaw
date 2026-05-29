package com.blackclaw.android.tool.impl

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.R
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import kotlin.random.Random

/**
 * Post a notification from the agent. Useful for reminders, async alerts,
 * or "ping me when X". Lower priority than scheduled tasks but cheaper.
 */
class SystemNotifyTool : BaseTool() {

    companion object {
        private const val CHANNEL_ID = "blackclaw_agent_alerts"
    }

    override fun getName() = "system_notify"
    override fun getDisplayName() = "Notificar"
    override fun getDescriptionEN() =
        "Show a system notification (heads-up). Use for reminders or to surface " +
        "information from a long-running task. NOT for chat replies."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("title", "string", "Notification title.", true),
        ToolParameter("body", "string", "Notification body / message.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val title = requireString(params, "title")
        val body = requireString(params, "body")
        val ctx = ClawApplication.instance
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alertas del agente",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Mensajes y recordatorios del agente BlackClaw" }
            nm.createNotificationChannel(channel)
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        return try {
            nm.notify(Random.nextInt(1, Int.MAX_VALUE), n)
            ToolResult.success("Notificación enviada: $title")
        } catch (e: SecurityException) {
            ToolResult.error("Falta el permiso POST_NOTIFICATIONS. Concédelo en Ajustes.")
        }
    }
}
