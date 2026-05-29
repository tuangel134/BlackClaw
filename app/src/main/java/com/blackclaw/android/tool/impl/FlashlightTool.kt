package com.blackclaw.android.tool.impl

import android.content.Context
import android.hardware.camera2.CameraManager
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Toggle the device flashlight (torch) via Camera2 API.
 * No CAMERA permission required for setTorchMode on API 23+.
 */
class FlashlightTool : BaseTool() {

    companion object {
        @Volatile private var torchOn: Boolean = false
    }

    override fun getName() = "flashlight"
    override fun getDisplayName() = "Linterna"
    override fun getDescriptionEN() =
        "Turn the flashlight on/off. action: 'on' | 'off' | 'toggle' (default toggle)."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("action", "string", "on | off | toggle", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = optionalString(params, "action", "toggle").lowercase()
        val ctx = ClawApplication.instance
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ToolResult.error("Camera service not available.")
        return try {
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolResult.error("No flash unit found on this device.")
            val target = when (action) {
                "on", "true", "1" -> true
                "off", "false", "0" -> false
                "toggle", "" -> !torchOn
                else -> return ToolResult.error("action must be on|off|toggle")
            }
            cm.setTorchMode(cameraId, target)
            torchOn = target
            ToolResult.success("Linterna ${if (target) "encendida" else "apagada"}")
        } catch (e: Exception) {
            ToolResult.error("Flashlight failed: ${e.message}")
        }
    }
}
