package com.blackclaw.android.ui.chat

import android.os.Handler
import android.os.Looper
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.AppViewModel
import com.blackclaw.android.service.AutoReplyManager
import com.blackclaw.android.service.ForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns active-task shell state for the top task bar and monitor stop actions.
 *
 * ComposeChatActivity observes the exposed state; it no longer polls AutoReplyManager directly.
 */
class ActiveTaskShellController(
    private val appViewModel: AppViewModel,
) {

    private val autoReplyManager = AutoReplyManager.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private val _activeTasks = MutableStateFlow<List<String>>(emptyList())

    val activeTasks: StateFlow<List<String>> = _activeTasks.asStateFlow()

    private val poller = object : Runnable {
        override fun run() {
            refreshActiveTasks()
            handler.postDelayed(this, 2000)
        }
    }

    fun onResume() {
        refreshActiveTasks()
        handler.removeCallbacks(poller)
        handler.post(poller)
    }

    fun onPause() {
        handler.removeCallbacks(poller)
    }

    fun stopTask(contact: String): String {
        autoReplyManager.removeContact(contact)
        if (autoReplyManager.monitoredContacts.isEmpty()) {
            autoReplyManager.isEnabled = false
        }
        refreshActiveTasks()
        ForegroundService.resetToIdle(ClawApplication.instance)
        return "Stopped monitoring $contact"
    }

    fun stopAllTasks(): String {
        var requestedTaskStop = false
        if (appViewModel.isTaskRunning()) {
            appViewModel.stopTask()
            requestedTaskStop = true
        }

        var stoppedMonitoring = false
        if (autoReplyManager.isEnabled) {
            autoReplyManager.stopAll()
            stoppedMonitoring = true
        }

        refreshActiveTasks()
        ForegroundService.resetToIdle(ClawApplication.instance)
        return when {
            requestedTaskStop -> "Stopping current task..."
            stoppedMonitoring -> "All tasks stopped"
            else -> "No active tasks"
        }
    }

    private fun refreshActiveTasks() {
        _activeTasks.value = if (autoReplyManager.isEnabled) {
            autoReplyManager.monitoredContacts.toList()
        } else {
            emptyList()
        }
    }
}
