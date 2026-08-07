package com.blackclaw.android.knowledge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.blackclaw.android.R
import com.blackclaw.android.ui.splash.SplashActivity
import com.blackclaw.android.utils.XLog
import java.io.File
import java.util.concurrent.Executors

/** Resumable foreground builder for the local ZIM full-content index. */
class ZimIndexService : Service() {
    companion object {
        const val ACTION_START = "com.blackclaw.android.ZIM_INDEX_START"
        const val ACTION_STOP = "com.blackclaw.android.ZIM_INDEX_STOP"
        private const val EXTRA_PATH = "zim_path"
        private const val EXTRA_REBUILD = "rebuild"
        const val CHANNEL_ID = "blackclaw_zim_index"
        const val NOTIFICATION_ID = 9120
        private const val BATCH_SIZE = 20
        private const val INDEXED_CHARS_PER_ARTICLE = 100_000
        private const val TAG = "ZimIndexService"

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var activePath: String? = null
            private set

        fun start(context: Context, file: File, rebuild: Boolean = false): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, ZimIndexService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PATH, file.absolutePath)
                putExtra(EXTRA_REBUILD, rebuild)
            })
            true
        }.getOrDefault(false)

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, ZimIndexService::class.java).setAction(ACTION_STOP)) }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var cancelled = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancelled = true
            if (!isRunning) {
                stopSelf()
                return START_NOT_STICKY
            }
            updateNotification("Pausando índice…", 0, 0, false)
            return START_NOT_STICKY
        }
        val path = intent?.getStringExtra(EXTRA_PATH).orEmpty()
        if (path.isBlank() || isRunning) return START_NOT_STICKY
        val file = File(path)
        if (!file.isFile || !file.extension.equals("zim", true)) {
            stopSelf(); return START_NOT_STICKY
        }
        cancelled = false
        isRunning = true
        activePath = file.absolutePath
        val promoted = runCatching {
            promote("Preparando índice de ${file.name}…", 0, 0)
        }.onFailure { XLog.e(TAG, "Unable to start ZIM index foreground service", it) }.isSuccess
        if (!promoted) {
            isRunning = false
            activePath = null
            stopSelf()
            return START_NOT_STICKY
        }
        executor.execute { buildIndex(file, intent?.getBooleanExtra(EXTRA_REBUILD, false) == true) }
        return START_NOT_STICKY
    }

    private fun buildIndex(file: File, rebuild: Boolean) {
        try {
            if (rebuild) ZimContentIndex.delete(this, file)
            DirectZimReader(file).use { reader ->
                ZimContentIndex.open(this, file, reader.titleEntryCount).use { index ->
                    var status = index.status()
                    updateNotification(statusText(file, status), status.position, status.total, true)
                    while (!cancelled && status.position < status.total) {
                        val articles = ArrayList<DirectZimReader.Article>(BATCH_SIZE)
                        var skipped = 0L
                        val end = (status.position + BATCH_SIZE).coerceAtMost(status.total)
                        var position = status.position
                        while (position < end && !cancelled) {
                            val article = runCatching {
                                reader.readArticleAtTitlePosition(position, INDEXED_CHARS_PER_ARTICLE)
                            }.getOrNull()
                            if (article == null || article.text.isBlank()) skipped++ else articles += article
                            position++
                        }
                        if (position > status.position) index.appendBatch(articles, position, skipped)
                        status = index.status()
                        updateNotification(statusText(file, status), status.position, status.total, true)
                    }
                    status = index.status()
                    val finalText = if (status.complete) {
                        "Índice listo · ${status.indexed} artículos de ${file.name}"
                    } else {
                        "Índice pausado en ${status.percent}% · di “reanuda el índice ZIM”"
                    }
                    updateNotification(finalText, status.position, status.total, false)
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "ZIM indexing failed", e)
            updateNotification("Error al indexar ${file.name}: ${e.message}", 0, 0, false)
        } finally {
            isRunning = false
            activePath = null
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun statusText(file: File, status: ZimContentIndex.Status) =
        "${file.name} · ${status.percent}% · ${status.indexed} artículos indexados"

    private fun promote(text: String, progress: Long, total: Long) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(text, progress, total, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun updateNotification(text: String, progress: Long, total: Long, ongoing: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, progress, total, ongoing))
    }

    private fun buildNotification(text: String, progress: Long, total: Long, ongoing: Boolean): android.app.Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, SplashActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, ZimIndexService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("BlackClaw · Índice ZIM offline")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (total > 0) setProgress(100, ((progress * 100) / total).toInt().coerceIn(0, 100), false)
                else setProgress(0, 0, true)
                if (ongoing) addAction(0, "PAUSAR", stop)
            }.build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Índices ZIM offline", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progreso de creación de índices locales para archivos ZIM"
            }
        )
    }

    override fun onDestroy() {
        cancelled = true
        executor.shutdownNow()
        isRunning = false
        activePath = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
