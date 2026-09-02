package com.blackclaw.android.automation

import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Versioned, Tasker-like automation programs.
 *
 * The old [AutomationRuleStore] is intentionally kept as a compatibility layer for
 * installed rules. New automations use this schema so a profile can have several
 * triggers, conditions and ordered actions instead of one opaque sentence.
 */
object AutomationProfileStore {
    private const val TAG = "AutomationProfiles"
    private const val KEY = "automation_profiles_v1"
    private const val MAX_PROFILES = 100
    const val SCHEMA_VERSION = 1

    enum class TriggerType {
        MANUAL, TIME, NOTIFICATION, LOCATION_ENTER, LOCATION_EXIT,
        APP_FOREGROUND, APP_CLOSED, CONNECTIVITY, BATTERY, CHARGING,
        SCREEN, HEADSET, BLUETOOTH, WIFI, CALL_STATE, SMS_RECEIVED, BOOT, WEBHOOK,
    }

    enum class ConditionType {
        TIME_WINDOW, DAY_OF_WEEK, APP, CONNECTIVITY, BATTERY_LEVEL,
        CHARGING, SCREEN, VARIABLE, NOTIFICATION,
    }

    enum class ActionType {
        TOOL, AGENT_TASK, RUN_ROUTINE, NOTIFY, SET_VARIABLE, WAIT, IF, LOOP,
    }

    enum class Concurrency { SKIP_IF_RUNNING, QUEUE, REPLACE }

    data class Trigger(
        val type: TriggerType,
        val params: Map<String, Any> = emptyMap(),
    ) {
        companion object {
            fun fromJson(o: JSONObject) = Trigger(
                type = runCatching { TriggerType.valueOf(o.optString("type")) }
                    .getOrDefault(TriggerType.MANUAL),
                params = AutomationProfileStore.jsonToMap(o.optJSONObject("params")),
            )
        }
    }

    data class Condition(
        val type: ConditionType,
        val params: Map<String, Any> = emptyMap(),
        val negate: Boolean = false,
    ) {
        companion object {
            fun fromJson(o: JSONObject) = Condition(
                type = runCatching { ConditionType.valueOf(o.optString("type")) }
                    .getOrDefault(ConditionType.VARIABLE),
                params = AutomationProfileStore.jsonToMap(o.optJSONObject("params")),
                negate = o.optBoolean("negate", false),
            )
        }
    }

    data class Action(
        val type: ActionType,
        val params: Map<String, Any> = emptyMap(),
        val requireConfirmation: Boolean = false,
    ) {
        companion object {
            fun fromJson(o: JSONObject) = Action(
                type = runCatching { ActionType.valueOf(o.optString("type")) }
                    .getOrDefault(ActionType.NOTIFY),
                params = AutomationProfileStore.jsonToMap(o.optJSONObject("params")),
                requireConfirmation = o.optBoolean("requireConfirmation", false),
            )
        }
    }

    data class Profile(
        val id: String,
        val name: String,
        val description: String = "",
        val enabled: Boolean = false,
        val triggers: List<Trigger> = emptyList(),
        val conditions: List<Condition> = emptyList(),
        val actions: List<Action> = emptyList(),
        val cooldownMs: Long = 60_000L,
        val maxRunsPerDay: Int = 0,
        val maxRuntimeMs: Long = 10 * 60_000L,
        val concurrency: Concurrency = Concurrency.SKIP_IF_RUNNING,
        val createdAtMs: Long = System.currentTimeMillis(),
        val updatedAtMs: Long = System.currentTimeMillis(),
        val lastRunAtMs: Long = 0L,
        val runCount: Int = 0,
        val runsToday: Int = 0,
        val runDayKey: String = "",
        val lastStatus: String = "never",
        val lastError: String = "",
        val lastEventType: String = "",
        val approvedAtMs: Long = 0L,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("id", id)
            put("name", name)
            put("description", description)
            put("enabled", enabled)
            put("triggers", JSONArray().also { a -> triggers.forEach { a.put(it.toJson()) } })
            put("conditions", JSONArray().also { a -> conditions.forEach { a.put(it.toJson()) } })
            put("actions", JSONArray().also { a -> actions.forEach { a.put(it.toJson()) } })
            put("cooldownMs", cooldownMs)
            put("maxRunsPerDay", maxRunsPerDay)
            put("maxRuntimeMs", maxRuntimeMs)
            put("concurrency", concurrency.name)
            put("createdAtMs", createdAtMs)
            put("updatedAtMs", updatedAtMs)
            put("lastRunAtMs", lastRunAtMs)
            put("runCount", runCount)
            put("runsToday", runsToday)
            put("runDayKey", runDayKey)
            put("lastStatus", lastStatus)
            put("lastError", lastError)
            put("lastEventType", lastEventType)
            put("approvedAtMs", approvedAtMs)
        }

        companion object {
            fun fromJson(o: JSONObject): Profile {
                val triggers = parseArray(o.optJSONArray("triggers")) { Trigger.fromJson(it) }
                val conditions = parseArray(o.optJSONArray("conditions")) { Condition.fromJson(it) }
                val actions = parseArray(o.optJSONArray("actions")) { Action.fromJson(it) }
                val storedId = o.optString("id").ifBlank { "legacy-${o.toString().hashCode()}" }
                return Profile(
                    id = storedId,
                    name = o.optString("name", ""),
                    description = o.optString("description", ""),
                    enabled = o.optBoolean("enabled", false),
                    triggers = triggers,
                    conditions = conditions,
                    actions = actions,
                    cooldownMs = o.optLong("cooldownMs", 60_000L),
                    maxRunsPerDay = o.optInt("maxRunsPerDay", 0),
                    maxRuntimeMs = o.optLong("maxRuntimeMs", 10 * 60_000L),
                    concurrency = runCatching { Concurrency.valueOf(o.optString("concurrency")) }
                        .getOrDefault(Concurrency.SKIP_IF_RUNNING),
                    createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis()),
                    updatedAtMs = o.optLong("updatedAtMs", System.currentTimeMillis()),
                    lastRunAtMs = o.optLong("lastRunAtMs", 0L),
                    runCount = o.optInt("runCount", 0),
                    runsToday = o.optInt("runsToday", 0),
                    runDayKey = o.optString("runDayKey", ""),
                    lastStatus = o.optString("lastStatus", "never"),
                    lastError = o.optString("lastError", ""),
                    lastEventType = o.optString("lastEventType", ""),
                    approvedAtMs = o.optLong("approvedAtMs", 0L),
                )
            }

            private fun <T> parseArray(array: JSONArray?, parser: (JSONObject) -> T): List<T> {
                if (array == null) return emptyList()
                return (0 until array.length()).mapNotNull { index ->
                    runCatching { parser(array.getJSONObject(index)) }.getOrNull()
                }
            }
        }
    }

    @Synchronized
    fun list(): List<Profile> {
        val raw = KVUtils.getString(KEY, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                // Keep malformed-but-readable profiles visible so the user can edit or
                // delete them. Runtime entry points validate again before execution.
                runCatching { Profile.fromJson(array.getJSONObject(index)) }.getOrNull()
            }
        }.getOrElse {
            XLog.w(TAG, "Could not parse stored profiles", it)
            emptyList()
        }
    }

    fun find(idOrName: String): Profile? = list().firstOrNull {
        it.id == idOrName || it.name.equals(idOrName, ignoreCase = true)
    }

    /** Compact context so the assistant can edit/test existing profiles without guessing. */
    fun asPromptSnippet(): String {
        val profiles = list()
            .filter { AutomationProfileValidator.validate(it).isEmpty() }
            .takeLast(20)
        if (profiles.isEmpty()) return ""
        return buildString {
            append("\n\n## Automatizaciones BlackClaw\n")
            profiles.forEach { profile ->
                append("- [${profile.id}] ${if (profile.enabled) "ACTIVA" else "BORRADOR"} · ${profile.name}: ")
                append(profile.triggers.joinToString { it.type.name.lowercase() })
                append(" → ")
                append(profile.actions.joinToString { it.type.name.lowercase() })
                if (profile.conditions.isNotEmpty()) append(" · ${profile.conditions.size} condición(es)")
                append("\n")
            }
            append("Usa automation_profile capabilities para conocer el catálogo real; ")
            append("update con el id para cambios parciales, draft para guardar una propuesta desactivada y run/test para probarla.\n")
        }
    }

    /** Stores a profile only after validation. New profiles are disabled until confirmed. */
    @Synchronized
    fun create(profile: Profile, enable: Boolean = false): Result<Profile> {
        val errors = AutomationProfileValidator.validate(profile)
        if (errors.isNotEmpty()) return Result.failure(IllegalArgumentException(errors.joinToString(" ")))
        val now = System.currentTimeMillis()
        val stored = profile.copy(
            id = profile.id.ifBlank { UUID.randomUUID().toString().take(8) },
            name = profile.name.trim().take(80),
            enabled = enable,
            updatedAtMs = now,
            approvedAtMs = if (enable) profile.approvedAtMs.coerceAtLeast(now) else 0L,
        )
        val remaining = list().filterNot {
            it.id == stored.id || it.name.equals(stored.name, ignoreCase = true)
        }
        save((remaining + stored).takeLast(MAX_PROFILES))
        return Result.success(stored)
    }

    @Synchronized
    fun setEnabled(idOrName: String, enabled: Boolean): Boolean {
        val all = list().toMutableList()
        val index = all.indexOfFirst { it.id == idOrName || it.name.equals(idOrName, true) }
        if (index < 0) return false
        val current = all[index]
        if (enabled && AutomationProfileValidator.validate(current).isNotEmpty()) return false
        all[index] = current.copy(
            enabled = enabled,
            updatedAtMs = System.currentTimeMillis(),
            approvedAtMs = if (enabled) current.approvedAtMs.coerceAtLeast(System.currentTimeMillis()) else current.approvedAtMs,
        )
        save(all)
        return true
    }

    @Synchronized
    fun delete(idOrName: String): Boolean {
        val all = list().toMutableList()
        val removed = all.removeAll { it.id == idOrName || it.name.equals(idOrName, true) }
        if (removed) save(all)
        return removed
    }

    @Synchronized
    fun markRun(id: String, now: Long = System.currentTimeMillis(), eventType: String = ""): Boolean {
        val all = list().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return false
        val current = all[index]
        val dayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))
        val todayCount = if (current.runDayKey == dayKey) current.runsToday + 1 else 1
        all[index] = current.copy(
            lastRunAtMs = now,
            runCount = current.runCount + 1,
            runsToday = todayCount,
            runDayKey = dayKey,
            lastStatus = "success",
            lastError = "",
            lastEventType = eventType,
            updatedAtMs = now,
        )
        save(all)
        return true
    }

    @Synchronized
    fun markFailure(id: String, error: String, eventType: String = ""): Boolean {
        val all = list().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return false
        val now = System.currentTimeMillis()
        all[index] = all[index].copy(
            lastRunAtMs = now,
            lastStatus = "failed",
            lastError = error.take(500),
            lastEventType = eventType,
            updatedAtMs = now,
        )
        save(all)
        return true
    }

    private fun save(profiles: List<Profile>) {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        KVUtils.putString(KEY, array.toString())
        KVUtils.sync()
    }

    private fun Trigger.toJson() = JSONObject().apply {
        put("type", type.name); put("params", params.toJsonObject())
    }

    private fun Condition.toJson() = JSONObject().apply {
        put("type", type.name); put("params", params.toJsonObject()); put("negate", negate)
    }

    private fun Action.toJson() = JSONObject().apply {
        put("type", type.name); put("params", params.toJsonObject()); put("requireConfirmation", requireConfirmation)
    }

    private fun Map<String, Any>.toJsonObject(): JSONObject = JSONObject().apply {
        forEach { (key, value) -> put(key, value) }
    }

    private fun jsonToMap(json: JSONObject?): Map<String, Any> {
        if (json == null) return emptyMap()
        val result = LinkedHashMap<String, Any>()
        json.keys().forEach { key ->
            val value = json.opt(key)
            if (value != JSONObject.NULL) result[key] = jsonValueToAny(value)
        }
        return result
    }

    private fun jsonValueToAny(value: Any): Any = when (value) {
        is JSONObject -> jsonToMap(value)
        is JSONArray -> (0 until value.length()).mapNotNull { index ->
            value.opt(index)?.takeIf { it != JSONObject.NULL }?.let { jsonValueToAny(it) }
        }
        else -> value
    }
}

/** Pure validation shared by the AI tool, UI and runtime. */
object AutomationProfileValidator {
    private const val MAX_TRIGGERS = 8
    private const val MAX_CONDITIONS = 16
    private const val MAX_ACTIONS = 40
    private const val MAX_NAME = 80
    private val privilegedTools = setOf("shell_exec", "terminal", "remote_shell", "remote_connect", "add_smart_device")

    fun validate(profile: AutomationProfileStore.Profile): List<String> = buildList {
        if (profile.name.isBlank()) add("El perfil necesita un nombre.")
        if (profile.name.length > MAX_NAME) add("El nombre no puede superar $MAX_NAME caracteres.")
        if (profile.triggers.isEmpty()) add("El perfil necesita al menos un disparador.")
        if (profile.triggers.size > MAX_TRIGGERS) add("Demasiados disparadores (máximo $MAX_TRIGGERS).")
        if (profile.conditions.size > MAX_CONDITIONS) add("Demasiadas condiciones (máximo $MAX_CONDITIONS).")
        if (profile.actions.isEmpty()) add("El perfil necesita al menos una acción.")
        if (profile.actions.size > MAX_ACTIONS) add("Demasiadas acciones (máximo $MAX_ACTIONS).")
        if (profile.cooldownMs !in 0L..7 * 24 * 60 * 60_000L) add("cooldown_ms está fuera de rango.")
        if (profile.maxRunsPerDay !in 0..1_000) add("max_runs_per_day está fuera de rango.")
        if (profile.maxRuntimeMs !in 1_000L..30 * 60 * 1_000L) add("max_runtime_ms está fuera de rango.")
        profile.triggers.forEachIndexed { index, trigger -> validateTrigger(index, trigger, this) }
        profile.conditions.forEachIndexed { index, condition -> validateCondition(index, condition, this) }
        profile.actions.forEachIndexed { index, action ->
            when (action.type) {
                AutomationProfileStore.ActionType.TOOL -> {
                    val tool = action.params["tool"]?.toString().orEmpty()
                    if (tool.isBlank()) add("La acción ${index + 1} necesita tool.")
                    if (tool in privilegedTools) add("El tool privilegiado '$tool' no puede ejecutarse automáticamente.")
                    if (tool == "automation_profile") add("Un perfil no puede invocar automation_profile recursivamente.")
                }
                AutomationProfileStore.ActionType.AGENT_TASK ->
                    if (action.params["text"]?.toString().isNullOrBlank()) add("La acción ${index + 1} necesita text.")
                AutomationProfileStore.ActionType.RUN_ROUTINE ->
                    if (action.params["name"]?.toString().isNullOrBlank()) add("La acción ${index + 1} necesita name.")
                AutomationProfileStore.ActionType.WAIT -> {
                    val ms = automationLong(action.params["ms"]) ?: -1
                    if (ms !in 0..60_000) add("wait solo permite 0..60000 ms.")
                }
                AutomationProfileStore.ActionType.NOTIFY ->
                    if (action.params["text"]?.toString().isNullOrBlank()) add("La acción ${index + 1} necesita text.")
                AutomationProfileStore.ActionType.SET_VARIABLE ->
                    if (action.params["name"]?.toString().isNullOrBlank()) add("set_variable necesita name.")
                AutomationProfileStore.ActionType.IF ->
                    if (action.params["then"] !is List<*>) {
                        add("if necesita then con acciones.")
                    } else {
                        validateNestedActions(action.params["then"], 1, this)
                        validateNestedActions(action.params["else"], 1, this)
                    }
                AutomationProfileStore.ActionType.LOOP -> {
                    val count = automationInt(action.params["count"]) ?: 0
                    if (count !in 1..20 || action.params["actions"] !is List<*>) {
                        add("loop necesita count 1..20 y actions.")
                    } else validateNestedActions(action.params["actions"], 1, this)
                }
            }
        }
    }

    private fun validateTrigger(
        index: Int,
        trigger: AutomationProfileStore.Trigger,
        errors: MutableList<String>,
    ) {
        val label = "El disparador ${index + 1}"
        val p = trigger.params
        fun required(key: String) {
            if (p[key]?.toString().isNullOrBlank()) errors += "$label necesita $key."
        }
        fun optionalBoolean(key: String) {
            val raw = p[key]?.toString() ?: return
            if (raw.toBooleanStrictOrNull() == null) errors += "$label: $key debe ser true o false."
        }
        when (trigger.type) {
            AutomationProfileStore.TriggerType.TIME -> {
                val hour = automationInt(p["hour"])
                val minute = automationInt(p["minute"])
                if (hour == null || hour !in 0..23) errors += "$label: hour debe estar entre 0 y 23."
                if (minute == null || minute !in 0..59) errors += "$label: minute debe estar entre 0 y 59."
                validateDays(p["days"]?.toString(), label, errors)
            }
            AutomationProfileStore.TriggerType.NOTIFICATION -> Unit
            AutomationProfileStore.TriggerType.LOCATION_ENTER,
            AutomationProfileStore.TriggerType.LOCATION_EXIT -> {
                val lat = p["latitude"]?.toString()?.toDoubleOrNull()
                val lon = p["longitude"]?.toString()?.toDoubleOrNull()
                val radius = p["radius_m"]?.let { automationFloat(it) }
                if (lat == null || lat !in -90.0..90.0) errors += "$label: latitude inválida."
                if (lon == null || lon !in -180.0..180.0) errors += "$label: longitude inválida."
                if (p["radius_m"] != null && radius == null) errors += "$label: radius_m debe ser un número."
                if (radius != null && radius !in 1f..100_000f) errors += "$label: radius_m debe estar entre 1 y 100000."
            }
            AutomationProfileStore.TriggerType.APP_FOREGROUND,
            AutomationProfileStore.TriggerType.APP_CLOSED -> required("package")
            AutomationProfileStore.TriggerType.CONNECTIVITY -> {
                validateOneOf(p["state"]?.toString(), setOf("online", "offline"), "$label state", errors)
                validateOneOf(p["transport"]?.toString(), setOf("wifi", "cellular", "ethernet", "none"), "$label transport", errors)
            }
            AutomationProfileStore.TriggerType.BATTERY -> validateRange(p, label, errors)
            AutomationProfileStore.TriggerType.CHARGING -> optionalBoolean("value")
            AutomationProfileStore.TriggerType.SCREEN ->
                validateOneOf(p["state"]?.toString(), setOf("on", "off", "unlocked"), "$label state", errors)
            AutomationProfileStore.TriggerType.HEADSET,
            AutomationProfileStore.TriggerType.BLUETOOTH,
            AutomationProfileStore.TriggerType.WIFI -> optionalBoolean("connected")
            AutomationProfileStore.TriggerType.CALL_STATE ->
                validateOneOf(p["state"]?.toString(), setOf("ringing", "offhook", "idle"), "$label state", errors)
            AutomationProfileStore.TriggerType.SMS_RECEIVED -> Unit
            AutomationProfileStore.TriggerType.BOOT,
            AutomationProfileStore.TriggerType.MANUAL -> Unit
            AutomationProfileStore.TriggerType.WEBHOOK -> {
                required("token")
                val token = p["token"]?.toString().orEmpty()
                if (token.isNotEmpty() && token.length !in 8..256) {
                    errors += "$label: token debe tener entre 8 y 256 caracteres."
                }
                if (token != token.trim()) errors += "$label: token no puede comenzar ni terminar con espacios."
            }
        }
    }

    private fun validateCondition(
        index: Int,
        condition: AutomationProfileStore.Condition,
        errors: MutableList<String>,
    ) {
        val label = "La condición ${index + 1}"
        val p = condition.params
        fun required(key: String) {
            if (p[key]?.toString().isNullOrBlank()) errors += "$label necesita $key."
        }
        when (condition.type) {
            AutomationProfileStore.ConditionType.TIME_WINDOW -> {
                validateClock(p["start"]?.toString(), "$label start", errors)
                validateClock(p["end"]?.toString(), "$label end", errors)
            }
            AutomationProfileStore.ConditionType.DAY_OF_WEEK -> {
                if (p["days"]?.toString().isNullOrBlank()) errors += "$label necesita days."
                else validateDays(p["days"]?.toString(), label, errors)
            }
            AutomationProfileStore.ConditionType.APP -> required("package")
            AutomationProfileStore.ConditionType.CONNECTIVITY -> {
                validateOneOf(p["state"]?.toString(), setOf("online", "offline"), "$label state", errors)
                validateOneOf(p["transport"]?.toString(), setOf("wifi", "cellular", "ethernet", "none"), "$label transport", errors)
            }
            AutomationProfileStore.ConditionType.BATTERY_LEVEL -> validateRange(p, label, errors)
            AutomationProfileStore.ConditionType.CHARGING -> {
                p["value"]?.toString()?.let { raw ->
                    if (raw.toBooleanStrictOrNull() == null) errors += "$label: value debe ser true o false."
                }
            }
            AutomationProfileStore.ConditionType.SCREEN ->
                validateOneOf(p["state"]?.toString(), setOf("on", "off", "unlocked"), "$label state", errors)
            AutomationProfileStore.ConditionType.VARIABLE -> required("name")
            AutomationProfileStore.ConditionType.NOTIFICATION -> Unit
        }
    }

    private fun validateRange(params: Map<String, Any>, label: String, errors: MutableList<String>) {
        val min = params["min"]?.let { automationInt(it) }
        val max = params["max"]?.let { automationInt(it) }
        if (params["min"] != null && min == null) errors += "$label: min debe ser un número."
        if (params["max"] != null && max == null) errors += "$label: max debe ser un número."
        if (min != null && min !in 0..100) errors += "$label: min debe estar entre 0 y 100."
        if (max != null && max !in 0..100) errors += "$label: max debe estar entre 0 y 100."
        if (min != null && max != null && min > max) errors += "$label: min no puede superar max."
    }

    private fun validateClock(raw: String?, label: String, errors: MutableList<String>) {
        val parts = raw?.split(":")
        val hour = parts?.getOrNull(0)?.toIntOrNull()
        val minute = parts?.getOrNull(1)?.toIntOrNull()
        if (parts?.size != 2 || hour == null || hour !in 0..23 || minute == null || minute !in 0..59) {
            errors += "$label debe usar HH:mm."
        }
    }

    private fun validateDays(raw: String?, label: String, errors: MutableList<String>) {
        if (raw.isNullOrBlank() || raw.equals("daily", true) || raw.equals("weekdays", true)) return
        val allowed = setOf(
            "1", "2", "3", "4", "5", "6", "7", "sun", "sunday", "dom", "domingo",
            "mon", "monday", "lun", "lunes", "tue", "tuesday", "mar", "martes",
            "wed", "wednesday", "mie", "miércoles", "thu", "thursday", "jue", "jueves",
            "fri", "friday", "vie", "viernes", "sat", "saturday", "sab", "sábado",
        )
        raw.split(',', '|', ' ').filter { it.isNotBlank() }.forEach {
            if (it.lowercase() !in allowed) errors += "$label: día desconocido '$it'."
        }
    }

    private fun validateOneOf(raw: String?, allowed: Set<String>, label: String, errors: MutableList<String>) {
        if (!raw.isNullOrBlank() && raw.lowercase() !in allowed) errors += "$label inválido."
    }

    private fun validateNestedActions(value: Any?, depth: Int, errors: MutableList<String>) {
        if (value !is List<*>) return
        if (depth > 3 || value.size > MAX_ACTIONS) {
            errors += "Las acciones anidadas superan el límite permitido."
            return
        }
        value.forEach { row ->
            val map = row as? Map<*, *> ?: run { errors += "Acción anidada inválida."; return@forEach }
            val rawType = map["type"]?.toString()?.uppercase()?.replace('-', '_')
            val type = runCatching { AutomationProfileStore.ActionType.valueOf(rawType ?: "") }.getOrNull()
                ?: run { errors += "Tipo de acción anidada desconocido."; return@forEach }
            @Suppress("UNCHECKED_CAST")
            val params = map["params"] as? Map<String, Any> ?: emptyMap()
            when (type) {
                AutomationProfileStore.ActionType.TOOL -> {
                    val tool = params["tool"]?.toString().orEmpty()
                    if (tool.isBlank()) errors += "La acción anidada necesita tool."
                    if (tool in privilegedTools) errors += "El tool privilegiado '$tool' no puede automatizarse."
                    if (tool == "automation_profile") errors += "Un perfil no puede invocar automation_profile recursivamente."
                }
                AutomationProfileStore.ActionType.AGENT_TASK ->
                    if (params["text"]?.toString().isNullOrBlank()) errors += "La acción agent_task anidada necesita text."
                AutomationProfileStore.ActionType.WAIT -> {
                    val ms = automationLong(params["ms"]) ?: -1
                    if (ms !in 0..60_000) errors += "wait anidado fuera de rango."
                }
                AutomationProfileStore.ActionType.IF -> {
                    if (params["then"] !is List<*>) errors += "if anidado necesita then con acciones."
                    else {
                        validateNestedActions(params["then"], depth + 1, errors)
                        validateNestedActions(params["else"], depth + 1, errors)
                    }
                }
                AutomationProfileStore.ActionType.LOOP -> {
                    val count = automationInt(params["count"]) ?: 0
                    if (count !in 1..20) errors += "loop anidado fuera de rango."
                    if (params["actions"] !is List<*>) errors += "loop anidado necesita actions."
                    else validateNestedActions(params["actions"], depth + 1, errors)
                }
                AutomationProfileStore.ActionType.NOTIFY ->
                    if (params["text"]?.toString().isNullOrBlank()) errors += "notify anidado necesita text."
                AutomationProfileStore.ActionType.SET_VARIABLE ->
                    if (params["name"]?.toString().isNullOrBlank()) errors += "set_variable anidado necesita name."
                AutomationProfileStore.ActionType.RUN_ROUTINE ->
                    if (params["name"]?.toString().isNullOrBlank()) errors += "run_routine anidado necesita name."
                else -> Unit
            }
        }
    }
}
