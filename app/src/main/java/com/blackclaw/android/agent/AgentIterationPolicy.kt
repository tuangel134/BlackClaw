package com.blackclaw.android.agent

/**
 * Safe iteration windows for long-running phone tasks.
 *
 * A configured iteration count is treated as a checkpoint window, not as an
 * immediate hard failure. Long lists and repetitive forms often need more than
 * one window, but an unbounded loop is still unsafe. We therefore allow two
 * automatic continuation windows, for a maximum of three windows total.
 */
object AgentIterationPolicy {
    const val MAX_AUTO_CONTINUATIONS = 2

    fun window(configured: Int): Int = configured.coerceAtLeast(1)

    fun hardLimit(configured: Int): Int =
        (window(configured) * (MAX_AUTO_CONTINUATIONS + 1)).coerceAtMost(240)

    fun isCheckpoint(iteration: Int, configured: Int): Boolean {
        val windowSize = window(configured)
        val limit = hardLimit(configured)
        return iteration > 0 && iteration % windowSize == 0 && iteration < limit
    }
}
