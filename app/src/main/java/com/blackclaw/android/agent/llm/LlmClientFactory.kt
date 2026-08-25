package com.blackclaw.android.agent.llm

import com.blackclaw.android.agent.AgentConfig
import com.blackclaw.android.agent.DefaultAgentService
import com.blackclaw.android.agent.LlmProvider
import com.blackclaw.android.agent.langchain.http.OkHttpClientBuilderAdapter
import java.time.Duration

object LlmClientFactory {

    fun create(config: AgentConfig): LlmClient {
        val automatic = ModelConfigRepository.isAutomaticActive()
        val client = createSingle(config, timeoutMs = if (automatic) AUTO_REQUEST_TIMEOUT_MS else null)
        // AUTO is a persisted user preference, not a special provider string in
        // AgentConfig. Wrap every runtime client while AUTO is enabled so both the
        // task agent and the chat/QuickAssist path get the same failover policy.
        return if (automatic) {
            AutoFailoverLlmClient(config, client)
        } else {
            client
        }
    }

    internal const val AUTO_REQUEST_TIMEOUT_MS = 45_000L
    internal const val BENCHMARK_REQUEST_TIMEOUT_MS = 20_000L

    internal fun createSingle(config: AgentConfig, timeoutMs: Long? = null): LlmClient {
        val httpClientBuilder = OkHttpClientBuilderAdapter().apply {
            timeoutMs?.let {
                val timeout = Duration.ofMillis(it)
                connectTimeout(timeout)
                readTimeout(timeout)
            }
            if (DefaultAgentService.FILE_LOGGING_ENABLED && DefaultAgentService.FILE_LOGGING_CACHE_DIR != null) {
                setFileLoggingEnabled(true, DefaultAgentService.FILE_LOGGING_CACHE_DIR)
            }
        }
        return when (config.provider) {
            LlmProvider.OPENAI -> OpenAiLlmClient(config, httpClientBuilder)
            LlmProvider.ANTHROPIC -> AnthropicLlmClient(config, httpClientBuilder)
            LlmProvider.LOCAL -> LocalLlmClient(config)
        }
    }
}
