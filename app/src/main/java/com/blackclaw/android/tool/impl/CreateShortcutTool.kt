package com.blackclaw.android.tool.impl

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.R
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import com.blackclaw.android.ui.chat.ComposeChatActivity

/**
 * Create a homescreen shortcut that fires a saved task or chat in BlackClaw.
 * Uses ShortcutManager.requestPinShortcut which prompts the user to pin it.
 */
class CreateShortcutTool : BaseTool() {
    override fun getName() = "create_shortcut"
    override fun getDisplayName() = "Crear acceso directo"
    override fun getDescriptionEN() =
        "Create a homescreen shortcut that runs a task or chat when tapped. " +
        "The user is asked to confirm the pin. Useful for one-tap automations."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("label", "string", "Visible label on the homescreen icon.", true),
        ToolParameter("text", "string",
            "Task or chat text fired when the shortcut launches.", true),
        ToolParameter("mode", "string", "task (default) | chat", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return ToolResult.error("Necesita Android 8.0+.")
        }
        val label = requireString(params, "label").trim().take(40)
        val text = requireString(params, "text").trim()
        val mode = optionalString(params, "mode", "task").lowercase()
        if (label.isEmpty() || text.isEmpty()) return ToolResult.error("label y text obligatorios")
        val ctx = ClawApplication.instance
        val sm = ctx.getSystemService(ShortcutManager::class.java)
            ?: return ToolResult.error("ShortcutManager no disponible.")
        if (!sm.isRequestPinShortcutSupported) {
            return ToolResult.error("El launcher actual no permite pinear accesos directos.")
        }
        val intent = Intent(ctx, ComposeChatActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            when (mode) {
                "chat" -> putExtra("chat", text)
                else -> putExtra("task", text)
            }
        }
        val shortcut = ShortcutInfo.Builder(ctx, "bc-${System.currentTimeMillis()}")
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(Icon.createWithResource(ctx, R.drawable.ic_launcher_monochrome))
            .setIntent(intent)
            .build()
        return try {
            val ok = sm.requestPinShortcut(shortcut, null)
            if (ok) ToolResult.success("Acceso directo pedido: \"$label\". Confirma en el launcher.")
            else ToolResult.error("El launcher rechazó la petición.")
        } catch (e: Exception) {
            ToolResult.error("Fallo creando acceso directo: ${e.message}")
        }
    }
}
