package com.blackclaw.android.assistant

import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The native data layer for BlackClaw's built-in Assistant hub.
 *
 * Instead of bouncing the user out to the system Clock / Calendar / Notes apps,
 * BlackClaw keeps reminders, notes, alarms, calendar events, alerts and finance
 * entries *inside the app*. The AI writes here, the Assistant screen shows them,
 * and time-based items fire as native push notifications via [AssistantScheduler].
 *
 * One flat list keyed by [AssistantItem.type] keeps persistence trivial (a single
 * MMKV JSON array) while the UI filters per tab.
 */
enum class AssistantItemType { REMINDER, NOTE, ALARM, EVENT, ALERT, FINANCE }

data class AssistantItem(
    val id: String,
    val type: AssistantItemType,
    val title: String,
    val body: String = "",
    /** Epoch ms the item should fire (reminders/alarms/events). 0 = no time. */
    val triggerAtMs: Long = 0L,
    /** Repeat: none|daily|weekly. */
    val repeat: String = "none",
    val done: Boolean = false,
    /** Finance: signed amount (negative = expense, positive = income). */
    val amount: Double = 0.0,
    /** Finance: category / note label. */
    val category: String = "",
    /** Alarm challenge to dismiss: none|math|memory|type. Empty = normal alarm. */
    val challenge: String = "none",
    val createdAtMs: Long = System.currentTimeMillis(),
    /** Source that created it, e.g. "ai" or "user" or a notification package. */
    val source: String = "user",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("title", title)
        put("body", body)
        put("triggerAtMs", triggerAtMs)
        put("repeat", repeat)
        put("done", done)
        put("amount", amount)
        put("category", category)
        put("challenge", challenge)
        put("createdAtMs", createdAtMs)
        put("source", source)
    }

    companion object {
        fun fromJson(o: JSONObject) = AssistantItem(
            id = o.optString("id", UUID.randomUUID().toString().take(8)),
            type = runCatching { AssistantItemType.valueOf(o.optString("type", "NOTE")) }
                .getOrDefault(AssistantItemType.NOTE),
            title = o.optString("title", ""),
            body = o.optString("body", ""),
            triggerAtMs = o.optLong("triggerAtMs", 0L),
            repeat = o.optString("repeat", "none"),
            done = o.optBoolean("done", false),
            amount = o.optDouble("amount", 0.0),
            category = o.optString("category", ""),
            challenge = o.optString("challenge", "none"),
            createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis()),
            source = o.optString("source", "user"),
        )
    }
}

object AssistantStore {
    private const val TAG = "AssistantStore"
    private const val KEY = "KEY_ASSISTANT_ITEMS_V1"

    @Synchronized
    fun all(): List<AssistantItem> {
        val raw = KVUtils.getString(KEY, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { AssistantItem.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrElse {
            XLog.w(TAG, "Failed to parse assistant items: ${it.message}")
            emptyList()
        }
    }

    fun byType(type: AssistantItemType): List<AssistantItem> =
        all().filter { it.type == type }.sortedWith(
            compareBy({ it.done }, { if (it.triggerAtMs > 0) it.triggerAtMs else it.createdAtMs })
        )

    fun find(id: String): AssistantItem? = all().firstOrNull { it.id == id }

    @Synchronized
    private fun saveAll(items: List<AssistantItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        KVUtils.putString(KEY, arr.toString())
        KVUtils.sync()
    }

    @Synchronized
    fun upsert(item: AssistantItem): AssistantItem {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx >= 0) list[idx] = item else list.add(item)
        saveAll(list)
        return item
    }

    /** Convenience: create a fresh item with a generated id. */
    fun create(
        type: AssistantItemType,
        title: String,
        body: String = "",
        triggerAtMs: Long = 0L,
        repeat: String = "none",
        amount: Double = 0.0,
        category: String = "",
        challenge: String = "none",
        source: String = "user",
    ): AssistantItem {
        val item = AssistantItem(
            id = UUID.randomUUID().toString().take(8),
            type = type, title = title, body = body,
            triggerAtMs = triggerAtMs, repeat = repeat,
            amount = amount, category = category, challenge = challenge, source = source,
        )
        upsert(item)
        return item
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val before = all()
        val after = before.filterNot { it.id == id }
        if (after.size == before.size) return false
        saveAll(after)
        return true
    }

    @Synchronized
    fun toggleDone(id: String): AssistantItem? {
        val item = find(id) ?: return null
        return upsert(item.copy(done = !item.done))
    }

    /** Sum of finance amounts (income positive, expense negative). */
    fun financeBalance(): Double = byType(AssistantItemType.FINANCE).sumOf { it.amount }

    fun countPending(type: AssistantItemType): Int =
        byType(type).count { !it.done }
}
