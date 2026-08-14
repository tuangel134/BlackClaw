package com.blackclaw.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blackclaw.android.scheduler.ScheduledTaskManager
import com.blackclaw.android.utils.XLog

/**
 * On boot, re-arm any AlarmManager-backed scheduled tasks the user had set up.
 * AlarmManager state is wiped on reboot, so without this all schedules silently die.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            XLog.i(TAG, "Boot broadcast received, re-arming scheduled tasks")
            try {
                ScheduledTaskManager.rearmAll(context.applicationContext)
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to re-arm scheduled tasks", e)
            }
            try {
                com.blackclaw.android.assistant.AssistantScheduler.rearmAll(context.applicationContext)
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to re-arm assistant alarms", e)
            }
            try {
                com.blackclaw.android.proactive.BriefingScheduler.syncAll(context.applicationContext)
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to re-arm briefings", e)
            }
            try {
                com.blackclaw.android.automation.AutomationProfileScheduler.sync(context.applicationContext)
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to re-arm automation profiles", e)
            }
        }
    }
}
