package com.blackclaw.android.tool.impl

import android.util.Base64
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

class Base64Tool : BaseTool() {
    override fun getName() = "base64"
    override fun getDisplayName() = "Base64"
    override fun getDescriptionEN() =
        "Encode or decode text to/from base64. action='encode' or 'decode'."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("action", "string", "encode | decode", true),
        ToolParameter("text", "string", "Texto a codificar o cadena base64 a decodificar.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = requireString(params, "action").lowercase()
        val text = requireString(params, "text")
        return try {
            when (action) {
                "encode" -> ToolResult.success(Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                "decode" -> ToolResult.success(String(Base64.decode(text, Base64.DEFAULT), Charsets.UTF_8))
                else -> ToolResult.error("action debe ser encode o decode")
            }
        } catch (e: Exception) {
            ToolResult.error("Error de base64: ${e.message}")
        }
    }
}
