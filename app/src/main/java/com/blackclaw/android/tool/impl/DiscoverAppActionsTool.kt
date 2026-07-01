package com.blackclaw.android.tool.impl

import com.blackclaw.android.perception.AppActionScanner
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Auto-discover what BlackClaw can do on THIS device: which installed apps can
 * play music, navigate, send email/SMS, call, share, create calendar events,
 * browse — plus which catalog deep-link apps are installed. Uses the system
 * PackageManager, so it reflects the user's REAL app set (no hardcoding).
 *
 * Use when the user asks "¿qué apps puedes controlar?", "¿con qué apps funcionas?",
 * or before acting when unsure which app handles a capability.
 */
class DiscoverAppActionsTool : BaseTool() {
    override fun getName() = "discover_app_actions"
    override fun getDisplayName() = "Descubrir apps"
    override fun getDescriptionEN() =
        "Discover which installed apps can fulfil capabilities (music, navigation, email, sms, call, " +
        "share, calendar, browser) and which deep-link catalog apps are installed on THIS device. " +
        "Optional 'capability' to filter: music|navigate|email|sms|call|browser|share|calendar|web_search. " +
        "Returns the real list for this phone."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "averigua por sí solo qué apps instaladas soporta (música, mapas, correo, etc.)"
    override fun getParameters() = listOf(
        ToolParameter("capability", "string",
            "Optional filter: music|navigate|email|sms|call|browser|share|calendar|web_search.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val filter = optionalString(params, "capability", "").lowercase().trim()
        if (filter.isNotBlank()) {
            val cap = AppActionScanner.scanCapabilities().firstOrNull { it.key == filter }
                ?: return ToolResult.error("Capacidad desconocida '$filter'.")
            if (cap.apps.isEmpty()) return ToolResult.success("Ninguna app instalada para: ${cap.label}.")
            val names = cap.apps.joinToString(", ") { "${it.label} (${it.pkg})" }
            return ToolResult.success("${cap.label}: $names")
        }
        return ToolResult.success(AppActionScanner.report())
    }
}
