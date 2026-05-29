package com.blackclaw.android.tool.impl

import com.blackclaw.android.adb.PrivilegedShell
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * High-speed tap via `input tap x y` over a privileged shell (Shizuku OR
 * self-ADB). Roughly an order of magnitude faster than the accessibility
 * GestureDescription path (~15 ms vs ~200 ms), and works inside games /
 * SurfaceView where accessibility taps don't register.
 *
 * No fallback inside the tool: if no privileged backend is ready we report it
 * explicitly so the LLM can choose plain `tap` instead.
 */
class FastTapTool : BaseTool() {
    override fun getName() = "fast_tap"
    override fun getDisplayName() = "Tap rápido"
    override fun getDescriptionEN() =
        "Tap at (x, y) using shell input via Shizuku or paired ADB — ~10x faster than tap, " +
        "and works in games / SurfaceView. Requires Shizuku active OR ADB self-paired. " +
        "Falls back: if it errors with 'sin acceso privilegiado', use the regular tap tool."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("x", "integer", "Coordenada X en pixels.", true),
        ToolParameter("y", "integer", "Coordenada Y en pixels.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        if (!PrivilegedShell.isAvailable()) {
            return ToolResult.error("Sin acceso privilegiado (Shizuku/ADB). Usa 'tap' en su lugar.")
        }
        val x = requireInt(params, "x")
        val y = requireInt(params, "y")
        validateCoordinates(x, y)?.let { return ToolResult.error(it) }
        if (!PrivilegedShell.execFast("input tap $x $y")) {
            return ToolResult.error("input tap falló")
        }
        return ToolResult.success("Tap rápido en ($x, $y)")
    }
}
