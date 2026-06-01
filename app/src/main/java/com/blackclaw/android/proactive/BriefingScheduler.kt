package com.blackclaw.android.proactive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.blackclaw.android.utils.XLog
import java.util.Calendar

/**
 * Schedules the daily morning / night briefings via AlarmManager and fires them
 * through [BriefingReceiver]. Re-armed on boot and whenever the user changes the
 * times in settings.
 */
object BriefingScheduler {

    private const val TAG = "BriefingScheduler"
    const val EXTRA_KIND = "briefing_kind"
    private const val REQ_MORNING = 70001
    private const val REQ_NIGHT = 70002
    private const val REQ_WEEKLY = 70003

    fun syncAll(context: Context) {
        sync(context, ProactiveBriefing.Kind.MORNING)
        sync(context, ProactiveBriefing.Kind.NIGHT)
        sync(context, ProactiveBriefing.Kind.WEEKLY)
    }

    fun sync(context: Context, kind: ProactiveBriefing.Kind) {
        val enabled = ProactiveConfig.enabled && when (kind) {
            ProactiveBriefing.Kind.MORNING -> ProactiveConfig.morningBriefingEnabled
            ProactiveBriefing.Kind.NIGHT -> ProactiveConfig.nightBriefingEnabled
            ProactiveBriefing.Kind.WEEKLY -> ProactiveConfig.weeklyFinanceEnabled
        }
        if (enabled) arm(context, kind) else cancel(context, kind)
    }

    private fun arm(context: Context, kind: ProactiveBriefing.Kind) {
        val (hour, min) = when (kind) {
            ProactiveBriefing.Kind.MORNING -> ProactiveConfig.morningHour to ProactiveConfig.morningMinute
            ProactiveBriefing.Kind.NIGHT -> ProactiveConfig.nightHour to ProactiveConfig.nightMinute
            ProactiveBriefing.Kind.WEEKLY -> ProactiveConfig.weeklyFinanceHour to ProactiveConfig.weeklyFinanceMinute
        }
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (kind == ProactiveBriefing.Kind.WEEKLY) {
                set(Calendar.DAY_OF_WEEK, ProactiveConfig.weeklyFinanceDay)
                // Roll forward to the next matching day if it's already past.
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.WEEK_OF_YEAR, 1)
            } else if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, kind)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
            XLog.i(TAG, "Armed $kind briefing at ${cal.time}")
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }

    private fun cancel(context: Context, kind: ProactiveBriefing.Kind) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, kind))
    }

    private fun pendingIntent(context: Context, kind: ProactiveBriefing.Kind): PendingIntent {
        val intent = Intent(context, BriefingReceiver::class.java).apply {
            action = BriefingReceiver.ACTION
            putExtra(EXTRA_KIND, kind.name)
            `package` = context.packageName
        }
        val req = when (kind) {
            ProactiveBriefing.Kind.MORNING -> REQ_MORNING
            ProactiveBriefing.Kind.NIGHT -> REQ_NIGHT
            ProactiveBriefing.Kind.WEEKLY -> REQ_WEEKLY
        }
        return PendingIntent.getBroadcast(
            context, req, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class BriefingReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.blackclaw.android.BRIEFING_FIRE"
        private const val TAG = "BriefingReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val kind = runCatching {
            ProactiveBriefing.Kind.valueOf(intent.getStringExtra(BriefingScheduler.EXTRA_KIND) ?: "")
        }.getOrNull() ?: return
        XLog.i(TAG, "Briefing alarm fired: $kind")
        // Run off the main thread (LLM + tool calls).
        Thread({ ProactiveBriefing.run(kind) }, "briefing-$kind").start()
        // Re-arm for the next day.
        BriefingScheduler.sync(context, kind)
    }
}
