package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import java.util.UUID
import kotlin.random.Random

/**
 * Pure-random helpers: number in range, dice roll, coin flip, pick one,
 * UUID, password generator. Bypasses the LLM hallucinating "randomness".
 */
class RandomTool : BaseTool() {
    override fun getName() = "random"
    override fun getDisplayName() = "Aleatorio"
    override fun getDescriptionEN() =
        "Generate something random. mode='number' (with min/max), 'dice' (sides), " +
        "'coin', 'pick' (from comma-separated 'choices'), 'uuid', or 'password' (length)."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("mode", "string", "number|dice|coin|pick|uuid|password", true),
        ToolParameter("min", "integer", "(number) min inclusive. Default 0.", false),
        ToolParameter("max", "integer", "(number) max inclusive. Default 100.", false),
        ToolParameter("sides", "integer", "(dice) number of sides. Default 6.", false),
        ToolParameter("choices", "string", "(pick) comma-separated options.", false),
        ToolParameter("length", "integer", "(password) length 8..64. Default 16.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        return try {
            when (val mode = requireString(params, "mode").lowercase()) {
                "number" -> {
                    val min = optionalInt(params, "min", 0)
                    val max = optionalInt(params, "max", 100)
                    if (min > max) return ToolResult.error("min > max")
                    ToolResult.success((min..max).random().toString())
                }
                "dice" -> {
                    val sides = optionalInt(params, "sides", 6).coerceIn(2, 1000)
                    ToolResult.success("🎲 ${(1..sides).random()} (de $sides)")
                }
                "coin" -> ToolResult.success(if (Random.nextBoolean()) "🪙 cara" else "🪙 cruz")
                "pick" -> {
                    val list = optionalString(params, "choices", "").split(",")
                        .map { it.trim() }.filter { it.isNotEmpty() }
                    if (list.isEmpty()) return ToolResult.error("choices vacío")
                    ToolResult.success("🎯 ${list.random()}")
                }
                "uuid" -> ToolResult.success(UUID.randomUUID().toString())
                "password" -> {
                    val len = optionalInt(params, "length", 16).coerceIn(8, 64)
                    val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#\$%^&*"
                    val pw = (1..len).map { chars.random() }.joinToString("")
                    ToolResult.success("🔐 $pw")
                }
                else -> ToolResult.error("mode desconocido '$mode'")
            }
        } catch (e: Exception) {
            ToolResult.error("Random failed: ${e.message}")
        }
    }
}
