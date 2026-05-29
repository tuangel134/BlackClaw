package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import java.net.URLDecoder
import java.net.URLEncoder

class UrlEncodeTool : BaseTool() {
    override fun getName() = "url_encode"
    override fun getDisplayName() = "URL encode/decode"
    override fun getDescriptionEN() =
        "URL-encode or URL-decode a string. action='encode' or 'decode'."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("action", "string", "encode | decode", true),
        ToolParameter("text", "string", "Texto a procesar.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = requireString(params, "action").lowercase()
        val text = requireString(params, "text")
        return try {
            when (action) {
                "encode" -> ToolResult.success(URLEncoder.encode(text, "UTF-8"))
                "decode" -> ToolResult.success(URLDecoder.decode(text, "UTF-8"))
                else -> ToolResult.error("action debe ser encode o decode")
            }
        } catch (e: Exception) {
            ToolResult.error("URL ${action} falló: ${e.message}")
        }
    }
}
