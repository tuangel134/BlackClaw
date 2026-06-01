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
enum class AssistantItemType { REMINDER, NOTE, ALARM, EVENT, ALERT, FINANCE, SHOPPING }

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
    /** Geofence (location reminder): target lat/lon and radius (m). 0 = none. */
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val radiusM: Int = 0,
    /** Geofence trigger: enter|exit. */
    val geoTrigger: String = "enter",
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
        put("lat", lat)
        put("lon", lon)
        put("radiusM", radiusM)
        put("geoTrigger", geoTrigger)
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
            lat = o.optDouble("lat", 0.0),
            lon = o.optDouble("lon", 0.0),
            radiusM = o.optInt("radiusM", 0),
            geoTrigger = o.optString("geoTrigger", "enter"),
            createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis()),
            source = o.optString("source", "user"),
        )
    }
}

object AssistantStore {
    private const val TAG = "AssistantStore"
    private const val KEY = "KEY_ASSISTANT_ITEMS_V1"

    /** Item types whose creation feeds habit learning (timed, user-routine items). */
    private val HABIT_TRACKED_TYPES = setOf(
        AssistantItemType.ALARM, AssistantItemType.REMINDER, AssistantItemType.EVENT)

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
        // Keep the home-screen widget + QS tile in sync.
        runCatching { AssistantWidget.refresh(com.blackclaw.android.ClawApplication.instance) }
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
        lat: Double = 0.0,
        lon: Double = 0.0,
        radiusM: Int = 0,
        geoTrigger: String = "enter",
        source: String = "user",
    ): AssistantItem {
        val item = AssistantItem(
            id = UUID.randomUUID().toString().take(8),
            type = type, title = title, body = body,
            triggerAtMs = triggerAtMs, repeat = repeat,
            amount = amount, category = category, challenge = challenge,
            lat = lat, lon = lon, radiusM = radiusM, geoTrigger = geoTrigger, source = source,
        )
        upsert(item)
        // Habit learning: record timed alarm/reminder/event creations so the
        // assistant can later offer to automate recurring patterns.
        if (triggerAtMs > 0 && type in HABIT_TRACKED_TYPES) {
            runCatching {
                com.blackclaw.android.proactive.HabitTracker.record(type.name.lowercase(), triggerAtMs)
            }
        }
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

    /** Total expenses (absolute) in the current calendar month. */
    fun monthExpenses(): Double {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val monthStart = cal.timeInMillis
        return byType(AssistantItemType.FINANCE)
            .filter { it.amount < 0 && it.createdAtMs >= monthStart }
            .sumOf { -it.amount }
    }

    /** Total expenses (absolute) since [sinceMs]. */
    fun expensesSince(sinceMs: Long): Double =
        byType(AssistantItemType.FINANCE)
            .filter { it.amount < 0 && it.createdAtMs >= sinceMs }
            .sumOf { -it.amount }

    /** Total income since [sinceMs]. */
    fun incomeSince(sinceMs: Long): Double =
        byType(AssistantItemType.FINANCE)
            .filter { it.amount > 0 && it.createdAtMs >= sinceMs }
            .sumOf { it.amount }

    /** Expenses (absolute) grouped by category since [sinceMs], biggest first. */
    fun expensesByCategorySince(sinceMs: Long): List<Pair<String, Double>> =
        byType(AssistantItemType.FINANCE)
            .filter { it.amount < 0 && it.createdAtMs >= sinceMs }
            .groupBy { it.category.ifBlank { "otros" } }
            .map { (cat, items) -> cat to items.sumOf { -it.amount } }
            .sortedByDescending { it.second }

    /** User's monthly budget (0 = not set). */
    var monthlyBudget: Double
        get() = KVUtils.getDouble("assistant_monthly_budget", 0.0)
        set(v) { KVUtils.putDouble("assistant_monthly_budget", v); KVUtils.sync() }

    /** User's savings goal target (0 = not set). */
    var savingsGoal: Double
        get() = KVUtils.getDouble("assistant_savings_goal", 0.0)
        set(v) { KVUtils.putDouble("assistant_savings_goal", v); KVUtils.sync() }

    /** Label for the savings goal (e.g. "vacaciones"). */
    var savingsGoalName: String
        get() = KVUtils.getString("assistant_savings_goal_name", "")
        set(v) { KVUtils.putString("assistant_savings_goal_name", v); KVUtils.sync() }

    /**
     * Average weekly expenses over the [weeks] full weeks BEFORE the current one.
     * Used to spot when this week's spending is anomalously high. Returns 0 if
     * there isn't enough history.
     */
    fun avgWeeklyExpenses(weeks: Int = 4): Double {
        val now = System.currentTimeMillis()
        val week = 7L * 24 * 60 * 60 * 1000
        val thisWeekStart = now - week
        val windowStart = now - (weeks + 1) * week
        val past = byType(AssistantItemType.FINANCE)
            .filter { it.amount < 0 && it.createdAtMs in windowStart until thisWeekStart }
        if (past.isEmpty()) return 0.0
        val total = past.sumOf { -it.amount }
        return total / weeks
    }

    /** All finance entries as CSV (date,type,description,category,amount). */
    fun financeCsv(): String {
        val df = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        val sb = StringBuilder("date,type,description,category,amount\n")
        byType(AssistantItemType.FINANCE)
            .sortedBy { it.createdAtMs }
            .forEach { i ->
                val type = if (i.amount >= 0) "income" else "expense"
                fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
                sb.append(df.format(java.util.Date(i.createdAtMs))).append(',')
                    .append(type).append(',')
                    .append(esc(i.title)).append(',')
                    .append(esc(i.category)).append(',')
                    .append("%.2f".format(i.amount)).append('\n')
            }
        return sb.toString()
    }

    fun countPending(type: AssistantItemType): Int =
        byType(type).count { !it.done }
}
