package com.blackclaw.android.assistant

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * Manages offline Vosk speech models for the wake-word engine — now multi-language.
 *
 *  - Spanish (es): ships BUNDLED in the APK (assets/vosk-model-es.zip), unpacked
 *    on first use. Always available, zero download.
 *  - English (en): DOWNLOADABLE on demand (kept out of the APK to stay small).
 *
 * The user picks the recognition language; the engine loads that model and uses
 * the matching wake word ("garra" for ES, "claw" for EN).
 */
object VoskModelManager {

    private const val TAG = "VoskModel"

    enum class Lang(
        val code: String,
        val dirName: String,
        val asset: String?,      // bundled asset zip (null = download only)
        val url: String?,        // download url (null = bundled only)
        val wakeWord: String,
    ) {
        ES("es", "vosk-model-es", "vosk-model-es.zip", null, "garra"),
        EN("en", "vosk-model-en", null,
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", "claw"),
        ;
        companion object {
            fun of(code: String): Lang = entries.firstOrNull { it.code == code } ?: ES
        }
    }

    private const val KEY_ACTIVE_LANG = "vosk_active_lang"
    private fun readyKey(lang: Lang) = "vosk_model_ready_${lang.code}"

    private val worker = Executors.newSingleThreadExecutor()
    private val preparingFlag = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    val preparing: Boolean get() = preparingFlag.get()
    val downloading: Boolean get() = preparingFlag.get()

    /** Recognition language the engine should use. */
    var activeLang: Lang
        get() = Lang.of(KVUtils.getString(KEY_ACTIVE_LANG, "es"))
        set(v) { KVUtils.putString(KEY_ACTIVE_LANG, v.code); KVUtils.sync() }

    // ── Paths / readiness (per language) ──

    fun modelPath(lang: Lang = activeLang): String {
        val root = File(ClawApplication.instance.filesDir, lang.dirName)
        val nested = root.listFiles()?.firstOrNull { File(it, "conf").isDirectory }
        return (nested ?: root).absolutePath
    }

    fun isReady(lang: Lang = activeLang): Boolean {
        if (!KVUtils.getBoolean(readyKey(lang), false)) return false
        return File(modelPath(lang), "conf").isDirectory
    }

    /** Wake word for the active language. */
    fun activeWakeWord(): String = activeLang.wakeWord

    fun isBundled(lang: Lang): Boolean {
        val a = lang.asset ?: return false
        return runCatching { ClawApplication.instance.assets.list("")?.contains(a) == true }
            .getOrDefault(false)
    }

    // ── Preparation (extract bundled OR download) ──

    /** Method name kept as `download` for existing callers; prepares [lang]. */
    fun download(lang: Lang = activeLang, onProgress: (Int) -> Unit = {}, onDone: (Boolean) -> Unit = {}) {
        if (isReady(lang)) { onDone(true); return }
        if (!preparingFlag.compareAndSet(false, true)) return
        worker.submit {
            val ok = runCatching { prepare(lang, onProgress) }.getOrElse {
                XLog.w(TAG, "Prepare ${lang.code} failed: ${it.message}"); false
            }
            if (ok) { KVUtils.putBoolean(readyKey(lang), true); KVUtils.sync() }
            preparingFlag.set(false)
            onDone(ok)
        }
    }

    /** Ensure the bundled Spanish model is unpacked in the background. */
    fun prepareIfNeeded() {
        if (isReady(Lang.ES) || preparing || !isBundled(Lang.ES)) return
        download(Lang.ES)
    }

    fun delete(lang: Lang) {
        runCatching {
            File(ClawApplication.instance.filesDir, lang.dirName).deleteRecursively()
            KVUtils.putBoolean(readyKey(lang), false); KVUtils.sync()
        }
    }

    private fun prepare(lang: Lang, onProgress: (Int) -> Unit): Boolean {
        val root = File(ClawApplication.instance.filesDir, lang.dirName)
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()
        return when {
            lang.asset != null && isBundled(lang) -> extractFromAsset(lang.asset, root, onProgress)
            lang.url != null -> downloadAndExtract(lang.url, root, onProgress)
            else -> false
        }.also { ok ->
            XLog.i(TAG, "Prepare ${lang.code} ${if (ok) "OK" else "FAILED"} at ${modelPath(lang)}")
        }
    }

    private fun extractFromAsset(asset: String, root: File, onProgress: (Int) -> Unit): Boolean {
        val approxTotal = 50L * 1024 * 1024
        var written = 0L
        ClawApplication.instance.assets.open(asset).use { input ->
            ZipInputStream(input.buffered()).use { zip -> unzip(zip, root) { written += it; onProgress(((written * 100) / approxTotal).toInt().coerceIn(0, 99)) } }
        }
        onProgress(100)
        return File(root.listFiles()?.firstOrNull { File(it, "conf").isDirectory } ?: root, "conf").isDirectory
            || File(root, "conf").isDirectory
    }

    private fun downloadAndExtract(url: String, root: File, onProgress: (Int) -> Unit): Boolean {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { XLog.w(TAG, "HTTP ${resp.code}"); return false }
            val body = resp.body ?: return false
            val total = body.contentLength().takeIf { it > 0 } ?: (45L * 1024 * 1024)
            var read = 0L
            ZipInputStream(body.byteStream().buffered()).use { zip ->
                unzip(zip, root) { read += it; onProgress(((read * 100) / total).toInt().coerceIn(0, 99)) }
            }
        }
        onProgress(100)
        return File(root.listFiles()?.firstOrNull { File(it, "conf").isDirectory } ?: root, "conf").isDirectory
            || File(root, "conf").isDirectory
    }

    private inline fun unzip(zip: ZipInputStream, root: File, onBytes: (Long) -> Unit) {
        var entry = zip.nextEntry
        val buf = ByteArray(64 * 1024)
        while (entry != null) {
            val outFile = File(root, entry.name)
            if (!outFile.canonicalPath.startsWith(root.canonicalPath)) {
                zip.closeEntry(); entry = zip.nextEntry; continue
            }
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { out ->
                    var n = zip.read(buf)
                    while (n >= 0) { out.write(buf, 0, n); onBytes(n.toLong()); n = zip.read(buf) }
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
}
