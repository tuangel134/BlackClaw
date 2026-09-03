package com.blackclaw.android.agent

import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.R
import com.blackclaw.android.agent.langchain.LangChain4jToolBridge
import com.blackclaw.android.agent.llm.LlmClient
import com.blackclaw.android.agent.llm.LlmClientFactory
import com.blackclaw.android.agent.llm.LlmResponse
import com.blackclaw.android.agent.llm.ModelConfigRepository
import com.blackclaw.android.service.ClawAccessibilityService
import com.blackclaw.android.tool.ToolRegistry
import com.blackclaw.android.tool.impl.GetScreenInfoTool
import com.blackclaw.android.tool.ToolResult
import com.blackclaw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.agent.tool.ToolExecutionRequest
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DefaultAgentService : AgentService {

    companion object {
        private const val TAG = "AgentService"
        private val GSON = Gson()

        /**
         * Determines whether a user prompt looks like a phone-control task
         * (should receive a pre-warmed screen snapshot) vs a conversational question.
         *
         * Uses a two-pass approach:
         *  1. Fast regex on common action verbs / device-state nouns (covers ~95% of cases).
         *  2. Explicit exclusion of pure question patterns to avoid false positives.
         */
        fun isTaskLike(prompt: String): Boolean {
            // Delegate to the robust bilingual classifier (ES/EN, imperatives,
            // infinitives, polite/indirect requests, app names, action objects).
            return TaskClassifier.isTask(prompt)
        }

        /** Fast mode is intentionally opt-in: both classifiers must agree. */
        internal fun shouldUseFastChat(prompt: String): Boolean =
            !isTaskLike(prompt) &&
                com.blackclaw.android.conversation.ConversationRouter.decide(prompt).mode ==
                com.blackclaw.android.conversation.ConversationRouter.Mode.CONVERSE

        /**
         * Reusing a remote client preserves its HTTP connection pool and avoids
         * rebuilding LangChain/OpenAI clients before every voice turn. Prompt/memory
         * and iteration changes do not affect the transport client, so they can be
         * updated live through configRef. Local/AUTO sessions retain their explicit
         * lifecycle because they may own native engines or switch providers.
         */
        internal fun canReuseCloudClient(
            current: AgentConfig?,
            incoming: AgentConfig,
            currentAutomatic: Boolean,
            incomingAutomatic: Boolean,
        ): Boolean {
            if (current == null || currentAutomatic || incomingAutomatic) return false
            if (current.provider == LlmProvider.LOCAL || incoming.provider == LlmProvider.LOCAL) return false
            return current.provider == incoming.provider &&
                current.apiKey == incoming.apiKey &&
                current.baseUrl == incoming.baseUrl &&
                current.modelName == incoming.modelName &&
                current.temperature == incoming.temperature
        }

        /** Whether to write raw network request/response data to sandbox cache files for debugging */
        @JvmField
        var FILE_LOGGING_ENABLED = false
        @JvmField
        var FILE_LOGGING_CACHE_DIR: File? = null
    }

    // UNSAFE PUBLICATION FIX: initialize()/updateConfig() run on the caller's
    // thread (UI / settings), while the agent loop reads these same fields from
    // the single-thread executor via closures. Without a memory barrier the agent
    // thread can observe a half-published AgentConfig or a stale/closed LlmClient
    // — which shows up as "using the old model after switching provider" or a
    // crash inside a client that was just close()d. `lateinit var` cannot be
    // @Volatile in Kotlin, so the backing fields are nullable @Volatile and the
    // original non-null `config`/`llmClient` names are kept as accessors so no
    // call site (or the public API) has to change.
    @Volatile
    private var configRef: AgentConfig? = null

    @Volatile
    private var llmClientRef: LlmClient? = null

    private val config: AgentConfig
        get() = configRef ?: error("Agent not initialized: call initialize(config) first")

    private val llmClient: LlmClient
        get() = llmClientRef ?: error("Agent not initialized: call initialize(config) first")

    /** Narrowed per task by the agent thread; also read by it. Volatile for safe publication. */
    @Volatile
    private var toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification> = emptyList()

    /** Full set built once at init (caller thread), read by the agent thread. */
    @Volatile
    private var allToolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification> = emptyList()

    /** Replaced by initialize()/updateConfig() from another thread than the reader. */
    @Volatile
    private var executor: ExecutorService? = null

    @Volatile
    private var automaticClientMode: Boolean = false

    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /** Written by executeTask(), read by cancel() from a different thread. */
    @Volatile
    private var taskFuture: java.util.concurrent.Future<*>? = null

    private val retryHandler = AgentRetryHandler(
        config = { config },
        llmClient = { llmClient },
        isCancelled = { cancelled.get() },
        onRateLimitWait = { iteration, waitMs -> },
    )
    private val contextCompressor = AgentContextCompressor(provider = { config.provider })

    override fun initialize(config: AgentConfig) {
        this.configRef = config
        this.automaticClientMode = ModelConfigRepository.isAutomaticActive()
        this.llmClientRef = LlmClientFactory.create(config)
        this.allToolSpecs = LangChain4jToolBridge.buildToolSpecifications()
        this.toolSpecs = allToolSpecs
        this.executor = Executors.newSingleThreadExecutor()
        XLog.i(TAG, "Agent initialized: provider=${config.provider}, model=${config.modelName}, streaming=${config.streaming}, automatic=$automaticClientMode")
    }

    override fun updateConfig(config: AgentConfig) {
        val incomingAutomatic = ModelConfigRepository.isAutomaticActive()
        val pool = executor
        if (!running.get() &&
            llmClientRef != null &&
            pool != null && !pool.isShutdown && !pool.isTerminated &&
            canReuseCloudClient(configRef, config, automaticClientMode, incomingAutomatic)
        ) {
            // System prompt, memory and iteration settings are read from configRef on
            // every turn, so update them without throwing away the warm HTTP client.
            configRef = config
            automaticClientMode = incomingAutomatic
            XLog.d(TAG, "Agent config unchanged at transport level; reusing warm cloud client (${config.modelName})")
            return
        }

        if (running.get()) {
            cancel()
            XLog.w(TAG, "Task was running during config update, cancelled")
        }
        executor?.shutdownNow()
        // Close old LlmClient before reinitializing to free native/AUTO resources.
        llmClientRef?.let { old ->
            try {
                old.close()
                XLog.i(TAG, "Old LlmClient closed before config update")
            } catch (e: Exception) {
                XLog.w(TAG, "Old LlmClient close error during config update", e)
            }
        }
        llmClientRef = null
        initialize(config)
        XLog.i(TAG, "Agent config updated, new model: ${config.modelName}")
    }

    override fun executeTask(userPrompt: String, callback: AgentCallback) {
        if (running.get()) {
            callback.onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }

        running.set(true)
        cancelled.set(false)
        var terminalCallback: (() -> Unit)? = null

        val callbackProxy = object : AgentCallback {
            override fun onLoopStart(round: Int) = callback.onLoopStart(round)

            override fun onContent(round: Int, content: String) = callback.onContent(round, content)

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                callback.onToolCall(round, toolId, toolName, parameters)
            }

            override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
                callback.onToolResult(round, toolId, toolName, parameters, result)
            }

            override fun onTokenUpdate(status: TokenMonitor.Status) = callback.onTokenUpdate(status)

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int, modelName: String?) {
                // Cross-task memory: remember what was asked + a short outcome so the
                // next task can resolve back-references ("again", "same person").
                runCatching { TaskHistoryStore.record(userPrompt, finalAnswer) }
                terminalCallback = { callback.onComplete(round, finalAnswer, totalTokens, modelName) }
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                runCatching { TaskHistoryStore.record(userPrompt, "Error: ${error.message.orEmpty()}") }
                terminalCallback = { callback.onError(round, error, totalTokens) }
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                terminalCallback = { callback.onSystemDialogBlocked(round, totalTokens) }
            }
        }

        val agentTask = Runnable {
            try {
                runAgentLoop(userPrompt, callbackProxy)
            } catch (e: Exception) {
                if (terminalCallback == null) {
                    if (cancelled.get()) {
                        XLog.i(TAG, "Agent task cancelled (interrupted)")
                        terminalCallback = {
                            callback.onComplete(0, ClawApplication.instance.getString(R.string.agent_task_cancel), 0)
                        }
                    } else {
                        XLog.e(TAG, "Agent execution error", e)
                        terminalCallback = { callback.onError(0, e, 0) }
                    }
                }
            } finally {
                // Local/AUTO clients may own native engines or swap to a local fallback,
                // so release them after every task. Direct cloud clients intentionally
                // stay alive: their HTTP connection pool is reused by the next voice turn.
                val shouldCloseAfterTask = configRef?.provider == LlmProvider.LOCAL || automaticClientMode
                if (shouldCloseAfterTask) {
                    llmClientRef?.let { client ->
                        try {
                            client.close()
                            XLog.i(TAG, "LlmClient closed after task completion")
                        } catch (e: Exception) {
                            XLog.w(TAG, "LlmClient close error after task", e)
                        }
                    }
                    llmClientRef = null
                } else {
                    XLog.d(TAG, "Keeping cloud LlmClient warm for the next turn")
                }
                running.set(false)
                val terminal = terminalCallback
                terminalCallback = null
                terminal?.invoke()
            }
        }

        // STUCK-FOREVER FIX: running was set to true above, but the only code that
        // clears it lives in the submitted Runnable's finally block. If submit()
        // throws (RejectedExecutionException after shutdown/updateConfig races) or
        // the executor is null (initialize() never ran), the flag stayed true for
        // the rest of the process and every subsequent executeTask() bailed out
        // with "Agent is already running a task" forever. Clear it here and report
        // the failure through the normal error channel.
        val pool = executor
        if (pool == null) {
            running.set(false)
            val err = IllegalStateException("Agent executor is not initialized")
            XLog.e(TAG, "executeTask: no executor available", err)
            callback.onError(0, err, 0)
            return
        }
        taskFuture = try {
            pool.submit(agentTask)
        } catch (e: Exception) {
            running.set(false)
            XLog.e(TAG, "executeTask: failed to submit agent task", e)
            callback.onError(0, e, 0)
            return
        }
    }

    // ==================== Pre-flight Check ====================

    private fun preCheck(): String? {
        if (ClawAccessibilityService.getInstance() == null) {
            return ClawApplication.instance.getString(R.string.agent_accessibility_not_enabled)
        }
        return null
    }

    // ==================== Device Context ====================

    private fun buildDeviceContext(): String {
        val app = ClawApplication.instance
        val sb = StringBuilder()
        sb.append("\n\n## Device Info\n")
        sb.append("- Brand: ").append(Build.BRAND).append("\n")
        sb.append("- Model: ").append(Build.MODEL).append("\n")
        sb.append("- Android Version: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")

        try {
            val wm = app
                .getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            sb.append("- Screen Resolution: ").append(dm.widthPixels).append("x").append(dm.heightPixels).append("\n")
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to get display metrics", e)
        }

        sb.append("- Registered Tools: ").append(ToolRegistry.getAllTools().size).append("\n")

        val appName = try {
            val appInfo = app.packageManager.getApplicationInfo(app.packageName, 0)
            app.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) { "BlackClaw" }
        sb.append("\n## This App Info\n")
        sb.append("- App Name: ").append(appName).append("\n")
        sb.append("- Package Name: ").append(app.packageName).append("\n")
        sb.append("- When the user refers to 'this app' or 'the app', they mean the app above.\n")

        return sb.toString()
    }

    // ==================== Dead Loop Detection ====================
    // NOTE: Legacy RoundFingerprint loop detection removed.
    // StuckDetector (5-signal, 3-level) is the single source of truth.

    // ==================== Main Execution Loop ====================

    private fun runAgentLoop(userPrompt: String, callback: AgentCallback) {
        val parsedPrompt = TaskPromptEnvelope.parse(userPrompt)
        val rawUserRequest = parsedPrompt.currentRequest
        // Fast chat is deliberately conservative: both routers must agree this is
        // conversation. Ambiguous requests retain the full tool-capable agent.
        val looksLikeTask = !shouldUseFastChat(rawUserRequest)

        // Conversational turns do not need Accessibility at all. Avoid both the
        // permission lookup and a needless failure before a plain cloud response.
        if (looksLikeTask) {
            preCheck()?.let {
                callback.onError(0, RuntimeException(it), 0)
                return
            }
        }

        // ── Progressive tool disclosure (token optimization for cloud) ──
        // Sending all ~85 tool schemas costs ~13k tokens/request and blows past
        // Groq's rate limit. Instead we PRELOAD a relevant subset (full schema)
        // and show the FULL catalog as compact text in the prompt; the model can
        // request_tool(...) to load anything else.
        //
        // For LOCAL models we DON'T use the request_tool indirection (small
        // models handle it poorly), but we STILL relevance-filter the toolset:
        // sending all ~115 full schemas every turn (~15k tokens) blows past a
        // local Gemma's context window. Instead we preload a generous relevant
        // subset (CORE + keyword matches). The inline "Tool selection guide" in
        // AgentPrompts.LOCAL_TASK keeps the model aware of the broader toolset.
        val progressiveDisclosure = looksLikeTask && config.provider != LlmProvider.LOCAL
        val activeToolNames = LinkedHashSet<String>()
        var toolCatalogSection = ""
        if (!looksLikeTask) {
            // Fast chat: no tool schemas and no catalog. A fast model should see
            // only the conversation, not thousands of tokens of Android controls.
            toolSpecs = emptyList()
            XLog.i(TAG, "runAgentLoop: FAST_CHAT — zero tool schemas")
        } else if (progressiveDisclosure) {
            activeToolNames.addAll(ToolSelector.selectPreloadNames(rawUserRequest))
            toolSpecs = LangChain4jToolBridge.buildToolSpecifications(activeToolNames)
                .ifEmpty { allToolSpecs }
            toolCatalogSection = "\n\n" + ToolSelector.buildCatalog(activeToolNames)
            XLog.i(TAG, "runAgentLoop: preloaded ${toolSpecs.size}/${allToolSpecs.size} tools + catalog")
        } else {
            // LOCAL: relevance-filtered preload (no catalog, no request_tool).
            activeToolNames.addAll(ToolSelector.selectPreloadNames(rawUserRequest, maxTools = 20))
            // request_tool only works with progressive disclosure (cloud); drop it
            // for local so the model doesn't waste a turn calling a no-op.
            activeToolNames.remove("request_tool")
            toolSpecs = LangChain4jToolBridge.buildToolSpecifications(activeToolNames)
                .ifEmpty { allToolSpecs }
            XLog.i(TAG, "runAgentLoop: LOCAL preloaded ${toolSpecs.size}/${allToolSpecs.size} tools (relevance-filtered)")
        }

        // Build System Prompt. Pure conversation gets a deliberately tiny prompt;
        // actionable tasks keep the full Android-control policy and safeguards.
        val basePrompt = when {
            !looksLikeTask -> PromptUtils.applyGlobalPrompt(AgentPrompts.FAST_CHAT)
            config.provider == LlmProvider.LOCAL -> AgentPrompts.LOCAL_TASK
            else -> config.systemPrompt
        }

        val inAppSearchGuard = InAppSearchGuard.fromTask(rawUserRequest)
        val emailComposeGuard = EmailComposeGuard.fromTask(rawUserRequest)
        val directDeviceDataGuard = DirectDeviceDataGuard.fromTask(rawUserRequest)
        val taskCreationGuard = TaskCreationGuard.fromTask(rawUserRequest)

        // For local LLM, inject matching playbook into system prompt
        val playbookSection = if (looksLikeTask && config.provider == LlmProvider.LOCAL) {
            val matched = PlaybookManager.match(rawUserRequest)
            if (matched != null) {
                XLog.i(TAG, "Playbook matched: ${matched.id} requestChars=${rawUserRequest.length}")
                "\n\n## Playbook: ${matched.name}\nFollow these steps exactly:\n\n${matched.body}"
            } else ""
        } else ""

        val fullSystemPrompt = if (!looksLikeTask) {
            buildString {
                append(basePrompt)
                append(LanguageDetector.getLanguageInstruction(rawUserRequest))
            }
        } else buildString {
            append(basePrompt)
            append(playbookSection)
            append(AgentPrompts.IN_APP_EXECUTION)
            append(LanguageDetector.getLanguageInstruction(rawUserRequest))
            append(inAppSearchGuard.buildPromptSection())
            append(emailComposeGuard.buildPromptSection())
            append(directDeviceDataGuard.buildPromptSection())
            append(taskCreationGuard.buildPromptSection())
            append(buildDeviceContext())
            append(AmbientContext.asPromptSection())
            // Unified memory: profile + facts + routines + task history +
            // conversations, assembled under a single budget by priority.
            append(com.blackclaw.android.memory.MemoryHub.assembleForProvider(
                config.provider == LlmProvider.LOCAL))
            append(toolCatalogSection)
        }

        if (looksLikeTask) {
            // Each actionable task starts with a fresh tool cache so we never serve stale state.
            ToolRegistry.getInstance().clearCache()
            // Reset the passive demonstration buffer so "guarda lo último" maps to THIS task.
            runCatching { com.blackclaw.android.agent.DemonstrationRecorder.noteTaskStart() }
        }

        val messages = mutableListOf<ChatMessage>()
        messages.add(SystemMessage.from(fullSystemPrompt))

        val promptForModel = if (parsedPrompt.hasChatHistory || parsedPrompt.hasBackgroundState) {
            buildString {
                append("You are continuing an existing chatroom. Use the provided context when the current request refers to earlier messages or asks about current background activity.\n\n")
                parsedPrompt.backgroundState?.trim()?.takeIf { it.isNotEmpty() }?.let { state ->
                    append("Current background status:\n")
                    append(state)
                    append("\n\n")
                }
                parsedPrompt.chatHistory?.trim()?.takeIf { it.isNotEmpty() }?.let { history ->
                    append("Chatroom so far:\n")
                    append(history)
                    append("\n\n")
                }
                append("Current user request:\n")
                append(rawUserRequest)
            }
        } else {
            rawUserRequest
        }

        // Opt-2: Pre-warm — only attach screen info for task-like prompts.
        // Chat/questions should NOT see screen data (it confuses the LLM into using tools).
        val enrichedPrompt = if (looksLikeTask) {
            try {
                val screenTool = ToolRegistry.getInstance().getTool("get_screen_info")
                if (screenTool != null) {
                    val screenResult = screenTool.execute(emptyMap())
                    if (screenResult.isSuccess && !screenResult.data.isNullOrBlank()) {
                        val compactScreen = ContextCompactor.collapseRepetitiveLines(screenResult.data!!)
                        XLog.i(TAG, "runAgentLoop: pre-warm screen attached (${screenResult.data!!.length}→${compactScreen.length} chars)")
                        "$promptForModel\n\nCurrent screen:\n$compactScreen"
                    } else promptForModel
                } else promptForModel
            } catch (e: Exception) { promptForModel }
        } else {
            XLog.i(TAG, "runAgentLoop: chat-like prompt, skipping pre-warm screen")
            promptForModel
        }
        messages.add(UserMessage.from(enrichedPrompt))

        var iterations = 0
        var totalTokens = 0
        var actualModelName: String? = null  // Track the real model name from API response
        val iterationWindow = AgentIterationPolicy.window(config.maxIterations)
        val hardIterationLimit = AgentIterationPolicy.hardLimit(config.maxIterations)
        var successfulToolsSinceCheckpoint = 0
        var lastScreenHash = 0
        var previousScreenTexts: Set<String> = emptySet()
        val tokenMonitor = TokenMonitor(config.modelName)
        val stuckDetector = StuckDetector()
        val taskBudget = TaskBudget.fromSettings()
        var softLimitWarned = false
        var consecutiveNoToolCalls = 0
        val uiActionPatternDetector = UiActionPatternDetector()

        while (iterations < hardIterationLimit && !cancelled.get()) {
            iterations++
            callback.onLoopStart(iterations)

            // Compress history messages before sending to save tokens
            contextCompressor.compressHistoryForSend(messages)

            // LLM call (with retry)
            val llmResponse: LlmResponse
            try {
                llmResponse = retryHandler.chatWithRetry(messages, toolSpecs, callback, iterations)
            } catch (e: Exception) {
                XLog.e(TAG, "LLM API call failed after retries", e)
                callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_api_call_failed, e.message)), totalTokens)
                return
            }

            if (cancelled.get()) {
                callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
                return
            }

            // Capture actual model name from first API response
            if (actualModelName == null && !llmResponse.modelName.isNullOrEmpty()) {
                actualModelName = llmResponse.modelName
                XLog.d(TAG, "runAgentLoop: actual model from API = $actualModelName")
            }
            // Accumulate token usage
            llmResponse.tokenUsage?.totalTokenCount()?.let { totalTokens += it }
            tokenMonitor.record(
                step = iterations,
                inputTokens = llmResponse.tokenUsage?.inputTokenCount(),
                outputTokens = llmResponse.tokenUsage?.outputTokenCount(),
                totalTokenCount = llmResponse.tokenUsage?.totalTokenCount()
            )
            callback.onTokenUpdate(tokenMonitor.getStatus())

            // Budget check
            val tokenStatus = tokenMonitor.getStatus()
            when (taskBudget.check(tokenStatus.totalTokens, tokenStatus.estimatedCostUsd)) {
                TaskBudget.Status.HARD_LIMIT -> {
                    XLog.w(TAG, "Budget HARD LIMIT reached at step $iterations: ${tokenStatus.formattedTokens} (${tokenStatus.formattedCost})")
                    callback.onComplete(
                        iterations,
                        "Task stopped: budget limit reached (${tokenStatus.formattedTokens} tokens, ${tokenStatus.formattedCost}). " +
                        "Increase budget in Settings if needed.",
                        totalTokens,
                        actualModelName
                    )
                    return
                }
                TaskBudget.Status.SOFT_LIMIT -> {
                    if (!softLimitWarned) {
                        softLimitWarned = true
                        XLog.i(TAG, "Budget SOFT LIMIT at step $iterations: ${tokenStatus.formattedTokens}")
                        messages.add(UserMessage.from(
                            "[System Notice] You are using ${tokenStatus.formattedTokens} tokens (${tokenStatus.formattedCost}), " +
                            "approaching the budget limit. Finish the task efficiently. " +
                            "If you cannot complete it soon, call finish with a partial summary."
                        ))
                    }
                }
                TaskBudget.Status.OK -> { /* continue normally */ }
            }

            // Keep response diagnostics structural: model output may contain private
            // screen/message context and must not be copied into logcat.
            XLog.i(TAG, "runAgentLoop iter=$iterations responseChars=${llmResponse.text?.length ?: 0}")
            XLog.i(TAG, "runAgentLoop iter=$iterations hasToolCalls=${llmResponse.hasToolExecutionRequests()} toolCallCount=${llmResponse.toolExecutionRequests?.size ?: 0}")

            // Add AI message to history (must construct AiMessage)
            val aiMessage = if (llmResponse.hasToolExecutionRequests()) {
                if (llmResponse.text.isNullOrEmpty()) {
                    AiMessage.from(llmResponse.toolExecutionRequests)
                } else {
                    AiMessage.from(llmResponse.text, llmResponse.toolExecutionRequests)
                }
            } else {
                AiMessage.from(llmResponse.text ?: "")
            }
            messages.add(aiMessage)

            // Push thinking content in non-streaming mode
            if (!config.streaming && !llmResponse.text.isNullOrEmpty()) {
                val suppressHallucinatedCompletion =
                    !llmResponse.hasToolExecutionRequests() &&
                        (inAppSearchGuard.shouldBlockTextOnlyCompletion() ||
                            emailComposeGuard.shouldBlockTextOnlyCompletion() ||
                            taskCreationGuard.shouldBlockTextOnlyCompletion(llmResponse.text))
                if (!suppressHallucinatedCompletion) {
                    callback.onContent(iterations, llmResponse.text)
                }
            }

            // No tool calls in this response — LLM chose to respond with text only.
            // Respect that. If there's text, it's the answer. Done.
            if (!llmResponse.hasToolExecutionRequests()) {
                val responseText = llmResponse.text ?: ""
                if (responseText.isNotEmpty()) {
                    if (inAppSearchGuard.shouldBlockTextOnlyCompletion()) {
                        val correction = inAppSearchGuard.buildCompletionCorrection()
                        XLog.i(TAG, "InAppSearchGuard blocked text-only completion (promptChars=${userPrompt.length})")
                        messages.add(UserMessage.from(correction))
                        continue
                    }
                    if (directDeviceDataGuard.shouldBlockTextOnlyCompletion()) {
                        val correction = directDeviceDataGuard.buildCompletionCorrection()
                        XLog.i(TAG, "DirectDeviceDataGuard blocked text-only completion (promptChars=${userPrompt.length})")
                        messages.add(UserMessage.from(correction))
                        continue
                    }
                    if (emailComposeGuard.shouldBlockTextOnlyCompletion()) {
                        val correction = emailComposeGuard.buildCompletionCorrection()
                        XLog.i(TAG, "EmailComposeGuard blocked text-only completion (promptChars=${userPrompt.length})")
                        messages.add(UserMessage.from(correction))
                        continue
                    }
                    if (taskCreationGuard.shouldBlockTextOnlyCompletion(responseText)) {
                        val correction = taskCreationGuard.maybeBlockFinish()
                            ?: "[System Guard] Create the requested task with a native BlackClaw tool before answering."
                        XLog.i(TAG, "TaskCreationGuard blocked text-only completion (promptChars=${userPrompt.length})")
                        messages.add(UserMessage.from(correction))
                        continue
                    }
                    XLog.i(TAG, "runAgentLoop: text-only response, completing")
                    callback.onComplete(iterations, responseText, totalTokens, actualModelName)
                    return
                }
                // Empty response with no tools — something went wrong, finish.
                // MUST return, like every other completion path. The old `continue`
                // reported completion and then kept hammering the LLM until
                // maxIterations: wasted tokens, and because callbackProxy.onComplete
                // only *stashes* the terminal callback, each later onComplete
                // overwrote it — so the user got the LAST answer (usually the
                // max-iterations error) instead of this one.
                XLog.w(TAG, "runAgentLoop: empty response with no tools, finishing")
                callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens, actualModelName)
                return
            }

            // Reset counter when LLM does use tools
            consecutiveNoToolCalls = 0

            // Execute tool calls
            for (toolRequest in llmResponse.toolExecutionRequests) {
                if (cancelled.get()) {
                    callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
                    return
                }

                val toolName = toolRequest.name() ?: ""
                val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
                val toolArgs = toolRequest.arguments() ?: "{}"

                // Parse parameters
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                var params: Map<String, Any>? = try {
                    GSON.fromJson(toolArgs, mapType)
                } catch (e: Exception) {
                    XLog.w(TAG, "Failed to parse tool args for $toolName (argChars=${toolArgs.length})")
                    HashMap()
                }
                if (params == null) params = HashMap()

                val blockedFinish = if (toolName == "finish") {
                    val screenInfo = try {
                        ToolRegistry.getInstance()
                            .getTool("get_screen_info")
                            ?.execute(emptyMap())
                            ?.takeIf { it.isSuccess }
                            ?.data
                    } catch (_: Exception) {
                        null
                    }
                    directDeviceDataGuard.maybeBlockFinish()
                        ?: inAppSearchGuard.maybeBlockFinish(screenInfo)
                        ?: emailComposeGuard.maybeBlockFinish(screenInfo)
                        ?: taskCreationGuard.maybeBlockFinish()
                } else null
                if (blockedFinish != null) {
                    val blockedResult = ToolResult.error(blockedFinish)
                    XLog.i(TAG, "Task guard blocked premature finish (promptChars=${userPrompt.length})")
                    callback.onToolCall(iterations, toolName, displayName, toolArgs)
                    callback.onToolResult(iterations, toolName, displayName, params.toString(), blockedResult)
                    messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(blockedResult)))
                    messages.add(UserMessage.from(blockedFinish))
                    continue
                }

                callback.onToolCall(iterations, toolName, displayName, toolArgs)
                directDeviceDataGuard.recordToolAttempt(toolName)
                emailComposeGuard.recordToolAttempt(toolName)
                taskCreationGuard.recordToolAttempt(toolName)

                // Soft destructive-action guard. We never silently block; we surface
                // an error result so the LLM can self-correct or request confirmation
                // through user-visible text instead of executing the dangerous call.
                val risk = ActionGuard.assess(toolName, params)
                if (risk == ActionGuard.Risk.DESTRUCTIVE) {
                    val reason = ActionGuard.describe(risk, toolName)
                    XLog.w(TAG, "ActionGuard blocked $toolName: $reason")
                    val blocked = ToolResult.error(
                        "Refused: this action looks destructive ($reason). " +
                        "Confirm with the user in plain text first, then retry only if they agree."
                    )
                    callback.onToolResult(iterations, toolName, displayName, params.toString(), blocked)
                    messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(blocked)))
                    continue
                }

                val result = ToolRegistry.getInstance().executeTool(toolName, params)
                runCatching { com.blackclaw.android.utils.ActivityTracker.recordToolUsed(toolName) }
                // Learning by demonstration: capture replayable steps when recording.
                runCatching { com.blackclaw.android.agent.DemonstrationRecorder.record(toolName, params, result.isSuccess) }
                if (result.isSuccess) successfulToolsSinceCheckpoint++
                val repeatedPattern = if (result.isSuccess) {
                    uiActionPatternDetector.record(toolName, params)
                } else null
                val paramsString = if (params.isEmpty()) "" else params.toString()
                callback.onToolResult(iterations, toolName, displayName, paramsString, result)
                if (result.isSuccess) {
                    inAppSearchGuard.recordSuccessfulTool(toolName, params)
                    emailComposeGuard.recordSuccessfulTool(toolName)
                }
                taskCreationGuard.recordToolResult(toolName, result.isSuccess)

                // Progressive disclosure: when the model loads tools via
                // request_tool, add their full schemas to the active set so the
                // next chatWithRetry call exposes them.
                if (toolName == "request_tool" && result.isSuccess && progressiveDisclosure) {
                    val requested = (params["names"]?.toString() ?: "")
                        .split(",", " ", ";").map { it.trim() }.filter { it.isNotEmpty() }
                    val newlyAdded = requested.filter {
                        ToolRegistry.getInstance().getTool(it) != null && activeToolNames.add(it)
                    }
                    if (newlyAdded.isNotEmpty()) {
                        toolSpecs = LangChain4jToolBridge.buildToolSpecifications(activeToolNames)
                        XLog.i(TAG, "request_tool unlocked ${newlyAdded.joinToString()} → ${toolSpecs.size} active tools")
                    }
                }

                // System dialog blocking detected → notify user and stop task
                if (!result.isSuccess && result.error == GetScreenInfoTool.SYSTEM_DIALOG_BLOCKED) {
                    XLog.w(TAG, "System dialog blocked, notifying user and stopping task")
                    callback.onSystemDialogBlocked(iterations, totalTokens)
                    return
                }

                // finish tool → task complete
                if (toolName == "finish" && result.isSuccess) {
                    val finishData = result.data
                    callback.onComplete(iterations, finishData ?: ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens, actualModelName)
                    return
                }

                // Opt-3: Auto-attach fresh screen state after action tools.
                // LLM sees updated UI in the same tool result → can decide next step
                // immediately without spending an extra 5 s inference round on get_screen_info.
                val combinedResultData: String = if (AgentExecutionPolicy.isActionTool(toolName)) {
                    try {
                        val screenTool = ToolRegistry.getInstance().getTool("get_screen_info")
                        Thread.sleep(AgentExecutionPolicy.settleTimeForTool(toolName))
                        var screenAfter = screenTool?.execute(emptyMap())
                        if (screenAfter != null && screenAfter.isSuccess && !screenAfter.data.isNullOrBlank()) {
                            val hash1 = screenAfter.data!!.hashCode()
                            Thread.sleep(200)
                            val recheck = screenTool?.execute(emptyMap())
                            if (recheck != null && recheck.isSuccess && !recheck.data.isNullOrBlank()
                                && recheck.data.hashCode() != hash1) {
                                Thread.sleep(300)
                                val stable = screenTool?.execute(emptyMap())
                                if (stable != null && stable.isSuccess && !stable.data.isNullOrBlank()) {
                                    screenAfter = stable
                                }
                            }
                        }
                        if (screenAfter != null && screenAfter.isSuccess && !screenAfter.data.isNullOrBlank()) {
                            // Update lastScreenHash for loop detection
                            lastScreenHash = screenAfter.data!!.hashCode()
                            XLog.i(TAG, "Opt3: auto-attached screen after $toolName (${screenAfter.data!!.length} chars)")
                            // Screen diff: extract text lines and compare with previous
                            val currentTexts = screenAfter.data!!.lines()
                                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                            val added = currentTexts - previousScreenTexts
                            val removed = previousScreenTexts - currentTexts
                            previousScreenTexts = currentTexts
                            val diffSection = buildString {
                                if (added.isNotEmpty()) append("\nNew on screen: ${added.take(10).joinToString(", ")}")
                                if (removed.isNotEmpty()) append("\nGone from screen: ${removed.take(10).joinToString(", ")}")
                            }
                            val baseData = result.data ?: ""
                            val enrichedData = "$baseData\n\nScreen after action:\n${screenAfter.data}$diffSection"
                            val enriched = if (result.isSuccess) ToolResult.success(enrichedData)
                                           else ToolResult.error(result.error ?: "")
                            GSON.toJson(enriched)
                        } else {
                            XLog.w(TAG, "Opt3: get_screen_info failed after $toolName: ${screenAfter?.error}")
                            GSON.toJson(result)
                        }
                    } catch (e: Exception) {
                        XLog.w(TAG, "Opt3: exception fetching screen after $toolName", e)
                        GSON.toJson(result)
                    }
                } else {
                    // Record fingerprint for dead-loop detection (non-action tools path)
                    if (toolName == "get_screen_info" && result.isSuccess && result.data != null) {
                        lastScreenHash = result.data.hashCode()
                    }
                    GSON.toJson(result)
                }

                // Add tool result to messages (compacted to save tokens —
                // minifies JSON envelope + collapses repetitive screen rows).
                val compacted = ContextCompactor.compactToolResult(toolName, combinedResultData)
                messages.add(ToolExecutionResultMessage.from(toolRequest, compacted))
                repeatedPattern?.let { match ->
                    messages.add(UserMessage.from(match.buildHint()))
                    XLog.i(TAG, "Detected repeated UI pattern (${match.steps.size} steps); suggested execute_plan acceleration")
                }
                XLog.d(TAG, "displayName:$displayName toolName:$toolName")
            }

            // Stuck detection (5-signal, 3-level recovery)
            val lastAction = llmResponse.toolExecutionRequests?.firstOrNull()?.let {
                "${it.name()}:${it.arguments()?.take(50)}"
            } ?: ""
            val screenDiffCount = (previousScreenTexts as? Set<*>)?.size ?: 0
            val toolError = llmResponse.toolExecutionRequests?.firstOrNull()?.let { req ->
                val result = ToolRegistry.getInstance().getTool(req.name() ?: "")
                null // error tracked per-tool above; simplified here
            }
            val detection = stuckDetector.record(lastAction, lastScreenHash, screenDiffCount, null)
            if (detection != null) {
                when (detection.level) {
                    StuckDetector.RecoveryLevel.AUTO_KILL -> {
                        XLog.w(TAG, "StuckDetector AUTO_KILL at iteration $iterations: ${detection.signal.description}")
                        val status = tokenMonitor.getStatus()
                        callback.onComplete(
                            iterations,
                            "Task stopped: agent was stuck (${detection.signal.description}). " +
                            "Used ${status.formattedTokens} tokens (${status.formattedCost}).",
                            totalTokens,
                            actualModelName
                        )
                        return
                    }
                    else -> {
                        XLog.w(TAG, "StuckDetector ${detection.level} at iteration $iterations: ${detection.signal.description}")
                        messages.add(UserMessage.from(detection.recoveryHint))
                    }
                }
            }

            // Long repetitive tasks (large forms, contact lists, imports) commonly
            // need more than the first configured window. Continue automatically
            // only when the previous window made real progress; if nothing worked,
            // stop instead of burning tokens in a loop. The checkpoint is explicit
            // in the model history so the next window preserves the current state.
            if (AgentIterationPolicy.isCheckpoint(iterations, iterationWindow)) {
                if (successfulToolsSinceCheckpoint > 0) {
                    messages.add(UserMessage.from(
                        "[System checkpoint] The task is still in progress after $iterationWindow steps. " +
                            "Continue from the current screen and preserve everything already completed. " +
                            "Do not restart completed items; finish the remaining items efficiently."
                    ))
                    XLog.i(TAG, "Iteration checkpoint at $iterations; continuing with $successfulToolsSinceCheckpoint successful tool calls")
                    successfulToolsSinceCheckpoint = 0
                } else {
                    XLog.w(TAG, "Iteration checkpoint at $iterations had no successful tool calls; stopping safely")
                    callback.onError(
                        iterations,
                        RuntimeException(ClawApplication.instance.getString(R.string.agent_max_iterations, hardIterationLimit)),
                        totalTokens
                    )
                    return
                }
            }
            XLog.d(TAG, "Round:$iterations total=$totalTokens thisRound=${llmResponse.tokenUsage?.totalTokenCount()}")
        }

        if (cancelled.get()) {
            callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
        } else {
            callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_max_iterations, hardIterationLimit)), totalTokens)
        }
    }

    override fun cancel() {
        cancelled.set(true)
        // Read the volatile ref once: config may be swapped by updateConfig() on
        // another thread, and cancel() can legitimately run before initialize().
        if (configRef?.provider == LlmProvider.LOCAL) {
            // LiteRT native sendMessage is not interrupt-safe; let the current round yield
            // naturally, then surface Task cancelled after the client closes cleanly.
            XLog.i(TAG, "cancel: LOCAL task marked cancelled; waiting for current LiteRT round to finish safely")
            return
        }
        // Cloud/network-backed tasks can be aborted safely via thread interruption.
        taskFuture?.cancel(true)
        XLog.i(TAG, "cancel: flag set + thread interrupted")
    }

    override fun shutdown() {
        cancel()
        executor?.shutdownNow()
        llmClientRef?.let { client ->
            try {
                client.close()
                XLog.i(TAG, "LlmClient closed on shutdown")
            } catch (e: Exception) {
                XLog.w(TAG, "LlmClient close error on shutdown", e)
            }
        }
    }

    override fun isRunning(): Boolean = running.get()
}
