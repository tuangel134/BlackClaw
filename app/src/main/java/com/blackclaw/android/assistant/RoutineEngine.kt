package com.blackclaw.android.assistant

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.ToolRegistry
import com.blackclaw.android.memory.JsonListStore
import com.blackclaw.android.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Routines — multi-step automated sequences that run at a time or on trigger.
 *
 * A routine is like a mini-skill created by the AI or user:
 * "Morning routine": alarm 7:00 → briefing → weather → read calendar
 * "Night routine": set alarm for tomorrow → silence phone → note what to do tomorrow
 * "Workout": timer 45min → play spotify playlist → reminder to stretch
 * "Focus mode": silence → DND on → timer 2h → notification when done
 *
 * Routines are stored as a list of steps. Each step is a tool call with params.
 * The engine executes them sequentially with configurable delays between steps.
 */
object RoutineEngine {

    private const val TAG = "RoutineEngine"
    private const val KEY_ROUTINES = "assistant_routines_v1"
    private const val MAX_ROUTINES = 30

    data class RoutineStep(
        val toolName: String,
        val params: Map<String, Any>,
        val delayAfterMs: Long = 1000,   // pause between steps
        val description: String = "",     // human-readable description
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("tool", toolName)
            put("params", JSONObject(params.mapValues { it.value.toString() }))
            put("delay", delayAfterMs)
            put("desc", description)
        }

        companion object {
            fun fromJson(o: JSONObject): RoutineStep {
                val paramsObj = o.optJSONObject("params") ?: JSONObject()
                val params = paramsObj.keys().asSequence().associate { it to (paramsObj.get(it) as Any) }
                return RoutineStep(
                    toolName = o.optString("tool", ""),
                    params = params,
                    delayAfterMs = o.optLong("delay", 1000),
                    description = o.optString("desc", ""),
                )
            }
        }
    }

    data class Routine(
        val id: String,
        val name: String,
        val description: String = "",
        val icon: String = "⚡",         // emoji icon
        val steps: List<RoutineStep>,
        val triggerTime: String = "",    // "HH:MM" for scheduled, "" for manual
        val triggerDays: String = "",    // "daily" | "weekdays" | "mon,wed,fri" | ""
        val enabled: Boolean = true,
        val createdAt: Long = System.currentTimeMillis(),
        val lastRunAt: Long = 0,
        val runCount: Int = 0,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("icon", icon)
            put("steps", JSONArray().also { arr -> steps.forEach { arr.put(it.toJson()) } })
            put("triggerTime", triggerTime)
            put("triggerDays", triggerDays)
            put("enabled", enabled)
            put("createdAt", createdAt)
            put("lastRunAt", lastRunAt)
            put("runCount", runCount)
        }

        companion object {
            fun fromJson(o: JSONObject): Routine {
                val stepsArr = o.optJSONArray("steps") ?: JSONArray()
                val steps = (0 until stepsArr.length()).mapNotNull { i ->
                    runCatching { RoutineStep.fromJson(stepsArr.getJSONObject(i)) }.getOrNull()
                }
                return Routine(
                    id = o.optString("id", UUID.randomUUID().toString().take(8)),
                    name = o.optString("name", ""),
                    description = o.optString("description", ""),
                    icon = o.optString("icon", "⚡"),
                    steps = steps,
                    triggerTime = o.optString("triggerTime", ""),
                    triggerDays = o.optString("triggerDays", ""),
                    enabled = o.optBoolean("enabled", true),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    lastRunAt = o.optLong("lastRunAt", 0),
                    runCount = o.optInt("runCount", 0),
                )
            }
        }
    }

    // ── Storage ──

    /**
     * Storage mechanics come from [JsonListStore].
     *
     * What this migration actually buys, beyond removing the duplicated
     * read-parse-write loop:
     *
     *  - **No fsync per write.** `saveAll` called `KVUtils.sync()` on every create,
     *    update and delete, including the `update` that only bumps `runCount` after a
     *    routine runs. MMKV already survives process death through its mmap.
     *  - **A routine that fails to parse is logged.** It used to be dropped by a silent
     *    `getOrNull()`, so a routine the user built could disappear with no trace.
     *  - **The cap evicts by recency, not by index.** `removeAt(0)` deleted whichever
     *    routine happened to be stored first, which after any `update` is not the oldest
     *    one. [timestampOf] makes "oldest" mean oldest.
     */
    private val store = object : JsonListStore<Routine>(KEY_ROUTINES, MAX_ROUTINES, encrypted = true) {
        override val logTag = TAG
        override fun toJson(item: Routine): JSONObject = item.toJson()

        // A routine with no steps cannot run and a nameless one cannot be invoked, so
        // neither is worth keeping a slot for.
        override fun fromJson(json: JSONObject): Routine? =
            runCatching { Routine.fromJson(json) }.getOrNull()
                ?.takeIf { it.name.isNotBlank() && it.steps.isNotEmpty() }

        override fun timestampOf(item: Routine): Long = item.createdAt
    }

    fun all(): List<Routine> = store.all()

    fun find(id: String): Routine? = all().firstOrNull { it.id == id }
    fun findByName(name: String): Routine? {
        val lower = name.lowercase()
        return all().firstOrNull { it.name.lowercase().contains(lower) }
    }

    fun create(routine: Routine): Routine? {
        val withId = if (routine.id.isBlank())
            routine.copy(id = UUID.randomUUID().toString().take(8))
        else routine
        val persisted = store.append(withId)
        val saved = persisted.firstOrNull { it.id == withId.id && it == withId }
        if (saved == null) {
            XLog.e(TAG, "Could not persist routine securely: stepCount=${withId.steps.size}")
            return null
        }
        XLog.i(TAG, "Created routine: stepCount=${withId.steps.size}")
        return saved
    }

    fun update(routine: Routine): Boolean {
        val persisted = store.upsert(routine) { it.id == routine.id }
        return persisted.any { it.id == routine.id && it == routine }
    }

    fun delete(id: String): Boolean = store.removeAll { it.id == id } > 0

    // ── Execution ──

    data class ExecutionResult(
        val success: Boolean,
        val stepsCompleted: Int,
        val totalSteps: Int,
        val failedStep: String? = null,
        val summary: String = "",
    )

    /**
     * Execute a routine step by step.
     * Returns a summary of what happened.
     */
    fun execute(routine: Routine, onProgress: ((Int, Int, String) -> Unit)? = null): ExecutionResult {
        XLog.i(TAG, "Executing routine: steps=${routine.steps.size}")
        val registry = ToolRegistry.getInstance()
        var completed = 0

        for ((index, step) in routine.steps.withIndex()) {
            onProgress?.invoke(index + 1, routine.steps.size, step.description.ifBlank { step.toolName })

            val result = registry.executeTool(step.toolName, step.params)
            if (!result.isSuccess) {
                XLog.w(TAG, "Routine step ${index + 1} failed: tool=${step.toolName} errorChars=${result.error?.length ?: 0}")
                // Mark as run even if partially failed. A secure-store failure does
                // not change the execution result, but is surfaced in diagnostics.
                if (!update(routine.copy(lastRunAt = System.currentTimeMillis(), runCount = routine.runCount + 1))) {
                    XLog.e(TAG, "Could not persist routine run metadata after failure")
                }
                return ExecutionResult(
                    success = false,
                    stepsCompleted = completed,
                    totalSteps = routine.steps.size,
                    failedStep = "${step.toolName}: ${result.error}",
                    summary = "Rutina '${routine.name}': ${completed}/${routine.steps.size} pasos completados. Falló: ${step.description}",
                )
            }
            completed++

            if (step.delayAfterMs > 0 && index < routine.steps.size - 1) {
                try {
                    Thread.sleep(step.delayAfterMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        if (!update(routine.copy(lastRunAt = System.currentTimeMillis(), runCount = routine.runCount + 1))) {
            XLog.e(TAG, "Could not persist routine run metadata after completion")
        }
        XLog.i(TAG, "Routine completed: steps=$completed")
        return ExecutionResult(
            success = true,
            stepsCompleted = completed,
            totalSteps = routine.steps.size,
            summary = "Rutina '${routine.name}' completada (${completed} pasos).",
        )
    }

    /**
     * Get routines that should trigger now based on their schedule.
     * Called from the briefing scheduler or a periodic check.
     */
    fun dueRoutines(): List<Routine> {
        val cal = java.util.Calendar.getInstance()
        val currentHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val currentMin = cal.get(java.util.Calendar.MINUTE)
        val currentDay = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val nowStr = "%02d:%02d".format(currentHour, currentMin)

        return all().filter { r ->
            if (!r.enabled || r.triggerTime.isBlank()) return@filter false
            if (r.triggerTime != nowStr) return@filter false
            // Check if already run today
            val lastRun = java.util.Calendar.getInstance().apply { timeInMillis = r.lastRunAt }
            if (lastRun.get(java.util.Calendar.DAY_OF_YEAR) == cal.get(java.util.Calendar.DAY_OF_YEAR) &&
                lastRun.get(java.util.Calendar.YEAR) == cal.get(java.util.Calendar.YEAR)) {
                return@filter false
            }
            // Check day filter
            when (r.triggerDays.lowercase()) {
                "", "daily" -> true
                "weekdays" -> currentDay in java.util.Calendar.MONDAY..java.util.Calendar.FRIDAY
                else -> {
                    val days = r.triggerDays.split(",").map { it.trim().lowercase() }
                    val dayMap = mapOf(
                        "mon" to 2, "tue" to 3, "wed" to 4, "thu" to 5, "fri" to 6, "sat" to 7, "sun" to 1,
                        "lun" to 2, "mar" to 3, "mie" to 4, "jue" to 5, "vie" to 6, "sab" to 7, "dom" to 1,
                    )
                    days.any { dayMap[it] == currentDay }
                }
            }
        }
    }

    /** Compact list for the assistant prompt — so the AI knows what routines exist. */
    fun asPromptSnippet(): String {
        val routines = all().filter { it.enabled }
        if (routines.isEmpty()) return ""
        return buildString {
            append("\n\n## Rutinas disponibles del usuario\n")
            routines.forEach { r ->
                append("- ${r.icon} ${r.name}")
                if (r.triggerTime.isNotBlank()) append(" (auto: ${r.triggerTime} ${r.triggerDays})")
                if (r.description.isNotBlank()) append(" — ${r.description}")
                append("\n")
            }
            append("El usuario puede pedir 'ejecuta mi rutina de mañana' y tú llamas run_routine.\n")
        }
    }
}
