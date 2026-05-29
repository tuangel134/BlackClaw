package com.blackclaw.android.tool.impl

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Lists the shortcuts an app exposes (e.g. "New incognito tab" in Chrome,
 * "New email" in Gmail). The agent can then open one with launch_app_shortcut.
 */
class AppShortcutsTool : BaseTool() {
    override fun getName() = "app_shortcuts"
    override fun getDisplayName() = "Atajos de app"
    override fun getDescriptionEN() =
        "List the shortcuts an installed app exposes (long-press menu items). " +
        "Useful before deciding which deep action to launch."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("package", "string", "Package name de la app.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return ToolResult.error("Necesita Android 7.1+.")
        }
        val pkg = requireString(params, "package").trim()
        val ctx = ClawApplication.instance
        val launcher = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return ToolResult.error("LauncherApps no disponible.")
        return try {
            val q = LauncherApps.ShortcutQuery().apply {
                setPackage(pkg)
                setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                )
            }
            val list = launcher.getShortcuts(q, Process.myUserHandle())
                ?: return ToolResult.error("Permiso denegado o app sin atajos.")
            if (list.isEmpty()) return ToolResult.success("Sin atajos para $pkg.")
            ToolResult.success(
                "${list.size} atajo(s):\n" + list.joinToString("\n") {
                    "- ${it.id} : ${it.shortLabel ?: it.longLabel ?: "(sin etiqueta)"}"
                }
            )
        } catch (e: SecurityException) {
            ToolResult.error("BlackClaw no es el launcher por defecto, no puedo leer atajos.")
        } catch (e: Exception) {
            ToolResult.error("Falló: ${e.message}")
        }
    }
}
