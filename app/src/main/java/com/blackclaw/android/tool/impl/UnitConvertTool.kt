package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Pure local unit conversion. Length, mass, time, temperature, volume, speed.
 * The LLM uses this instead of doing arithmetic in its head.
 */
class UnitConvertTool : BaseTool() {
    override fun getName() = "unit_convert"
    override fun getDisplayName() = "Conversor"
    override fun getDescriptionEN() =
        "Convert between units. Examples of pairs accepted: " +
        "km/mi, kg/lb, m/ft, °C/°F, l/gal, kmh/mph, h/min, gb/mb."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("value", "number", "Cantidad numérica.", true),
        ToolParameter("from", "string", "Unidad de origen (km, mi, kg, lb, c, f, …).", true),
        ToolParameter("to", "string", "Unidad de destino.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val v = (params["value"] as? Number)?.toDouble()
            ?: params["value"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult.error("value inválido")
        val from = requireString(params, "from").lowercase().trim().trimStart('°')
        val to = requireString(params, "to").lowercase().trim().trimStart('°')

        // Try same-category conversions
        val result = convert(v, from, to)
            ?: return ToolResult.error("No sé convertir de '$from' a '$to'")
        val pretty = if (result == result.toLong().toDouble()) result.toLong().toString()
                     else "%.4f".format(result).trimEnd('0').trimEnd('.')
        return ToolResult.success("$v $from = $pretty $to")
    }

    /** Each category exposes a `to-base` factor. We convert `from → base → to`. */
    private val LENGTH = mapOf(
        "m" to 1.0, "km" to 1000.0, "cm" to 0.01, "mm" to 0.001,
        "in" to 0.0254, "inch" to 0.0254, "ft" to 0.3048, "yd" to 0.9144,
        "mi" to 1609.344, "mile" to 1609.344,
    )
    private val MASS = mapOf(
        "g" to 1.0, "kg" to 1000.0, "mg" to 0.001, "t" to 1_000_000.0,
        "lb" to 453.59237, "oz" to 28.3495, "st" to 6350.293,
    )
    private val TIME = mapOf(
        "s" to 1.0, "sec" to 1.0, "min" to 60.0, "h" to 3600.0, "hr" to 3600.0,
        "d" to 86400.0, "day" to 86400.0, "wk" to 604800.0, "y" to 31536000.0,
    )
    private val VOLUME = mapOf(
        "ml" to 1.0, "l" to 1000.0, "cl" to 10.0, "dl" to 100.0,
        "gal" to 3785.411784, "pt" to 473.176473, "qt" to 946.352946,
        "fl_oz" to 29.5735296, "cup" to 236.588,
    )
    private val SPEED = mapOf(
        "kmh" to 1.0, "km/h" to 1.0, "mph" to 1.609344, "mi/h" to 1.609344,
        "m/s" to 3.6, "ms" to 3.6, "knot" to 1.852,
    )
    private val DIGITAL = mapOf(
        "b" to 1.0, "byte" to 1.0, "kb" to 1024.0, "mb" to 1048576.0,
        "gb" to 1073741824.0, "tb" to 1099511627776.0,
    )

    private fun convert(v: Double, from: String, to: String): Double? {
        // Temperature is special — non-multiplicative
        if (from in TEMPS && to in TEMPS) return tempConvert(v, from, to)
        for (cat in listOf(LENGTH, MASS, TIME, VOLUME, SPEED, DIGITAL)) {
            if (from in cat && to in cat) {
                return v * cat[from]!! / cat[to]!!
            }
        }
        return null
    }

    private val TEMPS = setOf("c", "celsius", "f", "fahrenheit", "k", "kelvin")
    private fun tempConvert(v: Double, from: String, to: String): Double {
        val celsius = when (from.first()) {
            'f' -> (v - 32) * 5.0 / 9.0
            'k' -> v - 273.15
            else -> v
        }
        return when (to.first()) {
            'f' -> celsius * 9.0 / 5.0 + 32
            'k' -> celsius + 273.15
            else -> celsius
        }
    }
}
