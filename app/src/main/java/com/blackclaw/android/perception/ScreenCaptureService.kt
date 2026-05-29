package com.blackclaw.android.perception

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.blackclaw.android.R
import com.blackclaw.android.utils.XLog
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service that owns a long-lived MediaProjection + VirtualDisplay so
 * we can capture the screen on demand without asking the user every time.
 *
 * The user grants MediaProjection ONCE per session (Android requires it per
 * service start, can't be persisted across reboots).
 *
 * Public API: [capture] returns the most recent frame as Bitmap or null.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "blackclaw_screen_capture"
        private const val NOTIF_ID = 9134
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        @Volatile
        private var instance: ScreenCaptureService? = null

        fun isRunning(): Boolean = instance != null

        /** Returns the current PNG bitmap (max 1 frame old) or null. Thread-safe. */
        fun captureBitmap(): Bitmap? = instance?.capture()

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val latestBitmap = AtomicReference<Bitmap?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            XLog.i(TAG, "MediaProjection stopped externally")
            cleanup()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (intent != null && intent.hasExtra(EXTRA_RESULT_CODE)) {
            val rc = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
            if (data != null) initProjection(rc, data)
        }
        instance = this
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Captura de pantalla",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "BlackClaw está capturando la pantalla para la IA" }
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("BlackClaw")
            .setContentText("Captura de pantalla activa")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun initProjection(resultCode: Int, data: Intent) {
        cleanup()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data) ?: run {
            XLog.e(TAG, "getMediaProjection returned null")
            stopSelf()
            return
        }
        projection!!.registerCallback(projectionCallback, mainHandler)

        val (w, h, dpi) = displaySize()
        // Cap image reader size to avoid OOM on very high-res phones.
        // Game/UI text is still legible at 1080p downscale.
        val targetW = if (w > 1080) 1080 else w
        val targetH = (h.toLong() * targetW / w).toInt()

        imageReader = ImageReader.newInstance(targetW, targetH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection!!.createVirtualDisplay(
            "blackclaw-capture",
            targetW, targetH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, mainHandler,
        )
        XLog.i(TAG, "MediaProjection ready ($targetW x $targetH @ $dpi dpi)")
    }

    @Suppress("DEPRECATION")
    private fun displaySize(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        return Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    /** Pulls the latest frame from the ImageReader and converts it to a Bitmap. */
    private fun capture(): Bitmap? {
        val reader = imageReader ?: return null
        val img: Image = try {
            reader.acquireLatestImage() ?: return latestBitmap.get()
        } catch (e: IllegalStateException) {
            XLog.w(TAG, "acquireLatestImage threw: ${e.message}")
            return latestBitmap.get()
        }
        val bmp = try {
            val planes = img.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * img.width
            val output = Bitmap.createBitmap(
                img.width + rowPadding / pixelStride,
                img.height,
                Bitmap.Config.ARGB_8888,
            )
            output.copyPixelsFromBuffer(buffer)
            // Crop padding columns added by stride alignment
            if (rowPadding > 0) {
                Bitmap.createBitmap(output, 0, 0, img.width, img.height)
            } else output
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to convert Image to Bitmap", e)
            null
        } finally {
            img.close()
        }
        if (bmp != null) latestBitmap.set(bmp)
        return bmp ?: latestBitmap.get()
    }

    private fun cleanup() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        projection = null
        latestBitmap.set(null)
    }

    override fun onDestroy() {
        cleanup()
        instance = null
        super.onDestroy()
    }
}
