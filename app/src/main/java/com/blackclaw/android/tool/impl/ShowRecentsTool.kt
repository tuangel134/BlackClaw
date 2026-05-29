package com.blackclaw.android.tool.impl

import android.accessibilityservice.AccessibilityService
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Trigger the system's Recents (overview) screen via accessibility.
 * Useful when the user wants to switch between recent apps.
 */
class ShowRecentsTool : BaseTool() {
    override fun getName() = "show_recents"
    override fun getDisplayName() = "Recent Apps"
    override fun getDescriptionEN() =
        "Open the Android Recent Apps (overview) screen. Use it when the user wants to " +
        "switch back to a recently used app or close one."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters() = emptyList<ToolParameter>()

    override fun execute(params: Map<String, Any>): ToolResult {
        val service = requireAccessibilityService(timeoutMs = 2_000L)
            ?: return ToolResult.error("Accessibility service not connected.")
        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        return if (ok) ToolResult.success("Recent apps opened.")
        else ToolResult.error("Failed to open Recents.")
    }
}
