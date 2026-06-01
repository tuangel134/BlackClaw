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
        // Two channels: a normal one and a HIGH one. Channel importance is fixed
        // at creation time on Android O+ (you can't upgrade it later), so we use
        // a dedicated high-importance channel for alarms/alerts so they pop as
        // heads-up with sound instead of arriving silently.
        private const val CHANNEL_ID = "blackclaw_assistant_v2"
        private const val CHANNEL_ID_HIGH = "blackclaw_assistant_high_v2"

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
            val channelId = if (highPriority) CHANNEL_ID_HIGH else CHANNEL_ID
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (highPriority) {
                    val ch = NotificationChannel(
                        CHANNEL_ID_HIGH, "Asistente · Alarmas y avisos",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "Alarmas y avisos importantes del asistente"
                        enableVibration(true)
                    }
                    nm.createNotificationChannel(ch)
                } else {
                    val ch = NotificationChannel(
                        CHANNEL_ID, "Asistente · Recordatorios",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { description = "Recordatorios y notas del asistente" }
                    nm.createNotificationChannel(ch)
                }
            }
            val n = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(if (highPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(if (highPriority) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
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

        // Alarms ring full-screen (sound loop + vibration + dismiss/snooze),
        // not a passive notification.
        if (item.type == AssistantItemType.ALARM) {
            ringAlarm(context, item)
            rescheduleIfRepeating(context, item)
            return
        }

        val emoji = when (item.type) {
            AssistantItemType.REMINDER -> "🔔"
            AssistantItemType.EVENT -> "📅"
            AssistantItemType.ALERT -> "📢"
            else -> "🐾"
        }
        val title = "$emoji ${item.title}"
        val body = item.body.ifBlank {
            when (item.type) {
                AssistantItemType.EVENT -> "Evento ahora"
                else -> "Recordatorio"
            }
        }
        val high = item.type == AssistantItemType.ALERT || item.type == AssistantItemType.REMINDER
        postNotification(context, title, body, high)
        XLog.i(TAG, "Fired assistant ${item.type} '${item.title}'")

        rescheduleIfRepeating(context, item)
    }

    private fun rescheduleIfRepeating(context: Context, item: AssistantItem) {
        val next = nextOccurrence(item)
        if (next != null) {
            val updated = item.copy(triggerAtMs = next)
            AssistantStore.upsert(updated)
            AssistantScheduler.arm(context, updated)
        }
    }

    /**
     * Fire a real alarm. We BOTH start the full-screen activity AND post a
     * full-screen-intent notification. On Android 10+ background activity
     * starts can be blocked, in which case the full-screen-intent notification
     * is what surfaces the ringing UI — so we always have a path that works.
     */
    private fun ringAlarm(context: Context, item: AssistantItem) {
        XLog.i(TAG, "Ringing ALARM '${item.title}'")
        val ringIntent = Intent(context, AlarmRingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AlarmRingActivity.EXTRA_TITLE, item.title)
            putExtra(AlarmRingActivity.EXTRA_ITEM_ID, item.id)
        }
        // Try direct launch first (works when app recently foregrounded / has perm).
        runCatching { context.startActivity(ringIntent) }
            .onFailure { XLog.w(TAG, "Direct alarm activity launch failed: ${it.message}") }

        // Always post a full-screen-intent notification as the reliable path.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID_HIGH, "Asistente · Alarmas y avisos",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Alarmas y avisos importantes"; enableVibration(true) }
            nm.createNotificationChannel(ch)
        }
        val fsPending = PendingIntent.getActivity(
            context, item.id.hashCode(), ringIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID_HIGH)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("⏰ ${item.title}")
            .setContentText("Alarma")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fsPending, true)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()
        nm.notify(item.id.hashCode(), n)
    }

    private fun nextOccurrence(item: AssistantItem): Long? {
        val cal = Calendar.getInstance().apply { timeInMillis = item.triggerAtMs }
        when (item.repeat.lowercase()) {
            "daily" -> cal.add(Calendar.DAY_OF_YEAR, 1)
            "weekly" -> cal.add(Calendar.WEEK_OF_YEAR, 1)
            "monthly" -> cal.add(Calendar.MONTH, 1)
            else -> return null
        }
        // Skip past slots if the device was off.
        val now = System.currentTimeMillis()
        while (cal.timeInMillis <= now) {
            when (item.repeat.lowercase()) {
                "daily" -> cal.add(Calendar.DAY_OF_YEAR, 1)
                "weekly" -> cal.add(Calendar.WEEK_OF_YEAR, 1)
                "monthly" -> cal.add(Calendar.MONTH, 1)
            }
        }
        return cal.timeInMillis
    }
}
