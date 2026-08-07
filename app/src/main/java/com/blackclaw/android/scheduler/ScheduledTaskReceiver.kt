package com.blackclaw.android.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blackclaw.android.ui.chat.ComposeChatActivity
import com.blackclaw.android.utils.XLog

/**
 * Fires when AlarmManager triggers a scheduled task.
 * Launches the chat activity with the saved task/chat extra,
 * then asks the manager to remove or re-arm the entry.
 */
class ScheduledTaskReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.blackclaw.android.SCHEDULED_TASK"
        private const val TAG = "ScheduledTaskReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.getStringExtra(ScheduledTaskManager.EXTRA_SCHEDULE_ID) ?: return
        val task = ScheduledTaskManager.find(id)
        if (task == null) {
            XLog.w(TAG, "Alarm fired for unknown schedule id=$id")
            return
        }
        XLog.i(TAG, "Firing scheduled task: ${task.describe()}")

        if (task.mode == ScheduledTaskManager.Mode.TASK) {
            // Execute in the background with a wake lock; Android may block an Activity
            // launch while the phone is asleep.
            com.blackclaw.android.automation.AutomationEngine.executeTask(
                context, task.text, "scheduled:${task.id}")
        } else {
            val launch = Intent(context, ComposeChatActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("chat", task.text)
            }
            runCatching { context.startActivity(launch) }
                .onFailure { XLog.e(TAG, "Failed to launch scheduled chat", it) }
        }

        ScheduledTaskManager.markFiredAndReschedule(context, id)
    }
}
