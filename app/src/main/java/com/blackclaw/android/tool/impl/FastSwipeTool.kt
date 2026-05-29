package com.blackclaw.android.tool.impl

import com.blackclaw.android.adb.PrivilegedShell
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/** `input swipe x1 y1 x2 y2 [duration_ms]` via Shizuku or paired ADB. */
class FastSwipeTool : BaseTool() {
    override fun getName() = "fast_swipe"
    override fun getDisplayName() = "Deslizar rápido"
    override fun getDescriptionEN() =
        "Swipe from (x1,y1) to (x2,y2) via shell input. ~10x faster than swipe " +
        "and works in games. Requires Shizuku active OR ADB self-paired."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("x1", "integer", "X inicial.", true),
        ToolParameter("y1", "integer", "Y inicial.", true),
        ToolParameter("x2", "integer", "X final.", true),
        ToolParameter("y2", "integer", "Y final.", true),
        ToolParameter("duration_ms", "integer",
            "Duración del swipe en ms. Default 250.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        if (!PrivilegedShell.isAvailable()) {
            return ToolResult.error("Sin acceso privilegiado (Shizuku/ADB). Usa 'swipe' en su lugar.")
        }
        val x1 = requireInt(params, "x1")
        val y1 = requireInt(params, "y1")
        val x2 = requireInt(params, "x2")
        val y2 = requireInt(params, "y2")
        val dur = optionalInt(params, "duration_ms", 250).coerceIn(50, 5000)
        if (!PrivilegedShell.execFast("input swipe $x1 $y1 $x2 $y2 $dur")) {
            return ToolResult.error("input swipe falló")
        }
        return ToolResult.success("Swipe rápido ($x1,$y1)→($x2,$y2) en ${dur}ms")
    }
}
