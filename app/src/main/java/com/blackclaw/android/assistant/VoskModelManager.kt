package com.blackclaw.android.assistant

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Downloads and manages the offline Vosk speech model used by the wake-word
 * engine. Kept OUT of the APK (it's ~40 MB) and fetched once at runtime to the
 * app's private storage. After download, everything runs 100% offline — no key,
 * no account, no audio ever leaves the device.
 *
 * We use the small Spanish model (vosk-model-small-es-0.42) — light enough for
 * continuous listening on a phone.
 */
object VoskModelManager {

    private const val TAG = "VoskModel"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
    private const val MODEL_DIR_NAME = "vosk-model-es"
    private const val KEY_READY = "vosk_model_ready"

    private val worker = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile var downloading = false
        private set

    /** Root dir that contains the unpacked model (the dir holding conf/, am/, …). */
    fun modelPath(): String {
        val root = File(ClawApplication.instance.filesDir, MODEL_DIR_NAME)
        // The zip unpacks into a nested folder; find the one with a "conf" dir.
        val nested = root.listFiles()?.firstOrNull { File(it, "conf").isDirectory }
        return (nested ?: root).absolutePath
    }

    fun isReady(): Boolean {
        if (!KVUtils.getBoolean(KEY_READY, false)) return false
        return File(modelPath(), "conf").isDirectory
    }

    /**
     * Download + unzip the model on a background thread.
     * [onProgress] gets 0-100 (or -1 on indeterminate), [onDone] gets success.
     */
    fun download(onProgress: (Int) -> Unit = {}, onDone: (Boolean) -> Unit = {}) {
        if (downloading) return
        if (isReady()) { onDone(true); return }
        downloading = true
        worker.submit {
            val ok = runCatching { doDownload(onProgress) }.getOrElse {
                XLog.w(TAG, "Model download failed: ${it.message}"); false
            }
            if (ok) {
                KVUtils.putBoolean(KEY_READY, true); KVUtils.sync()
            }
            downloading = false
            onDone(ok)
        }
    }

    private fun doDownload(onProgress: (Int) -> Unit): Boolean {
        val root = File(ClawApplication.instance.filesDir, MODEL_DIR_NAME)
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()

        XLog.i(TAG, "Downloading Vosk model…")
        val req = Request.Builder().url(MODEL_URL).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { XLog.w(TAG, "HTTP ${resp.code}"); return false }
            val body = resp.body ?: return false
            val total = body.contentLength()
            var read = 0L
            ZipInputStream(body.byteStream()).use { zip ->
                var entry = zip.nextEntry
                val buf = ByteArray(64 * 1024)
                while (entry != null) {
                    val outFile = File(root, entry.name)
                    // Zip-slip guard
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
                                read += n
                                if (total > 0) onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
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
        XLog.i(TAG, "Model download ${if (ok) "OK" else "INCOMPLETE"} at ${modelPath()}")
        return ok
    }

    fun delete() {
        runCatching {
            File(ClawApplication.instance.filesDir, MODEL_DIR_NAME).deleteRecursively()
            KVUtils.putBoolean(KEY_READY, false); KVUtils.sync()
        }
    }
}
