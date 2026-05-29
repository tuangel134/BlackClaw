package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Evaluate a math expression. Supports +, -, *, /, ^, %, parens,
 * functions sqrt, sin, cos, tan, ln, log, exp, abs, and constants pi, e.
 *
 * Pure-Kotlin shunting-yard parser — no JS engine dependency, safe for sandbox.
 */
class MathEvalTool : BaseTool() {
    override fun getName() = "math_eval"
    override fun getDisplayName() = "Calcular"
    override fun getDescriptionEN() =
        "Evaluate a math expression. Supports +, -, *, /, ^, %, parens, " +
        "functions sqrt/sin/cos/tan/ln/log/exp/abs and constants pi/e. " +
        "Use for arithmetic, conversions, percentages."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("expression", "string", "Expresión matemática a evaluar.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val expr = requireString(params, "expression").trim()
        if (expr.isEmpty()) return ToolResult.error("expression cannot be empty")
        return try {
            val v = Parser(expr).parse()
            val pretty = if (v == v.toLong().toDouble()) v.toLong().toString()
                         else "%.6f".format(v).trimEnd('0').trimEnd('.')
            ToolResult.success("$expr = $pretty")
        } catch (e: Exception) {
            ToolResult.error("No pude evaluar: ${e.message}")
        }
    }

    /** Tiny recursive-descent parser. Operator precedence: unary, then power, then mul/div/mod, then add/sub. */
    private class Parser(private val s: String) {
        private var i = 0

        fun parse(): Double {
            val v = expr()
            skipWs()
            if (i < s.length) error("Carácter inesperado '${s[i]}' en $i")
            return v
        }

        private fun expr(): Double {
            var v = term()
            while (true) {
                skipWs()
                when (peek()) {
                    '+' -> { i++; v += term() }
                    '-' -> { i++; v -= term() }
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = factor()
            while (true) {
                skipWs()
                when (peek()) {
                    '*' -> { i++; v *= factor() }
                    '/' -> { i++; v /= factor() }
                    '%' -> { i++; v %= factor() }
                    else -> return v
                }
            }
        }

        private fun factor(): Double {
            var v = unary()
            skipWs()
            while (peek() == '^') {
                i++
                v = v.pow(unary())
                skipWs()
            }
            return v
        }

        private fun unary(): Double {
            skipWs()
            if (peek() == '+') { i++; return unary() }
            if (peek() == '-') { i++; return -unary() }
            return primary()
        }

        private fun primary(): Double {
            skipWs()
            val c = peek() ?: error("Fin inesperado")
            if (c == '(') {
                i++
                val v = expr()
                skipWs()
                if (peek() != ')') error("Falta ')'")
                i++
                return v
            }
            if (c.isLetter()) return identifier()
            if (c.isDigit() || c == '.') return number()
            error("Símbolo desconocido '$c' en $i")
        }

        private fun identifier(): Double {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
            val name = s.substring(start, i).lowercase()
            skipWs()
            return when {
                name == "pi" -> PI
                name == "e" -> kotlin.math.E
                peek() == '(' -> {
                    i++
                    val arg = expr()
                    skipWs()
                    if (peek() != ')') error("Falta ')' tras $name")
                    i++
                    when (name) {
                        "sqrt" -> sqrt(arg)
                        "sin" -> sin(arg)
                        "cos" -> cos(arg)
                        "tan" -> tan(arg)
                        "ln" -> ln(arg)
                        "log", "log10" -> log10(arg)
                        "exp" -> exp(arg)
                        "abs" -> abs(arg)
                        else -> error("Función desconocida '$name'")
                    }
                }
                else -> error("Identificador desconocido '$name'")
            }
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                                    (s[i] == '-' && (s[i-1] == 'e' || s[i-1] == 'E')))) i++
            return s.substring(start, i).toDouble()
        }

        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun peek(): Char? = if (i < s.length) s[i] else null
        private fun error(msg: String): Nothing = throw IllegalArgumentException(msg)
    }
}
