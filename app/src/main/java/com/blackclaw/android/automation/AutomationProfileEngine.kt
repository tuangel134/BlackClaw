package com.blackclaw.android.automation

import android.content.Context
import android.location.Location
import com.blackclaw.android.assistant.AssistantReceiver
import com.blackclaw.android.assistant.RoutineEngine
import com.blackclaw.android.tool.ToolRegistry
import com.blackclaw.android.tool.guard.ToolExecutionContext
import com.blackclaw.android.tool.guard.ToolRiskPolicy
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Deterministic runtime for [AutomationProfileStore.Profile].
 *
 * The AI may author a profile, but it is never the event loop: triggers and conditions
 * are evaluated locally and actions are bounded, audited tool calls. This is what makes
 * an automation reliable when the network or model is unavailable.
 */
object AutomationProfileEngine {
    private const val TAG = "AutomationProfileEngine"
    private const val VARIABLE_PREFIX = "automation_variable_"
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "automation-dispatch").apply { isDaemon = true }
    }
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "automation-profile").apply { isDaemon = true }
    }
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val profileLocks = ConcurrentHashMap<String, Any>()
    private val jobs = ConcurrentHashMap<String, Future<*>>()
    private const val LOCATION_STATE_PREFIX = "automation_profile_location_inside_"

    data class Event(
        val type: AutomationProfileStore.TriggerType,
        val attributes: Map<String, String> = emptyMap(),
        val atMs: Long = System.currentTimeMillis(),
    )

    /** Submit an event without blocking NotificationListener/Accessibility callbacks. */
    fun emit(context: Context, event: Event) {
        val appContext = context.applicationContext
        dispatcher.execute {
            runCatching {
                AutomationProfileStore.list()
                    .asSequence()
                    .filter { it.enabled && AutomationProfileValidator.validate(it).isEmpty() }
                    .filter { it.triggers.any { trigger -> matches(trigger, event) } }
                    .filter { conditionsMatch(it, event) }
                    .filter { cooldownReady(it, event.atMs) }
                    .filter { dailyLimitReady(it, event.atMs) }
                    .forEach { profile -> scheduleProfile(appContext, profile, event, force = false) }
            }.onFailure { XLog.w(TAG, "Event dispatch failed: ${event.type}", it) }
        }
    }

    /** Bridges the existing low-power location checker to enter/exit profiles. */
    fun onLocation(context: Context, location: Location) {
        AutomationProfileStore.list()
            .filter { it.enabled && AutomationProfileValidator.validate(it).isEmpty() }
            .forEach { profile ->
                val locationTriggers = profile.triggers.filter { trigger ->
                    trigger.type == AutomationProfileStore.TriggerType.LOCATION_ENTER ||
                        trigger.type == AutomationProfileStore.TriggerType.LOCATION_EXIT
                }
                locationTriggers.groupBy { trigger ->
                    "${trigger.params["latitude"]}|${trigger.params["longitude"]}|${trigger.params["radius_m"] ?: 150}"
                }.forEach target@ { (_, triggersAtTarget) ->
                    val firstTrigger = triggersAtTarget.first()
                    val targetLat = firstTrigger.params["latitude"]?.toString()?.toDoubleOrNull() ?: return@target
                    val targetLon = firstTrigger.params["longitude"]?.toString()?.toDoubleOrNull() ?: return@target
                    val radius = automationFloat(firstTrigger.params["radius_m"])?.coerceAtLeast(1f) ?: 150f
                    val distance = FloatArray(1)
                    Location.distanceBetween(location.latitude, location.longitude, targetLat, targetLon, distance)
                    val inside = distance[0] <= radius
                    val stateKey = LOCATION_STATE_PREFIX + profile.id + "_" + targetLat + "_" + targetLon + "_" + radius
                    val previous = KVUtils.getString(stateKey, "").takeIf { it.isNotBlank() }?.toBoolean()
                    KVUtils.putString(stateKey, inside.toString())
                    KVUtils.sync()
                    if (previous == null) return@target
                    triggersAtTarget.forEach { trigger ->
                        val crossed = when (trigger.type) {
                            AutomationProfileStore.TriggerType.LOCATION_ENTER -> !previous && inside
                            AutomationProfileStore.TriggerType.LOCATION_EXIT -> previous && !inside
                            else -> false
                        }
                        if (crossed) emit(context, Event(trigger.type, mapOf(
                            "latitude" to location.latitude.toString(),
                            "longitude" to location.longitude.toString(),
                        )))
                    }
                }
            }
    }

    @JvmStatic
    fun onAppForeground(context: Context, packageName: String) =
        emit(context, Event(AutomationProfileStore.TriggerType.APP_FOREGROUND, mapOf("package" to packageName)))

    @JvmStatic
    fun onAppClosed(context: Context, packageName: String) =
        emit(context, Event(AutomationProfileStore.TriggerType.APP_CLOSED, mapOf("package" to packageName)))

    @JvmStatic
    fun emitSystemEvent(context: Context, type: AutomationProfileStore.TriggerType, attributes: Map<String, String> = emptyMap()) =
        emit(context, Event(type, attributes))

    /** Run from the assistant's explicit “test this automation” action. */
    fun runNow(context: Context, profile: AutomationProfileStore.Profile): Result<Unit> {
        val errors = AutomationProfileValidator.validate(profile)
        if (errors.isNotEmpty()) return Result.failure(IllegalArgumentException(errors.joinToString(" ")))
        if (profile.actions.any { it.requireConfirmation } && profile.approvedAtMs <= 0L) {
            return Result.failure(IllegalStateException("El perfil necesita confirmación del usuario antes de ejecutar acciones sensibles."))
        }
        dispatcher.execute {
            scheduleProfile(context.applicationContext, profile, Event(AutomationProfileStore.TriggerType.MANUAL), force = true)
        }
        return Result.success(Unit)
    }

    private fun scheduleProfile(context: Context, profile: AutomationProfileStore.Profile, event: Event, force: Boolean) {
        when (profile.concurrency) {
            AutomationProfileStore.Concurrency.SKIP_IF_RUNNING -> {
                if (!running.add(profile.id)) {
                    XLog.i(TAG, "Skipping already-running profile ${profile.id}")
                    return
                }
                workers.execute {
                    try { runEligible(context, profile, event, force) }
                    finally { running.remove(profile.id) }
                }
            }
            AutomationProfileStore.Concurrency.QUEUE -> {
                workers.execute {
                    synchronized(profileLocks.getOrPut(profile.id) { Any() }) {
                        runEligible(context, profile, event, force)
                    }
                }
            }
            AutomationProfileStore.Concurrency.REPLACE -> {
                jobs.remove(profile.id)?.cancel(true)
                val future = workers.submit {
                    synchronized(profileLocks.getOrPut(profile.id) { Any() }) {
                        runEligible(context, profile, event, force)
                    }
                }
                jobs[profile.id] = future
            }
        }
    }

    private fun runEligible(
        context: Context,
        profile: AutomationProfileStore.Profile,
        event: Event,
        force: Boolean,
    ) {
        val latest = AutomationProfileStore.find(profile.id) ?: return
        if (!force && (!latest.enabled || !cooldownReady(latest, System.currentTimeMillis()) ||
                !dailyLimitReady(latest, System.currentTimeMillis()))) return
        runProfile(context, latest, event)
    }

    internal fun matches(trigger: AutomationProfileStore.Trigger, event: Event): Boolean {
        if (trigger.type != event.type) return false
        val p = trigger.params
        val a = event.attributes
        return when (trigger.type) {
            AutomationProfileStore.TriggerType.MANUAL,
            AutomationProfileStore.TriggerType.BOOT -> true
            AutomationProfileStore.TriggerType.TIME -> {
                val hour = automationInt(a["hour"]) ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val minute = automationInt(a["minute"]) ?: Calendar.getInstance().get(Calendar.MINUTE)
                val targetHour = automationInt(p["hour"]) ?: hour
                val targetMinute = automationInt(p["minute"]) ?: minute
                hour == targetHour && minute == targetMinute
            }
            AutomationProfileStore.TriggerType.NOTIFICATION -> {
                val packageName = p["package"]?.toString().orEmpty()
                val match = p["match"]?.toString().orEmpty()
                (packageName.isBlank() || packageName == a["package"]) &&
                    (match.isBlank() || "${a["title"].orEmpty()}\n${a["text"].orEmpty()}".contains(match, true))
            }
            AutomationProfileStore.TriggerType.LOCATION_ENTER,
            AutomationProfileStore.TriggerType.LOCATION_EXIT -> locationMatches(p, a)
            AutomationProfileStore.TriggerType.APP_FOREGROUND,
            AutomationProfileStore.TriggerType.APP_CLOSED -> appMatches(p, a)
            AutomationProfileStore.TriggerType.CONNECTIVITY ->
                stringMatches(p["state"], a["state"]) && stringMatches(p["transport"], a["transport"])
            AutomationProfileStore.TriggerType.BATTERY -> {
                val level = automationInt(a["level"]) ?: return false
                val min = automationInt(p["min"]) ?: 0
                val max = automationInt(p["max"]) ?: 100
                level in min..max
            }
            AutomationProfileStore.TriggerType.CHARGING ->
                booleanMatches(p["value"], a["charging"])
            AutomationProfileStore.TriggerType.SCREEN ->
                stringMatches(p["state"], a["state"])
            AutomationProfileStore.TriggerType.CALL_STATE ->
                stringMatches(p["state"], a["state"]) && stringMatches(p["number"], a["number"])
            AutomationProfileStore.TriggerType.SMS_RECEIVED ->
                stringMatches(p["sender"], a["sender"]) &&
                    (p["match"]?.toString().isNullOrBlank() || a["body"].orEmpty().contains(p["match"].toString(), true))
            AutomationProfileStore.TriggerType.HEADSET,
            AutomationProfileStore.TriggerType.BLUETOOTH,
            AutomationProfileStore.TriggerType.WIFI -> {
                booleanMatches(p["connected"], a["connected"]) &&
                    stringMatches(p["name"], a["name"]) && stringMatches(p["ssid"], a["ssid"])
            }
            AutomationProfileStore.TriggerType.WEBHOOK ->
                p["token"]?.toString().orEmpty().let { expected ->
                    expected.isNotBlank() && expected == a["token"]
                }
        }
    }

    private fun conditionsMatch(profile: AutomationProfileStore.Profile, event: Event): Boolean =
        profile.conditions.all { condition ->
            val result = conditionMatches(condition, event)
            if (condition.negate) !result else result
        }

    private fun conditionMatches(condition: AutomationProfileStore.Condition, event: Event): Boolean {
        val p = condition.params
        val a = event.attributes
        return when (condition.type) {
            AutomationProfileStore.ConditionType.TIME_WINDOW -> {
                val now = a["time"] ?: SimpleDateFormat("HH:mm", Locale.US).format(Date(event.atMs))
                isTimeInWindow(now, p["start"]?.toString(), p["end"]?.toString())
            }
            AutomationProfileStore.ConditionType.DAY_OF_WEEK -> {
                val day = a["day"] ?: Calendar.getInstance().apply { timeInMillis = event.atMs }
                    .get(Calendar.DAY_OF_WEEK).toString()
                p["days"].toString().split(',', '|', ' ').filter { it.isNotBlank() }
                    .any { it.equals(day, true) || it.equals(dayName(day), true) }
            }
            AutomationProfileStore.ConditionType.APP -> stringMatches(p["package"], a["package"])
            AutomationProfileStore.ConditionType.CONNECTIVITY ->
                stringMatches(p["state"], a["state"]) && stringMatches(p["transport"], a["transport"])
            AutomationProfileStore.ConditionType.BATTERY_LEVEL -> {
                val level = automationInt(a["level"]) ?: return false
                val min = automationInt(p["min"]) ?: 0
                val max = automationInt(p["max"]) ?: 100
                level in min..max
            }
            AutomationProfileStore.ConditionType.CHARGING -> booleanMatches(p["value"], a["charging"])
            AutomationProfileStore.ConditionType.SCREEN -> stringMatches(p["state"], a["state"])
            AutomationProfileStore.ConditionType.VARIABLE -> {
                val current = KVUtils.getString(VARIABLE_PREFIX + p["name"].toString(), "")
                val expected = p["equals"]?.toString()
                expected == null || current == expected
            }
            AutomationProfileStore.ConditionType.NOTIFICATION -> {
                val match = p["match"]?.toString().orEmpty()
                match.isBlank() || "${a["title"].orEmpty()}\n${a["text"].orEmpty()}".contains(match, true)
            }
        }
    }

    private fun runProfile(context: Context, profile: AutomationProfileStore.Profile, event: Event) {
        XLog.i(TAG, "Running '${profile.name}' (${profile.id}) on ${event.type}")
        val deadline = System.currentTimeMillis() + profile.maxRuntimeMs
        val result = executeActions(context, profile, profile.actions, event, deadline, depth = 0)
        if (result.isSuccess) AutomationProfileStore.markRun(profile.id, eventType = event.type.name)
        else {
            val error = result.exceptionOrNull()?.message ?: "acción falló"
            AutomationProfileStore.markFailure(profile.id, error, event.type.name)
            XLog.w(TAG, "Profile ${profile.id} stopped: $error")
        }
    }

    private fun executeActions(
        context: Context,
        profile: AutomationProfileStore.Profile,
        actions: List<AutomationProfileStore.Action>,
        event: Event,
        deadline: Long,
        depth: Int,
    ): Result<Unit> {
        if (depth > 3) return Result.failure(IllegalStateException("profundidad máxima de if/loop alcanzada"))
        for ((index, action) in actions.withIndex()) {
            if (System.currentTimeMillis() > deadline) {
                return Result.failure(IllegalStateException("tiempo máximo del perfil alcanzado"))
            }
            if (action.requireConfirmation && profile.approvedAtMs <= 0L) {
                return Result.failure(IllegalStateException("acción ${index + 1} requiere confirmación"))
            }
            val result = runCatching { executeAction(context, profile, action, event, deadline, depth) }
                .getOrElse { Result.failure<Unit>(it) }
            if (result.isFailure) return result
        }
        return Result.success(Unit)
    }

    private fun executeAction(
        context: Context,
        profile: AutomationProfileStore.Profile,
        action: AutomationProfileStore.Action,
        event: Event,
        deadline: Long,
        depth: Int,
    ): Result<Unit> = when (action.type) {
        AutomationProfileStore.ActionType.WAIT -> {
            val ms = automationLong(action.params["ms"])?.coerceIn(0L, 60_000L) ?: 0L
            if (System.currentTimeMillis() + ms > deadline) {
                return Result.failure(IllegalStateException("tiempo máximo del perfil alcanzado"))
            }
            Thread.sleep(ms)
            Result.success(Unit)
        }
        AutomationProfileStore.ActionType.SET_VARIABLE -> {
            val name = action.params["name"]?.toString().orEmpty()
            val value = action.params["value"]?.toString().orEmpty()
            KVUtils.putString(VARIABLE_PREFIX + name.take(80), value.take(2_000)); KVUtils.sync()
            Result.success(Unit)
        }
        AutomationProfileStore.ActionType.NOTIFY -> {
            AssistantReceiver.postNotification(
                context, action.params["title"]?.toString() ?: profile.name,
                action.params["text"]?.toString().orEmpty(),
                action.params["highPriority"]?.toString()?.toBoolean() ?: false,
            )
            Result.success(Unit)
        }
        AutomationProfileStore.ActionType.RUN_ROUTINE -> {
            val name = action.params["name"]?.toString().orEmpty()
            val routine = RoutineEngine.findByName(name) ?: RoutineEngine.find(name)
                ?: return Result.failure(IllegalArgumentException("No encontré rutina '$name'."))
            ToolExecutionContext.setOrigin(ToolRiskPolicy.Origin.AUTOMATION)
            try {
                val result = RoutineEngine.execute(routine)
                if (result.success) Result.success(Unit)
                else Result.failure(IllegalStateException(result.summary))
            } finally { ToolExecutionContext.reset() }
        }
        AutomationProfileStore.ActionType.TOOL -> {
            val toolName = action.params["tool"]?.toString().orEmpty()
            val rawParams = action.params["params"]
            @Suppress("UNCHECKED_CAST")
            val toolParams = (rawParams as? Map<String, Any>).orEmpty()
            ToolExecutionContext.setOrigin(ToolRiskPolicy.Origin.AUTOMATION)
            try {
                val result = ToolRegistry.getInstance().executeTool(toolName, toolParams)
                if (result.isSuccess) Result.success(Unit)
                else Result.failure(IllegalStateException(result.error ?: "tool falló"))
            } finally { ToolExecutionContext.reset() }
        }
        AutomationProfileStore.ActionType.AGENT_TASK -> {
            val text = action.params["text"]?.toString().orEmpty()
            if (text.isBlank()) Result.failure(IllegalArgumentException("La tarea está vacía."))
            else {
                AutomationEngine.executeTask(context, text, "profile:${profile.id}")
                Result.success(Unit)
            }
        }
        AutomationProfileStore.ActionType.IF -> {
            val condition = conditionFrom(action) ?: return Result.failure(
                IllegalArgumentException("if necesita condition_type y condition_params."))
            val branch = if (conditionMatches(condition, event)) action.params["then"] else action.params["else"]
            executeActions(context, profile, nestedActions(branch), event, deadline, depth + 1)
        }
        AutomationProfileStore.ActionType.LOOP -> {
            val count = automationInt(action.params["count"]) ?: 0
            val nested = nestedActions(action.params["actions"])
            if (count !in 1..20 || nested.isEmpty()) {
                Result.failure(IllegalArgumentException("loop necesita count 1..20 y actions."))
            } else {
                var result: Result<Unit> = Result.success(Unit)
                repeat(count) {
                    if (result.isSuccess) result = executeActions(context, profile, nested, event, deadline, depth + 1)
                }
                result
            }
        }
    }

    private fun conditionFrom(action: AutomationProfileStore.Action): AutomationProfileStore.Condition? {
        val nested = action.params["condition"] as? Map<*, *>
        val typeRaw = nested?.get("type")?.toString() ?: action.params["condition_type"]?.toString()
        val type = runCatching {
            AutomationProfileStore.ConditionType.valueOf(typeRaw?.uppercase()?.replace('-', '_') ?: return null)
        }.getOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        val params = (nested?.get("params") as? Map<String, Any>)
            ?: (action.params["condition_params"] as? Map<String, Any>)
            ?: emptyMap()
        val negate = nested?.get("negate")?.toString()?.toBoolean()
            ?: action.params["negate"]?.toString()?.toBoolean() ?: false
        return AutomationProfileStore.Condition(type, params, negate)
    }

    private fun nestedActions(value: Any?): List<AutomationProfileStore.Action> =
        (value as? List<*>)?.mapNotNull { row ->
            @Suppress("UNCHECKED_CAST")
            val map = row as? Map<String, Any> ?: return@mapNotNull null
            val rawType = map["type"]?.toString()?.uppercase()?.replace('-', '_') ?: return@mapNotNull null
            val type = runCatching { AutomationProfileStore.ActionType.valueOf(rawType) }.getOrNull()
                ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val nestedParams = map["params"] as? Map<String, Any> ?: emptyMap()
            val sensitive = type == AutomationProfileStore.ActionType.AGENT_TASK ||
                (type == AutomationProfileStore.ActionType.TOOL &&
                    ToolRiskPolicy.classify(nestedParams["tool"]?.toString().orEmpty()) != ToolRiskPolicy.Tier.SAFE)
            AutomationProfileStore.Action(
                type,
                nestedParams,
                map["requireConfirmation"]?.toString()?.toBoolean() == true || sensitive,
            )
        }.orEmpty()

    private fun cooldownReady(profile: AutomationProfileStore.Profile, now: Long): Boolean =
        profile.lastRunAtMs <= 0L || now - profile.lastRunAtMs >= profile.cooldownMs

    private fun dailyLimitReady(profile: AutomationProfileStore.Profile, now: Long): Boolean {
        if (profile.maxRunsPerDay <= 0) return true
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        return profile.runDayKey != dayKey || profile.runsToday < profile.maxRunsPerDay
    }

    private fun locationMatches(params: Map<String, Any>, attrs: Map<String, String>): Boolean {
        val lat = attrs["latitude"]?.toDoubleOrNull() ?: return false
        val lon = attrs["longitude"]?.toDoubleOrNull() ?: return false
        val targetLat = params["latitude"]?.toString()?.toDoubleOrNull() ?: return false
        val targetLon = params["longitude"]?.toString()?.toDoubleOrNull() ?: return false
        val radius = automationFloat(params["radius_m"])?.coerceAtLeast(1f) ?: 150f
        val distance = FloatArray(1)
        Location.distanceBetween(lat, lon, targetLat, targetLon, distance)
        return distance[0] <= radius
    }

    private fun appMatches(params: Map<String, Any>, attrs: Map<String, String>): Boolean =
        stringMatches(params["package"], attrs["package"])

    private fun stringMatches(expected: Any?, actual: String?): Boolean =
        expected?.toString().isNullOrBlank() || expected.toString().equals(actual.orEmpty(), true)

    private fun booleanMatches(expected: Any?, actual: String?): Boolean =
        expected?.toString().isNullOrBlank() || expected.toString().toBoolean() == actual?.toBoolean()

    private fun isTimeInWindow(now: String, start: String?, end: String?): Boolean {
        if (start.isNullOrBlank() || end.isNullOrBlank()) return true
        val current = now.toMinutes(); val from = start.toMinutes(); val to = end.toMinutes()
        return if (from <= to) current in from..to else current >= from || current <= to
    }

    private fun String.toMinutes(): Int {
        val parts = split(':'); return (parts.getOrNull(0)?.toIntOrNull() ?: 0) * 60 +
            (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun dayName(value: String): String = when (value) {
        "1" -> "sun"; "2" -> "mon"; "3" -> "tue"; "4" -> "wed"; "5" -> "thu"; "6" -> "fri"; "7" -> "sat"
        else -> value.lowercase()
    }
}
