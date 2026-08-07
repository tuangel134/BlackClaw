package com.blackclaw.android.agent.llm

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.Locale

/**
 * Finds model files the user has deliberately made visible in shared storage.
 *
 * Android keeps each app's private data sandboxed.  This scanner therefore never
 * probes another app's private directory: it looks only in common user-visible
 * folders such as Downloads and Documents.  A model that is private to another
 * app can still be selected through the system document picker and imported.
 */
object ExternalModelDiscovery {

    private const val MAX_DEPTH = 4
    private const val MAX_FILES_PER_ROOT = 300

    enum class Format(
        val label: String,
        val canRunInBlackClaw: Boolean,
    ) {
        LITERT_LM("LiteRT-LM (.litertlm)", true),
        MEDIAPIPE_TASK("MediaPipe (.task)", false),
        TFLITE("LiteRT / TensorFlow Lite (.tflite)", false),
        GGUF("GGUF", false),
        ONNX("ONNX", false),
        MODEL_BIN("model binary (.bin)", false),
        UNKNOWN("unknown format", false),
    }

    data class ModelFile(
        val file: File,
        val format: Format,
        val source: String,
    ) {
        val isCompatible: Boolean get() = format.canRunInBlackClaw
    }

    /** Classifies a filename without reading it, so it is safe to use in UI/tests. */
    fun classify(fileName: String): Format {
        val normalized = fileName.lowercase(Locale.US)
        return when {
            normalized.endsWith(".litertlm") -> Format.LITERT_LM
            normalized.endsWith(".task") -> Format.MEDIAPIPE_TASK
            normalized.endsWith(".tflite") -> Format.TFLITE
            normalized.endsWith(".gguf") -> Format.GGUF
            normalized.endsWith(".onnx") -> Format.ONNX
            normalized.endsWith(".bin") && looksLikeModelName(normalized) -> Format.MODEL_BIN
            else -> Format.UNKNOWN
        }
    }

    fun isModelCandidate(fileName: String): Boolean = classify(fileName) != Format.UNKNOWN

    /**
     * Scans a bounded set of shared-storage folders.  It is intentionally bounded
     * so pressing refresh cannot crawl a user's whole phone or make the settings
     * screen sluggish.
     */
    fun discoverVisibleModels(context: Context): List<ModelFile> {
        val sharedRoot = Environment.getExternalStorageDirectory()
        val roots = linkedSetOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            File(sharedRoot, "Models"),
            File(sharedRoot, "LLM"),
            File(sharedRoot, "AI"),
            File(sharedRoot, "EdgeGallery"),
            File(sharedRoot, "AI Edge Gallery"),
        )
        val appModelDir = runCatching { LocalModelManager.getModelDir(context).canonicalPath }.getOrNull()
        val found = linkedMapOf<String, ModelFile>()

        roots.filter { it.isDirectory && it.canRead() }.forEach { root ->
            scanDirectory(root, root.name.ifBlank { "Shared storage" }, 0, found, appModelDir)
        }
        return found.values.sortedBy { it.file.name.lowercase(Locale.US) }
    }

    private fun scanDirectory(
        directory: File,
        source: String,
        depth: Int,
        found: MutableMap<String, ModelFile>,
        appModelDir: String?,
    ) {
        if (depth > MAX_DEPTH || found.size >= MAX_FILES_PER_ROOT) return
        val children = runCatching { directory.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
        children.forEach { child ->
            if (found.size >= MAX_FILES_PER_ROOT) return
            if (child.isDirectory) {
                scanDirectory(child, source, depth + 1, found, appModelDir)
            } else if (child.isFile && child.length() > 0L && isModelCandidate(child.name)) {
                val canonicalPath = runCatching { child.canonicalPath }.getOrDefault(child.absolutePath)
                // Managed downloads are already represented by the regular catalog.
                if (canonicalPath.substringBeforeLast('/', "") != appModelDir) {
                    found.putIfAbsent(canonicalPath, ModelFile(child, classify(child.name), source))
                }
            }
        }
    }

    private fun looksLikeModelName(name: String): Boolean = listOf(
        "gemma", "llama", "mistral", "qwen", "phi", "deepseek", "falcon", "vicuna", "mixtral"
    ).any(name::contains)
}
