package com.blackclaw.android.tool.impl.mobile

import com.blackclaw.android.adb.PrivilegedShell
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Auto-clicker style burst tap. Useful in:
 *  - Game shooting / quick-time events
 *  - Double-tap reset challenges
 *  - "Tap to skip" sequences (intro videos, paywalls, OK-OK-OK chains)
 *
 * Prefers a privileged shell (Shizuku / self-ADB) when available: `input tap`
 * registers inside games and SurfaceView where accessibility gestures don't,
 * and is dramatically faster. Falls back to the accessibility gesture path.
 */
class TapBurstTool : BaseTool() {
    override fun getName() = "tap_burst"
    override fun getDisplayName() = "Tap rápido x N"
    override fun getDescriptionEN() =
        "Hammer the same coordinate N times in a row, frame-aligned. " +
        "count clamped 2..30. interval_ms can be 0 for max speed (~16 ms per tap). " +
        "Useful for games, double-tap puzzles, OK chains. Uses privileged shell " +
        "(Shizuku/ADB) when available — works inside games."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("x", "integer", "X coordinate", true),
        ToolParameter("y", "integer", "Y coordinate", true),
        ToolParameter("count", "integer", "Cuántos taps (2..30).", true),
        ToolParameter("interval_ms", "integer",
            "Pausa entre taps (default 30ms). 0 = lo más rápido posible.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val x = requireInt(params, "x")
        val y = requireInt(params, "y")
        validateCoordinates(x, y)?.let { return ToolResult.error(it) }
        val count = requireInt(params, "count").coerceIn(2, 30)
        val interval = optionalInt(params, "interval_ms", 30).coerceAtLeast(0).toLong()

        // Privileged path: chain `input tap` calls in a single shell so the
        // round-trip cost is paid once. Works in games.
        if (PrivilegedShell.isAvailable()) {
            val sep = if (interval > 0) "; sleep ${"%.3f".format(interval / 1000.0)}; " else "; "
            val cmd = (1..count).joinToString(sep) { "input tap $x $y" }
            if (PrivilegedShell.exec(cmd, timeoutMs = (count * (interval + 200)) + 2000) != null) {
                return ToolResult.success("$count taps (shell) en ($x, $y)")
            }
            // fall through to accessibility on failure
        }

        val service = requireAccessibilityService()
            ?: return ToolResult.error("Accessibility service no activo")
        val ok = service.performTapBurst(x, y, count, interval)
        return if (ok) ToolResult.success("$count taps en ($x, $y) cada ${interval}ms")
        else ToolResult.error("El gesto fue cancelado")
    }
}
