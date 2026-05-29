package com.blackclaw.android.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Persistent scheduler for AI tasks and chats.
 *
 * Each scheduled entry is stored in MMKV as a JSON record under KEY_SCHEDULED_TASKS.
 * AlarmManager wakes the app at the requested time and broadcasts to
 * [ScheduledTaskReceiver] which launches ComposeChatActivity with the task/chat extra.
 *
 * Recurring schedules use simple period semantics:
 *  - "once"     : one-shot, removed after firing
 *  - "daily"    : every 24 h at the same time
 *  - "hourly"   : every hour
 *  - "weekly"   : every 7 days
 *  - "interval" : custom intervalMs
 */
object ScheduledTaskManager {

    private const val TAG = "ScheduledTaskManager"
    private const val KEY_SCHEDULED_TASKS = "KEY_SCHEDULED_TASKS_V1"
    const val EXTRA_SCHEDULE_ID = "schedule_id"

    enum class Recurrence { ONCE, HOURLY, DAILY, WEEKLY, INTERVAL }
    enum class Mode { TASK, CHAT }

    data class ScheduledTask(
        val id: String,
        val mode: Mode,
        val text: String,
        val triggerAtMs: Long,
        val recurrence: Recurrence,
        val intervalMs: Long,
        val createdAtMs: Long,
        val lastRunAtMs: Long = 0L,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("mode", mode.name)
            put("text", text)
            put("triggerAtMs", triggerAtMs)
            put("recurrence", recurrence.name)
            put("intervalMs", intervalMs)
            put("createdAtMs", createdAtMs)
            put("lastRunAtMs", lastRunAtMs)
        }

        fun describe(): String {
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val whenStr = df.format(Date(triggerAtMs))
            val recurStr = when (recurrence) {
                Recurrence.ONCE -> "once"
                Recurrence.HOURLY -> "every hour"
                Recurrence.DAILY -> "daily"
                Recurrence.WEEKLY -> "weekly"
                Recurrence.INTERVAL -> "every ${intervalMs / 60_000}m"
            }
            val modeStr = if (mode == Mode.TASK) "task" else "chat"
            return "[$id] $modeStr at $whenStr ($recurStr): \"$text\""
        }

        companion object {
            fun fromJson(json: JSONObject): ScheduledTask = ScheduledTask(
                id = json.getString("id"),
                mode = Mode.valueOf(json.optString("mode", Mode.TASK.name)),
                text = json.getString("text"),
                triggerAtMs = json.getLong("triggerAtMs"),
                recurrence = Recurrence.valueOf(json.optString("recurrence", Recurrence.ONCE.name)),
                intervalMs = json.optLong("intervalMs", 0L),
                createdAtMs = json.optLong("createdAtMs", 0L),
                lastRunAtMs = json.optLong("lastRunAtMs", 0L),
            )
        }
    }

    @Synchronized
    fun listAll(): List<ScheduledTask> {
        val raw = KVUtils.getString(KEY_SCHEDULED_TASKS, "")
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { ScheduledTask.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to parse stored schedules", e)
            emptyList()
        }
    }

    @Synchronized
    fun find(id: String): ScheduledTask? = listAll().firstOrNull { it.id == id }

    @Synchronized
    private fun saveAll(tasks: List<ScheduledTask>) {
        val arr = JSONArray()
        tasks.forEach { arr.put(it.toJson()) }
        KVUtils.putString(KEY_SCHEDULED_TASKS, arr.toString())
        KVUtils.sync()
    }

    /**
     * Add a new scheduled task. Returns the generated id.
     */
    @Synchronized
    fun schedule(
        context: Context,
        mode: Mode,
        text: String,
        triggerAtMs: Long,
        recurrence: Recurrence = Recurrence.ONCE,
        intervalMs: Long = 0L,
    ): ScheduledTask {
        val id = UUID.randomUUID().toString().take(8)
        val task = ScheduledTask(
            id = id,
            mode = mode,
            text = text,
            triggerAtMs = triggerAtMs,
            recurrence = recurrence,
            intervalMs = intervalMs,
            createdAtMs = System.currentTimeMillis(),
        )
        val all = listAll().toMutableList()
        all.add(task)
        saveAll(all)
        scheduleAlarm(context, task)
        XLog.i(TAG, "Scheduled: ${task.describe()}")
        return task
    }

    /**
     * Cancel and remove a scheduled task.
     */
    @Synchronized
    fun cancel(context: Context, id: String): Boolean {
        val all = listAll().toMutableList()
        val target = all.firstOrNull { it.id == id } ?: return false
        all.removeAll { it.id == id }
        saveAll(all)
        cancelAlarm(context, target)
        XLog.i(TAG, "Cancelled scheduled task $id")
        return true
    }

    /**
     * Update lastRunAt and reschedule if recurring.
     */
    @Synchronized
    fun markFiredAndReschedule(context: Context, id: String) {
        val all = listAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return
        val original = all[idx]
        val now = System.currentTimeMillis()
        val nextTrigger = computeNextTrigger(original, now)
        if (nextTrigger == null) {
            // One-shot — remove
            all.removeAt(idx)
            saveAll(all)
            XLog.i(TAG, "One-shot $id fired and removed")
        } else {
            val updated = original.copy(triggerAtMs = nextTrigger, lastRunAtMs = now)
            all[idx] = updated
            saveAll(all)
            scheduleAlarm(context, updated)
            XLog.i(TAG, "Recurring $id rescheduled for ${Date(nextTrigger)}")
        }
    }

    private fun computeNextTrigger(task: ScheduledTask, nowMs: Long): Long? {
        val intervalMs = when (task.recurrence) {
            Recurrence.ONCE -> return null
            Recurrence.HOURLY -> 60L * 60L * 1000L
            Recurrence.DAILY -> 24L * 60L * 60L * 1000L
            Recurrence.WEEKLY -> 7L * 24L * 60L * 60L * 1000L
            Recurrence.INTERVAL -> task.intervalMs.coerceAtLeast(60_000L)
        }
        var next = task.triggerAtMs + intervalMs
        // Skip past missed slots (e.g. device was off)
        while (next <= nowMs) next += intervalMs
        return next
    }

    /**
     * Re-arm all alarms (called from BootReceiver after reboot).
     */
    @Synchronized
    fun rearmAll(context: Context) {
        val now = System.currentTimeMillis()
        val all = listAll()
        for (task in all) {
            val effectiveTrigger = if (task.triggerAtMs <= now && task.recurrence != Recurrence.ONCE) {
                computeNextTrigger(task, now) ?: task.triggerAtMs
            } else {
                task.triggerAtMs
            }
            val toArm = if (effectiveTrigger != task.triggerAtMs) {
                task.copy(triggerAtMs = effectiveTrigger)
            } else {
                task
            }
            scheduleAlarm(context, toArm)
        }
        XLog.i(TAG, "Re-armed ${all.size} scheduled alarms after boot")
    }

    private fun pendingIntentFor(context: Context, task: ScheduledTask): PendingIntent {
        val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
            action = ScheduledTaskReceiver.ACTION
            putExtra(EXTRA_SCHEDULE_ID, task.id)
            `package` = context.packageName
        }
        return PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun scheduleAlarm(context: Context, task: ScheduledTask) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntentFor(context, task)
        try {
            // Use exact alarms when allowed; fall back to inexact otherwise.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.triggerAtMs, pi)
                XLog.w(TAG, "Exact alarms not permitted; using inexact for ${task.id}")
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.triggerAtMs, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.triggerAtMs, pi)
            XLog.w(TAG, "Falling back to inexact alarm for ${task.id}: ${e.message}")
        }
    }

    private fun cancelAlarm(context: Context, task: ScheduledTask) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntentFor(context, task))
    }
}
