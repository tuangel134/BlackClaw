package com.blackclaw.android.agent

import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog

/**
 * Per-task token budget with soft and hard limits.
 *
 * Architecture reference:
 * - AgentBudget: soft 90% warning + hard 100% kill
 * - Adapted for BlackClaw: configurable via KVUtils settings
 */
class TaskBudget(
    val maxTokens: Int,
    val maxCostUsd: Double,
    private val softLimitPercent: Float = 0.8f
) {

    enum class Status {
        OK,
        SOFT_LIMIT,     // 80% — inject warning prompt
        HARD_LIMIT      // 100% — force finish
    }

    /**
     * Check current token/cost against budget.
     */
    fun check(currentTokens: Int, currentCostUsd: Double): Status {
        val tokenLimitEnabled = maxTokens in 1 until Int.MAX_VALUE
        val costLimitEnabled = maxCostUsd > 0

        // Hard limit check (either tokens or cost)
        if (tokenLimitEnabled && currentTokens >= maxTokens) {
            XLog.w(TAG, "HARD LIMIT: tokens $currentTokens >= max $maxTokens")
            return Status.HARD_LIMIT
        }
        if (costLimitEnabled && currentCostUsd >= maxCostUsd) {
            XLog.w(TAG, "HARD LIMIT: cost $$currentCostUsd >= max $$maxCostUsd")
            return Status.HARD_LIMIT
        }

        // Soft limit check
        if (tokenLimitEnabled) {
            val tokenPercent = currentTokens.toFloat() / maxTokens
            if (tokenPercent >= softLimitPercent) {
                XLog.i(TAG, "SOFT LIMIT: tokens at ${(tokenPercent * 100).toInt()}% of budget")
                return Status.SOFT_LIMIT
            }
        }
        if (costLimitEnabled) {
            val costPercent = currentCostUsd / maxCostUsd
            if (costPercent >= softLimitPercent) {
                XLog.i(TAG, "SOFT LIMIT: cost at ${(costPercent * 100).toInt()}% of budget")
                return Status.SOFT_LIMIT
            }
        }

        return Status.OK
    }

    companion object {
        private const val TAG = "TaskBudget"

        private const val KEY_MAX_TOKENS = "KEY_TASK_MAX_TOKENS"
        private const val KEY_MAX_COST = "KEY_TASK_MAX_COST_USD"
        private const val KEY_BUDGET_VERSION = "KEY_TASK_BUDGET_VERSION"
        private const val KEY_USER_SET = "KEY_TASK_BUDGET_USER_SET"

        private const val MIGRATED_DEFAULT_MAX_TOKENS = 250_000
        private const val MIGRATED_DEFAULT_MAX_COST_USD = 1.00
        private const val UNLIMITED_TOKENS = Int.MAX_VALUE
        private const val UNLIMITED_COST_USD = 0.0
        private const val CURRENT_BUDGET_VERSION = 3

        private fun hasTokenLimit(): Boolean = KVUtils.contains(KEY_MAX_TOKENS)
        private fun hasCostLimit(): Boolean = KVUtils.contains(KEY_MAX_COST)

        /**
         * Create a TaskBudget from user settings.
         */
        fun fromSettings(): TaskBudget {
            maybeClearAutoDefaults()
            val maxTokens = getConfiguredMaxTokens() ?: UNLIMITED_TOKENS
            val maxCost = getConfiguredMaxCost() ?: UNLIMITED_COST_USD
            return TaskBudget(maxTokens, maxCost)
        }

        fun saveMaxTokens(value: Int): Boolean {
            markUserConfigured()
            return KVUtils.putInt(KEY_MAX_TOKENS, value)
        }

        fun saveMaxCost(value: Double): Boolean {
            markUserConfigured()
            return KVUtils.putDouble(KEY_MAX_COST, value)
        }

        fun clearMaxTokens() {
            markUserConfigured()
            KVUtils.remove(KEY_MAX_TOKENS)
        }

        fun clearMaxCost() {
            markUserConfigured()
            KVUtils.remove(KEY_MAX_COST)
        }

        fun clearSettings() {
            markUserConfigured()
            KVUtils.remove(KEY_MAX_TOKENS, KEY_MAX_COST)
        }

        fun getConfiguredMaxTokens(): Int? {
            maybeClearAutoDefaults()
            return if (hasTokenLimit()) KVUtils.getInt(KEY_MAX_TOKENS, UNLIMITED_TOKENS) else null
        }

        fun getConfiguredMaxCost(): Double? {
            maybeClearAutoDefaults()
            return if (hasCostLimit()) KVUtils.getDouble(KEY_MAX_COST, UNLIMITED_COST_USD) else null
        }

        fun getMaxTokens(): Int = getConfiguredMaxTokens() ?: UNLIMITED_TOKENS

        fun getMaxCost(): Double = getConfiguredMaxCost() ?: UNLIMITED_COST_USD

        fun describeCurrentBudget(): String {
            val tokenLimit = getConfiguredMaxTokens()
            val costLimit = getConfiguredMaxCost()
            return when {
                tokenLimit == null && costLimit == null -> "Unlimited"
                tokenLimit != null && costLimit != null ->
                    "${ModelPricing.formatTokens(tokenLimit)} / ${String.format("$%.2f", costLimit)}"
                tokenLimit != null ->
                    "${ModelPricing.formatTokens(tokenLimit)} / no $ cap"
                else ->
                    "Unlimited / ${String.format("$%.2f", costLimit)}"
            }
        }

        private fun maybeClearAutoDefaults() {
            if (KVUtils.getInt(KEY_BUDGET_VERSION, 0) >= CURRENT_BUDGET_VERSION) return

            val tokenLimit = if (hasTokenLimit()) KVUtils.getInt(KEY_MAX_TOKENS, UNLIMITED_TOKENS) else null
            val costLimit = if (hasCostLimit()) KVUtils.getDouble(KEY_MAX_COST, UNLIMITED_COST_USD) else null
            val userSet = KVUtils.getBoolean(KEY_USER_SET, false)

            val looksLikeOldAutoDefault = !userSet &&
                tokenLimit == MIGRATED_DEFAULT_MAX_TOKENS &&
                costLimit != null &&
                kotlin.math.abs(costLimit - MIGRATED_DEFAULT_MAX_COST_USD) < 0.000001

            if (looksLikeOldAutoDefault) {
                KVUtils.remove(KEY_MAX_TOKENS, KEY_MAX_COST)
                XLog.i(TAG, "Removed migrated default task budget; new default is unlimited until user sets one")
            }

            KVUtils.putInt(KEY_BUDGET_VERSION, CURRENT_BUDGET_VERSION)
        }

        private fun markUserConfigured() {
            KVUtils.putBoolean(KEY_USER_SET, true)
            KVUtils.putInt(KEY_BUDGET_VERSION, CURRENT_BUDGET_VERSION)
        }
    }
}
