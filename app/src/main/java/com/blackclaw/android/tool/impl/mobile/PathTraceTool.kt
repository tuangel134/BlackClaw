package com.blackclaw.android.tool.impl.mobile

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Trace a multi-point path in a single continuous gesture. Useful for:
 *  - Lock screen patterns
 *  - Drawing apps
 *  - Game maneuvers that need a curved swipe (RPG dodges, certain card games)
 *  - Signing on a sign-here field
 *
 * The whole path runs as ONE gesture so timing/pressure stay coherent.
 */
class PathTraceTool : BaseTool() {
    override fun getName() = "path_trace"
    override fun getDisplayName() = "Trazar trayectoria"
    override fun getDescriptionEN() =
        "Trace a multi-point path in one continuous gesture. " +
        "points must be a JSON array like [[x1,y1],[x2,y2],...]. Min 2 points, max 32. " +
        "Use for lock patterns, drawing apps, signed signatures, curved swipes."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("points", "string",
            "JSON array de pares [x,y] en orden. Ej: '[[100,200],[300,400],[500,600]]'.", true),
        ToolParameter("duration_ms", "integer", "Duración total (default 800ms).", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val service = requireAccessibilityService()
            ?: return ToolResult.error("Accessibility service no activo")
        val raw = requireString(params, "points")
        val arr: List<List<Int>> = try {
            val type = object : TypeToken<List<List<Number>>>() {}.type
            val parsed: List<List<Number>> = Gson().fromJson(raw.trim(), type)
                ?: return ToolResult.error("points debe ser JSON array")
            parsed.map { it.map { n -> n.toInt() } }
        } catch (e: Exception) {
            return ToolResult.error("JSON inválido: ${e.message}")
        }
        if (arr.size < 2) return ToolResult.error("Necesito al menos 2 puntos")
        if (arr.size > 32) return ToolResult.error("Máximo 32 puntos")
        if (arr.any { it.size < 2 }) return ToolResult.error("Cada punto debe ser [x, y]")
        // Validate each point
        for (p in arr) {
            validateCoordinates(p[0], p[1])?.let { return ToolResult.error(it) }
        }
        val dur = optionalInt(params, "duration_ms", 800).coerceIn(100, 5000).toLong()
        val matrix = arr.map { intArrayOf(it[0], it[1]) }.toTypedArray()
        val ok = service.performPath(matrix, dur)
        return if (ok) ToolResult.success("Trayectoria de ${arr.size} puntos en ${dur}ms")
        else ToolResult.error("Gesto cancelado")
    }
}
