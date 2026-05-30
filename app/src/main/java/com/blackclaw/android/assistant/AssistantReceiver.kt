package com.blackclaw.android.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.blackclaw.android.R
import com.blackclaw.android.utils.XLog
import java.util.Calendar
import kotlin.random.Random

/**
 * Fires when an assistant reminder/alarm/event is due. Posts a native push
 * notification from BlackClaw itself, then either removes the one-shot item or
 * re-arms it for the next occurrence if it repeats.
 */
class AssistantReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.blackclaw.android.ASSISTANT_FIRE"
        private const val TAG = "AssistantReceiver"
        private const val CHANNEL_ID = "blackclaw_assistant"

        /** Tapping the notification opens the Assistant hub. */
        private fun contentIntent(context: Context): PendingIntent {
            val open = Intent().setClassName(
                context.packageName,
                "com.blackclaw.android.ui.assistant.AssistantActivity",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun postNotification(context: Context, title: String, body: String, highPriority: Boolean) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "Asistente BlackClaw",
                    if (highPriority) NotificationManager.IMPORTANCE_HIGH
                    else NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Recordatorios, alarmas y avisos del asistente" }
                nm.createNotificationChannel(ch)
            }
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(if (highPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(contentIntent(context))
                .build()
            nm.notify(Random.nextInt(1, Int.MAX_VALUE), n)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.getStringExtra(AssistantScheduler.EXTRA_ITEM_ID) ?: return
        val item = AssistantStore.find(id) ?: run {
            XLog.w(TAG, "Fire for unknown item $id"); return
        }

        val emoji = when (item.type) {
            AssistantItemType.ALARM -> "⏰"
            AssistantItemType.REMINDER -> "🔔"
            AssistantItemType.EVENT -> "📅"
            AssistantItemType.ALERT -> "📢"
            else -> "🐾"
        }
        val title = "$emoji ${item.title}"
        val body = item.body.ifBlank {
            when (item.type) {
                AssistantItemType.ALARM -> "Alarma"
                AssistantItemType.EVENT -> "Evento ahora"
                else -> "Recordatorio"
            }
        }
        val high = item.type == AssistantItemType.ALARM || item.type == AssistantItemType.ALERT
        postNotification(context, title, body, high)
        XLog.i(TAG, "Fired assistant ${item.type} '${item.title}'")

        // Repeat handling.
        val next = nextOccurrence(item)
        if (next != null) {
            val updated = item.copy(triggerAtMs = next)
            AssistantStore.upsert(updated)
            AssistantScheduler.arm(context, updated)
        }
        // One-shot items stay in the list (marked by time in the past) so the
        // user still sees history; they just won't re-fire.
    }

    private fun nextOccurrence(item: AssistantItem): Long? {
        val cal = Calendar.getInstance().apply { timeInMillis = item.triggerAtMs }
        when (item.repeat.lowercase()) {
            "daily" -> cal.add(Calendar.DAY_OF_YEAR, 1)
            "weekly" -> cal.add(Calendar.WEEK_OF_YEAR, 1)
            else -> return null
        }
        // Skip past slots if the device was off.
        val now = System.currentTimeMillis()
        while (cal.timeInMillis <= now) {
            when (item.repeat.lowercase()) {
                "daily" -> cal.add(Calendar.DAY_OF_YEAR, 1)
                "weekly" -> cal.add(Calendar.WEEK_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }
}
