package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/** Convert colors between hex, rgb, hsl, and named. */
class ColorTool : BaseTool() {
    override fun getName() = "color_convert"
    override fun getDisplayName() = "Color"
    override fun getDescriptionEN() =
        "Convert a color value to other formats. " +
        "Accepts hex (#RRGGBB), rgb(r,g,b), or named ('red', 'cyan', etc). " +
        "Returns hex + rgb + hsl together."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("input", "string", "Color en cualquier formato común.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val raw = requireString(params, "input").trim().lowercase()
        val (r, g, b) = parse(raw) ?: return ToolResult.error("No pude interpretar '$raw'")
        val hex = "#%02X%02X%02X".format(r, g, b)
        val (h, s, l) = rgbToHsl(r, g, b)
        return ToolResult.success(
            "HEX: $hex\n" +
            "RGB: rgb($r, $g, $b)\n" +
            "HSL: hsl(${h.toInt()}, ${(s * 100).toInt()}%, ${(l * 100).toInt()}%)"
        )
    }

    private fun parse(raw: String): Triple<Int, Int, Int>? {
        val named = NAMED[raw]
        if (named != null) return named
        val hex = raw.removePrefix("#")
        if (hex.length == 6 && hex.all { it.isDigit() || it in 'a'..'f' }) {
            return Triple(
                hex.substring(0, 2).toInt(16),
                hex.substring(2, 4).toInt(16),
                hex.substring(4, 6).toInt(16),
            )
        }
        if (hex.length == 3 && hex.all { it.isDigit() || it in 'a'..'f' }) {
            return Triple(
                (hex[0].toString().repeat(2)).toInt(16),
                (hex[1].toString().repeat(2)).toInt(16),
                (hex[2].toString().repeat(2)).toInt(16),
            )
        }
        Regex("""rgb\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)""").matchEntire(raw)?.let {
            return Triple(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        return null
    }

    private fun rgbToHsl(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf)
        val l = (max + min) / 2f
        if (max == min) return Triple(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            rf -> ((gf - bf) / d + if (gf < bf) 6f else 0f)
            gf -> ((bf - rf) / d + 2f)
            else -> ((rf - gf) / d + 4f)
        } * 60f
        return Triple(h, s, l)
    }

    private val NAMED = mapOf(
        "red" to Triple(255, 0, 0), "green" to Triple(0, 128, 0), "blue" to Triple(0, 0, 255),
        "cyan" to Triple(0, 255, 255), "magenta" to Triple(255, 0, 255), "yellow" to Triple(255, 255, 0),
        "black" to Triple(0, 0, 0), "white" to Triple(255, 255, 255), "gray" to Triple(128, 128, 128),
        "orange" to Triple(255, 165, 0), "purple" to Triple(128, 0, 128), "pink" to Triple(255, 192, 203),
        "brown" to Triple(165, 42, 42), "navy" to Triple(0, 0, 128), "teal" to Triple(0, 128, 128),
    )
}
