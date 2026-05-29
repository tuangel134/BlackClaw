package com.blackclaw.android.agent

import com.blackclaw.android.utils.XLog

/**
 * Tracks token usage and estimated cost during agent task execution.
 * Updated after each LLM call in the agent loop.
 */
class TokenMonitor(private val modelName: String) {

    private var totalInputTokens: Int = 0
    private var totalOutputTokens: Int = 0
    private var totalTokens: Int = 0
    private var currentStep: Int = 0

    enum class State {
        NORMAL,     // 0-30K tokens
        CAUTION,    // 30K-100K
        WARNING,    // 100K-200K
        CRITICAL    // 200K+
    }

    data class Status(
        val step: Int,
        val totalTokens: Int,
        val inputTokens: Int,
        val outputTokens: Int,
        val estimatedCostUsd: Double,
        val state: State,
        val formattedTokens: String,
        val formattedCost: String
    )

    /**
     * Record token usage from one LLM call.
     * Call this after each agent loop iteration.
     */
    fun record(step: Int, inputTokens: Int?, outputTokens: Int?, totalTokenCount: Int?) {
        currentStep = step
        if (inputTokens != null) totalInputTokens += inputTokens
        if (outputTokens != null) totalOutputTokens += outputTokens
        if (totalTokenCount != null) {
            totalTokens += totalTokenCount
        } else {
            totalTokens = totalInputTokens + totalOutputTokens
        }

        val status = getStatus()
        XLog.i(TAG, "Step $step: ${status.formattedTokens} tokens, ${status.formattedCost} [${status.state}]")
    }

    /**
     * Get current token status snapshot.
     */
    fun getStatus(): Status {
        val cost = ModelPricing.estimateCost(modelName, totalInputTokens, totalOutputTokens)
        val state = when {
            totalTokens >= 200_000 -> State.CRITICAL
            totalTokens >= 100_000 -> State.WARNING
            totalTokens >= 30_000 -> State.CAUTION
            else -> State.NORMAL
        }
        return Status(
            step = currentStep,
            totalTokens = totalTokens,
            inputTokens = totalInputTokens,
            outputTokens = totalOutputTokens,
            estimatedCostUsd = cost,
            state = state,
            formattedTokens = ModelPricing.formatTokens(totalTokens),
            formattedCost = ModelPricing.formatCost(cost)
        )
    }

    fun reset() {
        totalInputTokens = 0
        totalOutputTokens = 0
        totalTokens = 0
        currentStep = 0
    }

    companion object {
        private const val TAG = "TokenMonitor"
    }
}
