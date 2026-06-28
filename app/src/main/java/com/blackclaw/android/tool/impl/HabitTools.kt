package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import com.blackclaw.android.utils.KVUtils
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Habit tracking system — users can track daily habits, see streaks,
 * and BlackClaw reminds/motivates them based on context.
 */

class HabitLogTool : BaseTool() {
    override fun getName() = "habit_log"
    override fun getDisplayName() = "Registrar hábito"
    override fun getDescriptionEN() =
        "Log a habit completion. Creates the habit if new. Tracks streaks and daily progress. " +
        "Use for: 'bebí agua', 'hice ejercicio', 'medité', 'leí 30 min', 'no fumé'."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "registra que el usuario completó un hábito (agua, ejercicio, etc.)"
    override fun getParameters() = listOf(
        ToolParameter("habit", "string", "Habit name (e.g. 'agua', 'ejercicio', 'meditación').", true),
        ToolParameter("value", "string", "Optional quantity (e.g. '2 vasos', '30 min', '5 km').", false),
        ToolParameter("note", "string", "Optional note.", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val habit = requireString(params, "habit").trim().lowercase()
        val value = optionalString(params, "value", "")
        val note = optionalString(params, "note", "")
        val result = HabitStore.log(habit, value, note)
        return ToolResult.success(result)
    }
}

class HabitStatusTool : BaseTool() {
    override fun getName() = "habit_status"
    override fun getDisplayName() = "Estado de hábitos"
    override fun getDescriptionEN() =
        "Show current habit tracking status — streaks, today's completions, weekly progress."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "muestra el estado de los hábitos (rachas, progreso)"
    override fun getParameters() = listOf(
        ToolParameter("habit", "string", "Optional: specific habit to check. Omit for all.", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val habit = optionalString(params, "habit", "").trim().lowercase()
        return if (habit.isNotBlank()) {
            ToolResult.success(HabitStore.statusFor(habit))
        } else {
            ToolResult.success(HabitStore.fullStatus())
        }
    }
}

class HabitCreateTool : BaseTool() {
    override fun getName() = "habit_create"
    override fun getDisplayName() = "Crear hábito"
    override fun getDescriptionEN() =
        "Create a new habit to track with optional daily goal and reminder time."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "crea un nuevo hábito para trackear con meta diaria opcional"
    override fun getParameters() = listOf(
        ToolParameter("habit", "string", "Habit name.", true),
        ToolParameter("goal", "string", "Daily goal (e.g. '8 vasos', '30 min', '1'). Default '1'.", false),
        ToolParameter("icon", "string", "Emoji icon. Default auto-assigned.", false),
        ToolParameter("reminder_time", "string", "Optional HH:MM to remind daily.", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val habit = requireString(params, "habit").trim().lowercase()
        val goal = optionalString(params, "goal", "1")
        val icon = optionalString(params, "icon", HabitStore.autoIcon(habit))
        val reminderTime = optionalString(params, "reminder_time", "")
        HabitStore.createHabit(habit, goal, icon, reminderTime)
        return ToolResult.success("Hábito '${icon} $habit' creado (meta diaria: $goal). ${if (reminderTime.isNotBlank()) "Recordatorio a las $reminderTime." else ""}")
    }
}

/**
 * Persistent habit storage and streak tracking.
 */
object HabitStore {
    private const val KEY_HABITS = "habit_store_habits_v1"
    private const val KEY_LOGS = "habit_store_logs_v1"
    private const val MAX_LOGS = 500

    data class Habit(
        val name: String,
        val goal: String = "1",
        val icon: String = "✅",
        val reminderTime: String = "",
        val createdAt: Long = System.currentTimeMillis(),
    )

    data class HabitLog(
        val habit: String,
        val date: String,  // yyyy-MM-dd
        val value: String,
        val note: String,
        val timestamp: Long,
    )

    private fun todayStr(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun log(habit: String, value: String, note: String): String {
        // Auto-create habit if not exists
        val habits = allHabits().toMutableMap()
        if (habit !in habits) {
            habits[habit] = Habit(name = habit, icon = autoIcon(habit))
            saveHabits(habits)
        }

        val today = todayStr()
        val logs = allLogs().toMutableList()
        val todayCount = logs.count { it.habit == habit && it.date == today }
        logs.add(HabitLog(habit, today, value, note, System.currentTimeMillis()))
        if (logs.size > MAX_LOGS) logs.removeAt(0)
        saveLogs(logs)

        val h = habits[habit]!!
        val streak = calculateStreak(habit, logs)
        val emoji = if (streak >= 7) "🔥" else if (streak >= 3) "⚡" else "✓"
        val streakText = if (streak > 1) " (racha: $streak días $emoji)" else ""
        val valueText = if (value.isNotBlank()) " — $value" else ""
        return "${h.icon} $habit registrado$valueText$streakText"
    }

    fun statusFor(habit: String): String {
        val habits = allHabits()
        val h = habits[habit] ?: return "No estoy rastreando '$habit'. ¿Quieres que lo añada?"
        val logs = allLogs()
        val streak = calculateStreak(habit, logs)
        val today = todayStr()
        val todayLogs = logs.filter { it.habit == habit && it.date == today }
        val weekLogs = logs.filter { it.habit == habit && isThisWeek(it.date) }

        return buildString {
            append("${h.icon} $habit\n")
            append("Hoy: ${todayLogs.size}/${h.goal}\n")
            append("Esta semana: ${weekLogs.size} veces\n")
            append("Racha actual: $streak días${if (streak >= 7) " 🔥" else ""}\n")
            if (todayLogs.isNotEmpty() && todayLogs.last().value.isNotBlank()) {
                append("Último: ${todayLogs.last().value}")
            }
        }
    }

    fun fullStatus(): String {
        val habits = allHabits()
        if (habits.isEmpty()) return "No hay hábitos configurados. Dime qué hábitos quieres trackear."
        val logs = allLogs()
        val today = todayStr()

        return buildString {
            append("📊 Tus hábitos:\n")
            habits.forEach { (name, h) ->
                val streak = calculateStreak(name, logs)
                val todayCount = logs.count { it.habit == name && it.date == today }
                val done = todayCount > 0
                val mark = if (done) "✓" else "○"
                val streakStr = if (streak >= 3) " 🔥$streak" else if (streak > 0) " ×$streak" else ""
                append("$mark ${h.icon} $name ($todayCount/${h.goal})$streakStr\n")
            }
            val completedToday = habits.count { (name, _) ->
                logs.any { it.habit == name && it.date == today }
            }
            append("\nHoy: $completedToday/${habits.size} completados")
        }
    }

    fun calculateStreak(habit: String, logs: List<HabitLog>): Int {
        val dates = logs.filter { it.habit == habit }
            .map { it.date }.distinct().sorted().reversed()
        if (dates.isEmpty()) return 0

        val today = todayStr()
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(
            Date(System.currentTimeMillis() - 86_400_000))

        // Streak must include today or yesterday
        if (dates.first() != today && dates.first() != yesterday) return 0

        var streak = 1
        for (i in 0 until dates.size - 1) {
            val current = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dates[i])!!.time
            val prev = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dates[i + 1])!!.time
            if (current - prev <= 86_400_000 + 3_600_000) { // 25h tolerance
                streak++
            } else break
        }
        return streak
    }

    private fun isThisWeek(dateStr: String): Boolean {
        val date = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr)?.time }.getOrNull() ?: return false
        return System.currentTimeMillis() - date < 7 * 86_400_000
    }

    fun autoIcon(habit: String): String = when {
        habit.contains("agua") || habit.contains("water") -> "💧"
        habit.contains("ejercicio") || habit.contains("gym") || habit.contains("exercise") -> "🏋️"
        habit.contains("medit") -> "🧘"
        habit.contains("le") || habit.contains("read") || habit.contains("libro") -> "📖"
        habit.contains("dormir") || habit.contains("sleep") -> "😴"
        habit.contains("caminar") || habit.contains("walk") || habit.contains("pasos") -> "🚶"
        habit.contains("fruta") || habit.contains("verdura") || habit.contains("comida") -> "🥗"
        habit.contains("fumar") || habit.contains("smok") -> "🚭"
        habit.contains("estudiar") || habit.contains("study") -> "📚"
        habit.contains("correr") || habit.contains("run") -> "🏃"
        habit.contains("yoga") -> "🧘"
        habit.contains("vitamina") || habit.contains("medicina") -> "💊"
        else -> "✅"
    }

    fun createHabit(name: String, goal: String, icon: String, reminderTime: String) {
        val habits = allHabits().toMutableMap()
        habits[name] = Habit(name, goal, icon, reminderTime)
        saveHabits(habits)
        // Auto-set reminder if time provided
        if (reminderTime.isNotBlank()) {
            runCatching {
                val ts = com.blackclaw.android.assistant.AssistantTime.parse(reminderTime)
                if (ts > 0) {
                    val item = com.blackclaw.android.assistant.AssistantStore.create(
                        type = com.blackclaw.android.assistant.AssistantItemType.REMINDER,
                        title = "$icon ¿Completaste tu hábito de $name?",
                        triggerAtMs = ts, repeat = "daily", category = "habit", source = "ai")
                    com.blackclaw.android.assistant.AssistantScheduler.arm(
                        com.blackclaw.android.ClawApplication.instance, item)
                }
            }
        }
    }

    @Synchronized private fun allHabits(): Map<String, Habit> {
        val raw = KVUtils.getString(KEY_HABITS, "")
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associate { key ->
                val o = obj.getJSONObject(key)
                key to Habit(o.optString("name", key), o.optString("goal", "1"),
                    o.optString("icon", "✅"), o.optString("reminder", ""),
                    o.optLong("created", 0))
            }
        }.getOrDefault(emptyMap())
    }

    @Synchronized private fun saveHabits(habits: Map<String, Habit>) {
        val obj = JSONObject()
        habits.forEach { (k, v) ->
            obj.put(k, JSONObject().apply {
                put("name", v.name); put("goal", v.goal); put("icon", v.icon)
                put("reminder", v.reminderTime); put("created", v.createdAt)
            })
        }
        KVUtils.putString(KEY_HABITS, obj.toString()); KVUtils.sync()
    }

    @Synchronized private fun allLogs(): List<HabitLog> {
        val raw = KVUtils.getString(KEY_LOGS, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                HabitLog(o.optString("h"), o.optString("d"), o.optString("v", ""),
                    o.optString("n", ""), o.optLong("t"))
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized private fun saveLogs(logs: List<HabitLog>) {
        val arr = JSONArray()
        logs.forEach { l ->
            arr.put(JSONObject().apply {
                put("h", l.habit); put("d", l.date); put("v", l.value)
                put("n", l.note); put("t", l.timestamp)
            })
        }
        KVUtils.putString(KEY_LOGS, arr.toString()); KVUtils.sync()
    }
}
