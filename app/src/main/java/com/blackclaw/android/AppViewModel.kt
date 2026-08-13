package com.blackclaw.android

import android.os.PowerManager
import androidx.lifecycle.ViewModel
import com.blackclaw.android.ClawApplication.Companion.appViewModelInstance
import com.blackclaw.android.TaskEvent
import com.blackclaw.android.agent.AgentConfig
import com.blackclaw.android.agent.llm.ModelConfigRepository
import com.blackclaw.android.channel.Channel
import com.blackclaw.android.channel.ChannelManager
import com.blackclaw.android.channel.ChannelSetup
import com.blackclaw.android.service.ForegroundService
import com.blackclaw.android.floating.FloatingCircleManager
import com.blackclaw.android.server.ConfigServerManager
import com.blackclaw.android.service.KeepAliveJobService
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import com.blackclaw.android.conversation.ConversationRepository
import com.blackclaw.android.conversation.ConversationRouter
import com.blackclaw.android.agent.TaskPromptEnvelope

class AppViewModel : ViewModel() {

    companion object {
        private const val TAG = "AppViewModel"
    }

    /**
     * Volatile + the @Synchronized accessors below because acquire/release are now
     * driven by the task lifecycle: acquisition happens on whichever thread starts
     * the task (agent executor, channel poller, scheduler) and release happens on
     * the agent callback thread. Two unsynchronized threads racing here would
     * either leak a lock or release one twice.
     */
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    private var _commonInitialized = false

    val taskOrchestrator = TaskOrchestrator(
        agentConfigProvider = { getAgentConfig() },
        onTaskFinished = { /* refresh */ }
    )

    private val channelSetup = ChannelSetup(taskOrchestrator = taskOrchestrator)

    val taskSessionStore: TaskSessionStore
        get() = taskOrchestrator.taskSessionStore
    val inProgressTaskMessageId: String get() = taskSessionStore.snapshot().messageId
    val inProgressTaskChannel: Channel? get() = taskSessionStore.snapshot().channel

    // ==================== Task API (clean interface for Activity) ====================

    /**
     * Called before a task starts — allows the chat UI to release its local LLM conversation
     * so the task agent can use the same LiteRT-LM engine (only 1 session supported).
     */
    var onBeforeTask: (() -> Unit)? = null

    fun startTask(
        task: String,
        taskId: String,
        agentPromptOverride: String? = null,
        autoReturnToChat: Boolean = true,
        surface: ConversationRepository.Surface = ConversationRepository.Surface.TASK,
        onEvent: (TaskEvent) -> Unit,
    ) {
        val decision = ConversationRouter.decide(task)
        val sharedLines = ConversationRepository.recentLocalLines()
        val parsedOverride = agentPromptOverride?.let { TaskPromptEnvelope.parse(it) }
        val existingLines = parsedOverride?.chatHistory?.lines()?.filter { it.isNotBlank() }.orEmpty()
        val extraBackground = buildString {
            append("Conversation route: ${decision.mode}; confidence=${decision.confidence}; confirmation=${decision.confirmation}.")
            parsedOverride?.backgroundState?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
            if (agentPromptOverride != null && parsedOverride?.chatHistory == null &&
                parsedOverride?.backgroundState == null && agentPromptOverride.trim() != task.trim()) {
                append("\nSurface context:\n").append(agentPromptOverride.take(2_000))
            }
        }
        val effectivePrompt = TaskPromptEnvelope.build(
            chatHistoryLines = (sharedLines + existingLines).takeLast(16),
            currentRequest = task,
            backgroundState = extraBackground,
        )
        ConversationRepository.appendLocal(surface, ConversationRepository.Role.USER, task, decision.mode.name)
        onBeforeTask?.invoke()
        if (!updateAgentConfig()) {
            onEvent(TaskEvent.Failed("AI service not ready"))
            return
        }
        taskOrchestrator.taskEventCallback = { event ->
            when (event) {
                is TaskEvent.Completed -> ConversationRepository.appendLocal(
                    surface, ConversationRepository.Role.ASSISTANT, event.answer, decision.mode.name
                )
                is TaskEvent.Failed -> ConversationRepository.appendLocal(
                    surface, ConversationRepository.Role.ASSISTANT, "Task error: ${event.error}", decision.mode.name
                )
                else -> Unit
            }
            onEvent(event)
        }
        taskOrchestrator.startNewTask(Channel.LOCAL, task, taskId,
            agentPromptOverride = effectivePrompt, autoReturnToChat = autoReturnToChat)
    }

    fun stopTask() {
        taskOrchestrator.cancelCurrentTask()
    }

    fun isTaskRunning(): Boolean = taskSessionStore.isTaskRunning()

    fun clearTaskCallback() {
        taskOrchestrator.taskEventCallback = null
    }

    fun init() {
        initCommon()
        initAgent()
    }

    fun initCommon() {
        if (_commonInitialized) return
        _commonInitialized = true
    }

    fun initAgent() {
        if (!KVUtils.hasLlmConfig()) return
        taskOrchestrator.initAgent()
    }

    fun getAgentConfig(): AgentConfig =
        ModelConfigRepository.snapshot().toAgentConfig(
            temperature = 0.1,
            maxIterations = 60
        )

    fun updateAgentConfig(): Boolean = taskOrchestrator.updateAgentConfig()

    fun afterInit() {
        // NOTE: the screen wake lock is deliberately NOT acquired here any more.
        // afterInit() runs on every cold start, so a SCREEN_DIM_WAKE_LOCK with
        // ACQUIRE_CAUSES_WAKEUP was forcing the display on for 10 minutes just
        // because the app was launched — and releaseScreenWakeLock() had no
        // callers at all, so nothing ever gave it back. It is now paired with the
        // task lock in TaskOrchestrator (acquire on task start, release on task
        // end), which is the only time we actually need the screen awake.
        KeepAliveJobService.cancel(ClawApplication.instance)
        ForegroundService.syncToBackgroundState(ClawApplication.instance)
        ConfigServerManager.autoStartIfNeeded(ClawApplication.instance)
        channelSetup.setup()
    }


    /**
     * Acquire a wake lock so the screen stays on while the agent drives the UI.
     *
     * Call ONLY when a task actually starts — see TaskOrchestrator.tryAcquireTask.
     * Every acquire must be matched by [releaseScreenWakeLock]; the 10 min timeout
     * is a safety net, not the intended release mechanism.
     */
    @Synchronized
    internal fun acquireScreenWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = ClawApplication.instance.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
            ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "BlackClaw::ScreenWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minute timeout to prevent battery drain
        }
        XLog.i(TAG, "Wake lock acquired")
    }

    /**
     * Release the wake lock. Paired with [acquireScreenWakeLock] on the task
     * lifecycle so the display is not pinned on after the agent is done.
     */
    @Synchronized
    internal fun releaseScreenWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                runCatching { it.release() }
                    .onFailure { e -> XLog.w(TAG, "Wake lock release failed", e) }
                XLog.i(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    /**
     * Show the circular floating window
     */
    fun showFloatingCircle() {
        try {
            FloatingCircleManager.show(ClawApplication.instance)
            FloatingCircleManager.onFloatClick = {
                XLog.d(TAG, "Floating circle clicked")
                bringAppToForeground()
            }
            FloatingCircleManager.onStopTask = {
                XLog.i(TAG, "Stop task requested from floating pill")
                stopTask()
                bringAppToForeground()
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to show floating circle: ${e.message}")
        }
    }

    /**
     * Bring the app to the foreground
     */
    private fun bringAppToForeground() {
        val context = ClawApplication.instance
        val intent = android.content.Intent(context, com.blackclaw.android.ui.chat.ComposeChatActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
    }

    // Old pass-through methods removed — use startTask/stopTask/isTaskRunning/clearTaskCallback instead

    private fun trySendScreenshot(channel: Channel, filePath: String, messageID: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                XLog.w(TAG, "Screenshot file does not exist: $filePath")
                return
            }
            val imageBytes = file.readBytes()
            ChannelManager.sendImage(channel, imageBytes, messageID)
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to send screenshot", e)
        }
    }
}
