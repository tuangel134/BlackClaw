package com.blackclaw.android.tool.impl

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.automation.AutomationEngine
import com.blackclaw.android.automation.AutomationRuleStore
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

class AutomationRuleTool : BaseTool() {
    override fun getName() = "automation_rule"
    override fun getDisplayName() = "Reglas automáticas"
    override fun getDescriptionEN() =
        "Create and manage deterministic IF→THEN automations. Notification rules match a contact/title/text " +
        "and run a precise multi-step task. Location rules fire when entering/exiting coordinates. " +
        "Use schedule_task for simple clock/cron triggers. For new Tasker-like profiles with multiple " +
        "triggers, conditions and bounded actions, use automation_profile. Explicit user-created rules execute without asking again."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "crea reglas si-entonces por notificación o ubicación y ejecuta acciones complejas"
    override fun getParameters() = listOf(
        ToolParameter("operation", "string", "create, list, enable, disable, delete o run", true),
        ToolParameter("name", "string", "Nombre de la regla", false),
        ToolParameter("trigger", "string", "notification, location_enter o location_exit", false),
        ToolParameter("match", "string", "Texto/contacto a buscar en título o contenido", false),
        ToolParameter("package_name", "string", "Paquete opcional, ej. com.whatsapp", false),
        ToolParameter("action", "string", "Tarea o secuencia completa que BlackClaw ejecutará", false),
        ToolParameter("latitude", "number", "Latitud para ubicación", false),
        ToolParameter("longitude", "number", "Longitud para ubicación", false),
        ToolParameter("radius_m", "integer", "Radio 50..5000 metros", false),
        ToolParameter("cooldown_minutes", "integer", "Evita repeticiones; predeterminado 5", false),
        ToolParameter("id", "string", "ID o nombre para enable/disable/delete/run", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult = when (requireString(params, "operation").lowercase()) {
        "create" -> create(params)
        "list" -> {
            val rules = AutomationRuleStore.list()
            ToolResult.success(if (rules.isEmpty()) "No hay reglas automáticas." else rules.joinToString("\n") {
                "${if (it.enabled) "●" else "○"} [${it.id}] ${it.name} · ${it.trigger.name.lowercase()} → ${it.actionText.take(100)}"
            })
        }
        "enable", "disable" -> {
            val id = optionalString(params, "id", optionalString(params, "name", ""))
            if (AutomationRuleStore.setEnabled(id, requireString(params, "operation") == "enable"))
                ToolResult.success("Regla actualizada.") else ToolResult.error("No encontré la regla '$id'.")
        }
        "delete" -> {
            val id = optionalString(params, "id", optionalString(params, "name", ""))
            if (AutomationRuleStore.delete(id)) ToolResult.success("Regla eliminada.") else ToolResult.error("No encontré '$id'.")
        }
        "run" -> {
            val id = optionalString(params, "id", optionalString(params, "name", ""))
            val rule = AutomationRuleStore.list().firstOrNull { it.id == id || it.name.equals(id, true) }
                ?: return ToolResult.error("No encontré '$id'.")
            AutomationEngine.fire(ClawApplication.instance, rule)
            ToolResult.success("Ejecutando '${rule.name}'.")
        }
        else -> ToolResult.error("operation debe ser create, list, enable, disable, delete o run.")
    }

    private fun create(params: Map<String, Any>): ToolResult {
        val name = requireString(params, "name")
        val action = requireString(params, "action")
        val trigger = when (requireString(params, "trigger").lowercase()) {
            "notification" -> AutomationRuleStore.Trigger.NOTIFICATION
            "location_enter" -> AutomationRuleStore.Trigger.LOCATION_ENTER
            "location_exit" -> AutomationRuleStore.Trigger.LOCATION_EXIT
            else -> return ToolResult.error("trigger debe ser notification, location_enter o location_exit.")
        }
        val lat = params["latitude"]?.toString()?.toDoubleOrNull() ?: 0.0
        val lon = params["longitude"]?.toString()?.toDoubleOrNull() ?: 0.0
        if (trigger != AutomationRuleStore.Trigger.NOTIFICATION && lat == 0.0 && lon == 0.0) {
            return ToolResult.error("Las reglas de ubicación necesitan latitude/longitude. Usa get_location primero.")
        }
        val rule = AutomationRuleStore.create(
            name, trigger, optionalString(params, "match", ""), optionalString(params, "package_name", ""), action,
            lat, lon, optionalInt(params, "radius_m", 150).toFloat(),
            optionalInt(params, "cooldown_minutes", 5).coerceAtLeast(1) * 60_000L,
        )
        return ToolResult.success("✓ Regla '${rule.name}' activa [${rule.id}]: SI ${rule.trigger.name.lowercase()} ENTONCES $action")
    }
}
