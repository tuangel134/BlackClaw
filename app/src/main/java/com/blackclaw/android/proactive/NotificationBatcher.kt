package com.blackclaw.android.proactive

import com.blackclaw.android.utils.XLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Batches rapid-fire notifications from the same package before forwarding
 * them to [ProactiveAssistantManager].
 *
 * Problem: when a messaging app delivers 5 messages in quick succession,
 * classifying each one individually wastes 5 LLM calls. Instead, we collect
 * all notifications from the same package within a short window and merge
 * them into a single classification request.
 *
 * Usage: call [submit] instead of ProactiveAssistantManager.onNotification
 * directly from ClawNotificationListener.
 */
object NotificationBatcher {

    private const val TAG = "NotificationBatcher"

    /** How long to wait for more notifications before flushing. */
    var batchWindowMs: Long = 8_000L

    data class PendingNotification(
        val pkg: String,
        val title: String,
        val text: String,
        val receivedAt: Long,
    )

    /** Pending notifications keyed by package name. */
    private val pending = ConcurrentHashMap<String, MutableList<PendingNotification>>()

    /** Scheduled flush tasks keyed by package name. */
    private val timers = ConcurrentHashMap<String, ScheduledFuture<*>>()

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "NotifBatcher").apply { isDaemon = true }
        }

    /**
     * Entry point. Call this for every incoming notification.
     * The batcher will either start a new batch window or append to an existing one.
     */
    fun submit(pkg: String, title: String, text: String) {
        val now = System.currentTimeMillis()
        val notification = PendingNotification(pkg, title, text, now)

        // Single lock guards the map+list as one unit, so a notification can't be
        // appended to a list that flush() just removed (which would strand it).
        synchronized(pending) {
            pending.getOrPut(pkg) { mutableListOf() }.add(notification)
        }

        // Reset (or start) the flush timer for this package.
        timers[pkg]?.cancel(false)
        val future = scheduler.schedule({ flush(pkg) }, batchWindowMs, TimeUnit.MILLISECONDS)
        timers[pkg] = future

        XLog.d(TAG, "Queued notification from $pkg (batch size: ${pending[pkg]?.size ?: 0})")
    }

    /**
     * Flush all pending notifications for a package: merge and forward to
     * ProactiveAssistantManager.
     */
    private fun flush(pkg: String) {
        val batch: List<PendingNotification>
        synchronized(pending) {
            val list = pending.remove(pkg) ?: return
            batch = list.toList()
            timers.remove(pkg)
        }

        if (batch.isEmpty()) return

        if (batch.size == 1) {
            // Single notification — no merging needed.
            val n = batch.first()
            XLog.d(TAG, "Flushing single notification from $pkg")
            ProactiveAssistantManager.onNotification(pkg, n.title, n.text)
            return
        }

        // Merge multiple notifications.
        val mergedTitle = batch.last().title  // Most recent title.
        val uniqueTexts = batch.map { it.text }
            .filter { it.isNotBlank() }
            .distinct()
        val mergedText = buildString {
            append("[${batch.size} mensajes] ")
            append(uniqueTexts.joinToString("\n"))
        }

        XLog.i(TAG, "Flushing batch of ${batch.size} notifications from $pkg")
        ProactiveAssistantManager.onNotification(pkg, mergedTitle, mergedText)
    }
}
