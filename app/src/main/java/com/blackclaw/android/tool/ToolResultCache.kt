package com.blackclaw.android.tool

import com.blackclaw.android.utils.XLog

/**
 * Tiny per-task TTL cache for read-only tool results.
 *
 * Why this exists:
 *   The LLM frequently re-calls expensive observation tools (get_installed_apps,
 *   find_contact, get_calendar_events) within the same multi-turn task. Caching
 *   their results for a short window saves real time and tokens with no semantic
 *   loss as long as the data is unlikely to change in seconds.
 *
 * Scope:
 *   - Cache is process-global but cleared explicitly between tasks via clear().
 *   - Only a hand-picked allowlist of stable tools is cacheable. Anything that
 *     reads live UI state (get_screen_info, screenshots) or can mutate state
 *     (open_app, tap, send_message) is never cached.
 */
object ToolResultCache {

    private const val TAG = "ToolResultCache"

    /** Tools that are safe to cache for [DEFAULT_TTL_MS] milliseconds. */
    private val CACHEABLE_TOOLS = mapOf(
        "get_installed_apps" to 5L * 60_000L,   // app list rarely changes within a task
        "get_device_info" to 30_000L,            // battery/wifi/storage etc
        "find_contact" to 5L * 60_000L,          // contacts table is stable
        "get_calendar_events" to 60_000L,        // calendar updates rare in 1 min
        "get_call_log" to 30_000L,
        "recall_facts" to 60_000L,
        "list_scheduled_tasks" to 30_000L,
        "get_foreground_app" to 2_000L,          // very short — UI changes constantly
    )

    private const val DEFAULT_TTL_MS = 30_000L

    private data class Entry(val expiresAtMs: Long, val result: ToolResult)

    private val cache = HashMap<String, Entry>()

    @Synchronized
    fun get(toolName: String, params: Map<String, Any>): ToolResult? {
        val ttl = CACHEABLE_TOOLS[toolName] ?: return null
        val key = makeKey(toolName, params)
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAtMs) {
            cache.remove(key)
            return null
        }
        XLog.d(TAG, "cache hit: $toolName (params=${params.size})")
        return entry.result
    }

    @Synchronized
    fun put(toolName: String, params: Map<String, Any>, result: ToolResult) {
        if (!result.isSuccess) return  // never cache errors
        val ttl = CACHEABLE_TOOLS[toolName] ?: return
        val key = makeKey(toolName, params)
        cache[key] = Entry(System.currentTimeMillis() + ttl, result)
        XLog.d(TAG, "cache put: $toolName (ttl=${ttl}ms, size=${cache.size})")
    }

    /** Wipe the cache. Call between agent tasks so a new task sees fresh state. */
    @Synchronized
    fun clear() {
        if (cache.isNotEmpty()) {
            XLog.d(TAG, "cache cleared (${cache.size} entries)")
            cache.clear()
        }
    }

    private fun makeKey(toolName: String, params: Map<String, Any>): String {
        if (params.isEmpty()) return toolName
        // Stable key: sort by param name
        val sorted = params.toSortedMap()
        return buildString {
            append(toolName)
            sorted.forEach { (k, v) -> append('|').append(k).append('=').append(v) }
        }
    }
}
