package com.blackclaw.android.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.blackclaw.android.utils.XLog

/**
 * Arms native [AlarmManager] alarms for time-based assistant items (reminders,
 * alarms, events). When an alarm fires, [AssistantReceiver] posts a native
 * BlackClaw push notification — no external Clock/Calendar app involved.
 */
object AssistantScheduler {

    private const val TAG = "AssistantScheduler"
    const val EXTRA_ITEM_ID = "assistant_item_id"

    fun arm(context: Context, item: AssistantItem) {
        if (item.triggerAtMs <= 0L) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, item.id)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerAtMs, pi)
                XLog.w(TAG, "Exact alarms not permitted; inexact for ${item.id}")
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerAtMs, pi)
            }
            XLog.i(TAG, "Armed ${item.type} '${item.title}' at ${item.triggerAtMs}")
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerAtMs, pi)
            XLog.w(TAG, "Inexact fallback for ${item.id}: ${e.message}")
        }
    }

    fun cancel(context: Context, itemId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, itemId))
    }

    /** Re-arm all future time-based items (call from BootReceiver). */
    fun rearmAll(context: Context) {
        val now = System.currentTimeMillis()
        var armed = 0
        AssistantStore.all().forEach { item ->
            if (item.triggerAtMs > now && !item.done) { arm(context, item); armed++ }
        }
        XLog.i(TAG, "Re-armed $armed assistant alarms")
    }

    private fun pendingIntent(context: Context, itemId: String): PendingIntent {
        val intent = Intent(context, AssistantReceiver::class.java).apply {
            action = AssistantReceiver.ACTION
            putExtra(EXTRA_ITEM_ID, itemId)
            `package` = context.packageName
        }
        return PendingIntent.getBroadcast(
            context, itemId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
