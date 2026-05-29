package com.blackclaw.android.tool.impl.mobile

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Two-finger pinch (zoom). action='in' brings fingers together (zoom out / unzoom),
 * action='out' moves them apart (zoom in). Works in maps, photo viewers,
 * browsers, and most games.
 */
class PinchTool : BaseTool() {
    override fun getName() = "pinch"
    override fun getDisplayName() = "Pinch / zoom"
    override fun getDescriptionEN() =
        "Two-finger pinch gesture for zoom. " +
        "action: 'in' = fingers come together (zoom out), 'out' = move apart (zoom in)."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("center_x", "integer", "X del centro del gesto.", true),
        ToolParameter("center_y", "integer", "Y del centro.", true),
        ToolParameter("action", "string", "in (zoom out) | out (zoom in)", true),
        ToolParameter("amount", "integer",
            "Distancia en pixels entre los dedos al inicio (default 400). " +
            "Cuanto mayor, más zoom.", false),
        ToolParameter("duration_ms", "integer", "Duración (default 400ms).", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val service = requireAccessibilityService()
            ?: return ToolResult.error("Accessibility service no activo")
        val cx = requireInt(params, "center_x")
        val cy = requireInt(params, "center_y")
        validateCoordinates(cx, cy)?.let { return ToolResult.error(it) }
        val action = requireString(params, "action").lowercase().trim()
        val amount = optionalInt(params, "amount", 400).coerceIn(50, 2000)
        val duration = optionalInt(params, "duration_ms", 400).coerceIn(100, 2000).toLong()

        val (start, end) = when (action) {
            "out", "zoom_in", "zoom-in" -> Pair(120, amount)        // start close, end apart
            "in", "zoom_out", "zoom-out" -> Pair(amount, 120)       // start apart, end close
            else -> return ToolResult.error("action debe ser 'in' o 'out'")
        }
        val ok = service.performPinch(cx, cy, start, end, duration)
        return if (ok) ToolResult.success("Pinch $action en ($cx, $cy)")
        else ToolResult.error("Gesto cancelado")
    }
}
