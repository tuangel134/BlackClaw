package com.blackclaw.android.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.blackclaw.android.utils.XLog

/**
 * Periodic watchdog JobService
 * Checks every 15 minutes whether the foreground service is alive; restarts it if killed
 */
class KeepAliveJobService : JobService() {

    companion object {
        private const val TAG = "KeepAliveJob"
        private const val JOB_ID = 10086
        private const val INTERVAL_MS = 15 * 60 * 1000L // 15 minutes

        fun cancel(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.cancel(JOB_ID)
            XLog.i(TAG, "KeepAlive job cancelled")
        }

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (scheduler.getPendingJob(JOB_ID) != null) return

            val jobInfo = JobInfo.Builder(JOB_ID, ComponentName(context, KeepAliveJobService::class.java))
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .build()

            val result = scheduler.schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                XLog.i(TAG, "KeepAlive job scheduled")
            } else {
                XLog.e(TAG, "KeepAlive job schedule failed")
            }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        XLog.i(TAG, "KeepAlive job triggered, ForegroundService running: ${ForegroundService.isRunning()}")
        ForegroundService.syncToBackgroundState(applicationContext)
        // Opportunistic location-reminder check (no constant GPS).
        runCatching {
            com.blackclaw.android.assistant.GeofenceChecker.check(applicationContext)
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true
    }
}
