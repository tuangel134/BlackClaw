package com.blackclaw.android.agent

import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.SecretStore
import com.blackclaw.android.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Queues tasks that couldn't be executed because no model was available
 * (offline, model not loaded, rate-limited). When the model becomes available
 * again, pending tasks can be popped and executed.
 *
 * Each queued task has an expiry (default 2 hours) — stale tasks are dropped
 * since they're no longer relevant.
 */
object OfflineTaskQueue {

    private const val TAG = "OfflineTaskQueue"
    private const val KEY = "offline_task_queue_v1"
    private const val MAX_TASKS = 10
    private const val DEFAULT_EXPIRY_MS = 2 * 60 * 60 * 1000L  // 2 hours

    data class QueuedTask(
        val id: String,
        val text: String,
        val queuedAt: Long,
        val expiresAt: Long,
        val source: String = "user",  // "user" | "proactive" | "scheduled"
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("text", text)
            put("queuedAt", queuedAt)
            put("expiresAt", expiresAt)
            put("source", source)
        }

        companion object {
            fun fromJson(o: JSONObject) = QueuedTask(
                id = o.optString("id", UUID.randomUUID().toString().take(8)),
                text = o.optString("text", ""),
                queuedAt = o.optLong("queuedAt", 0L),
                expiresAt = o.optLong("expiresAt", 0L),
                source = o.optString("source", "user"),
            )
        }
    }

    /**
     * Queue a task for later execution.
     * Returns the task ID, or null if the queue is full.
     */
    @Synchronized
    fun enqueue(text: String, source: String = "user", expiryMs: Long = DEFAULT_EXPIRY_MS): String? {
        if (text.isBlank()) return null
        val now = System.currentTimeMillis()
        val task = QueuedTask(
            id = UUID.randomUUID().toString().take(8),
            text = text.trim(),
            queuedAt = now,
            expiresAt = now + expiryMs,
            source = source,
        )
        val list = pendingTasks().toMutableList()
        if (list.size >= MAX_TASKS) {
            XLog.w(TAG, "Queue full, dropping oldest task")
            list.removeAt(0)
        }
        list.add(task)
        if (!save(list)) {
            XLog.e(TAG, "Could not persist queued task securely")
            return null
        }
        XLog.i(TAG, "Queued task: chars=${task.text.length} pending=${list.size}")
        return task.id
    }

    /**
     * Get all non-expired pending tasks.
     */
    @Synchronized
    fun pendingTasks(): List<QueuedTask> {
        val raw = readEncryptedOrMigrateLegacy() ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { QueuedTask.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }.filter { !it.isExpired() }
        }.getOrDefault(emptyList())
    }

    /**
     * Pop the next task from the queue (FIFO). Returns null if empty.
     * Automatically skips expired tasks.
     */
    @Synchronized
    fun dequeue(): QueuedTask? {
        val list = pendingTasks().toMutableList()
        if (list.isEmpty()) return null
        val task = list.first()
        val remaining = list.drop(1)
        if (!save(remaining)) {
            XLog.e(TAG, "Could not persist queue removal securely")
            return null
        }
        XLog.i(TAG, "Dequeued task: chars=${task.text.length} remaining=${remaining.size}")
        return task
    }

    /**
     * Drain all pending tasks (for batch execution when model comes back).
     */
    @Synchronized
    fun drainAll(): List<QueuedTask> {
        val tasks = pendingTasks()
        if (tasks.isEmpty()) return emptyList()
        if (!save(emptyList())) {
            XLog.e(TAG, "Could not persist queue drain securely")
            return emptyList()
        }
        XLog.i(TAG, "Drained ${tasks.size} queued tasks")
        return tasks
    }

    /**
     * Remove a specific task by ID.
     */
    @Synchronized
    fun remove(taskId: String): Boolean {
        val list = pendingTasks().toMutableList()
        val removed = list.removeAll { it.id == taskId }
        return removed && save(list)
    }

    fun isEmpty(): Boolean = pendingTasks().isEmpty()
    fun size(): Int = pendingTasks().size

    @Synchronized
    fun clear() {
        if (!save(emptyList())) XLog.e(TAG, "Could not clear queued tasks securely")
    }

    /**
     * Queue entries are user-authored tasks and may contain message text, contacts,
     * URLs or other private context. Prefer encrypted storage and migrate the legacy
     * MMKV document only after an encrypted read-back proves the write succeeded.
     */
    private fun readEncryptedOrMigrateLegacy(): String? {
        if (SecretStore.contains(KEY)) return SecretStore.getString(KEY)

        val legacy = KVUtils.getString(KEY, "")
        if (legacy.isBlank()) return ""
        val migrated = SecretStore.putString(KEY, legacy) && SecretStore.getString(KEY) == legacy
        if (migrated) {
            KVUtils.remove(KEY)
            KVUtils.sync()
            XLog.i(TAG, "Migrated offline task queue to encrypted storage")
        } else {
            XLog.w(TAG, "Offline task queue migration deferred; legacy data retained")
        }
        return legacy
    }

    private fun save(tasks: List<QueuedTask>): Boolean {
        val arr = JSONArray()
        tasks.forEach { arr.put(it.toJson()) }
        val encoded = arr.toString()
        if (!SecretStore.putString(KEY, encoded)) return false

        // A successful secure write supersedes any legacy MMKV copy. Removing it only
        // after commit keeps migration/write failures lossless.
        KVUtils.remove(KEY)
        KVUtils.sync()
        return true
    }
}
