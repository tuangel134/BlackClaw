package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolRegistry
import com.blackclaw.android.tool.ToolResult
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Execute a small chain of tool calls without going back to the LLM between them.
 *
 * The LLM emits a JSON array of `{tool, params}` objects and the runtime runs
 * them sequentially until any of them fails or all succeed. This is huge for
 * latency: a typical "open app → wait → tap text → input" sequence used to
 * cost 4 LLM rounds (~12 seconds with local Gemma). With this it's 1 round.
 *
 * Hard limits:
 *  - Max 6 steps per plan (otherwise the LLM is hallucinating)
 *  - Each step uses a fresh ToolRegistry call → caching + ActionGuard still apply
 *  - On first error, plan stops and returns a rich error so the LLM can recover
 */
class ExecutePlanTool : BaseTool() {

    override fun getName() = "execute_plan"
    override fun getDisplayName() = "Plan multi-paso"
    override fun getDescriptionEN() =
        "Execute a short chain of tool calls in one shot. Use this when you " +
        "already know 2-6 sequential actions (e.g. 'open app, wait, tap_node, " +
        "input_text'). Skip if any step depends on screen output you haven't seen yet. " +
        "steps must be a JSON array like " +
        "[{\"tool\":\"open_app\",\"params\":{\"package_name\":\"com.x\"}}, " +
        "{\"tool\":\"wait\",\"params\":{\"duration_ms\":1500}}, ...]"
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("steps", "string",
            "JSON array of {tool, params} objects. Max 6 entries.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val raw = requireString(params, "steps")
        val plan = parsePlan(raw)
            ?: return ToolResult.error("steps must be a JSON array of {tool, params}.")
        if (plan.isEmpty()) return ToolResult.error("plan vacío")
        if (plan.size > 6) {
            return ToolResult.error("Plan demasiado largo (${plan.size}). Máximo 6 pasos.")
        }
        // Refuse plans that include another execute_plan to prevent recursive blowups
        if (plan.any { it.tool == "execute_plan" }) {
            return ToolResult.error("execute_plan dentro de execute_plan no permitido.")
        }

        val results = mutableListOf<String>()
        for ((idx, step) in plan.withIndex()) {
            val r = ToolRegistry.getInstance().executeTool(step.tool, step.params)
            val label = "[$idx] ${step.tool}"
            if (!r.isSuccess) {
                results.add("$label → ✗ ${r.error}")
                return ToolResult.error(
                    "Plan abortado en paso $idx (${step.tool}): ${r.error}\n\n" +
                    "Hechos hasta aquí:\n" + results.joinToString("\n")
                )
            }
            results.add("$label → ✓ ${(r.data ?: "").take(160)}")
        }
        return ToolResult.success("Plan completado (${plan.size} pasos):\n" + results.joinToString("\n"))
    }

    private data class Step(val tool: String, val params: Map<String, Any>)

    private fun parsePlan(json: String): List<Step>? {
        return try {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val raw: List<Map<String, Any>> = Gson().fromJson(json.trim(), type) ?: return null
            raw.map { m ->
                val tool = m["tool"]?.toString() ?: return null
                @Suppress("UNCHECKED_CAST")
                val params = (m["params"] as? Map<String, Any>) ?: emptyMap()
                Step(tool, params)
            }
        } catch (_: Exception) { null }
    }
}
