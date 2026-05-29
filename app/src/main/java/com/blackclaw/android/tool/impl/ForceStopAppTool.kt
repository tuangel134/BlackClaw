package com.blackclaw.android.tool.impl

import com.blackclaw.android.adb.PrivilegedShell
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Real force-stop via `am force-stop`. The accessibility-only `close_app` tool
 * just sends HOME, leaving the app in the background. This kills it for real,
 * which matters for "the app is hung, restart it" or "kill all background apps
 * to free RAM" workflows.
 *
 * Uses whichever privileged backend is live (Shizuku or self-paired ADB).
 */
class ForceStopAppTool : BaseTool() {
    override fun getName() = "force_stop_app"
    override fun getDisplayName() = "Forzar detención"
    override fun getDescriptionEN() =
        "Truly force-stop a package via `am force-stop`. Requires Shizuku or self-paired ADB. " +
        "Use when the user wants to kill an app, not just leave it. Falls back to error " +
        "if no privileged backend is active."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("package", "string", "Package name a matar.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        if (!PrivilegedShell.isAvailable()) {
            return ToolResult.error("Sin acceso privilegiado (Shizuku/ADB). Usa close_app (HOME) en su lugar.")
        }
        val pkg = requireString(params, "package").trim()
        if (pkg.isEmpty()) return ToolResult.error("package vacío")
        // Sanity: refuse to kill BlackClaw itself
        if (pkg == "com.blackclaw.android") {
            return ToolResult.error("No voy a matar BlackClaw a mí mismo.")
        }
        PrivilegedShell.exec("am force-stop $pkg") ?: return ToolResult.error("am force-stop falló")
        return ToolResult.success("Forzada la detención de $pkg")
    }
}
