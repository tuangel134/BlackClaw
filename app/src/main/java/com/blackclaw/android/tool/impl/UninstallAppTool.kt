package com.blackclaw.android.tool.impl

import android.content.Intent
import android.net.Uri
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Open the system uninstaller dialog for the given package. The user has to
 * confirm — silent uninstall is not allowed for non-system apps.
 */
class UninstallAppTool : BaseTool() {
    override fun getName() = "uninstall_app"
    override fun getDisplayName() = "Desinstalar"
    override fun getDescriptionEN() =
        "Open the system uninstaller for a given package. The user must confirm — " +
        "we cannot silently uninstall apps. Use only when the user explicitly asks to uninstall."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("package", "string", "Package name to uninstall.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val pkg = requireString(params, "package").trim()
        if (pkg.isEmpty()) return ToolResult.error("package cannot be empty")
        return try {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ClawApplication.instance.startActivity(intent)
            ToolResult.success("Diálogo de desinstalación abierto para $pkg. El usuario debe confirmar.")
        } catch (e: Exception) {
            ToolResult.error("No se pudo abrir el desinstalador: ${e.message}")
        }
    }
}
