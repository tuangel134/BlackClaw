package com.blackclaw.android.automation

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import com.blackclaw.android.assistant.AssistantReceiver
import com.blackclaw.android.assistant.RoutineEngine
import com.blackclaw.android.tool.ToolRegistry
import com.blackclaw.android.tool.guard.ToolExecutionContext
import com.blackclaw.android.tool.guard.ToolRiskPolicy
import com.blackclaw.android.server.ConfigServerPolicy
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.SecretStore
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
                    .filter { conditionsMatch(appContext, it, event) }
                    .filter { cooldownReady(it, event.atMs) }
                    .filter { dailyLimitReady(it, event.atMs) }
                    .forEach { profile -> scheduleProfile(appContext, profile, event, force = false) }
            }.onFailure { XLog.w(TAG, "Event dispatch failed: ${event.type}", it) }
        }
    }

    /** Platform-only fallback when Play Services geofencing is unavailable. */
    fun onLocation(context: Context, location: Location) {
        AutomationProfileStore.list()
            .filter { it.enabled && AutomationProfileValidator.validate(it).isEmpty() }
            .forEach { profile ->
                val groups = profile.triggers
                    .filter { it.type == AutomationProfileStore.TriggerType.LOCATION_ENTER || it.type == AutomationProfileStore.TriggerType.LOCATION_EXIT }
                    .mapNotNull { trigger -> AutomationLocationTarget.resolve(trigger.params)?.let { it to trigger } }
                    .groupBy({ AutomationLocationTarget.requestId(it.first) }, { it })
                groups.forEach { (_, rows) ->
                    val target = rows.first().first
                    val distance = FloatArray(1)
                    Location.distanceBetween(location.latitude, location.longitude, target.latitude, target.longitude, distance)
                    val inside = distance[0] <= target.radiusM
                    val stateKey = LOCATION_STATE_PREFIX + profile.id + "_" + AutomationLocationTarget.requestId(target)
                    val previous = SecretStore.getString(stateKey)?.toBooleanStrictOrNull()
                        ?: KVUtils.getString(stateKey, "").takeIf { it.isNotBlank() }?.toBooleanStrictOrNull()
                    if (SecretStore.putString(stateKey, inside.toString())) {
                        KVUtils.remove(stateKey); KVUtils.sync()
                    }
                    if (previous == null) return@forEach
                    rows.forEach { (_, trigger) ->
                        val crossed = when (trigger.type) {
                            AutomationProfileStore.TriggerType.LOCATION_ENTER -> !previous && inside
                            AutomationProfileStore.TriggerType.LOCATION_EXIT -> previous && !inside
                            else -> false
                        }
                        if (crossed) emit(context, Event(trigger.type, locationEventAttributes(target, location)))
                    }
                }
            }
    }

    /** Primary path called by [AutomationGeofenceReceiver]. */
    fun onGeofenceTransition(
        context: Context,
        type: AutomationProfileStore.TriggerType,
        target: AutomationLocationTarget.Target,
    ) {
        if (type != AutomationProfileStore.TriggerType.LOCATION_ENTER && type != AutomationProfileStore.TriggerType.LOCATION_EXIT) return
        emit(context, Event(type, locationEventAttributes(target, null)))
    }

    private fun locationEventAttributes(target: AutomationLocationTarget.Target, current: Location?): Map<String, String> = buildMap {
        // Keep coordinates local. Event templates only receive an opaque target id,
        // semantic place metadata and non-sensitive accuracy information.
        put("geofence_request_id", AutomationLocationTarget.requestId(target))
        if (target.placeId.isNotBlank()) put("place_id", target.placeId)
        if (target.placeName.isNotBlank()) put("place", target.placeName)
        current?.let { put("accuracy", it.accuracy.toString()) }
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
            AutomationProfileStore.TriggerType.BOOT,
            AutomationProfileStore.TriggerType.INTERVAL -> true
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
            AutomationProfileStore.TriggerType.CHARGING -> booleanMatches(p["value"], a["charging"])
            AutomationProfileStore.TriggerType.SCREEN -> stringMatches(p["state"], a["state"])
            AutomationProfileStore.TriggerType.CALL_STATE ->
                stringMatches(p["state"], a["state"]) && stringMatches(p["number"], a["number"])
            AutomationProfileStore.TriggerType.SMS_RECEIVED ->
                stringMatches(p["sender"], a["sender"]) &&
                    (p["match"]?.toString().isNullOrBlank() || a["body"].orEmpty().contains(p["match"].toString(), true))
            AutomationProfileStore.TriggerType.HEADSET,
            AutomationProfileStore.TriggerType.BLUETOOTH,
            AutomationProfileStore.TriggerType.WIFI ->
                booleanMatches(p["connected"], a["connected"]) &&
                    stringMatches(p["name"], a["name"]) && stringMatches(p["ssid"], a["ssid"])
            AutomationProfileStore.TriggerType.AIRPLANE_MODE,
            AutomationProfileStore.TriggerType.POWER_SAVE,
            AutomationProfileStore.TriggerType.DEVICE_IDLE -> booleanMatches(p["value"], a["value"])
            AutomationProfileStore.TriggerType.USB ->
                booleanMatches(p["connected"], a["connected"]) &&
                    stringMatches(p["vendor_id"], a["vendor_id"]) && stringMatches(p["product_id"], a["product_id"])
            AutomationProfileStore.TriggerType.STORAGE -> stringMatches(p["state"], a["state"])
            AutomationProfileStore.TriggerType.TIMEZONE -> stringMatches(p["id"], a["id"])
            AutomationProfileStore.TriggerType.LOCALE -> stringMatches(p["tag"], a["tag"])
            AutomationProfileStore.TriggerType.WEBHOOK -> p["token"]?.toString().orEmpty().let { expected ->
                expected.isNotBlank() && ConfigServerPolicy.tokensMatch(expected, a["token"])
            }
        }
    }

    private fun conditionsMatch(context: Context, profile: AutomationProfileStore.Profile, event: Event): Boolean {
        if (profile.conditions.isEmpty()) return true
        val values = profile.conditions.map { condition ->
            val raw = conditionMatches(context, condition, event)
            if (condition.negate) !raw else raw
        }
        return when (profile.conditionLogic) {
            AutomationProfileStore.ConditionLogic.ALL -> values.all { it }
            AutomationProfileStore.ConditionLogic.ANY -> values.any { it }
            AutomationProfileStore.ConditionLogic.NONE -> values.none { it }
            AutomationProfileStore.ConditionLogic.XOR -> values.count { it } == 1
        }
    }

    private fun conditionMatches(context: Context, condition: AutomationProfileStore.Condition, event: Event): Boolean {
        val p = condition.params
        val a = event.attributes
        return when (condition.type) {
            AutomationProfileStore.ConditionType.TIME_WINDOW -> {
                val now = a["time"] ?: SimpleDateFormat("HH:mm", Locale.US).format(Date(event.atMs))
                isTimeInWindow(now, p["start"]?.toString(), p["end"]?.toString())
            }
            AutomationProfileStore.ConditionType.DAY_OF_WEEK -> {
                val day = a["day"] ?: Calendar.getInstance().apply { timeInMillis = event.atMs }.get(Calendar.DAY_OF_WEEK).toString()
                p["days"].toString().split(',', '|', ' ').filter { it.isNotBlank() }
                    .any { it.equals(day, true) || it.equals(dayName(day), true) }
            }
            AutomationProfileStore.ConditionType.APP -> stringMatches(p["package"], a["package"])
            AutomationProfileStore.ConditionType.CONNECTIVITY -> {
                val state = currentConnectivity(context)
                stringMatches(p["state"], a["state"] ?: state.first) && stringMatches(p["transport"], a["transport"] ?: state.second)
            }
            AutomationProfileStore.ConditionType.BATTERY_LEVEL -> {
                val level = automationInt(a["level"]) ?: currentBatteryLevel(context) ?: return false
                val min = automationInt(p["min"]) ?: 0
                val max = automationInt(p["max"]) ?: 100
                level in min..max
            }
            AutomationProfileStore.ConditionType.CHARGING -> booleanMatches(p["value"], a["charging"] ?: currentCharging(context).toString())
            AutomationProfileStore.ConditionType.SCREEN -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                stringMatches(p["state"], a["state"] ?: if (pm.isInteractive) "on" else "off")
            }
            AutomationProfileStore.ConditionType.VARIABLE -> variableMatches(p)
            AutomationProfileStore.ConditionType.NOTIFICATION -> {
                val match = p["match"]?.toString().orEmpty()
                match.isBlank() || "${a["title"].orEmpty()}\n${a["text"].orEmpty()}".contains(match, true)
            }
            AutomationProfileStore.ConditionType.LOCATION -> {
                val target = AutomationLocationTarget.resolve(p) ?: return false
                val loc = LocationSnapshotProvider.lastKnown(context, 15 * 60_000L) ?: return false
                val distance = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, target.latitude, target.longitude, distance)
                val inside = distance[0] <= target.radiusM
                inside == (p["inside"]?.toString()?.toBooleanStrictOrNull() ?: true)
            }
            AutomationProfileStore.ConditionType.WIFI -> {
                val connected = currentConnectivity(context).second == "wifi"
                val ssid = currentWifiSsid(context)
                booleanMatches(p["connected"] ?: p["value"], connected.toString()) && stringMatches(p["ssid"], ssid)
            }
            AutomationProfileStore.ConditionType.BLUETOOTH -> {
                val enabled = runCatching { android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled == true }.getOrDefault(false)
                booleanMatches(p["value"], enabled.toString())
            }
            AutomationProfileStore.ConditionType.HEADSET -> {
                val connected = a["connected"]?.toBooleanStrictOrNull() ?: currentHeadsetConnected(context)
                booleanMatches(p["value"], connected.toString())
            }
            AutomationProfileStore.ConditionType.AIRPLANE_MODE -> {
                val value = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
                booleanMatches(p["value"], value.toString())
            }
            AutomationProfileStore.ConditionType.POWER_SAVE -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                booleanMatches(p["value"], pm.isPowerSaveMode.toString())
            }
            AutomationProfileStore.ConditionType.DEVICE_IDLE -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                booleanMatches(p["value"], pm.isDeviceIdleMode.toString())
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
                Result.failure(IllegalStateException("tiempo máximo del perfil alcanzado"))
            } else {
                Thread.sleep(ms)
                Result.success(Unit)
            }
        }
        AutomationProfileStore.ActionType.SET_VARIABLE -> {
            val name = expandString(action.params["name"]?.toString().orEmpty(), profile, event).take(80)
            val value = expandString(action.params["value"]?.toString().orEmpty(), profile, event).take(2_000)
            if (name.isBlank()) Result.failure(IllegalArgumentException("La variable necesita nombre."))
            else if (SecretStore.putString(VARIABLE_PREFIX + name, value)) Result.success(Unit)
            else Result.failure(IllegalStateException("No se pudo guardar la variable de forma segura."))
        }
        AutomationProfileStore.ActionType.NOTIFY -> {
            AssistantReceiver.postNotification(
                context, expandString(action.params["title"]?.toString() ?: profile.name, profile, event),
                expandString(action.params["text"]?.toString().orEmpty(), profile, event),
                action.params["highPriority"]?.toString()?.toBoolean() ?: false,
            )
            Result.success(Unit)
        }
        AutomationProfileStore.ActionType.RUN_ROUTINE -> {
            val name = expandString(action.params["name"]?.toString().orEmpty(), profile, event)
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
            val toolParams = expandValue((rawParams as? Map<String, Any>).orEmpty(), profile, event) as Map<String, Any>
            ToolExecutionContext.setOrigin(ToolRiskPolicy.Origin.AUTOMATION)
            try {
                val result = ToolRegistry.getInstance().executeTool(toolName, toolParams)
                if (result.isSuccess) Result.success(Unit)
                else Result.failure(IllegalStateException(result.error ?: "tool falló"))
            } finally { ToolExecutionContext.reset() }
        }
        AutomationProfileStore.ActionType.AGENT_TASK -> {
            val text = expandString(action.params["text"]?.toString().orEmpty(), profile, event)
            if (text.isBlank()) Result.failure(IllegalArgumentException("La tarea está vacía."))
            else {
                AutomationEngine.executeTask(context, text, "profile:${profile.id}")
                Result.success(Unit)
            }
        }
        AutomationProfileStore.ActionType.IF -> {
            val condition = conditionFrom(action) ?: return Result.failure(
                IllegalArgumentException("if necesita condition_type y condition_params."))
            val branch = if (conditionMatches(context, condition, event)) action.params["then"] else action.params["else"]
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
        val expected = AutomationLocationTarget.resolve(params) ?: return false
        attrs["geofence_request_id"]?.let { return it == AutomationLocationTarget.requestId(expected) }
        // Backward-compatible path for any in-process caller that still emits raw
        // coordinates; new geofence/fallback events never populate these attributes.
        val lat = attrs["latitude"]?.toDoubleOrNull() ?: return false
        val lon = attrs["longitude"]?.toDoubleOrNull() ?: return false
        val distance = FloatArray(1)
        Location.distanceBetween(lat, lon, expected.latitude, expected.longitude, distance)
        return distance[0] <= expected.radiusM
    }

    internal fun variableMatches(params: Map<String, Any>): Boolean {
        val name = params["name"]?.toString().orEmpty().take(80)
        if (name.isBlank()) return false
        val current = SecretStore.getString(VARIABLE_PREFIX + name)
            ?: KVUtils.getString(VARIABLE_PREFIX + name, "").takeIf { it.isNotEmpty() }
        val op = params["op"]?.toString()?.lowercase() ?: if (params.containsKey("equals")) "equals" else "exists"
        val expected = params["value"]?.toString() ?: params["equals"]?.toString().orEmpty()
        return when (op) {
            "exists" -> current != null
            "equals" -> current.orEmpty() == expected
            "not_equals" -> current.orEmpty() != expected
            "contains" -> current.orEmpty().contains(expected, ignoreCase = params["ignore_case"]?.toString()?.toBoolean() == true)
            "regex" -> runCatching { Regex(expected).containsMatchIn(current.orEmpty()) }.getOrDefault(false)
            "gt", "gte", "lt", "lte" -> {
                val left = current?.toDoubleOrNull() ?: return false
                val right = expected.toDoubleOrNull() ?: return false
                when (op) { "gt" -> left > right; "gte" -> left >= right; "lt" -> left < right; else -> left <= right }
            }
            else -> false
        }
    }

    private fun currentConnectivity(context: Context): Pair<String, String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            else -> "none"
        }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        return (if (online) "online" else "offline") to transport
    }

    private fun currentWifiSsid(context: Context): String = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        SavedPlaceStore.normalizeSsid(wm.connectionInfo?.ssid.orEmpty())
    }.getOrDefault("")

    private fun currentHeadsetConnected(context: Context): Boolean = runCatching {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type in setOf(
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
            )
        }
    }.getOrDefault(false)

    private fun currentBatteryLevel(context: Context): Int? {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    }

    private fun currentCharging(context: Context): Boolean {
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1).let {
            it == android.os.BatteryManager.BATTERY_STATUS_CHARGING || it == android.os.BatteryManager.BATTERY_STATUS_FULL
        }
    }

    private fun expandString(value: String, profile: AutomationProfileStore.Profile, event: Event): String {
        val regex = Regex("\\{\\{(event|var)\\.([A-Za-z0-9_.-]{1,80})\\}\\}")
        var out = regex.replace(value) { match ->
            when (match.groupValues[1]) {
                "event" -> event.attributes[match.groupValues[2]].orEmpty()
                else -> SecretStore.getString(VARIABLE_PREFIX + match.groupValues[2]).orEmpty()
            }
        }
        out = out.replace("{{profile.name}}", profile.name)
            .replace("{{profile.id}}", profile.id)
            .replace("{{event.type}}", event.type.name.lowercase())
            .replace("{{now_ms}}", event.atMs.toString())
        return out
    }

    private fun expandValue(value: Any, profile: AutomationProfileStore.Profile, event: Event): Any = when (value) {
        is String -> expandString(value, profile, event)
        is Map<*, *> -> buildMap<String, Any> {
            value.forEach { (k, v) -> if (k is String && v != null) put(k, expandValue(v, profile, event)) }
        }
        is List<*> -> value.mapNotNull { it?.let { item -> expandValue(item, profile, event) } }
        else -> value
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
