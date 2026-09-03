package com.blackclaw.android

import com.blackclaw.android.agent.AgentCallback
import com.blackclaw.android.agent.AgentConfig
import com.blackclaw.android.agent.AgentService
import com.blackclaw.android.agent.AgentServiceFactory
import com.blackclaw.android.agent.PipelineRouter
import com.blackclaw.android.agent.skill.SkillExecutor
import com.blackclaw.android.agent.skill.SkillRegistry
import com.blackclaw.android.channel.Channel
import com.blackclaw.android.channel.ChannelManager
import com.blackclaw.android.floating.FloatingCircleManager
import com.blackclaw.android.service.ClawAccessibilityService
import com.blackclaw.android.service.ForegroundService
import com.blackclaw.android.tool.ToolResult
import com.blackclaw.android.utils.XLog

/**
 * Task orchestrator — manages agent lifecycle, task locking, pipeline routing, and execution.
 */
class TaskOrchestrator(
    private val agentConfigProvider: () -> AgentConfig,
    private val onTaskFinished: () -> Unit
) {
    /**
     * Typed event callback for in-app Task mode UI.
     * Called on the agent executor thread — UI must post to main thread.
     */
    var taskEventCallback: ((TaskEvent) -> Unit)? = null

    companion object {
        private const val TAG = "TaskOrchestrator"
    }

    private lateinit var agentService: AgentService
    private val pipelineRouter = PipelineRouter(ClawApplication.instance)
    private val skillExecutor = SkillExecutor()
    val taskSessionStore = TaskSessionStore()

    val inProgressTaskMessageId: String
        get() = taskSessionStore.snapshot().messageId
    val inProgressTaskChannel: Channel?
        get() = taskSessionStore.snapshot().channel

    // ==================== Agent Lifecycle ====================

    fun initAgent() {
        agentService = AgentServiceFactory.create()
        try {
            agentService.initialize(agentConfigProvider())
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to initialize AgentService", e)
        }
    }

    fun updateAgentConfig(): Boolean {
        return try {
            val config = agentConfigProvider()
            if (::agentService.isInitialized) {
                agentService.updateConfig(config)
                XLog.d(TAG, "Agent config updated: model=${config.modelName}, temp=${config.temperature}")
                true
            } else {
                XLog.w(TAG, "AgentService not initialized, initializing with new config")
                agentService = AgentServiceFactory.create()
                agentService.initialize(config)
                true
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to update agent config", e)
            false
        }
    }

    // ==================== Task Lock ====================

    fun tryAcquireTask(messageId: String, channel: Channel, taskText: String = "",
                       autoReturnToChat: Boolean = (channel == Channel.LOCAL),
                       originOverride: com.blackclaw.android.tool.guard.ToolRiskPolicy.Origin? = null): Boolean {
        val acquired = taskSessionStore.tryAcquire(
            messageId = messageId,
            channel = channel,
            taskText = taskText,
            autoReturnToChat = autoReturnToChat,
        )
        // Screen must stay awake only WHILE a task runs. Acquiring here (the single
        // gate every entry point goes through) and releasing in releaseTask() gives
        // us a strict pair; previously the lock was taken once at app start and
        // never released, pinning the display on for 10 min on every cold boot.
        if (acquired) {
            acquireTaskWakeLock()
            // Publish provenance so the tool layer can refuse arbitrary-command tools
            // for anything that did not originate on this device. Same single gate, so
            // no entry point can start a task without setting it.
            com.blackclaw.android.tool.guard.ToolExecutionContext.setOrigin(originOverride ?: originOf(channel))
        }
        return acquired
    }

    /**
     * Map a channel onto a trust origin.
     *
     * LOCAL covers in-app chat, voice and the car surface — the user is present.
     * Everything arriving over a messaging channel is untrusted even when the sender
     * is the verified owner, because the agent's own inputs (screen text,
     * notifications, fetched pages) can steer the model.
     */
    private fun originOf(channel: Channel): com.blackclaw.android.tool.guard.ToolRiskPolicy.Origin =
        when (channel) {
            Channel.LOCAL -> com.blackclaw.android.tool.guard.ToolRiskPolicy.Origin.LOCAL
            Channel.TELEGRAM, Channel.DISCORD, Channel.WECHAT ->
                com.blackclaw.android.tool.guard.ToolRiskPolicy.Origin.REMOTE
        }

    private fun releaseTask(): TaskSessionState {
        // Release before returning so every terminal path (complete, error, cancel,
        // blocked, Tier-1 shortcut) gives the wake lock back exactly once.
        releaseTaskWakeLock()
        // Fail closed: with no task running, an unattributed tool call is treated as
        // strictly as a remote one.
        com.blackclaw.android.tool.guard.ToolExecutionContext.reset()
        return taskSessionStore.release()
    }

    private fun acquireTaskWakeLock() {
        runCatching { ClawApplication.appViewModelInstance.acquireScreenWakeLock() }
            .onFailure { XLog.w(TAG, "Could not acquire screen wake lock for task", it) }
    }

    private fun releaseTaskWakeLock() {
        runCatching { ClawApplication.appViewModelInstance.releaseScreenWakeLock() }
            .onFailure { XLog.w(TAG, "Could not release screen wake lock after task", it) }
    }

    fun isTaskRunning(): Boolean = taskSessionStore.isTaskRunning()

    // ==================== Task Execution ====================

    fun cancelCurrentTask() {
        if (!taskSessionStore.markStopping()) return
        if (::agentService.isInitialized) {
            agentService.cancel()
        }
        if (ForegroundService.isRunning()) {
            ForegroundService.updateTaskStatus(ClawApplication.instance, "Stopping task...")
        }
        XLog.d(TAG, "Current task cancellation requested")
    }

    /**
     * Start a new task. Routes through the 3-tier pipeline.
     */
    fun startNewTask(
        channel: Channel,
        task: String,
        messageID: String,
        agentPromptOverride: String? = null,
        isFallback: Boolean = false,
        autoReturnToChat: Boolean = (channel == Channel.LOCAL),
        originOverride: com.blackclaw.android.tool.guard.ToolRiskPolicy.Origin? = null,
    ) {
        // Acquire task lock if not already held
        if (!isTaskRunning()) {
            if (!tryAcquireTask(messageID, channel, task, autoReturnToChat, originOverride)) {
                XLog.w(TAG, "Failed to acquire task lock (chars=${task.length})")
                taskEventCallback?.invoke(TaskEvent.Failed("Another task is running"))
                return
            }
        } else {
            val current = taskSessionStore.snapshot()
            if (current.messageId == messageID && current.channel == channel) {
                taskSessionStore.updateTaskText(task)
            } else {
                XLog.w(
                    TAG,
                    "Rejecting new task while another task is still active: current=${current.messageId}/${current.channel} new=$messageID/$channel"
                )
                taskEventCallback?.invoke(TaskEvent.Failed("Another task is still running. Stop it first."))
                ChannelManager.sendMessage(channel, "Another task is still running. Stop it first.", messageID)
                return
            }
        }

        ForegroundService.updateTaskStatus(ClawApplication.instance, "Preparing task...")

        // Tier 1: Deterministic routing
        val route = pipelineRouter.route(task)
        var confirmationRequired = false
        when (route) {
            is PipelineRouter.Route.DirectIntent -> {
                XLog.i(TAG, "Pipeline Tier 1: DirectIntent — ${route.description}")
                // Signal that we're launching/handing off to another app so voice
                // UIs (assist panel) close instead of listening over that app.
                taskEventCallback?.invoke(TaskEvent.ToolAction("open_app"))
                pipelineRouter.executeIntent(route.intent)
                XLog.i(TAG, "onComplete: rounds=0, totalTokens=0, model=direct, answer=${route.description}")
                taskEventCallback?.invoke(TaskEvent.Completed(route.description))
                ChannelManager.sendMessage(channel, "✓ ${route.description}", messageID)
                releaseTask()
                ForegroundService.resetToIdle(ClawApplication.instance)
                FloatingCircleManager.setSuccessState()
                onTaskFinished()
                return
            }
            is PipelineRouter.Route.DirectTool -> {
                XLog.i(TAG, "Pipeline Tier 1: DirectTool — ${route.toolName}")
                // Signal the tool being run (so the assist panel treats app-launch
                // tools as a hand-off and closes rather than re-listening).
                taskEventCallback?.invoke(TaskEvent.ToolAction(route.toolName))
                Thread({
                    var success = false
                    val answer = try {
                        val toolResult = pipelineRouter.executeTool(route.toolName, route.params)
                        if (!toolResult.isSuccess) {
                            val error = toolResult.error ?: "Unknown error"
                            XLog.w(TAG, "Tier 1 tool failed: ${route.toolName} errorChars=${error.length}")
                            taskEventCallback?.invoke(TaskEvent.Completed("Failed: ${route.description}"))
                            ChannelManager.sendMessage(channel, "✗ ${route.description}: $error", messageID)
                            "Failed: ${route.description}: $error"
                        } else {
                            success = true
                            taskEventCallback?.invoke(TaskEvent.Completed(route.description))
                            ChannelManager.sendMessage(channel, "✓ ${route.description}", messageID)
                            route.description
                        }
                    } catch (e: Exception) {
                        val message = e.message ?: "Unknown error"
                        XLog.e(TAG, "Tier 1 tool crashed: ${route.toolName}", e)
                        taskEventCallback?.invoke(TaskEvent.Failed(message))
                        ChannelManager.sendMessage(channel, "✗ ${route.description}: $message", messageID)
                        "Failed: ${route.description}: $message"
                    } finally {
                        releaseTask()
                        ForegroundService.resetToIdle(ClawApplication.instance)
                        if (success) {
                            FloatingCircleManager.setSuccessState()
                        } else {
                            FloatingCircleManager.setErrorState()
                        }
                        onTaskFinished()
                    }
                    XLog.i(TAG, "onComplete: rounds=0, totalTokens=0, model=direct, answerChars=${answer.length}")
                }, "direct-tool-${route.toolName}").start()
                return
            }
            is PipelineRouter.Route.Skill -> {
                if (isFallback) {
                    XLog.i(TAG, "Skipping skill route on fallback, going to agent loop: ${route.skillId}")
                } else {
                    XLog.i(TAG, "Pipeline Tier 2: Skill — ${route.skillId}")
                    val skill = SkillRegistry.findById(route.skillId)
                    if (skill != null) {
                        FloatingCircleManager.ensureShowing()
                        FloatingCircleManager.showTaskNotify(task, channel)
                        Thread({
                            val skillResult = skillExecutor.execute(skill, route.params) { step, total, desc ->
                                taskEventCallback?.invoke(TaskEvent.Progress(step, "Step $step/$total: $desc"))
                                ForegroundService.updateTaskStatus(ClawApplication.instance, desc)
                            }
                            if (skillResult.success) {
                                ChannelManager.sendMessage(channel, skillResult.message, messageID)
                                taskEventCallback?.invoke(TaskEvent.Completed(skillResult.message))
                                releaseTask()
                                FloatingCircleManager.setSuccessState()
                                ForegroundService.resetToIdle(ClawApplication.instance)
                                onTaskFinished()
                            } else {
                                val fallbackGoal = skill.fallbackGoal
                                    .let { g -> route.params.entries.fold(g) { acc, (k, v) -> acc.replace("{$k}", v) } }
                                XLog.i(TAG, "Skill ${skill.id} failed; falling back to agent loop (goalChars=${fallbackGoal.length})")
                                taskEventCallback?.invoke(TaskEvent.ToolAction("Retrying with AI agent"))
                                startNewTask(channel, fallbackGoal, messageID, isFallback = true)
                            }
                        }, "skill-executor").start()
                        return
                    }
                    XLog.w(TAG, "Skill ${route.skillId} not found, falling through to agent loop")
                }
            }
            is PipelineRouter.Route.Chat -> {
                // Fall through to agent loop (chat mode)
            }
            is PipelineRouter.Route.AgentLoop -> {
                confirmationRequired = route.confirmationRequired
                if (confirmationRequired) {
                    XLog.w(TAG, "Pipeline: destructive intent flagged, injecting confirmation gate")
                }
            }
        }

        if (!::agentService.isInitialized) {
            XLog.e(TAG, "AgentService not initialized, attempting to initialize")
            try {
                agentService = AgentServiceFactory.create()
                agentService.initialize(agentConfigProvider())
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to initialize AgentService", e)
                releaseTask()
                ForegroundService.resetToIdle(ClawApplication.instance)
                taskEventCallback?.invoke(TaskEvent.Failed("AI service not ready"))
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_service_not_ready), messageID)
                return
            }
        }

        // Per-round message buffer for channel messaging
        val roundBuffer = StringBuilder()
        fun flushRoundBuffer() {
            if (roundBuffer.isNotEmpty()) {
                ChannelManager.sendMessage(channel, roundBuffer.toString().trim(), messageID)
                roundBuffer.clear()
            }
        }

        var floatingShown = false

        val basePrompt = agentPromptOverride?.takeIf { it.isNotBlank() } ?: task
        val agentPrompt = if (confirmationRequired) {
            "[SAFETY GATE] The user's request was classified as potentially destructive. " +
            "You MUST first explain what you are about to do and ask the user to confirm explicitly " +
            "before executing ANY action. Do NOT call any tool until the user says yes. " +
            "If the user does not confirm, call finish(summary=\"Cancelled by user\") immediately.\n\n" +
            basePrompt
        } else {
            basePrompt
        }
        agentService.executeTask(agentPrompt, object : AgentCallback {
            override fun onLoopStart(round: Int) {
                flushRoundBuffer()
                XLog.d(TAG, "onLoopStart: round=$round")
                if (round > 1) {
                    FloatingCircleManager.ensureShowing()
                    FloatingCircleManager.setRunningState(round, channel)
                    taskEventCallback?.invoke(TaskEvent.LoopStart(round))
                    if (ForegroundService.isRunning()) {
                        ForegroundService.updateTaskStatus(ClawApplication.instance, "Step $round")
                    }
                }
            }

            override fun onTokenUpdate(status: com.blackclaw.android.agent.TokenMonitor.Status) {
                FloatingCircleManager.updateTokenStatus(
                    step = status.step,
                    formattedTokens = status.formattedTokens,
                    formattedCost = status.formattedCost,
                    tokenState = status.state
                )
                taskEventCallback?.invoke(TaskEvent.TokenUpdate(
                    step = status.step,
                    formattedTokens = status.formattedTokens,
                    formattedCost = status.formattedCost,
                    tokenState = status.state
                ))
            }

            override fun onContent(round: Int, content: String) {
                if (content.isNotEmpty()) {
                    roundBuffer.append(content)
                    taskEventCallback?.invoke(TaskEvent.Thinking(content))
                }
            }

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                // Never log raw tool parameters: remote_connect may contain an SSH
                // password, messaging tools contain private message bodies, and API
                // helpers may carry tokens. Length is enough for execution tracing.
                XLog.d(TAG, "onToolCall: $toolId($toolName), params=${parameters.length} chars")
                // Don't show floating circle for finish tool (it's just completion, not a real action)
                val isFinish = toolName == "finish" || toolId == "finish"
                if (!floatingShown && !isFinish) {
                    floatingShown = true
                    FloatingCircleManager.ensureShowing()
                    FloatingCircleManager.showTaskNotify(task, channel)
                    ForegroundService.updateTaskStatus(ClawApplication.instance, "Running task...")
                }
                if (toolName.isNotEmpty()) {
                    val displayName = com.blackclaw.android.tool.ToolRegistry.getInstance().getDisplayName(toolName)
                    taskEventCallback?.invoke(TaskEvent.ToolAction(displayName))
                    ForegroundService.updateTaskStatus(ClawApplication.instance, "$displayName...")
                }
            }

            override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
                val app = ClawApplication.instance
                val success = result.isSuccess
                var data = if (success) result.data else result.error
                if (data != null && data.length > 300) data = data.substring(0, 300) + "..."
                if (!success) XLog.e(TAG, "Tool failed: $toolName errorChars=${data?.length ?: 0}")

                val displayName = com.blackclaw.android.tool.ToolRegistry.getInstance().getDisplayName(toolName)
                taskEventCallback?.invoke(TaskEvent.ToolResult(displayName, success, data ?: ""))
                // Emitted separately and whole. The status event above is deliberately
                // truncated for a progress row; putting a payload through that path would
                // cut it mid-JSON and it would decode to nothing.
                if (success && result.hasCards) {
                    taskEventCallback?.invoke(TaskEvent.ToolCards(result.cards!!))
                }

                if (toolId == "finish" && result.data?.isNotEmpty() == true) {
                    flushRoundBuffer()
                    ChannelManager.sendMessage(channel, result.data, messageID)
                } else {
                    if (roundBuffer.isNotEmpty()) roundBuffer.append("\n")
                    // Do not echo raw parameters into the conversation/channel status.
                    // Some tools carry credentials or private message bodies.
                    roundBuffer.append(app.getString(R.string.channel_msg_tool_execution, toolName,
                        if (success) app.getString(R.string.channel_msg_tool_success) else app.getString(R.string.channel_msg_tool_failure)))
                }
            }

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int, modelName: String?) {
                XLog.i(TAG, "onComplete: rounds=$round, totalTokens=$totalTokens, model=$modelName, answerChars=${finalAnswer.length}")
                // Track activity
                runCatching { com.blackclaw.android.utils.ActivityTracker.recordTaskCompleted(true, totalTokens) }
                val cancelAnswers = setOf(
                    ClawApplication.instance.getString(R.string.agent_task_cancel),
                    ClawApplication.instance.getString(R.string.agent_task_cancelled),
                    ClawApplication.instance.getString(R.string.channel_msg_task_cancelled)
                )
                if (finalAnswer.trim() in cancelAnswers) {
                    taskEventCallback?.invoke(TaskEvent.Cancelled)
                    ForegroundService.resetToIdle(ClawApplication.instance)
                    flushRoundBuffer()
                    val cancelledSession = releaseTask()
                    if (cancelledSession.channel != null && cancelledSession.messageId.isNotEmpty()) {
                        ChannelManager.sendMessage(
                            cancelledSession.channel,
                            ClawApplication.instance.getString(R.string.channel_msg_task_cancelled),
                            cancelledSession.messageId
                        )
                        ChannelManager.flushMessages(cancelledSession.channel)
                    }
                    FloatingCircleManager.setErrorState()
                    onTaskFinished()
                    XLog.d(TAG, "Current task cancelled by user")
                    return
                }
                // Strip common LLM-added prefixes from the answer
                var answer = finalAnswer.ifEmpty { "Done." }
                answer = answer.removePrefix("Task completed:").removePrefix("Task completed").trim()
                if (answer.isEmpty()) answer = "Done."
                taskEventCallback?.invoke(TaskEvent.Completed(answer, modelName))
                ForegroundService.resetToIdle(ClawApplication.instance)
                flushRoundBuffer()
                val completedSession = releaseTask()
                ChannelManager.flushMessages(completedSession.channel ?: channel)
                FloatingCircleManager.setSuccessState()
                // Auto-return to BlackClaw after in-app task completes
                if (completedSession.autoReturnToChat) {
                    XLog.i(TAG, "onComplete: auto-returning to BlackClaw chatroom")
                    try {
                        val context = ClawApplication.instance
                        val intent = android.content.Intent(context, com.blackclaw.android.ui.chat.ComposeChatActivity::class.java).apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        XLog.w(TAG, "onComplete: auto-return failed", e)
                    }
                }
                onTaskFinished()
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                XLog.e(TAG, "onError: type=${error.javaClass.simpleName}, totalTokens=$totalTokens")
                runCatching { com.blackclaw.android.utils.ActivityTracker.recordTaskCompleted(false, totalTokens) }
                taskEventCallback?.invoke(TaskEvent.Failed(error.message ?: "Unknown error"))
                ForegroundService.resetToIdle(ClawApplication.instance)
                flushRoundBuffer()
                val failedSession = releaseTask()
                val failedChannel = failedSession.channel ?: channel
                val failedMessageId = failedSession.messageId.ifEmpty { messageID }
                ChannelManager.sendMessage(
                    failedChannel,
                    ClawApplication.instance.getString(R.string.channel_msg_task_error, error.message),
                    failedMessageId
                )
                ChannelManager.flushMessages(failedChannel)
                FloatingCircleManager.setErrorState()
                onTaskFinished()
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                XLog.w(TAG, "onSystemDialogBlocked: round=$round, totalTokens=$totalTokens")
                taskEventCallback?.invoke(TaskEvent.Blocked)
                flushRoundBuffer()
                val blockedSession = releaseTask()
                val blockedChannel = blockedSession.channel ?: channel
                val blockedMessageId = blockedSession.messageId.ifEmpty { messageID }
                ChannelManager.sendMessage(
                    blockedChannel,
                    ClawApplication.instance.getString(R.string.channel_msg_system_dialog_blocked),
                    blockedMessageId
                )
                try {
                    val service = ClawAccessibilityService.getInstance()
                    val bitmap = service?.takeScreenshot(5000)
                    if (bitmap != null) {
                        val stream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, stream)
                        bitmap.recycle()
                        ChannelManager.sendImage(blockedChannel, stream.toByteArray(), blockedMessageId)
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to send screenshot for system dialog", e)
                }
                FloatingCircleManager.setErrorState()
                onTaskFinished()
            }
        })
    }
}
