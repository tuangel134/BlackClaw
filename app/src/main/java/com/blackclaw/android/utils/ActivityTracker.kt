package com.blackclaw.android.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tracks daily activity metrics for the assistant dashboard.
 *
 * Records: tasks executed, proactive actions taken, tokens consumed,
 * success/failure rates, and tools used. Persists daily stats in MMKV
 * with a rolling window of 30 days.
 */
object ActivityTracker {

    private const val TAG = "ActivityTracker"
    private const val KEY_DAILY_STATS = "activity_daily_stats_v1"
    private const val KEY_TODAY_DETAIL = "activity_today_detail_v1"
    private const val MAX_DAYS = 30

    data class DailyStat(
        val date: String,  // "yyyy-MM-dd"
        val tasksRun: Int = 0,
        val tasksSuccess: Int = 0,
        val tasksFailed: Int = 0,
        val proactiveActions: Int = 0,
        val proactiveIgnored: Int = 0,
        val alarmsSet: Int = 0,
        val remindersSet: Int = 0,
        val tokensUsed: Long = 0,
        val estimatedCost: Double = 0.0,
        val topTools: List<String> = emptyList(),
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("date", date)
            put("tasksRun", tasksRun)
            put("tasksSuccess", tasksSuccess)
            put("tasksFailed", tasksFailed)
            put("proactiveActions", proactiveActions)
            put("proactiveIgnored", proactiveIgnored)
            put("alarmsSet", alarmsSet)
            put("remindersSet", remindersSet)
            put("tokensUsed", tokensUsed)
            put("estimatedCost", estimatedCost)
            put("topTools", JSONArray(topTools))
        }

        companion object {
            fun fromJson(o: JSONObject) = DailyStat(
                date = o.optString("date", ""),
                tasksRun = o.optInt("tasksRun"),
                tasksSuccess = o.optInt("tasksSuccess"),
                tasksFailed = o.optInt("tasksFailed"),
                proactiveActions = o.optInt("proactiveActions"),
                proactiveIgnored = o.optInt("proactiveIgnored"),
                alarmsSet = o.optInt("alarmsSet"),
                remindersSet = o.optInt("remindersSet"),
                tokensUsed = o.optLong("tokensUsed"),
                estimatedCost = o.optDouble("estimatedCost"),
                topTools = (0 until (o.optJSONArray("topTools")?.length() ?: 0)).map {
                    o.optJSONArray("topTools")!!.getString(it)
                },
            )
        }
    }

    private fun todayStr(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())

    @Synchronized
    fun allStats(): List<DailyStat> {
        val raw = KVUtils.getString(KEY_DAILY_STATS, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { DailyStat.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun todayStat(): DailyStat {
        val today = todayStr()
        return allStats().lastOrNull { it.date == today } ?: DailyStat(date = today)
    }

    @Synchronized
    private fun saveStat(stat: DailyStat) {
        val list = allStats().toMutableList()
        val idx = list.indexOfFirst { it.date == stat.date }
        if (idx >= 0) list[idx] = stat else list.add(stat)
        // Keep only last MAX_DAYS
        val capped = if (list.size > MAX_DAYS) list.takeLast(MAX_DAYS) else list
        val arr = JSONArray()
        capped.forEach { arr.put(it.toJson()) }
        KVUtils.putString(KEY_DAILY_STATS, arr.toString())
        KVUtils.sync()
    }

    // ── Recording methods ──

    fun recordTaskCompleted(success: Boolean, tokensUsed: Int = 0, estimatedCost: Double = 0.0) {
        val s = todayStat()
        saveStat(s.copy(
            tasksRun = s.tasksRun + 1,
            tasksSuccess = s.tasksSuccess + if (success) 1 else 0,
            tasksFailed = s.tasksFailed + if (!success) 1 else 0,
            tokensUsed = s.tokensUsed + tokensUsed,
            estimatedCost = s.estimatedCost + estimatedCost,
        ))
    }

    fun recordProactiveAction(acted: Boolean) {
        val s = todayStat()
        saveStat(s.copy(
            proactiveActions = s.proactiveActions + if (acted) 1 else 0,
            proactiveIgnored = s.proactiveIgnored + if (!acted) 1 else 0,
        ))
    }

    fun recordAlarmSet() {
        val s = todayStat()
        saveStat(s.copy(alarmsSet = s.alarmsSet + 1))
    }

    fun recordReminderSet() {
        val s = todayStat()
        saveStat(s.copy(remindersSet = s.remindersSet + 1))
    }

    fun recordToolUsed(toolName: String) {
        // Track in today's detail
        val raw = KVUtils.getString(KEY_TODAY_DETAIL, "")
        val today = todayStr()
        val obj = if (raw.isNotBlank()) {
            runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        } else JSONObject()
        // Reset if it's a new day
        if (obj.optString("date") != today) {
            obj.put("date", today)
            obj.put("tools", JSONObject())
        }
        val tools = obj.optJSONObject("tools") ?: JSONObject()
        tools.put(toolName, tools.optInt(toolName) + 1)
        obj.put("tools", tools)
        KVUtils.putString(KEY_TODAY_DETAIL, obj.toString())
    }

    // ── Query methods ──

    fun today(): DailyStat = todayStat()

    fun thisWeek(): List<DailyStat> {
        val stats = allStats()
        return stats.takeLast(7)
    }

    fun todayTopTools(limit: Int = 5): List<Pair<String, Int>> {
        val raw = KVUtils.getString(KEY_TODAY_DETAIL, "")
        if (raw.isBlank()) return emptyList()
        val obj = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        if (obj.optString("date") != todayStr()) return emptyList()
        val tools = obj.optJSONObject("tools") ?: return emptyList()
        return tools.keys().asSequence()
            .map { it to tools.getInt(it) }
            .sortedByDescending { it.second }
            .take(limit)
            .toList()
    }

    /** Quick summary string for display. */
    fun todaySummary(): String {
        val s = todayStat()
        if (s.tasksRun == 0 && s.proactiveActions == 0) return "Sin actividad hoy"
        return buildString {
            if (s.tasksRun > 0) append("${s.tasksRun} tareas (${s.tasksSuccess}✓ ${s.tasksFailed}✗)")
            if (s.proactiveActions > 0) {
                if (isNotEmpty()) append(" · ")
                append("${s.proactiveActions} acciones proactivas")
            }
            if (s.alarmsSet > 0) {
                if (isNotEmpty()) append(" · ")
                append("${s.alarmsSet} alarmas")
            }
            if (s.tokensUsed > 0) {
                if (isNotEmpty()) append(" · ")
                append("${s.tokensUsed} tokens")
            }
        }
    }
}
