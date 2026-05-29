package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolRegistry
import com.blackclaw.android.tool.ToolResult

/**
 * Meta-tool for progressive tool disclosure.
 *
 * To stay within cloud rate limits, BlackClaw only ships the full schema for a
 * relevant subset of tools per task. The full tool list is shown to the model
 * as a compact catalog in the system prompt. When the model needs a tool that
 * isn't loaded yet, it calls request_tool(names="weather,translate") and the
 * agent loop injects those tools' full schemas before the next round.
 *
 * This tool itself just validates the requested names against the registry and
 * reports which were found. The actual "unlock" (adding schemas to the request)
 * is handled by DefaultAgentService, which intercepts this tool by name.
 */
class RequestToolTool : BaseTool() {
    override fun getName() = "request_tool"
    override fun getDisplayName() = "Cargar herramienta"
    override fun getDescriptionEN() =
        "Load the full schema of one or more tools from the catalog so you can call them. " +
        "Pass tool names (comma-separated) exactly as shown in the catalog, e.g. " +
        "names=\"weather\" or names=\"send_sms,find_contact\". After this returns, the tools " +
        "are ready to call in your next step."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getBrief() =
        "carga el esquema completo de otras tools del catálogo para poder usarlas"

    override fun getParameters() = listOf(
        ToolParameter("names", "string",
            "Nombres de tools separados por coma, tal cual aparecen en el catálogo.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val raw = requireString(params, "names").trim()
        if (raw.isEmpty()) return ToolResult.error("names vacío")
        val requested = raw.split(",", " ", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val registry = ToolRegistry.getInstance()
        val found = requested.filter { registry.getTool(it) != null }
        val missing = requested.filter { registry.getTool(it) == null }

        if (found.isEmpty()) {
            return ToolResult.error(
                "Ninguna de esas tools existe: ${requested.joinToString()}. " +
                "Revisa los nombres en el catálogo."
            )
        }
        val msg = StringBuilder("Cargadas: ${found.joinToString()}.")
        if (missing.isNotEmpty()) {
            msg.append(" No existen (ignoradas): ${missing.joinToString()}.")
        }
        msg.append(" Ya puedes llamarlas.")
        // The data field carries the resolved names so the loop can unlock them.
        return ToolResult.success(msg.toString())
    }
}
