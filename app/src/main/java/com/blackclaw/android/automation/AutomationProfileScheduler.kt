package com.blackclaw.android.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import java.util.Calendar

/** AlarmManager adapter for daily/weekly TIME triggers. */
object AutomationProfileScheduler {
    private const val TAG = "AutomationProfileScheduler"
    private const val ACTION = "com.blackclaw.android.AUTOMATION_PROFILE_TIME"
    private const val EXTRA_PROFILE_ID = "profile_id"
    private const val EXTRA_TRIGGER_INDEX = "trigger_index"
    private const val KEY_ARMED = "automation_profile_time_alarms_v1"

    @Synchronized
    fun sync(context: Context) {
        val app = context.applicationContext
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val old = KVUtils.getString(KEY_ARMED, "").split('|').filter { it.isNotBlank() }
        old.forEach { cancel(alarm, app, it) }
        val armed = mutableListOf<String>()
        AutomationProfileStore.list()
            .filter { it.enabled && AutomationProfileValidator.validate(it).isEmpty() }
            .forEach { profile ->
            profile.triggers.forEachIndexed { index, trigger ->
                if (trigger.type != AutomationProfileStore.TriggerType.TIME &&
                    trigger.type != AutomationProfileStore.TriggerType.INTERVAL) return@forEachIndexed
                val key = key(profile.id, index)
                val at = nextAt(trigger) ?: return@forEachIndexed
                schedule(alarm, app, profile.id, index, at)
                armed += key
            }
        }
        KVUtils.putString(KEY_ARMED, armed.joinToString("|")); KVUtils.sync()
    }

    private fun schedule(alarm: AlarmManager, context: Context, profileId: String, index: Int, atMs: Long) {
        val pi = pendingIntent(context, profileId, index)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            } else {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
        }.onFailure {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            XLog.w(TAG, "Exact profile alarm unavailable; using inexact", it)
        }
    }

    private fun cancel(alarm: AlarmManager, context: Context, key: String) {
        val parts = key.split(":")
        if (parts.size != 2) return
        alarm.cancel(pendingIntent(context, parts[0], parts[1].toIntOrNull() ?: return))
    }

    private fun pendingIntent(context: Context, profileId: String, index: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            key(profileId, index).hashCode(),
            Intent(context, AutomationProfileTimeReceiver::class.java).apply {
                action = ACTION
                putExtra(EXTRA_PROFILE_ID, profileId)
                putExtra(EXTRA_TRIGGER_INDEX, index)
                `package` = context.packageName
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun key(profileId: String, index: Int) = "$profileId:$index"

    private fun nextAt(trigger: AutomationProfileStore.Trigger): Long? {
        if (trigger.type == AutomationProfileStore.TriggerType.INTERVAL) {
            val minutes = automationInt(trigger.params["minutes"])?.coerceIn(1, 10_080) ?: return null
            return System.currentTimeMillis() + minutes * 60_000L
        }
        if (trigger.type != AutomationProfileStore.TriggerType.TIME) return null
        val params = trigger.params
        val hour = automationInt(params["hour"]) ?: return null
        val minute = automationInt(params["minute"]) ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        val allowedDays = parseDays(params["days"]?.toString().orEmpty())
        val now = Calendar.getInstance()
        val candidate = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            if (candidate.timeInMillis > now.timeInMillis &&
                (allowedDays.isEmpty() || candidate.get(Calendar.DAY_OF_WEEK) in allowedDays)) {
                return candidate.timeInMillis
            }
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    private fun parseDays(raw: String): Set<Int> {
        if (raw.isBlank() || raw.equals("daily", true)) return emptySet()
        if (raw.equals("weekdays", true)) return setOf(2, 3, 4, 5, 6)
        val names = mapOf(
            "sun" to 1, "sunday" to 1, "dom" to 1, "domingo" to 1,
            "mon" to 2, "monday" to 2, "lun" to 2, "lunes" to 2,
            "tue" to 3, "tuesday" to 3, "mar" to 3, "martes" to 3,
            "wed" to 4, "wednesday" to 4, "mie" to 4, "miércoles" to 4,
            "thu" to 5, "thursday" to 5, "jue" to 5, "jueves" to 5,
            "fri" to 6, "friday" to 6, "vie" to 6, "viernes" to 6,
            "sat" to 7, "saturday" to 7, "sab" to 7, "sábado" to 7,
        )
        return raw.split(',', '|', ' ').mapNotNull { token ->
            token.toIntOrNull()?.takeIf { it in 1..7 } ?: names[token.lowercase()]
        }.toSet()
    }
}

class AutomationProfileTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != "com.blackclaw.android.AUTOMATION_PROFILE_TIME") return
        val id = intent.getStringExtra("profile_id") ?: return
        val index = intent.getIntExtra("trigger_index", -1)
        val profile = AutomationProfileStore.find(id) ?: return
        val trigger = profile.triggers.getOrNull(index) ?: return
        if (!profile.enabled || (trigger.type != AutomationProfileStore.TriggerType.TIME &&
                trigger.type != AutomationProfileStore.TriggerType.INTERVAL)) return
        val now = Calendar.getInstance()
        val attrs = if (trigger.type == AutomationProfileStore.TriggerType.TIME) mapOf(
            "hour" to now.get(Calendar.HOUR_OF_DAY).toString(),
            "minute" to now.get(Calendar.MINUTE).toString(),
            "day" to now.get(Calendar.DAY_OF_WEEK).toString(),
            "time" to "%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)),
        ) else mapOf("minutes" to trigger.params["minutes"].toString())
        AutomationProfileEngine.emitSystemEvent(context, trigger.type, attrs)
        AutomationProfileScheduler.sync(context)
    }
}
