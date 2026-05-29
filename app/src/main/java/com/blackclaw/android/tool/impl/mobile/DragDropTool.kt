package com.blackclaw.android.tool.impl.mobile

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Real drag-and-drop: long-press (press_ms) at the source point, then move to
 * destination while still pressed. This is what reorders homescreen icons,
 * drags items in lists, drops files in file managers, pulls down quick settings
 * tiles, etc.
 *
 * Differs from `swipe`: swipe is a quick flick. Drag holds the press first
 * (so the long-press handler fires) and then translates.
 */
class DragDropTool : BaseTool() {
    override fun getName() = "drag_drop"
    override fun getDisplayName() = "Arrastrar"
    override fun getDescriptionEN() =
        "Long-press at (start_x, start_y) and drag to (end_x, end_y). " +
        "Different from swipe — this triggers the long-press handler first " +
        "(which is what most apps need to enable drag mode). " +
        "Use for: reordering homescreen icons, moving cards in lists, " +
        "dragging files, dragging notification tiles."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("start_x", "integer", "X de origen.", true),
        ToolParameter("start_y", "integer", "Y de origen.", true),
        ToolParameter("end_x", "integer", "X destino.", true),
        ToolParameter("end_y", "integer", "Y destino.", true),
        ToolParameter("press_ms", "integer",
            "Cuánto mantener pulsado antes de mover (default 500ms para activar long-press).", false),
        ToolParameter("move_ms", "integer", "Duración del arrastre (default 500ms).", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val service = requireAccessibilityService()
            ?: return ToolResult.error("Accessibility service no activo")
        val sx = requireInt(params, "start_x")
        val sy = requireInt(params, "start_y")
        val ex = requireInt(params, "end_x")
        val ey = requireInt(params, "end_y")
        validateCoordinates(sx, sy)?.let { return ToolResult.error(it) }
        validateCoordinates(ex, ey)?.let { return ToolResult.error(it) }
        val press = optionalInt(params, "press_ms", 500).coerceIn(200, 2000).toLong()
        val move = optionalInt(params, "move_ms", 500).coerceIn(100, 3000).toLong()
        val ok = service.performDragAndDrop(sx, sy, ex, ey, press, move)
        return if (ok) ToolResult.success("Arrastrado ($sx,$sy)→($ex,$ey)")
        else ToolResult.error("Gesto cancelado")
    }
}
