package com.blackclaw.android.tool.impl

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.automation.AutomationProfileEngine
import com.blackclaw.android.automation.AutomationProfileScheduler
import com.blackclaw.android.automation.AutomationProfileStore
import com.blackclaw.android.automation.AutomationProfileValidator
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
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
            "bounded actions. Create is a dry-run preview unless confirm=true. " +
            "Triggers: time, notification, location_enter/exit, app_foreground/closed, " +
            "connectivity, battery, charging, screen, headset, bluetooth, wifi, call_state, sms_received, boot or webhook/Tasker intent. " +
            "Actions: tool, agent_task, run_routine, notify, set_variable, wait, if and bounded loop. " +
            "Webhook triggers accept the broadcast action com.blackclaw.android.AUTOMATION_WEBHOOK with extra token. " +
            "Example trigger JSON: [{\"type\":\"time\",\"params\":{\"hour\":7,\"minute\":30,\"days\":\"weekdays\"}}]."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "crea y controla automatizaciones tipo Tasker con vista previa y confirmación"

    override fun getParameters() = listOf(
        ToolParameter("operation", "string", "create, update (reemplaza el perfil completo), list, enable, disable, delete, run o test", true),
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
        "create", "update" -> create(params)
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
        else -> ToolResult.error("operation debe ser create, update, list, enable, disable, delete, run o test.")
    }

    private fun create(params: Map<String, Any>): ToolResult {
        val profile = parseProfile(params).getOrElse { return ToolResult.error(it.message ?: "Perfil inválido.") }
        val errors = AutomationProfileValidator.validate(profile)
        if (errors.isNotEmpty()) return ToolResult.error(errors.joinToString(" "))
        val confirm = optionalBoolean(params, "confirm", false)
        if (confirm && ToolExecutionContext.origin != ToolRiskPolicy.Origin.LOCAL) {
            return ToolResult.error("La activación de una automatización debe confirmarse desde BlackClaw en el teléfono.")
        }
        val preview = preview(profile)
        if (!confirm) {
            return ToolResult.success(
                "PREVIEW — no se activó nada.\n$preview\n" +
                    "Si el usuario lo aprueba, vuelve a llamar automation_profile create con confirm=true."
            )
        }
        val stored = AutomationProfileStore.create(profile, enable = true)
            .getOrElse { return ToolResult.error(it.message ?: "No se pudo guardar el perfil.") }
        AutomationProfileScheduler.sync(ClawApplication.instance)
        return ToolResult.success("✓ Perfil '${stored.name}' activo [${stored.id}].\n$preview")
    }

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

    private fun parseProfile(params: Map<String, Any>): Result<AutomationProfileStore.Profile> = runCatching {
        val name = requireString(params, "name").trim()
        val triggers = parseArray(jsonText(params, "triggers", "[]")) { row ->
            AutomationProfileStore.Trigger(
                type = enumValue(row["type"], AutomationProfileStore.TriggerType.values()),
                params = row["params"] as? Map<String, Any> ?: emptyMap(),
            )
        }
        val conditions = parseArray(jsonText(params, "conditions", "[]")) { row ->
            AutomationProfileStore.Condition(
                type = enumValue(row["type"], AutomationProfileStore.ConditionType.values()),
                params = row["params"] as? Map<String, Any> ?: emptyMap(),
                negate = row["negate"]?.toString()?.toBoolean() ?: false,
            )
        }
        val actions = parseArray(jsonText(params, "actions", "[]")) { row ->
            val type = enumValue(row["type"], AutomationProfileStore.ActionType.values())
            val actionParams = row["params"] as? Map<String, Any> ?: emptyMap()
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
        AutomationProfileStore.Profile(
            id = optionalString(params, "id", ""),
            name = name,
            description = optionalString(params, "description", ""),
            triggers = triggers,
            conditions = conditions,
            actions = actions,
            cooldownMs = optionalLong(params, "cooldown_ms", 60_000L),
            maxRunsPerDay = optionalInt(params, "max_runs_per_day", 0),
            maxRuntimeMs = optionalLong(params, "max_runtime_ms", 10 * 60_000L),
            concurrency = runCatching {
                AutomationProfileStore.Concurrency.valueOf(
                    optionalString(params, "concurrency", "SKIP_IF_RUNNING").uppercase().replace('-', '_'),
                )
            }.getOrDefault(AutomationProfileStore.Concurrency.SKIP_IF_RUNNING),
        )
    }

    private fun <T> parseArray(raw: String, transform: (Map<String, Any>) -> T): List<T> {
        val type = object : TypeToken<List<Map<String, Any>>>() {}.type
        val rows: List<Map<String, Any>> = gson.fromJson(raw, type) ?: emptyList()
        return rows.map(transform)
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
