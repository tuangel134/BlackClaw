package com.blackclaw.android.tool.impl

import android.content.pm.PackageManager
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detailed info about an installed app: version, install/update time, target SDK,
 * permissions, package size hint. Useful for "tell me about this app" or before
 * suggesting an uninstall to free space.
 */
class AppInfoTool : BaseTool() {
    override fun getName() = "app_info"
    override fun getDisplayName() = "Info de app"
    override fun getDescriptionEN() =
        "Read detailed info about an installed app by package name (or label). " +
        "Returns version, install date, target SDK, declared permissions, etc."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("package", "string",
            "Package name (e.g. 'com.whatsapp') or app label (matches first found).", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val needle = requireString(params, "package").trim()
        if (needle.isEmpty()) return ToolResult.error("package cannot be empty")
        val pm = ClawApplication.instance.packageManager
        val info = try {
            pm.getPackageInfo(needle, PackageManager.GET_PERMISSIONS)
        } catch (_: PackageManager.NameNotFoundException) {
            // Try by label
            val matched = pm.getInstalledApplications(0).firstOrNull {
                pm.getApplicationLabel(it).toString().equals(needle, ignoreCase = true)
            } ?: return ToolResult.error("No app found with package or label '$needle'.")
            try { pm.getPackageInfo(matched.packageName, PackageManager.GET_PERMISSIONS) }
            catch (e: Exception) { return ToolResult.error("Failed to load app info: ${e.message}") }
        }
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val ai = info.applicationInfo
        val label = ai?.let { pm.getApplicationLabel(it).toString() } ?: info.packageName
        val perms = info.requestedPermissions?.toList()?.takeLast(40) ?: emptyList()
        return ToolResult.success(buildString {
            appendLine("App: $label")
            appendLine("Paquete: ${info.packageName}")
            appendLine("Versión: ${info.versionName} (${info.longVersionCode})")
            appendLine("Instalada: ${df.format(Date(info.firstInstallTime))}")
            appendLine("Actualizada: ${df.format(Date(info.lastUpdateTime))}")
            ai?.let {
                appendLine("Target SDK: ${it.targetSdkVersion}")
                appendLine("Min SDK: ${it.minSdkVersion}")
                if (it.dataDir != null) appendLine("Data dir: ${it.dataDir}")
                appendLine("Es del sistema: ${(it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0}")
            }
            if (perms.isNotEmpty()) {
                appendLine("Permisos (${info.requestedPermissions?.size}):")
                perms.forEach { appendLine("  - $it") }
            }
        })
    }
}
