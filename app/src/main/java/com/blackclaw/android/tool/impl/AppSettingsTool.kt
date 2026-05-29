package com.blackclaw.android.tool.impl

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Open the system "App Info" page for a given package. From there the user can
 * grant/revoke permissions, force-stop, clear data, etc.
 */
class AppSettingsTool : BaseTool() {
    override fun getName() = "open_app_settings"
    override fun getDisplayName() = "Ajustes de app"
    override fun getDescriptionEN() =
        "Open the system App Info screen for a given package, where the user can " +
        "grant/revoke permissions, force-stop, or clear app data. Use when the user wants " +
        "to manage permissions or clear data for a specific app."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("package", "string", "Package name (e.g. 'com.whatsapp').", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val pkg = requireString(params, "package").trim()
        if (pkg.isEmpty()) return ToolResult.error("package cannot be empty")
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$pkg")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ClawApplication.instance.startActivity(intent)
            ToolResult.success("Pantalla de ajustes abierta para $pkg.")
        } catch (e: Exception) {
            ToolResult.error("No se pudo abrir ajustes: ${e.message}")
        }
    }
}
