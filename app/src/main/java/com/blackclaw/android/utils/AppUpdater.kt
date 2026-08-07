package com.blackclaw.android.utils

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * In-app updater that pulls new versions straight from GitHub Releases — no Play
 * Store. On launch it checks the repo's latest release; if the tag is newer than
 * the installed versionName it offers to download the signed APK and launch the
 * installer. Because releases are signed with the same key as the installed app,
 * the update installs in place with no uninstall.
 *
 * Flow: GET /releases/latest → compare tag vs BuildConfig version → pick the
 * arm64 (else universal) asset → DownloadManager → FileProvider install intent.
 */
object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val RELEASES_API =
        "https://api.github.com/repos/tuangel134/BlackClaw/releases/latest"
    private const val RELEASES_PAGE =
        "https://github.com/tuangel134/BlackClaw/releases/latest"
    private const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000 // twice a day
    private const val KEY_LAST_CHECK = "last_update_check"
    private const val APK_NAME = "BlackClaw-update.apk"

    /**
     * One shared daemon worker for update checks.
     *
     * WHY: checkForUpdate() used to call Executors.newSingleThreadExecutor() on
     * every invocation. Each of those executors is never shut down, so its
     * non-daemon core thread stays alive for the whole process — and since the
     * check is triggered from Activity onCreate (plus a manual "check now"
     * button), the count grows with every screen visit. A single daemon thread
     * serialises the checks and never blocks process exit.
     */
    private val CHECK_EXECUTOR = Executors.newSingleThreadExecutor { r ->
        Thread(r, "app-updater").apply { isDaemon = true }
    }

    /**
     * Check for a newer release. Throttled to [CHECK_INTERVAL_MS] unless [force].
     * Runs the network call off the main thread; shows a dialog on the UI thread.
     */
    fun checkForUpdate(activity: Activity, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - KVUtils.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) {
            return
        }
        // WeakReference + liveness re-check: this block spans a network round trip
        // (up to 16 s of connect+read timeouts). A strong capture pins the whole
        // Activity — and its view hierarchy — until the request finishes, and
        // showing a dialog on an Activity the user already left throws
        // BadTokenException. Package info is read through the application context
        // so it does not need the Activity at all.
        val activityRef = java.lang.ref.WeakReference(activity)
        val appContext = activity.applicationContext
        val pkgName = activity.packageName
        CHECK_EXECUTOR.execute {
            try {
                val current = runCatching {
                    appContext.packageManager.getPackageInfo(pkgName, 0).versionName
                }.getOrNull() ?: "0"

                val json = httpGet(RELEASES_API)
                if (json == null) {
                    if (force) onLiveActivity(activityRef) { act ->
                        toast(act, "No pude comprobar actualizaciones.")
                    }
                    return@execute
                }
                val release = JSONObject(json)
                if (release.optBoolean("draft", false)) return@execute

                val tag = release.optString("tag_name")
                    .replaceFirst(Regex("^v"), "")
                    .replaceFirst(Regex("-.*"), "")
                val body = release.optString("body", "")
                val htmlUrl = release.optString("html_url", RELEASES_PAGE)
                val apkUrl = pickApkAsset(release)

                KVUtils.putLong(KEY_LAST_CHECK, now); KVUtils.sync()

                if (tag.isNotBlank() && isNewer(tag, current)) {
                    onLiveActivity(activityRef) { act -> showDialog(act, tag, body, apkUrl, htmlUrl) }
                } else if (force) {
                    onLiveActivity(activityRef) { act ->
                        toast(act, "Ya tienes la última versión (v$current).")
                    }
                }
            } catch (e: Exception) {
                XLog.w(TAG, "update check failed: ${e.message}")
                if (force) onLiveActivity(activityRef) { act ->
                    toast(act, "No pude comprobar actualizaciones.")
                }
            }
        }
    }

    /**
     * Runs [block] on the UI thread only while the Activity is still usable.
     * Both checks matter: the WeakReference can be cleared, and a reachable but
     * finishing/destroyed Activity still rejects window operations.
     */
    private inline fun onLiveActivity(
        ref: java.lang.ref.WeakReference<Activity>,
        crossinline block: (Activity) -> Unit,
    ) {
        val act = ref.get() ?: return
        if (act.isFinishing || act.isDestroyed) return
        act.runOnUiThread {
            if (!act.isFinishing && !act.isDestroyed) block(act)
        }
    }

    /** Pick the arm64 APK asset, falling back to the universal one. */
    private fun pickApkAsset(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        var universal: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name").lowercase()
            if (!name.endsWith(".apk")) continue
            val url = a.optString("browser_download_url")
            if (name.contains("arm64")) return url
            if (name.contains("universal")) universal = url
        }
        return universal
    }

    private fun httpGet(urlStr: String): String? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "BlackClaw-App")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode != 200) {
                XLog.w(TAG, "GitHub API ${conn.responseCode}")
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            XLog.w(TAG, "httpGet failed: ${e.message}")
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** Semantic compare — true when remote > local (e.g. 1.0.1 > 1.0.0). */
    private fun isNewer(remote: String, local: String): Boolean {
        return try {
            val r = remote.split(".")
            val l = local.split(".")
            for (i in 0 until maxOf(r.size, l.size)) {
                val rv = r.getOrNull(i)?.toIntOrNull() ?: 0
                val lv = l.getOrNull(i)?.toIntOrNull() ?: 0
                if (rv != lv) return rv > lv
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun showDialog(
        activity: Activity,
        tag: String,
        body: String,
        apkUrl: String?,
        htmlUrl: String,
    ) {
        try {
            val msg = buildString {
                append("BlackClaw v").append(tag).append(" está disponible.\n\n")
                if (body.isNotBlank()) {
                    append(body.trim().take(600))
                    if (body.length > 600) append("…")
                    append("\n\n")
                }
                append(
                    if (apkUrl != null) "¿Descargar e instalar ahora?"
                    else "¿Abrir la página de descarga?"
                )
            }
            AlertDialog.Builder(activity)
                .setTitle("Actualización disponible")
                .setMessage(msg)
                .setPositiveButton(if (apkUrl != null) "Actualizar" else "Descargar") { _, _ ->
                    if (apkUrl != null) {
                        // On Android 8+ the installer won't launch unless the app
                        // is allowed to install unknown apps. Check first so the
                        // update doesn't silently do nothing after downloading.
                        if (!canInstall(activity)) {
                            toast(activity, "Activa \"Instalar apps desconocidas\" para BlackClaw y vuelve a pulsar Actualizar.")
                            requestInstallPermission(activity)
                        } else {
                            startDownload(activity, apkUrl, tag)
                        }
                    } else {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl)))
                    }
                }
                .setNegativeButton("Después", null)
                .show()
        } catch (e: Exception) {
            XLog.w(TAG, "showDialog failed: ${e.message}")
        }
    }

    /** Download the APK via DownloadManager and launch the installer on completion. */
    private fun startDownload(activity: Activity, apkUrl: String, tag: String) {
        try {
            val app = activity.applicationContext
            val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            File(dir, APK_NAME).takeIf { it.exists() }?.delete()

            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("BlackClaw v$tag")
                .setDescription("Descargando la actualización…")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setMimeType("application/vnd.android.package-archive")
                .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
            val id = dm.enqueue(req)
            toast(activity, "Descargando la actualización…")

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                    runCatching { ctx.applicationContext.unregisterReceiver(this) }
                    val apk = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)
                    if (apk.exists() && apk.length() > 0) install(app, apk)
                    else toast(app, "La descarga falló. Intenta desde la página de releases.")
                }
            }
            ContextCompat.registerReceiver(
                app,
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED,
            )
        } catch (e: Exception) {
            XLog.w(TAG, "startDownload failed: ${e.message}")
            toast(activity, "No pude iniciar la descarga.")
        }
    }

    /** Whether the app may launch the package installer (Android 8+ gate). */
    private fun canInstall(ctx: Context): Boolean =
        runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** Send the user to the "install unknown apps" screen for BlackClaw. */
    private fun requestInstallPermission(activity: Activity) {
        runCatching {
            activity.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                ),
            )
        }
    }

    /** Launch the package installer for [apk] through the app's FileProvider. */
    private fun install(ctx: Context, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            XLog.w(TAG, "install failed: ${e.message}")
        }
    }

    private fun toast(ctx: Context, text: String) {
        runCatching { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }
    }
}
