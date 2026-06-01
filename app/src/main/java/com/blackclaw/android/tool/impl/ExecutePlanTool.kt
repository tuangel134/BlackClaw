package com.blackclaw.android.tool.impl

import com.blackclaw.android.agent.ActionGuard
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
 * Reliability (oleada 14):
 *  - Each step may declare `expect`: a substring that must appear in the step's
 *    own result for it to count as successful. If it's missing, the step is
 *    retried once before the plan aborts — this catches "tap reported success
 *    but nothing happened" cases.
 *  - Each step may declare `verify_text`: after the step runs, the screen is
 *    read (get_screen_info) and must contain that text, else retry once.
 *  - ActionGuard is applied to every step here too, so destructive calls can't
 *    sneak through a plan (executeTool itself does NOT run the guard).
 *
 * Hard limits:
 *  - Max 6 steps per plan (otherwise the LLM is hallucinating)
 *  - No nested execute_plan (prevents recursive blowups)
 */
class ExecutePlanTool : BaseTool() {

    override fun getName() = "execute_plan"
    override fun getDisplayName() = "Plan multi-paso"
    override fun getDescriptionEN() =
        "Execute a short chain of tool calls in one shot. Use this when you " +
        "already know 2-6 sequential actions (e.g. 'open app, wait, tap_node, " +
        "input_text'). Skip if any step depends on screen output you haven't seen yet. " +
        "Each step is {\"tool\":..,\"params\":{..}} and may optionally include " +
        "\"expect\":\"substring the step result must contain\" and/or " +
        "\"verify_text\":\"text that must appear on screen after the step\"; " +
        "failed expectations are retried once before aborting. Example: " +
        "[{\"tool\":\"open_app\",\"params\":{\"package_name\":\"com.x\"},\"verify_text\":\"Chats\"}, " +
        "{\"tool\":\"wait\",\"params\":{\"duration_ms\":1500}}]"
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("steps", "string",
            "JSON array of {tool, params, expect?, verify_text?} objects. Max 6 entries.", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val raw = requireString(params, "steps")
        val plan = parsePlan(raw)
            ?: return ToolResult.error("steps must be a JSON array of {tool, params}.")
        when (val problem = validate(plan)) {
            null -> {}
            else -> return ToolResult.error(problem)
        }

        val results = mutableListOf<String>()
        for ((idx, step) in plan.withIndex()) {
            // Guard every step (executeTool does not). A destructive step aborts the plan.
            if (ActionGuard.assess(step.tool, step.params) == ActionGuard.Risk.DESTRUCTIVE) {
                results.add("[$idx] ${step.tool} → ✗ bloqueado (acción destructiva)")
                return ToolResult.error(
                    "Plan abortado en paso $idx (${step.tool}): acción potencialmente destructiva. " +
                    "Confírmalo con el usuario en texto antes de intentarlo.\n\n" +
                    "Hechos hasta aquí:\n" + results.joinToString("\n"))
            }

            val (ok, detail) = runStepWithVerification(step)
            val label = "[$idx] ${step.tool}"
            if (!ok) {
                results.add("$label → ✗ $detail")
                return ToolResult.error(
                    "Plan abortado en paso $idx (${step.tool}): $detail\n\n" +
                    "Hechos hasta aquí:\n" + results.joinToString("\n"))
            }
            results.add("$label → ✓ ${detail.take(160)}")
        }
        return ToolResult.success("Plan completado (${plan.size} pasos):\n" + results.joinToString("\n"))
    }

    /** Run a step, honoring its expect/verify_text, retrying once on failure. */
    private fun runStepWithVerification(step: Step): Pair<Boolean, String> {
        var lastDetail = ""
        repeat(2) { attempt ->
            val r = ToolRegistry.getInstance().executeTool(step.tool, step.params)
            if (!r.isSuccess) {
                lastDetail = r.error ?: "error desconocido"
                return@repeat // retry
            }
            val data = r.data ?: ""
            // expect: substring must be in the step's own result.
            if (step.expect != null && !data.contains(step.expect, ignoreCase = true)) {
                lastDetail = "esperaba '${step.expect}' en el resultado pero no apareció"
                return@repeat
            }
            // verify_text: read the screen and confirm the text is present.
            if (step.verifyText != null && !screenContains(step.verifyText)) {
                lastDetail = "no encontré '${step.verifyText}' en pantalla tras el paso"
                return@repeat
            }
            val suffix = if (attempt > 0) " (reintento)" else ""
            return true to (data.ifBlank { "ok" } + suffix)
        }
        return false to lastDetail
    }

    private fun screenContains(text: String): Boolean {
        val r = ToolRegistry.getInstance().executeTool("get_screen_info", emptyMap())
        return r.isSuccess && (r.data ?: "").contains(text, ignoreCase = true)
    }

    internal data class Step(
        val tool: String,
        val params: Map<String, Any>,
        val expect: String? = null,
        val verifyText: String? = null,
    )

    /** Returns an error string if the plan is invalid, or null if OK. Pure. */
    internal fun validate(plan: List<Step>): String? {
        if (plan.isEmpty()) return "plan vacío"
        if (plan.size > 6) return "Plan demasiado largo (${plan.size}). Máximo 6 pasos."
        if (plan.any { it.tool == "execute_plan" }) return "execute_plan dentro de execute_plan no permitido."
        if (plan.any { it.tool.isBlank() }) return "un paso no tiene 'tool'."
        return null
    }

    private fun parsePlan(json: String): List<Step>? {
        return try {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val raw: List<Map<String, Any>> = Gson().fromJson(json.trim(), type) ?: return null
            raw.map { m ->
                val tool = m["tool"]?.toString() ?: return null
                @Suppress("UNCHECKED_CAST")
                val params = (m["params"] as? Map<String, Any>) ?: emptyMap()
                Step(
                    tool = tool,
                    params = params,
                    expect = m["expect"]?.toString()?.takeIf { it.isNotBlank() },
                    verifyText = m["verify_text"]?.toString()?.takeIf { it.isNotBlank() },
                )
            }
        } catch (_: Exception) { null }
    }
}
