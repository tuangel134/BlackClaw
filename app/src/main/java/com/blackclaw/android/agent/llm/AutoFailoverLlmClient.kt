package com.blackclaw.android.agent.llm

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.agent.AgentConfig
import com.blackclaw.android.utils.XLog
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage

/**
 * LLM client used by AUTO mode. It starts with the fastest known candidate and
 * transparently moves through the ranked list when a request fails. Streaming
 * tokens are buffered per attempt, so a dead provider never leaves half an answer
 * from model A mixed with the answer from model B in the chat bubble.
 */
class AutoFailoverLlmClient(
    private val template: AgentConfig,
    initialClient: LlmClient,
) : LlmClient {

    private companion object {
        const val TAG = "AutoFailoverLlmClient"
    }

    private val context = ClawApplication.instance
    private val rankedCandidates = AutomaticModelManager.ranked(
        context = context,
        preferredKey = AutomaticModelManager.candidateKeyFor(template),
    )
    private val candidates = buildList {
        addAll(rankedCandidates)
        val configured = AutomaticModelManager.candidateForConfig(template)
        val offlineLocalRoute = !AutomaticModelResolver.isInternetValidated(context) &&
            any { it.kind == AutomaticModelManager.Kind.LOCAL }
        if (configured != null && none { it.key == configured.key } &&
            !(offlineLocalRoute && configured.kind == AutomaticModelManager.Kind.CLOUD)) {
            add(configured)
        }
    }
    private var index = 0
    private var activeClient: LlmClient = initialClient
    private var activeCandidate: AutomaticModelManager.Candidate? = candidates.firstOrNull()
    private var initialClientClosed = false
    // Keep a separate reference so close() also releases the factory-created
    // client when AUTO switched to a measured candidate during construction.
    private val initialClientReference: LlmClient = initialClient

    init {
        // The factory has already created the configured client. Replace it only
        // when a measured candidate is known to be a better starting point.
        val preferred = AutomaticModelManager.candidateKeyFor(template)
        val fastest = candidates.firstOrNull()
        if (fastest != null && fastest.key != preferred) {
            runCatching { initialClient.close() }
            initialClientClosed = true
            activeClient = LlmClientFactory.createSingle(
                fastest.toAgentConfig(template),
                timeoutMs = LlmClientFactory.AUTO_REQUEST_TIMEOUT_MS,
            )
            XLog.i(TAG, "AUTO selected ${fastest.displayName} (${fastest.key})")
        } else if (fastest == null) {
            activeCandidate = null
        }
    }

    override fun chat(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
    ): LlmResponse = withFailover { client, _ ->
        client.chat(messages, toolSpecs).also(::requireUsableResponse)
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener,
    ): LlmResponse = withFailover { client, _ ->
        val buffered = StringBuilder()
        val response = client.chatStreaming(messages, toolSpecs, object : StreamingListener {
            override fun onPartialText(token: String) {
                if (token.isNotEmpty()) buffered.append(token)
            }

            override fun onComplete(response: LlmResponse) = Unit

            override fun onError(error: Throwable) = Unit
        })
        requireUsableResponse(response)
        if (buffered.isNotEmpty()) listener.onPartialText(buffered.toString())
        listener.onComplete(response)
        response
    }

    private fun requireUsableResponse(response: LlmResponse) {
        if (response.text.isNullOrBlank() && !response.hasToolExecutionRequests()) {
            throw IllegalStateException("El modelo no devolvió una respuesta")
        }
    }

    override fun close() {
        runCatching { activeClient.close() }
        if (!initialClientClosed && activeClient !== initialClientReference) {
            runCatching { initialClientReference.close() }
        }
    }

    private fun <T> withFailover(operation: (LlmClient, AutomaticModelManager.Candidate?) -> T): T {
        var lastError: Exception? = null
        while (true) {
            val candidate = activeCandidate
            val started = System.nanoTime()
            try {
                val result = operation(activeClient, candidate)
                candidate?.let {
                    AutomaticModelManager.recordRuntimeSuccess(it, elapsedMs(started))
                }
                return result
            } catch (error: Exception) {
                lastError = error
                candidate?.let {
                    AutomaticModelManager.recordRuntimeFailure(it, elapsedMs(started), error)
                }
                XLog.w(TAG, "${candidate?.displayName ?: "configured model"} failed; trying fallback", error)
                if (!moveToNext()) break
            }
        }
        throw IllegalStateException(
            "No se pudo conectar con ningún modelo configurado",
            lastError,
        )
    }

    private fun moveToNext(): Boolean {
        var nextIndex = index + 1
        while (nextIndex < candidates.size) {
            val candidate = candidates[nextIndex]
            val nextClient = try {
                LlmClientFactory.createSingle(
                    candidate.toAgentConfig(template),
                    timeoutMs = LlmClientFactory.AUTO_REQUEST_TIMEOUT_MS,
                )
            } catch (error: Exception) {
                AutomaticModelManager.recordRuntimeFailure(candidate, 1L, error)
                XLog.w(TAG, "Could not create AUTO fallback ${candidate.displayName}", error)
                nextIndex++
                continue
            }
            index = nextIndex
            runCatching { activeClient.close() }
            activeClient = nextClient
            activeCandidate = candidate
            XLog.i(TAG, "AUTO fallback -> ${candidate.displayName}")
            return true
        }
        return false
    }

    private fun elapsedMs(started: Long): Long =
        ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
}
