package com.blackclaw.android.ui.chat

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.UserMessage
import com.blackclaw.android.agent.ModelPricing
import com.blackclaw.android.agent.llm.LlmClient
import com.blackclaw.android.agent.llm.LlmSessionManager
import com.blackclaw.android.agent.llm.LocalModelManager
import com.blackclaw.android.agent.llm.LocalModelRuntime
import com.blackclaw.android.agent.llm.ModelConfigRepository
import com.blackclaw.android.utils.XLog
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Content
import com.blackclaw.android.perception.VisionImage
import java.io.File
import java.util.concurrent.ExecutorService

data class ChatSessionUiState(
    val messages: SnapshotStateList<ChatMessage>,
    val modelStatus: MutableState<String>,
    val isAwaitingReply: MutableState<Boolean>,
    val inputEnabled: MutableState<Boolean>,
    val isDownloading: MutableState<Boolean>,
    val downloadProgress: MutableState<Int>,
    val sessionTokens: MutableState<Int>,
    val sessionCost: MutableState<Double>,
)

class ChatSessionController(
    private val activity: ComponentActivity,
    private val executor: ExecutorService,
    private val uiState: ChatSessionUiState,
    private val onPersistConversation: () -> Unit,
    private val onRefreshSidebarHistory: () -> Unit,
    private val isTaskRunning: () -> Boolean,
) {

    companion object {
        private const val TAG = "ChatSessionController"
        private const val BASE_SYSTEM_PROMPT = "You are BlackClaw, a helpful AI assistant on an Android phone. ${com.blackclaw.android.agent.PromptUtils.CREATOR_INSTRUCTION}"
    }

    private var engine: Engine? = null
    private var loadedModelPath: String? = null
    private var conversation: Conversation? = null
    private var isModelReady = false

    private var cloudClient: LlmClient? = null
    private var cloudModelName: String? = null
    /** Optional hook fired when a chat reply (cloud or local) arrives — used to
     *  speak the answer aloud after a voice command. */
    var onChatReply: ((String) -> Unit)? = null
    private val cloudHistory = mutableListOf<dev.langchain4j.data.message.ChatMessage>()
    private var localUiGeneration: Long = 0
    @Volatile private var localLoadInProgress: Boolean = false
    private var suppressNextCloudSwitchMessage: Boolean = false

    fun isModelReady(): Boolean = isModelReady

    fun loadModelIfReady(
        conversationId: String? = null,
        visibleMessages: List<ChatMessage> = emptyList(),
    ) {
        val resolvedConfig = ModelConfigRepository.snapshot()

        if (!resolvedConfig.isLocalActive()) {
            localUiGeneration++
            // Automatic mode can move from the local runtime back to cloud when
            // connectivity returns. LiteRT-LM permits only one live conversation,
            // so release it before creating the cloud client.
            if (conversation != null || engine != null) {
                try {
                    conversation?.close()
                } catch (e: Exception) {
                    XLog.w(TAG, "loadModelIfReady: local conversation close error", e)
                }
                conversation = null
                engine = null
                loadedModelPath = null
                isModelReady = false
                cloudHistory.clear()
            }
            val cloudConfig = resolvedConfig.activeCloud
            if (cloudConfig.isConfigured) {
                val previousModel = cloudModelName
                cloudClient = LlmSessionManager.createCloudClient(temperature = 0.7)
                if (cloudClient == null) {
                    uiState.modelStatus.value = "No model selected"
                    isModelReady = false
                    setButtonsEnabled(false)
                    return
                }
                cloudModelName = cloudConfig.modelName
                if (previousModel == null || cloudHistory.isEmpty()) {
                    rebuildCloudHistoryFromVisibleMessages()
                } else if (previousModel != cloudConfig.modelName) {
                    cloudHistory.add(
                        SystemMessage.from(
                            "The user has switched from $previousModel to ${cloudConfig.modelName}. Continue the conversation naturally."
                        )
                    )
                    if (suppressNextCloudSwitchMessage) {
                        suppressNextCloudSwitchMessage = false
                    } else {
                        addSystem("Switched to ${cloudConfig.modelName}")
                    }
                }
                isModelReady = true
                uiState.modelStatus.value = "● ${cloudConfig.modelName} · " +
                    if (resolvedConfig.isAutomaticActive()) "Automático · Cloud" else "Cloud"
                setButtonsEnabled(true)
                XLog.i(TAG, "Cloud chat ready: ${cloudConfig.modelName} via ${cloudConfig.resolvedBaseUrl}")
            } else {
                uiState.modelStatus.value = "No model selected"
                isModelReady = false
                setButtonsEnabled(false)
            }
            return
        }

        cloudClient = null
        if (localLoadInProgress) return
        val modelPath = resolvedConfig.local.modelPath
        if (isTaskRunning()) {
            uiState.modelStatus.value = "● Local task using model"
            isModelReady = false
            setButtonsEnabled(false)
            return
        }
        XLog.d(TAG, "loadModelIfReady: stored=$modelPath loaded=$loadedModelPath engine=${engine != null}")

        if (modelPath.isNotEmpty() && engine != null && modelPath != loadedModelPath) {
            XLog.d(TAG, "loadModelIfReady: model changed ($loadedModelPath -> $modelPath), closing conversation")
            val oldConv = conversation
            engine = null
            conversation = null
            isModelReady = false
            loadedModelPath = null
            executor.submit {
                try {
                    oldConv?.close()
                } catch (e: Exception) {
                    XLog.w(TAG, "loadModelIfReady: conv close error", e)
                }
                postToMain { loadModelIfReady() }
            }
            return
        }

        if (modelPath.isEmpty()) {
            val deviceSupport = LocalModelManager.deviceSupport(activity)
            val defaultModel = deviceSupport.bestSupportedModel
            if (defaultModel == null) {
                uiState.modelStatus.value = "Local model unavailable on this device"
                uiState.isDownloading.value = false
                setButtonsEnabled(false)
                addSystem(
                    "This device reports ${deviceSupport.deviceRamGb}GB RAM. Current built-in local models need at least ${deviceSupport.minimumBuiltInRamGb}GB."
                )
                return
            }
            uiState.modelStatus.value = "Downloading ${defaultModel.displayName}..."
            uiState.isDownloading.value = true
            uiState.downloadProgress.value = 0
            setButtonsEnabled(false)
            localLoadInProgress = true

            executor.submit {
                LocalModelManager.downloadModel(activity, defaultModel, object : LocalModelManager.DownloadCallback {
                    override fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) {
                        val pct = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
                        postToMain {
                            uiState.downloadProgress.value = pct
                            uiState.modelStatus.value = "Downloading: $pct%"
                        }
                    }

                    override fun onComplete(modelPath: String) {
                        val currentPath = ModelConfigRepository.snapshot().local.modelPath
                        if (currentPath.isEmpty() || currentPath == modelPath) {
                            val keepAutomatic = ModelConfigRepository.isAutomaticActive()
                            ModelConfigRepository.saveLocalDefault(
                                modelPath = modelPath,
                                modelId = defaultModel.id,
                                activateNow = !keepAutomatic,
                            )
                        }
                        postToMain {
                            localLoadInProgress = false
                            uiState.isDownloading.value = false
                            loadModelIfReady()
                        }
                    }

                    override fun onError(error: String) {
                        postToMain {
                            localLoadInProgress = false
                            uiState.isDownloading.value = false
                            uiState.modelStatus.value = "Download failed"
                            addSystem("Download failed: $error")
                        }
                    }
                })
            }
            return
        }

        val restoredSystemPrompt = buildRestoredSystemPrompt(conversationId, visibleMessages)
        uiState.modelStatus.value = "Loading..."
        setButtonsEnabled(false)
        val generation = ++localUiGeneration
        localLoadInProgress = true
        executor.submit { loadModel(modelPath, generation, restoredSystemPrompt) }
    }

    fun onResume(
        conversationId: String,
        visibleMessages: List<ChatMessage>,
    ) {
        syncUiToActiveModel()
        val currentModelPath = ModelConfigRepository.snapshot().local.modelPath
        if (currentModelPath.isNotEmpty() && currentModelPath != loadedModelPath) {
            loadModelIfReady(conversationId, visibleMessages)
        } else if (!isModelReady && engine != null && currentModelPath.isNotEmpty()) {
            val generation = ++localUiGeneration
            executor.submit {
                try {
                    try {
                        conversation?.close()
                    } catch (_: Exception) {
                    }
                    conversation = null
                    val lease = LocalModelRuntime.openConversation(
                        activity,
                        currentModelPath,
                        buildConversationConfig(buildRestoredSystemPrompt(conversationId, visibleMessages))
                    )
                    engine = lease.engine
                    conversation = lease.conversation
                    isModelReady = true
                    postToMain {
                        if (!isLocalUiStillExpected(currentModelPath, generation)) {
                            return@postToMain
                        }
                        updateLocalModelStatus(currentModelPath)
                        setButtonsEnabled(true)
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to recreate conversation", e)
                    val isSessionConflict = e.message?.contains("session already exists") == true
                    postToMain {
                        if (isSessionConflict) {
                            uiState.modelStatus.value = "⚠ Model busy — tap model to retry"
                            Toast.makeText(
                                activity,
                                "Model is being used by a task. Wait for it to finish, then tap the model name to retry.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            uiState.modelStatus.value = "⚠ Model load failed — tap to retry"
                            Toast.makeText(
                                activity,
                                "Failed to load model: ${e.message?.take(80)}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        setButtonsEnabled(false)
                    }
                }
            }
        } else if (!isModelReady && engine == null && currentModelPath.isNotEmpty()) {
            loadModelIfReady(conversationId, visibleMessages)
        }
    }

    fun onPause(conversationId: String) {
        if (engine != null && ConversationCompactor.needsCompaction(uiState.messages)) {
            executor.submit {
                try {
                    conversation?.close()
                } catch (_: Exception) {
                }
                conversation = null
                ConversationCompactor.compact(engine!!, uiState.messages, activity, conversationId)
                isModelReady = false
            }
        }
        executor.submit {
            try {
                conversation?.close()
            } catch (_: Exception) {
            }
            conversation = null
            isModelReady = false
        }
    }

    fun onDestroy() {
        executor.submit {
            XLog.i(TAG, "onDestroy: closing conversation (engine stays in EngineHolder)")
            try {
                conversation?.close()
            } catch (e: Exception) {
                XLog.w(TAG, "onDestroy: conversation close error", e)
            }
            conversation = null
        }
    }

    fun releaseForTask() {
        try {
            conversation?.close()
        } catch (_: Exception) {
        }
        conversation = null
        isModelReady = false
    }

    fun prepareForTaskStart() {
        try {
            conversation?.close()
        } catch (_: Exception) {
        }
        conversation = null
        isModelReady = false
    }

    /**
     * The task agent has its own request history. After a task finishes, the
     * visible chat contains the task and its result, so rebuild the cloud history
     * before the next conversational turn. Without this, saying "continúa" could
     * hit a stale chat history that never saw the task at all.
     */
    fun refreshCloudHistoryFromVisibleMessages() {
        if (cloudClient == null) return
        rebuildCloudHistoryFromVisibleMessages()
        XLog.d(TAG, "Cloud history synchronized after task (${cloudHistory.size} messages)")
    }

    fun sendChat(text: String) {
        com.blackclaw.android.conversation.ConversationRepository.appendLocal(
            com.blackclaw.android.conversation.ConversationRepository.Surface.CHAT,
            com.blackclaw.android.conversation.ConversationRepository.Role.USER, text,
            com.blackclaw.android.conversation.ConversationRouter.Mode.CONVERSE.name)
        addUser(text)
        uiState.isAwaitingReply.value = true
        uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, ChatMessage.PENDING))

        executor.submit {
            try {
                if (cloudClient != null) {
                    ensureCloudHistoryInitialized()
                    cloudHistory.add(UserMessage.from(text))
                    val fallbackModelName = cloudModelName ?: ModelConfigRepository.snapshot().activeCloud.modelName
                    // Index of the "..." placeholder we stream into.
                    val streamIdx = uiState.messages.indexOfLast {
                        it.isPending }
                    val sb = StringBuilder()
                    var hasVisibleContent = false
                    val llmResponse = try {
                        cloudClient!!.chatStreaming(cloudHistory, emptyList(),
                            object : com.blackclaw.android.agent.llm.StreamingListener {
                                override fun onPartialText(token: String) {
                                    if (token.isEmpty()) {
                                        if (!hasVisibleContent) {
                                            postToMain { setStreamingText(streamIdx, "Pensando…", fallbackModelName) }
                                        }
                                        return
                                    }
                                    hasVisibleContent = true
                                    sb.append(token)
                                    val soFar = sb.toString()
                                    postToMain { setStreamingText(streamIdx, soFar, fallbackModelName) }
                                }
                                override fun onComplete(response: com.blackclaw.android.agent.llm.LlmResponse) {}
                                override fun onError(error: Throwable) {}
                            })
                    } catch (e: Exception) {
                        XLog.w(TAG, "cloud streaming failed, falling back: ${e.message}")
                        // A timeout is not a streaming-format incompatibility. Retrying
                        // it as a blocking call recreates the endless-looking state.
                        if (e.message?.contains("timed out", ignoreCase = true) == true) throw e
                        cloudClient!!.chat(cloudHistory, emptyList())
                    }
                    val responseText = llmResponse.text?.takeIf { it.isNotBlank() }
                        ?: sb.toString().ifBlank { "(no response)" }
                    cloudHistory.add(AiMessage.from(responseText))
                    recordSharedAssistant(responseText)
                    val usage = llmResponse.tokenUsage
                    val inputTokens = usage?.inputTokenCount() ?: (text.length / 4 + 1)
                    val outputTokens = usage?.outputTokenCount() ?: (responseText.length / 4 + 1)
                    val modelTag = llmResponse.modelName ?: fallbackModelName
                    postToMain {
                        setStreamingText(streamIdx, responseText, modelTag)
                        uiState.isAwaitingReply.value = false
                        uiState.sessionTokens.value += inputTokens + outputTokens
                        uiState.sessionCost.value += ModelPricing.estimateCost(modelTag, inputTokens, outputTokens)
                        onPersistConversation()
                        onChatReply?.invoke(responseText)
                    }
                } else {
                    val currentConversation = conversation
                    if (currentConversation == null || !isModelReady) {
                        throw IllegalStateException("Local model is still loading. Try again in a moment.")
                    }
                    val modelPath = ModelConfigRepository.snapshot().local.modelPath.ifEmpty { loadedModelPath.orEmpty() }
                    val localModelTag = localModelTag(modelPath)
                    val streamIdx = uiState.messages.indexOfLast {
                        it.isPending }
                    val sb = StringBuilder()
                    val responseText = try {
                        streamLocal(currentConversation, text, sb) { soFar ->
                            postToMain { setStreamingText(streamIdx, soFar, localModelTag) }
                        }
                    } catch (se: Exception) {
                        // Some runtime builds may not support async streaming — fall back.
                        XLog.w(TAG, "local streaming failed, sync fallback: ${se.message}")
                        currentConversation.sendMessage(text)?.toString() ?: sb.toString()
                    }.ifBlank { "(no response)" }
                    recordSharedAssistant(responseText)
                    val inputTokensEst = text.length / 4 + 1
                    val outputTokensEst = responseText.length / 4 + 1
                    postToMain {
                        setStreamingText(streamIdx, responseText, localModelTag)
                        uiState.isAwaitingReply.value = false
                        uiState.sessionTokens.value += inputTokensEst + outputTokensEst
                        onPersistConversation()
                        onChatReply?.invoke(responseText)
                    }
                }
            } catch (e: Exception) {
                if (conversation != null && LocalModelRuntime.isGpuBackendFailure(e)) {
                    XLog.w(TAG, "GPU inference failed, falling back to CPU: ${e.message}")
                    try {
                        val modelPath = ModelConfigRepository.snapshot().local.modelPath.ifEmpty { loadedModelPath.orEmpty() }
                        val responseText = retryLocalChatOnCpu(modelPath, text)
                        recordSharedAssistant(responseText)
                        val inputTokensEst = text.length / 4 + 1
                        val outputTokensEst = responseText.length / 4 + 1
                        val cpuModelTag = localModelTag(modelPath)
                        postToMain {
                            replaceTypingIndicator(responseText, cpuModelTag)
                            uiState.isAwaitingReply.value = false
                            uiState.sessionTokens.value += inputTokensEst + outputTokensEst
                            updateLocalModelStatus(modelPath)
                            onPersistConversation()
                        }
                        return@submit
                    } catch (cpuError: Exception) {
                        XLog.e(TAG, "CPU fallback also failed", cpuError)
                    }
                }
                XLog.e(TAG, "Chat error", e)
                postToMain {
                    replaceTypingIndicator("Error: ${e.message}")
                    uiState.isAwaitingReply.value = false
                }
            }
        }
    }

    /**
     * Sends the actual image bytes to a multimodal model. OCR remains supplemental
     * context and is used as a safe fallback for text-only or incompatible models.
     */
    fun sendImage(image: VisionImage) {
        val prompt = visionPrompt(image.ocrText)
        val historyText = imageHistoryText(image.ocrText)
        com.blackclaw.android.conversation.ConversationRepository.appendLocal(
            com.blackclaw.android.conversation.ConversationRepository.Surface.CHAT,
            com.blackclaw.android.conversation.ConversationRepository.Role.USER,
            historyText,
            com.blackclaw.android.conversation.ConversationRouter.Mode.CONVERSE.name,
        )
        addUser(historyText)
        uiState.isAwaitingReply.value = true
        uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, ChatMessage.PENDING))

        executor.submit {
            try {
                val streamIdx = uiState.messages.indexOfLast { it.isPending }
                if (cloudClient != null) {
                    ensureCloudHistoryInitialized()
                    // If this is the first turn, rebuilding from the visible UI has
                    // already added the textual placeholder. Replace it with the
                    // multimodal message instead of sending both representations.
                    val lastHistory = cloudHistory.lastOrNull() as? UserMessage
                    if (lastHistory?.hasSingleText() == true && lastHistory.singleText() == historyText) {
                        cloudHistory.removeAt(cloudHistory.lastIndex)
                    }
                    val visionMessage = UserMessage.from(listOf(
                        TextContent.from(prompt),
                        ImageContent.from(image.base64, image.mimeType, ImageContent.DetailLevel.AUTO),
                    ))
                    cloudHistory.add(visionMessage)
                    val visionIndex = cloudHistory.lastIndex
                    val fallbackModelName = cloudModelName ?: ModelConfigRepository.snapshot().activeCloud.modelName
                    val buffer = StringBuilder()
                    var hasVisibleContent = false
                    val response = try {
                        cloudClient!!.chatStreaming(cloudHistory, emptyList(), object : com.blackclaw.android.agent.llm.StreamingListener {
                            override fun onPartialText(token: String) {
                                if (token.isNotEmpty()) {
                                    hasVisibleContent = true
                                    buffer.append(token)
                                    postToMain { setStreamingText(streamIdx, buffer.toString(), fallbackModelName) }
                                } else if (!hasVisibleContent) {
                                    postToMain { setStreamingText(streamIdx, "Analizando imagen…", fallbackModelName) }
                                }
                            }
                            override fun onComplete(response: com.blackclaw.android.agent.llm.LlmResponse) = Unit
                            override fun onError(error: Throwable) = Unit
                        })
                    } catch (visionError: Exception) {
                        // Some OpenAI-compatible endpoints expose text-only models.
                        // Replace the large image message before retrying so the OCR
                        // fallback does not send image bytes to a model that rejected it.
                        XLog.w(TAG, "Vision request failed; using OCR fallback: ${visionError.message}")
                        cloudHistory[visionIndex] = UserMessage.from(prompt)
                        buffer.clear()
                        cloudClient!!.chat(cloudHistory, emptyList())
                    }
                    val responseText = response.text?.takeIf { it.isNotBlank() }
                        ?: buffer.toString().ifBlank { "(no response)" }
                    // Do not resend the same base64 payload on every later turn.
                    cloudHistory[visionIndex] = UserMessage.from(historyText)
                    cloudHistory.add(AiMessage.from(responseText))
                    recordSharedAssistant(responseText)
                    val usage = response.tokenUsage
                    postToMain {
                        setStreamingText(streamIdx, responseText, response.modelName ?: fallbackModelName)
                        uiState.isAwaitingReply.value = false
                        uiState.sessionTokens.value += (usage?.inputTokenCount() ?: (prompt.length / 4 + 1)) +
                            (usage?.outputTokenCount() ?: (responseText.length / 4 + 1))
                        uiState.sessionCost.value += ModelPricing.estimateCost(
                            response.modelName ?: fallbackModelName,
                            usage?.inputTokenCount() ?: (prompt.length / 4 + 1),
                            usage?.outputTokenCount() ?: (responseText.length / 4 + 1),
                        )
                        onPersistConversation()
                        onChatReply?.invoke(responseText)
                    }
                } else {
                    val currentConversation = conversation
                        ?: throw IllegalStateException("El modelo local aún está cargando")
                    val modelPath = ModelConfigRepository.snapshot().local.modelPath.ifEmpty { loadedModelPath.orEmpty() }
                    val modelTag = localModelTag(modelPath)
                    val responseText = try {
                        currentConversation.sendMessage(
                            Contents.of(Content.ImageBytes(image.bytes), Content.Text(prompt))
                        ).contents?.toString()?.trim().orEmpty()
                    } catch (visionError: Exception) {
                        XLog.w(TAG, "Local model has no usable vision path; using OCR fallback: ${visionError.message}")
                        currentConversation.sendMessage(prompt)?.contents?.toString()?.trim().orEmpty()
                    }.ifBlank { "(no response)" }
                    recordSharedAssistant(responseText)
                    postToMain {
                        setStreamingText(streamIdx, responseText, modelTag)
                        uiState.isAwaitingReply.value = false
                        uiState.sessionTokens.value += prompt.length / 4 + responseText.length / 4 + 2
                        onPersistConversation()
                        onChatReply?.invoke(responseText)
                    }
                }
            } catch (error: Exception) {
                XLog.e(TAG, "Image chat failed", error)
                postToMain {
                    replaceTypingIndicator("Error al analizar la imagen: ${error.message}")
                    uiState.isAwaitingReply.value = false
                }
            }
        }
    }

    private fun visionPrompt(ocrText: String): String = buildString {
        append("Analiza la imagen adjunta directamente. Describe lo que realmente ves y responde en español. ")
        append("No inventes detalles si algo no se distingue.")
        if (ocrText.isNotBlank()) {
            append("\n\nOCR auxiliar (puede contener errores):\n")
            append(ocrText.take(4000))
        }
    }

    private fun imageHistoryText(ocrText: String): String = buildString {
        append("📷 Imagen adjunta")
        if (ocrText.isNotBlank()) append("\nTexto OCR:\n").append(ocrText.take(4000))
    }

    fun switchModel(modelId: String, displayName: String) {
        if (modelId == "NONE") {
            uiState.modelStatus.value = "No model selected"
            isModelReady = false
            setButtonsEnabled(false)
            XLog.i(TAG, "switchModel: NONE — no model configured for current tab")
            return
        }
        if (modelId == "LOCAL") {
            val localConfig = ModelConfigRepository.snapshot().local
            if (!localConfig.isConfigured) {
                uiState.modelStatus.value = "No model selected"
                isModelReady = false
                setButtonsEnabled(false)
                XLog.i(TAG, "switchModel: LOCAL requested but no local default configured")
                return
            }
            ModelConfigRepository.activateLocal(localConfig.modelPath, localConfig.modelId)
            uiState.modelStatus.value = "● ${localConfig.displayName} · On-device"
            addSystem("Switched to local model")
            loadModelIfReady()
        } else if (modelId == "AUTO") {
            ModelConfigRepository.activateAutomatic()
            addSystem("Modo automático activado: usaré la nube con internet y el modelo local sin conexión.")
            loadModelIfReady()
        } else {
            localUiGeneration++
            ModelConfigRepository.activateCloudSelection(modelId)
            suppressNextCloudSwitchMessage = true
            loadModelIfReady()
            addSystem("Switched to $displayName")
        }
        XLog.i(TAG, "Model switched to: $modelId ($displayName)")
    }

    fun startNewConversationRuntime() {
        if (cloudClient != null) {
            cloudHistory.clear()
            cloudHistory.add(SystemMessage.from(BASE_SYSTEM_PROMPT))
            postToMain {
                addSystem("New conversation started.")
                onRefreshSidebarHistory()
            }
            return
        }

        executor.submit {
            try {
                conversation?.close()
            } catch (_: Exception) {
            }
            val modelPath = ModelConfigRepository.snapshot().local.modelPath.ifEmpty { loadedModelPath.orEmpty() }
            if (modelPath.isNotEmpty()) {
                val lease = LocalModelRuntime.openConversation(activity, modelPath, buildConversationConfig())
                engine = lease.engine
                conversation = lease.conversation
                isModelReady = true
            }
            postToMain {
                addSystem("New conversation started.")
                onRefreshSidebarHistory()
            }
        }
    }

    fun restoreConversationRuntime(conversationId: String, messages: List<ChatMessage>) {
        if (cloudClient != null) {
            rebuildCloudHistoryFromVisibleMessages()
            return
        }
        if (engine != null) {
            executor.submit {
                try {
                    try {
                        conversation?.close()
                    } catch (_: Exception) {
                    }
                    val recentMsgs = messages.takeLast(5)
                    val systemPrompt = ConversationCompactor.buildRestoredSystemPrompt(activity, conversationId, recentMsgs)
                    val modelPath = ModelConfigRepository.snapshot().local.modelPath.ifEmpty { loadedModelPath.orEmpty() }
                    val lease = LocalModelRuntime.openConversation(
                        context = activity,
                        modelPath = modelPath,
                        conversationConfig = ConversationConfig(
                            systemInstruction = Contents.of(com.blackclaw.android.agent.PromptUtils.applyGlobalPrompt(systemPrompt)),
                            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
                        )
                    )
                    engine = lease.engine
                    conversation = lease.conversation
                    isModelReady = true
                    postToMain {
                        setButtonsEnabled(true)
                        addSystem("Conversation restored.")
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to restore conversation", e)
                    postToMain { addSystem("History loaded. New context started.") }
                }
            }
        }
    }

    private fun loadModel(
        modelPath: String,
        generation: Long,
        restoredSystemPrompt: String? = null,
    ) {
        try {
            XLog.i(TAG, "loadModel: acquiring shared runtime for $modelPath")
            try {
                conversation?.close()
            } catch (_: Exception) {
            }
            conversation = null
            Thread.sleep(200)

            val lease = LocalModelRuntime.openConversation(
                activity,
                modelPath,
                buildConversationConfig(restoredSystemPrompt)
            )
            engine = lease.engine
            XLog.i(TAG, "loadModel: engine ready (${lease.backendLabel})")
            conversation = lease.conversation

            isModelReady = true
            loadedModelPath = modelPath
            postToMain {
                if (!isLocalUiStillExpected(modelPath, generation)) {
                    XLog.i(TAG, "Ignoring stale local UI update for $modelPath (generation=$generation)")
                    try {
                        conversation?.close()
                    } catch (_: Exception) {
                    }
                    conversation = null
                    engine = null
                    isModelReady = false
                    return@postToMain
                }
                updateLocalModelStatus(modelPath)
                setButtonsEnabled(true)
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Model load failed", e)
            val isSessionConflict = e.message?.contains("session already exists") == true
                || e.message?.contains("5 retries") == true
            postToMain {
                if (isSessionConflict) {
                    uiState.modelStatus.value = "⚠ Model busy — tap model to retry"
                    addSystem("Model is being used by a background task. Wait for it to finish, then tap the model name above to reload.")
                    Toast.makeText(
                        activity,
                        "Model is busy. Wait for the task to finish, then tap the model name to retry.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    uiState.modelStatus.value = "⚠ Load failed — tap model to retry"
                    addSystem("Failed to load model: ${e.message?.take(100)}")
                    Toast.makeText(
                        activity,
                        "Model load failed: ${e.message?.take(80)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                setButtonsEnabled(false)
            }
        } finally {
            localLoadInProgress = false
        }
    }

    private fun retryLocalChatOnCpu(modelPath: String, text: String): String {
        require(modelPath.isNotEmpty()) { "Local model path missing for CPU retry" }
        try {
            conversation?.close()
        } catch (_: Exception) {
        }
        conversation = null
        LocalModelRuntime.forceCpuEngine(activity, modelPath)
        val lease = LocalModelRuntime.openConversation(
            context = activity,
            modelPath = modelPath,
            conversationConfig = buildConversationConfig(),
            preferCpu = true,
        )
        engine = lease.engine
        loadedModelPath = modelPath
        conversation = lease.conversation
        XLog.i(TAG, "retryLocalChatOnCpu: CPU runtime ready, retrying sendMessage")
        return conversation!!.sendMessage(text)?.toString() ?: "(no response)"
    }

    /**
     * Context appended to the chat system prompt: long-term memory first, then the
     * recent cross-surface timeline.
     *
     * ## Why memory belongs here and not only in the agent loop
     *
     * [com.blackclaw.android.memory.MemoryHub] used to be assembled in exactly one
     * place, `DefaultAgentService`, so it only reached prompts when a message was
     * routed as a *task*. A plain conversational reply therefore ran without the
     * user's profile or any of the facts they had explicitly asked to remember —
     * the assistant could store "mi mamá se llama Ana" through a tool and then fail
     * to use it two turns later, purely because the second message did not happen to
     * need a tool. From the user's side that reads as the assistant forgetting at
     * random, which is worse than never remembering at all.
     *
     * The budget concern that might have justified leaving it out is already handled:
     * `assembleForProvider` drops the lowest-priority sections to fit
     * `LOCAL_BUDGET_CHARS` on-device.
     *
     * @param isLocal true for the on-device runtime, which gets the tighter budget.
     */
    private fun sharedContextSuffix(isLocal: Boolean): String {
        // Degrade to no memory rather than failing the chat: MemoryHub fans out to
        // five MMKV/JSON-backed stores, and a single malformed record in any of them
        // must not be able to stop the user from talking to the assistant.
        val memory = runCatching {
            com.blackclaw.android.memory.MemoryHub.assembleForProvider(isLocal)
        }.onFailure {
            XLog.w(TAG, "MemoryHub assembly failed, chatting without long-term memory: ${it.message}")
        }.getOrDefault("")

        val recent = runCatching {
            com.blackclaw.android.conversation.ConversationRepository.recentLocalLines(8, 1_200)
        }.getOrDefault(emptyList())

        return buildString {
            if (memory.isNotBlank()) {
                append("\n\n").append(memory.trim())
            }
            if (recent.isNotEmpty()) {
                append("\n\nRecent conversation across BlackClaw surfaces:\n")
                append(recent.joinToString("\n"))
            }
        }
    }

    private fun buildConversationConfig(systemPrompt: String? = null): ConversationConfig {
        val withShared = buildString {
            append(systemPrompt ?: BASE_SYSTEM_PROMPT)
            append(sharedContextSuffix(isLocal = true))
        }
        val finalPrompt = com.blackclaw.android.agent.PromptUtils.applyGlobalPrompt(withShared)
        return ConversationConfig(
            systemInstruction = Contents.of(finalPrompt),
            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
        )
    }

    private fun buildRestoredSystemPrompt(
        conversationId: String?,
        visibleMessages: List<ChatMessage>,
    ): String? {
        val meaningfulMessages = visibleMessages.filter {
            it.role == ChatMessage.Role.USER || it.role == ChatMessage.Role.ASSISTANT
        }
        if (conversationId.isNullOrBlank() || meaningfulMessages.isEmpty()) return null
        return ConversationCompactor.buildRestoredSystemPrompt(
            activity,
            conversationId,
            meaningfulMessages.takeLast(6)
        )
    }

    private fun rebuildCloudHistoryFromVisibleMessages() {
        cloudHistory.clear()
        cloudHistory.add(
            SystemMessage.from(BASE_SYSTEM_PROMPT + sharedContextSuffix(isLocal = false))
        )
        uiState.messages.forEach { msg ->
            when (msg.role) {
                ChatMessage.Role.USER -> cloudHistory.add(UserMessage.from(msg.content))
                ChatMessage.Role.ASSISTANT -> cloudHistory.add(AiMessage.from(msg.content))
                else -> Unit
            }
        }
    }

    private fun recordSharedAssistant(text: String) {
        com.blackclaw.android.conversation.ConversationRepository.appendLocal(
            com.blackclaw.android.conversation.ConversationRepository.Surface.CHAT,
            com.blackclaw.android.conversation.ConversationRepository.Role.ASSISTANT, text,
            com.blackclaw.android.conversation.ConversationRouter.Mode.CONVERSE.name)
    }

    private fun ensureCloudHistoryInitialized() {
        if (cloudHistory.isEmpty()) {
            rebuildCloudHistoryFromVisibleMessages()
        }
    }

    /**
     * Stream a local (LiteRT-LM) chat response token-by-token via sendMessageAsync.
     * Robust to delta OR cumulative emissions. Returns the full text; throws on
     * error/timeout so the caller can fall back to the blocking path. No tools
     * here (plain chat), so there's no tool-call parsing risk.
     */
    private fun streamLocal(
        conv: com.google.ai.edge.litertlm.Conversation,
        text: String,
        sb: StringBuilder,
        onDelta: (String) -> Unit,
    ): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        val err = java.util.concurrent.atomic.AtomicReference<Throwable>()
        conv.sendMessageAsync(text, object : com.google.ai.edge.litertlm.MessageCallback {
            override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                val t = runCatching { message.contents?.toString() }.getOrNull()
                    ?: runCatching { message.toString() }.getOrNull() ?: ""
                if (t.isEmpty()) return
                if (t.length >= sb.length && t.startsWith(sb.toString())) {
                    sb.setLength(0); sb.append(t)   // cumulative snapshot
                } else {
                    sb.append(t)                    // incremental delta
                }
                onDelta(sb.toString())
            }
            override fun onDone() { latch.countDown() }
            override fun onError(throwable: Throwable) { err.set(throwable); latch.countDown() }
        })
        if (!latch.await(120, java.util.concurrent.TimeUnit.SECONDS))
            throw RuntimeException("local stream timeout")
        err.get()?.let { throw it }
        return sb.toString()
    }

    /** Update the streaming assistant message at [idx] with the text so far. */
    private fun setStreamingText(idx: Int, text: String, modelTag: String?) {
        if (idx in uiState.messages.indices &&
            uiState.messages[idx].role == ChatMessage.Role.ASSISTANT) {
            uiState.messages[idx] = ChatMessage(
                ChatMessage.Role.ASSISTANT, text.ifBlank { "…" }, modelName = modelTag ?: "")
        } else {
            uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelTag ?: ""))
        }
    }

    private fun replaceTypingIndicator(text: String, actualModelName: String? = null) {        val modelTag = actualModelName
            ?: uiState.modelStatus.value.removePrefix("● ").split(" ·").firstOrNull()?.trim()
            ?: ""
        val idx = uiState.messages.indexOfLast { it.isPending }
        if (idx >= 0) {
            uiState.messages[idx] = ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelTag)
        } else {
            uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelTag))
        }
    }

    private fun addUser(text: String) {
        uiState.messages.add(ChatMessage(ChatMessage.Role.USER, text))
    }

    private fun addSystem(text: String) {
        val last = uiState.messages.lastOrNull()
        if (last?.role == ChatMessage.Role.SYSTEM && last.content.equals(text, ignoreCase = true)) {
            return
        }
        uiState.messages.add(ChatMessage(ChatMessage.Role.SYSTEM, text))
    }

    private fun updateLocalModelStatus(modelPath: String?) {
        if (modelPath.isNullOrEmpty()) {
            uiState.modelStatus.value = "No model selected"
            return
        }
        val modelInfo = LocalModelManager.AVAILABLE_MODELS.find { modelPath.endsWith(it.fileName) }
        val modelName = modelInfo?.displayName ?: modelPath.substringAfterLast('/').substringBeforeLast('.')
        val backendLabel = LocalModelRuntime.currentBackendLabel(modelPath) ?: "On-device"
        uiState.modelStatus.value = "● $modelName · " +
            if (ModelConfigRepository.isAutomaticActive()) {
                "Automático · $backendLabel"
            } else {
                backendLabel
            }
    }

    fun syncUiToActiveModel() {
        val config = ModelConfigRepository.snapshot()
        if (config.isLocalActive()) {
            val modelPath = config.local.modelPath
            if (modelPath.isNullOrBlank()) {
                uiState.modelStatus.value = "No model selected"
                setButtonsEnabled(false)
                return
            }
            if (loadedModelPath == modelPath && isModelReady && cloudClient == null) {
                updateLocalModelStatus(modelPath)
                setButtonsEnabled(true)
                return
            }
            loadModelIfReady()
            return
        }

        val cloud = config.activeCloud
        if (!cloud.isConfigured) {
            uiState.modelStatus.value = "No model selected"
            setButtonsEnabled(false)
            return
        }
        if (conversation != null) {
            try {
                conversation?.close()
            } catch (_: Exception) {
            }
            conversation = null
            isModelReady = false
        }
        loadedModelPath = null
        if (cloudClient == null || cloudModelName != cloud.modelName || !isModelReady) {
            loadModelIfReady()
            return
        }
        uiState.modelStatus.value = "● ${cloud.modelName} · " +
            if (config.isAutomaticActive()) "Automático · Cloud" else "Cloud"
        setButtonsEnabled(true)
    }

    private fun isLocalUiStillExpected(modelPath: String, generation: Long): Boolean {
        val config = ModelConfigRepository.snapshot()
        return generation == localUiGeneration &&
            config.isLocalActive() &&
            config.local.modelPath == modelPath
    }

    private fun localModelTag(modelPath: String): String {
        val baseName = modelPath.takeIf { it.isNotEmpty() }?.let { File(it).nameWithoutExtension } ?: "Local"
        val backendLabel = LocalModelRuntime.currentBackendLabel(modelPath)
        return if (backendLabel.isNullOrBlank() || backendLabel.equals("GPU", ignoreCase = true)) {
            baseName
        } else {
            "$baseName ($backendLabel)"
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        uiState.inputEnabled.value = enabled
    }

    private fun postToMain(action: () -> Unit) {
        activity.runOnUiThread(action)
    }
}
