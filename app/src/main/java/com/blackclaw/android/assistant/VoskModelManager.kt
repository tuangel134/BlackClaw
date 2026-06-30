package com.blackclaw.android.assistant

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Manages the offline Vosk speech model used by the wake-word engine.
 *
 * The model ships BUNDLED inside the APK (app/src/main/assets/vosk-model-es.zip)
 * so the user doesn't have to download anything and it works out of the box,
 * offline, with no key/account. On first use we unpack the ~40 MB zip from
 * assets into the app's private storage (Vosk needs a directory path).
 *
 * We use the small Spanish model (vosk-model-small-es-0.42) — light enough for
 * continuous listening on a phone.
 */
object VoskModelManager {

    private const val TAG = "VoskModel"
    private const val ASSET_ZIP = "vosk-model-es.zip"
    private const val MODEL_DIR_NAME = "vosk-model-es"
    private const val KEY_READY = "vosk_model_ready"

    private val worker = Executors.newSingleThreadExecutor()
    private val preparingFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    val preparing: Boolean get() = preparingFlag.get()
    // Kept for API compatibility with the voice tool wording.
    val downloading: Boolean get() = preparingFlag.get()

    /** Root dir that contains the unpacked model (the dir holding conf/, am/, …). */
    fun modelPath(): String {
        val root = File(ClawApplication.instance.filesDir, MODEL_DIR_NAME)
        val nested = root.listFiles()?.firstOrNull { File(it, "conf").isDirectory }
        return (nested ?: root).absolutePath
    }

    fun isReady(): Boolean {
        if (!KVUtils.getBoolean(KEY_READY, false)) return false
        return File(modelPath(), "conf").isDirectory
    }

    /** Is the bundled model asset present in this build? */
    fun isBundled(): Boolean = runCatching {
        ClawApplication.instance.assets.list("")?.contains(ASSET_ZIP) == true
    }.getOrDefault(false)

    /**
     * Ensure the model is unpacked and ready. Extracts from the bundled asset on
     * a background thread. [onProgress] gets 0-100, [onDone] gets success.
     * (Method name kept as `download` so existing callers/tools don't change.)
     */
    fun download(onProgress: (Int) -> Unit = {}, onDone: (Boolean) -> Unit = {}) {
        if (isReady()) { onDone(true); return }
        // Atomic check-and-set so two callers can't both start extraction.
        if (!preparingFlag.compareAndSet(false, true)) return
        worker.submit {
            val ok = runCatching { extractFromAssets(onProgress) }.getOrElse {
                XLog.w(TAG, "Model extract failed: ${it.message}"); false
            }
            if (ok) { KVUtils.putBoolean(KEY_READY, true); KVUtils.sync() }
            preparingFlag.set(false)
            onDone(ok)
        }
    }

    /** Eagerly prepare the model in the background if bundled and not yet ready. */
    fun prepareIfNeeded() {
        if (isReady() || preparing || !isBundled()) return
        download()
    }

    private fun extractFromAssets(onProgress: (Int) -> Unit): Boolean {
        val ctx = ClawApplication.instance
        val root = File(ctx.filesDir, MODEL_DIR_NAME)
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()

        XLog.i(TAG, "Unpacking bundled Vosk model…")
        // Approximate size for progress (small-es model unpacks to ~50MB).
        val approxTotal = 50L * 1024 * 1024
        var written = 0L

        ctx.assets.open(ASSET_ZIP).use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                val buf = ByteArray(64 * 1024)
                while (entry != null) {
                    val outFile = File(root, entry.name)
                    if (!outFile.canonicalPath.startsWith(root.canonicalPath)) {
                        entry = zip.nextEntry; continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out ->
                            var n = zip.read(buf)
                            while (n >= 0) {
                                out.write(buf, 0, n)
                                written += n
                                onProgress(((written * 100) / approxTotal).toInt().coerceIn(0, 99))
                                n = zip.read(buf)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        val ok = File(modelPath(), "conf").isDirectory
        onProgress(100)
        XLog.i(TAG, "Model unpack ${if (ok) "OK" else "INCOMPLETE"} at ${modelPath()}")
        return ok
    }

    fun delete() {
        runCatching {
            File(ClawApplication.instance.filesDir, MODEL_DIR_NAME).deleteRecursively()
            KVUtils.putBoolean(KEY_READY, false); KVUtils.sync()
        }
    }
}
