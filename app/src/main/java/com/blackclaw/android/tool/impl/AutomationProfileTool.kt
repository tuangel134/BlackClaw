package com.blackclaw.android.tool.impl

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.automation.AutomationProfileEngine
import com.blackclaw.android.automation.AutomationProfileScheduler
import com.blackclaw.android.automation.AutomationProfileStore
import com.blackclaw.android.automation.AutomationProfileValidator
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolRegistry
import com.blackclaw.android.tool.ToolResult
import com.blackclaw.android.tool.guard.ToolRiskPolicy
import com.blackclaw.android.tool.guard.ToolExecutionContext
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * AI-facing authoring surface for Tasker-like profiles.
 *
 * A first call without confirm=true is a dry-run preview. The model can explain the
 * trigger/conditions/actions and requested permissions; only the user's confirmation
 * enables the profile. This prevents a prompt-injected notification from silently
 * creating a persistent automation.
 */
class AutomationProfileTool : BaseTool() {
    private val gson = Gson()

    override fun getName() = "automation_profile"
    override fun getDisplayName() = "Perfiles de automatización"
    override fun getDescriptionEN() =
        "Author Tasker-like automation profiles with multiple triggers, conditions and " +
            "bounded actions. Use capabilities before authoring when you need the live catalog. " +
            "Use draft to save a disabled profile the user can inspect visually; create/update are previews unless confirm=true. " +
            "Update is PATCH-like: only fields supplied by the agent are changed. " +
            "Triggers: time, notification, location_enter/exit, app_foreground/closed, connectivity, battery, charging, screen, " +
            "headset, bluetooth, wifi, call_state, sms_received, boot or webhook/Tasker intent. " +
            "Actions: tool, agent_task, run_routine, notify, set_variable, wait, if and bounded loop. " +
            "Webhook triggers accept the broadcast action com.blackclaw.android.AUTOMATION_WEBHOOK with extra token."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "crea y controla automatizaciones tipo Tasker con vista previa y confirmación"

    override fun getParameters() = listOf(
        ToolParameter("operation", "string", "capabilities, validate, draft, create, update (parcial), list, enable, disable, delete, run o test", true),
        ToolParameter("name", "string", "Nombre del perfil", false),
        ToolParameter("description", "string", "Qué hace el perfil", false),
        ToolParameter("triggers", "string", "JSON o lista: [{\"type\":\"notification\",\"params\":{...}}]", false),
        ToolParameter("conditions", "string", "JSON o lista: [{\"type\":\"time_window\",\"params\":{\"start\":\"07:00\",\"end\":\"22:00\"}}]", false),
        ToolParameter("actions", "string", "JSON o lista: [{\"type\":\"tool\",\"params\":{\"tool\":\"set_volume\",\"params\":{...}}}]", false),
        ToolParameter("cooldown_ms", "integer", "Enfriamiento mínimo entre ejecuciones", false),
        ToolParameter("max_runs_per_day", "integer", "Límite diario; 0 = sin límite", false),
        ToolParameter("max_runtime_ms", "integer", "Tiempo máximo de una ejecución; predeterminado 600000", false),
        ToolParameter("concurrency", "string", "skip_if_running, queue o replace", false),
        ToolParameter("confirm", "boolean", "true solo después de que el usuario confirme la vista previa", false),
        ToolParameter("id", "string", "ID o nombre para enable/disable/delete/run/test", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult = when (requireString(params, "operation").lowercase()) {
        "capabilities" -> capabilities()
        "validate" -> validate(params)
        "draft" -> draft(params)
        "create" -> create(params)
        "update" -> update(params)
        "list" -> list()
        "enable" -> toggle(params, true)
        "disable" -> toggle(params, false)
        "delete" -> {
            val id = optionalString(params, "id", optionalString(params, "name", ""))
            if (AutomationProfileStore.delete(id)) {
                AutomationProfileScheduler.sync(ClawApplication.instance)
                ToolResult.success("Perfil eliminado.")
            } else ToolResult.error("No encontré el perfil '$id'.")
        }
        "run", "test" -> run(params)
        else -> ToolResult.error("operation debe ser capabilities, validate, draft, create, update, list, enable, disable, delete, run o test.")
    }

    private fun create(params: Map<String, Any>): ToolResult {
        val profile = parseProfile(params).getOrElse { return ToolResult.error(it.message ?: "Perfil inválido.") }
        profileErrors(profile).takeIf { it.isNotEmpty() }?.let { return ToolResult.error(it.joinToString(" ")) }
        val confirm = optionalBoolean(params, "confirm", false)
        val preview = preview(profile)
        if (!confirm) {
            return ToolResult.success(
                "PREVIEW — no se activó nada.\n$preview\n" +
                    "Si quieres que aparezca en el editor sin ejecutarse usa operation=draft. " +
                    "Para activarlo tras aprobación usa create con confirm=true."
            )
        }
        requireLocalConfirmation()?.let { return it }
        val stored = AutomationProfileStore.create(profile, enable = true)
            .getOrElse { return ToolResult.error(it.message ?: "No se pudo guardar el perfil.") }
        AutomationProfileScheduler.sync(ClawApplication.instance)
        return ToolResult.success("✓ Flujo '${stored.name}' activo [${stored.id}].\n$preview")
    }

    /** Save an editable, non-running automation authored by the agent. */
    private fun draft(params: Map<String, Any>): ToolResult {
        val profile = parseProfile(params).getOrElse { return ToolResult.error(it.message ?: "Perfil inválido.") }
        profileErrors(profile).takeIf { it.isNotEmpty() }?.let { return ToolResult.error(it.joinToString(" ")) }
        val duplicate = profile.id.takeIf { it.isNotBlank() }?.let { AutomationProfileStore.find(it) }
            ?: AutomationProfileStore.find(profile.name)
        if (duplicate != null) {
            return ToolResult.error("Ya existe '${duplicate.name}' [${duplicate.id}]. Usa update para no reemplazarlo accidentalmente.")
        }
        val stored = AutomationProfileStore.create(profile, enable = false)
            .getOrElse { return ToolResult.error(it.message ?: "No se pudo guardar el borrador.") }
        AutomationProfileScheduler.sync(ClawApplication.instance)
        return ToolResult.success(
            "✓ Borrador '${stored.name}' guardado [${stored.id}] y DESACTIVADO.\n${preview(stored)}\n" +
                "El usuario puede revisarlo en Automatizaciones y activarlo desde el teléfono."
        )
    }

    /** PATCH-like update: omitted fields are preserved instead of being erased. */
    private fun update(params: Map<String, Any>): ToolResult {
        val id = optionalString(params, "id", optionalString(params, "name", "")).trim()
        if (id.isBlank()) return ToolResult.error("update necesita id o name del perfil existente.")
        val existing = AutomationProfileStore.find(id)
            ?: return ToolResult.error("No encontré el perfil '$id'.")
        val updated = parseProfile(params, existing).getOrElse {
            return ToolResult.error(it.message ?: "Actualización inválida.")
        }
        profileErrors(updated).takeIf { it.isNotEmpty() }?.let { return ToolResult.error(it.joinToString(" ")) }
        val preview = preview(updated)
        if (!optionalBoolean(params, "confirm", false)) {
            return ToolResult.success(
                "PREVIEW DE CAMBIO — no se modificó nada.\n$preview\n" +
                    "Solo se cambiarán los campos enviados; el resto se conserva. " +
                    "Tras aprobación vuelve a llamar update con confirm=true."
            )
        }
        requireLocalConfirmation()?.let { return it }
        val stored = AutomationProfileStore.create(updated, enable = existing.enabled)
            .getOrElse { return ToolResult.error(it.message ?: "No se pudo actualizar el perfil.") }
        AutomationProfileScheduler.sync(ClawApplication.instance)
        return ToolResult.success(
            "✓ Flujo '${stored.name}' actualizado [${stored.id}] · ${if (stored.enabled) "activo" else "desactivado"}.\n$preview"
        )
    }

    private fun validate(params: Map<String, Any>): ToolResult {
        val id = optionalString(params, "id", "").trim()
        val base = id.takeIf { it.isNotBlank() }?.let { AutomationProfileStore.find(it) }
        val profile = parseProfile(params, base).getOrElse { return ToolResult.error(it.message ?: "Perfil inválido.") }
        val errors = profileErrors(profile)
        return if (errors.isEmpty()) ToolResult.success("✓ Perfil válido.\n${preview(profile)}")
        else ToolResult.error(errors.joinToString("\n") { "• $it" })
    }

    private fun capabilities(): ToolResult {
        val runnableTools = ToolRegistry.getInstance().getAllTools()
            .map { it.getName() }
            .filter { it != getName() && ToolRiskPolicy.classify(it) != ToolRiskPolicy.Tier.PRIVILEGED }
        val safe = runnableTools.filter { ToolRiskPolicy.classify(it) == ToolRiskPolicy.Tier.SAFE }.sorted()
        val sensitive = runnableTools.filter { ToolRiskPolicy.classify(it) == ToolRiskPolicy.Tier.SENSITIVE }.sorted()
        return ToolResult.success(buildString {
            append("AUTOMATION CAPABILITIES\n")
            append("Triggers: ${AutomationProfileStore.TriggerType.values().joinToString { it.name.lowercase() }}\n")
            append("Conditions: ${AutomationProfileStore.ConditionType.values().joinToString { it.name.lowercase() }}\n")
            append("Actions: ${AutomationProfileStore.ActionType.values().joinToString { it.name.lowercase() }}\n")
            append("Safe tools (${safe.size}): ${safe.joinToString()}\n")
            append("Sensitive tools (${sensitive.size}, require user-approved profile): ${sensitive.joinToString()}\n")
            append("Privileged arbitrary-shell/network tools are intentionally unavailable inside automations. ")
            append("Prefer deterministic TOOL actions; use AGENT_TASK only when the action genuinely needs reasoning.")
        })
    }

    private fun requireLocalConfirmation(): ToolResult? =
        if (ToolExecutionContext.origin == ToolRiskPolicy.Origin.LOCAL) null
        else ToolResult.error("Crear o modificar una automatización activa debe confirmarse desde BlackClaw en el teléfono.")

    private fun profileErrors(profile: AutomationProfileStore.Profile): List<String> = buildList {
        addAll(AutomationProfileValidator.validate(profile))
        fun validateActions(actions: List<AutomationProfileStore.Action>, depth: Int = 0) {
            if (depth > 3) return
            actions.forEach { action ->
                if (action.type == AutomationProfileStore.ActionType.TOOL) {
                    val toolName = action.params["tool"]?.toString().orEmpty()
                    if (toolName.isNotBlank() && ToolRegistry.getInstance().getTool(toolName) == null) {
                        add("La herramienta '$toolName' no existe en este dispositivo.")
                    }
                }
                listOf("then", "else", "actions").forEach { key ->
                    val nested = objectList(action.params[key], "action.$key")
                    val parsed = nested.mapNotNull { row ->
                        val type = runCatching {
                            AutomationProfileStore.ActionType.valueOf(
                                row["type"]?.toString()?.uppercase()?.replace('-', '_') ?: return@mapNotNull null,
                            )
                        }.getOrNull() ?: return@mapNotNull null
                        val nestedParams = objectMap(row["params"], "action.$key.params")
                        AutomationProfileStore.Action(type, nestedParams)
                    }
                    validateActions(parsed, depth + 1)
                }
            }
        }
        validateActions(profile.actions)
    }.distinct()

    private fun list(): ToolResult {
        val profiles = AutomationProfileStore.list()
        if (profiles.isEmpty()) return ToolResult.success("No hay perfiles de automatización.")
        return ToolResult.success(profiles.joinToString("\n") { profile ->
            "${if (profile.enabled) "●" else "○"} [${profile.id}] ${profile.name} · " +
                "${profile.triggers.size} disparador(es), ${profile.actions.size} acción(es), " +
                "ejecutado ${profile.runCount}x · ${profile.lastStatus}"
        })
    }

    private fun toggle(params: Map<String, Any>, enabled: Boolean): ToolResult {
        if (enabled && ToolExecutionContext.origin != ToolRiskPolicy.Origin.LOCAL) {
            return ToolResult.error("Activar una automatización requiere confirmación local en el teléfono.")
        }
        val id = optionalString(params, "id", optionalString(params, "name", ""))
        return if (AutomationProfileStore.setEnabled(id, enabled)) {
            AutomationProfileScheduler.sync(ClawApplication.instance)
            ToolResult.success("Perfil '${id}' ${if (enabled) "activado" else "desactivado"}.")
        } else ToolResult.error("No encontré el perfil '$id'.")
    }

    private fun run(params: Map<String, Any>): ToolResult {
        if (ToolExecutionContext.origin == ToolRiskPolicy.Origin.AUTOMATION) {
            return ToolResult.error("Un perfil no puede iniciar otro perfil; evita ciclos de automatización.")
        }
        val id = optionalString(params, "id", optionalString(params, "name", ""))
        val profile = AutomationProfileStore.find(id) ?: return ToolResult.error("No encontré el perfil '$id'.")
        val result = AutomationProfileEngine.runNow(ClawApplication.instance, profile)
        return if (result.isSuccess) ToolResult.success("Perfil '${profile.name}' en ejecución.")
        else ToolResult.error(result.exceptionOrNull()?.message ?: "No se pudo ejecutar.")
    }

    private fun parseProfile(
        params: Map<String, Any>,
        base: AutomationProfileStore.Profile? = null,
    ): Result<AutomationProfileStore.Profile> = runCatching {
        val name = when {
            params.containsKey("name") -> requireString(params, "name").trim()
            base != null -> base.name
            else -> throw IllegalArgumentException("Falta name.")
        }
        val triggers = if (params.containsKey("triggers")) {
            parseArray(jsonText(params, "triggers", "[]")) { row ->
                AutomationProfileStore.Trigger(
                    type = enumValue(row["type"], AutomationProfileStore.TriggerType.values()),
                    params = objectMap(row["params"], "trigger.params"),
                )
            }
        } else base?.triggers ?: emptyList()
        val conditions = if (params.containsKey("conditions")) {
            parseArray(jsonText(params, "conditions", "[]")) { row ->
                AutomationProfileStore.Condition(
                    type = enumValue(row["type"], AutomationProfileStore.ConditionType.values()),
                    params = objectMap(row["params"], "condition.params"),
                    negate = row["negate"]?.toString()?.toBoolean() ?: false,
                )
            }
        } else base?.conditions ?: emptyList()
        val actions = if (params.containsKey("actions")) {
            parseArray(jsonText(params, "actions", "[]")) { row ->
                val type = enumValue(row["type"], AutomationProfileStore.ActionType.values())
                val actionParams = objectMap(row["params"], "action.params")
                val toolName = actionParams["tool"]?.toString().orEmpty()
                val automaticConfirmation = type == AutomationProfileStore.ActionType.AGENT_TASK ||
                    (type == AutomationProfileStore.ActionType.TOOL &&
                        ToolRiskPolicy.classify(toolName) != ToolRiskPolicy.Tier.SAFE)
                AutomationProfileStore.Action(
                    type = type,
                    params = actionParams,
                    requireConfirmation = row["requireConfirmation"]?.toString()?.toBoolean() == true || automaticConfirmation,
                )
            }
        } else base?.actions ?: emptyList()
        val description = if (params.containsKey("description")) {
            optionalString(params, "description", "")
        } else base?.description.orEmpty()
        val cooldownMs = if (params.containsKey("cooldown_ms")) {
            optionalLong(params, "cooldown_ms", 60_000L)
        } else base?.cooldownMs ?: 60_000L
        val maxRunsPerDay = if (params.containsKey("max_runs_per_day")) {
            optionalInt(params, "max_runs_per_day", 0)
        } else base?.maxRunsPerDay ?: 0
        val maxRuntimeMs = if (params.containsKey("max_runtime_ms")) {
            optionalLong(params, "max_runtime_ms", 10 * 60_000L)
        } else base?.maxRuntimeMs ?: 10 * 60_000L
        val concurrency = if (params.containsKey("concurrency")) {
            AutomationProfileStore.Concurrency.valueOf(
                optionalString(params, "concurrency", "SKIP_IF_RUNNING")
                    .uppercase().replace('-', '_'),
            )
        } else base?.concurrency ?: AutomationProfileStore.Concurrency.SKIP_IF_RUNNING

        if (base != null) {
            base.copy(
                name = name,
                description = description,
                triggers = triggers,
                conditions = conditions,
                actions = actions,
                cooldownMs = cooldownMs,
                maxRunsPerDay = maxRunsPerDay,
                maxRuntimeMs = maxRuntimeMs,
                concurrency = concurrency,
            )
        } else {
            AutomationProfileStore.Profile(
                id = optionalString(params, "id", ""),
                name = name,
                description = description,
                triggers = triggers,
                conditions = conditions,
                actions = actions,
                cooldownMs = cooldownMs,
                maxRunsPerDay = maxRunsPerDay,
                maxRuntimeMs = maxRuntimeMs,
                concurrency = concurrency,
            )
        }
    }

    private fun <T> parseArray(raw: String, transform: (Map<String, Any>) -> T): List<T> {
        val type = object : TypeToken<List<Map<String, Any>>>() {}.type
        val rows: List<Map<String, Any>> = gson.fromJson(raw, type) ?: emptyList()
        return rows.map(transform)
    }

    /** Convert a JSON/object value to the profile's strongly shaped params map.
     * Rejecting non-object values here is safer than an unchecked generic cast. */
    private fun objectMap(value: Any?, field: String): Map<String, Any> = when (value) {
        null -> emptyMap()
        is Map<*, *> -> buildMap {
            value.forEach { (rawKey, rawValue) ->
                val key = rawKey as? String
                    ?: throw IllegalArgumentException("$field contiene una clave que no es texto.")
                if (rawValue != null) put(key, rawValue)
            }
        }
        else -> throw IllegalArgumentException("$field debe ser un objeto JSON.")
    }

    private fun objectList(value: Any?, field: String): List<Map<String, Any>> = when (value) {
        null -> emptyList()
        is List<*> -> value.mapIndexed { index, item -> objectMap(item, "$field[$index]") }
        else -> throw IllegalArgumentException("$field debe ser una lista JSON.")
    }

    /** Tool transports sometimes preserve arrays/maps instead of serializing them. */
    private fun jsonText(params: Map<String, Any>, key: String, defaultValue: String): String {
        val value = params[key] ?: return defaultValue
        return if (value is String) value else gson.toJson(value)
    }

    private fun <T : Enum<T>> enumValue(raw: Any?, values: Array<T>): T {
        val normalized = raw?.toString()?.trim()?.uppercase()?.replace('-', '_')
            ?: throw IllegalArgumentException("Falta type.")
        return values.firstOrNull { it.name == normalized }
            ?: throw IllegalArgumentException("Tipo desconocido '$raw'.")
    }

    private fun preview(profile: AutomationProfileStore.Profile): String {
        val permissions = linkedSetOf<String>()
        profile.triggers.forEach {
            when (it.type) {
                AutomationProfileStore.TriggerType.NOTIFICATION -> permissions += "Notification Access"
                AutomationProfileStore.TriggerType.LOCATION_ENTER,
                AutomationProfileStore.TriggerType.LOCATION_EXIT -> permissions += "Ubicación"
                AutomationProfileStore.TriggerType.APP_FOREGROUND,
                AutomationProfileStore.TriggerType.APP_CLOSED -> permissions += "Accesibilidad"
                AutomationProfileStore.TriggerType.CALL_STATE -> permissions += "Estado del teléfono"
                AutomationProfileStore.TriggerType.SMS_RECEIVED -> permissions += "Permiso de SMS recibido"
                AutomationProfileStore.TriggerType.WEBHOOK -> permissions += "Intent externo con token"
                else -> Unit
            }
        }
        profile.actions.forEach { action ->
            if (action.type == AutomationProfileStore.ActionType.NOTIFY) permissions += "Notificaciones"
            if (action.type == AutomationProfileStore.ActionType.TOOL &&
                ToolRiskPolicy.classify(action.params["tool"].toString()) == ToolRiskPolicy.Tier.SENSITIVE) {
                permissions += "Confirmación para acción sensible"
            }
        }
        return buildString {
            append("Nombre: ${profile.name}\n")
            append("Disparadores: ${profile.triggers.joinToString { it.type.name.lowercase() }}\n")
            append("Condiciones: ${profile.conditions.size}\n")
            append("Acciones: ${profile.actions.joinToString { it.type.name.lowercase() }}\n")
            append("Límites: cooldown ${profile.cooldownMs}ms, máximo diario ${if (profile.maxRunsPerDay == 0) "sin límite" else profile.maxRunsPerDay}, tiempo máximo ${profile.maxRuntimeMs}ms, concurrencia ${profile.concurrency.name.lowercase()}\n")
            append("Permisos/capacidades: ${if (permissions.isEmpty()) "ninguno adicional" else permissions.joinToString()}")
        }
    }
}
