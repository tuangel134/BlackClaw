package com.blackclaw.android.tool.guard

/**
 * Provenance of the task currently driving tool execution.
 *
 * Tools run on a shared `tool-exec` pool rather than the task thread, so a
 * `ThreadLocal` would not propagate. A single volatile field works because the task
 * lock in `TaskSessionStore` already guarantees one task at a time; this simply
 * mirrors that task's origin somewhere the tool layer can read it.
 *
 * The dependency points inward on purpose: the orchestrator pushes provenance in,
 * and the tool layer never reaches back out to ask.
 *
 * Fails closed — an unset origin reads as [ToolRiskPolicy.Origin.UNKNOWN], which
 * [ToolRiskPolicy] treats as strictly as a remote request.
 */
object ToolExecutionContext {

    @Volatile
    private var currentOrigin: ToolRiskPolicy.Origin = ToolRiskPolicy.Origin.UNKNOWN

    val origin: ToolRiskPolicy.Origin get() = currentOrigin

    fun setOrigin(origin: ToolRiskPolicy.Origin) {
        currentOrigin = origin
    }

    fun reset() {
        currentOrigin = ToolRiskPolicy.Origin.UNKNOWN
    }
}
