package com.blackclaw.android.tool.impl

import android.accessibilityservice.AccessibilityService
import com.blackclaw.android.service.ClawAccessibilityService
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Closes the foreground app by issuing the global HOME action via the accessibility service.
 *
 * NOTE: Non-system apps cannot force-stop other packages on modern Android. The cleanest
 * generic-purpose action is to leave the app via HOME so the user/system can dispose of it.
 * For an actual force-stop, the agent should be told to navigate to App Info.
 */
class CloseAppTool : BaseTool() {
    override fun getName() = "close_app"
    override fun getDisplayName() = "Close App"
    override fun getDescriptionEN() =
        "Leave the current foreground app. Equivalent to pressing Home. " +
        "Cannot truly force-stop other apps on modern Android — for that, navigate to " +
        "App Info via Settings."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters() = emptyList<ToolParameter>()

    override fun execute(params: Map<String, Any>): ToolResult {
        val service = requireAccessibilityService(timeoutMs = 2_000L)
            ?: return ToolResult.error("Accessibility service not connected.")
        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        return if (ok) ToolResult.success("Returned to home screen.")
        else ToolResult.error("Failed to perform HOME action.")
    }
}
