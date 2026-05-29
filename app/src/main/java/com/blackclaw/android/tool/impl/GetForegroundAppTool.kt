package com.blackclaw.android.tool.impl

import com.blackclaw.android.service.ClawAccessibilityService
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Reports the package + label currently in the foreground.
 * Uses the accessibility service's last-window record (no permissions beyond accessibility).
 */
class GetForegroundAppTool : BaseTool() {
    override fun getName() = "get_foreground_app"
    override fun getDisplayName() = "Foreground App"
    override fun getDescriptionEN() =
        "Returns the package name and label of the app currently in the foreground. " +
        "Use this when the user asks 'what app am I in' or before deciding whether to navigate."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters() = emptyList<ToolParameter>()

    override fun execute(params: Map<String, Any>): ToolResult {
        val service = requireAccessibilityService(timeoutMs = 2_000L)
            ?: return ToolResult.error("Accessibility service not connected.")
        val pkg = try {
            service.rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) { null }
        if (pkg.isNullOrBlank()) {
            return ToolResult.error("Could not read foreground package.")
        }
        val label = try {
            val pm = service.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) { pkg }
        return ToolResult.success("Foreground: $label ($pkg)")
    }
}
