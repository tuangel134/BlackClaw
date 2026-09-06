package com.blackclaw.android.agent.llm

import com.blackclaw.android.utils.XLog
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig

/**
 * Process-wide singleton that keeps a single LiteRT-LM Engine alive across
 * the chat UI and the task agent.
 *
 * Why: Engine initialisation on CPU backend takes 2-3 s. Without this,
 * ComposeChatActivity closes its engine before a task, TaskOrchestrator opens a
 * new one, then after the task chat reloads again — 4-6 s wasted per round trip.
 *
 * Text-only and multimodal .litertlm bundles are both supported. LiteRT-LM
 * requires visionBackend to be null for bundles that do not contain a vision
 * encoder. Community "uncensored" conversions commonly strip vision/audio to
 * save several gigabytes, so blindly enabling vision makes engine init fail with
 * TF_LITE_VISION_ENCODER not found. We probe once and transparently retry the
 * same compute backend as text-only when the model reports that condition.
 *
 * Thread safety: all mutations are @Synchronized so chat executor and task
 * executor threads can both call getOrCreate() safely.
 */
object EngineHolder {

    private const val TAG = "EngineHolder"

    private var engine: Engine? = null
    private var currentModelPath: String? = null
    private var currentBackendLabel: String? = null
    private var currentVisionEnabled: Boolean? = null

    private fun backendLabel(backend: Backend): String =
        if (backend is Backend.CPU) "CPU" else if (backend is Backend.GPU) "GPU" else backend.javaClass.simpleName

    /**
     * Return the existing Engine if the model path matches, otherwise close the
     * old one and create a fresh Engine for the new model.
     *
     * @param modelPath absolute path to the .litertlm model file
     * @param cacheDir app's cacheDir.path — passed in so this object stays
     * context-free and easier to unit-test
     */
    @Synchronized
    @JvmOverloads
    fun getOrCreate(modelPath: String, cacheDir: String, backend: Backend = Backend.CPU()): Engine {
        val existing = engine
        val requestedBackendLabel = backendLabel(backend)
        if (existing != null && currentModelPath == modelPath && currentBackendLabel == requestedBackendLabel) {
            XLog.d(TAG, "getOrCreate: reusing engine for $modelPath (${currentBackendLabel ?: "unknown"}, vision=${currentVisionEnabled ?: false})")
            return existing
        }

        if (existing != null) {
            XLog.i(
                TAG,
                "getOrCreate: runtime changed (model=$currentModelPath/${currentBackendLabel ?: "?"} -> $modelPath/$requestedBackendLabel), closing old engine"
            )
            try {
                existing.close()
            } catch (e: Exception) {
                XLog.w(TAG, "getOrCreate: error closing old engine", e)
            }
            engine = null
            currentModelPath = null
            currentBackendLabel = null
            currentVisionEnabled = null
        }

        val catalogVision = LocalModelManager.visionSupportForPath(modelPath)
        val tryVisionFirst = catalogVision != LocalModelManager.VisionSupport.NO
        XLog.i(
            TAG,
            "getOrCreate: creating new engine for $modelPath with $requestedBackendLabel (vision=${if (tryVisionFirst) "probe" else "disabled"})"
        )

        if (backend is Backend.GPU) {
            LocalBackendHealth.markGpuInitStarted(modelPath)
        }

        return try {
            val created = try {
                createInitializedEngine(
                    modelPath = modelPath,
                    cacheDir = cacheDir,
                    backend = backend,
                    enableVision = tryVisionFirst,
                ) to tryVisionFirst
            } catch (visionError: Exception) {
                if (!tryVisionFirst || !isMissingVisionEncoder(visionError)) throw visionError

                XLog.w(
                    TAG,
                    "Model ${modelPath.substringAfterLast('/')} has no LiteRT-LM vision encoder; retrying text-only on $requestedBackendLabel"
                )
                createInitializedEngine(
                    modelPath = modelPath,
                    cacheDir = cacheDir,
                    backend = backend,
                    enableVision = false,
                ) to false
            }

            if (backend is Backend.GPU) {
                LocalBackendHealth.markGpuInitFinished()
                LocalBackendHealth.noteGpuInitSuccess(modelPath)
            }
            engine = created.first
            currentModelPath = modelPath
            currentBackendLabel = requestedBackendLabel
            currentVisionEnabled = created.second
            XLog.i(
                TAG,
                "getOrCreate: engine ready for $modelPath ($currentBackendLabel, vision=${created.second})"
            )
            created.first
        } catch (e: Exception) {
            if (backend is Backend.GPU) {
                LocalBackendHealth.noteRecoverableGpuFailure(modelPath, e)
            } else {
                LocalBackendHealth.markGpuInitFinished()
            }
            XLog.e(TAG, "getOrCreate: failed to create engine for $modelPath", e)
            throw e
        }
    }

    private fun createInitializedEngine(
        modelPath: String,
        cacheDir: String,
        backend: Backend,
        enableVision: Boolean,
    ): Engine {
        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            visionBackend = if (enableVision) backend else null,
            maxNumTokens = 8192,
            cacheDir = cacheDir,
        )
        val candidate = Engine(engineConfig)
        return try {
            candidate.initialize()
            candidate
        } catch (error: Exception) {
            // initialize() can allocate native state before discovering that an
            // optional executor is absent. Release that partial engine before the
            // text-only retry so two native runtimes never overlap.
            runCatching { candidate.close() }
            throw error
        }
    }

    /**
     * LiteRT-LM reports missing optional multimodal assets through native errors.
     * Keep this deliberately narrow so genuine model corruption still surfaces.
     */
    internal fun isMissingVisionEncoder(error: Throwable?): Boolean {
        var current = error
        repeat(8) {
            val message = current?.message.orEmpty()
            if (message.contains("TF_LITE_VISION_ENCODER", ignoreCase = true) ||
                message.contains("TF_LITE_VISION_ADAPTER", ignoreCase = true) ||
                (message.contains("vision encoder", ignoreCase = true) &&
                    (message.contains("not found", ignoreCase = true) || message.contains("missing", ignoreCase = true)))) {
                return true
            }
            current = current?.cause
        }
        return false
    }

    /**
     * Explicitly close and release the engine. Call only when the model is being
     * unloaded entirely (e.g. user deletes the model file). Normal chat/task
     * transitions should NOT call this — they just close their Conversation objects.
     */
    @Synchronized
    fun close() {
        XLog.i(TAG, "close: releasing engine for $currentModelPath")
        try {
            engine?.close()
        } catch (e: Exception) {
            XLog.w(TAG, "close: error closing engine", e)
        }
        engine = null
        currentModelPath = null
        currentBackendLabel = null
        currentVisionEnabled = null
        XLog.i(TAG, "close: done")
    }

    /** Returns true if an engine is live for the given model path. */
    @Synchronized
    fun isReady(modelPath: String): Boolean = engine != null && currentModelPath == modelPath

    /** Returns the actual backend label of the current shared engine, if any. */
    @Synchronized
    fun getBackendLabel(modelPath: String? = null): String? {
        return if (modelPath == null || currentModelPath == modelPath) currentBackendLabel else null
    }

    /** True when the live engine was successfully initialized with a vision executor. */
    @Synchronized
    fun isVisionEnabled(modelPath: String? = null): Boolean? {
        return if (modelPath == null || currentModelPath == modelPath) currentVisionEnabled else null
    }
}
