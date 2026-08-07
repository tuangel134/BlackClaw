package com.blackclaw.android.agent

import com.blackclaw.android.agent.llm.LlmClient
import com.blackclaw.android.agent.llm.LlmResponse
import com.blackclaw.android.agent.llm.StreamingListener
import com.blackclaw.android.utils.XLog
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage

class AgentRetryHandler(
    private val config: () -> AgentConfig,
    private val llmClient: () -> LlmClient,
    private val isCancelled: () -> Boolean,
    private val onRateLimitWait: (iteration: Int, waitMs: Long) -> Unit,
) {

    companion object {
        private const val TAG = "AgentRetry"
        private const val MAX_API_RETRIES = 3
        private const val MAX_RATE_LIMIT_RETRIES = 6
    }

    fun chatWithRetry(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        callback: AgentCallback,
        iteration: Int,
    ): LlmResponse {
        val cfg = config()
        var lastException: Exception? = null
        var attempt = 0
        var rateLimitRetries = 0

        while (attempt < MAX_API_RETRIES) {
            if (isCancelled()) throw RuntimeException("Task cancelled")
            try {
                return if (cfg.streaming) {
                    try {
                        llmClient().chatStreaming(messages, toolSpecs, object : StreamingListener {
                            override fun onPartialText(token: String) {
                                if (token.isNotEmpty()) callback.onContent(iteration, token)
                            }
                            override fun onComplete(response: LlmResponse) {}
                            override fun onError(error: Throwable) {}
                        })
                    } catch (se: Exception) {
                        XLog.w(TAG, "Streaming failed, falling back to non-streaming: ${se.message}")
                        llmClient().chat(messages, toolSpecs)
                    }
                } else {
                    llmClient().chat(messages, toolSpecs)
                }
            } catch (e: Exception) {
                lastException = e
                val msg = e.message ?: ""

                if (msg.contains("401") || msg.contains("403") ||
                    (msg.contains("insufficient") && !msg.contains("rate"))) {
                    if (cfg.baseUrl.contains("opencode.ai/zen", ignoreCase = true) &&
                        (msg.contains("401") || msg.contains("403"))) {
                        XLog.w(TAG, "OpenCode Zen auth failure on '${cfg.modelName}' — re-verifying free models")
                        runCatching { OpenCodeZenModels.refreshNow() }
                    }
                    throw e
                }

                val rateLimitWaitMs = parseRateLimitWaitMs(msg)
                if (rateLimitWaitMs != null) {
                    rateLimitRetries++
                    if (rateLimitRetries > MAX_RATE_LIMIT_RETRIES) {
                        XLog.w(TAG, "Rate limit retries exhausted ($MAX_RATE_LIMIT_RETRIES)")
                        throw e
                    }
                    val waitMs = (rateLimitWaitMs + 500).coerceAtMost(60_000L)
                    XLog.w(TAG, "Rate limited; waiting ${waitMs}ms then retrying (rl retry $rateLimitRetries)")
                    onRateLimitWait(iteration, waitMs)
                    var slept = 0L
                    while (slept < waitMs) {
                        if (isCancelled()) throw RuntimeException("Task cancelled")
                        try {
                            Thread.sleep(500)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt(); throw e
                        }
                        slept += 500
                    }
                    continue
                }

                attempt++
                if (attempt >= MAX_API_RETRIES) break
                val delay = (Math.pow(2.0, attempt.toDouble()) * 1000).toLong()
                XLog.w(TAG, "LLM API call failed (attempt $attempt/$MAX_API_RETRIES), retrying in ${delay}ms: $msg")
                try {
                    Thread.sleep(delay)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw lastException!!
    }

    private fun parseRateLimitWaitMs(msg: String): Long? {
        val lower = msg.lowercase()
        val isRateLimit = lower.contains("rate_limit") || lower.contains("rate limit") ||
            lower.contains("429") || lower.contains("tokens per minute") ||
            lower.contains("requests per minute") || lower.contains("tpm")
        if (!isRateLimit) return null

        val combo = Regex("""in\s+(?:(\d+)m)?\s*([\d.]+)?\s*s""").find(lower)
        if (combo != null) {
            val mins = combo.groupValues[1].toLongOrNull() ?: 0L
            val secs = combo.groupValues[2].toDoubleOrNull() ?: 0.0
            val total = mins * 60_000L + (secs * 1000).toLong()
            if (total > 0) return total
        }
        val secOnly = Regex("""in\s+([\d.]+)\s*seconds?""").find(lower)
        if (secOnly != null) {
            val secs = secOnly.groupValues[1].toDoubleOrNull() ?: 0.0
            if (secs > 0) return (secs * 1000).toLong()
        }
        return 15_000L
    }
}
