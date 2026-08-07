package com.blackclaw.android.perception

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Short-lived pixel text captured while another app is still foreground.
 *
 * It is fed only from accessibility events and only after the user has already
 * enabled MediaProjection. We retain recognised text for a few seconds, never
 * the bitmap, so Quick Assist can combine Canvas/WebView/game text with the
 * accessibility snapshot taken before its full-screen panel appears.
 */
object ExternalScreenOcrCache {
    private const val MIN_CAPTURE_INTERVAL_MS = 1_200L
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "external-screen-ocr").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    @Volatile private var lastRequestedAtMs = 0L
    @Volatile private var lastCapturedAtMs = 0L
    @Volatile private var latestLines: List<String> = emptyList()

    @JvmStatic
    fun captureIfAvailable() {
        if (!ScreenCaptureService.isRunning() || !inFlight.compareAndSet(false, true)) return
        val now = System.currentTimeMillis()
        if (now - lastRequestedAtMs < MIN_CAPTURE_INTERVAL_MS) {
            inFlight.set(false)
            return
        }
        lastRequestedAtMs = now
        val copy = ScreenCaptureService.captureBitmapCopy()
        if (copy == null) {
            inFlight.set(false)
            return
        }
        executor.execute {
            try {
                val lines = ScreenOcr.readingOrder(ScreenOcr.recognizeScreen(copy), limit = 60)
                if (lines.isNotEmpty()) {
                    latestLines = lines
                    lastCapturedAtMs = now
                }
            } finally {
                if (!copy.isRecycled) copy.recycle()
                inFlight.set(false)
            }
        }
    }

    @JvmStatic
    fun recentLines(maxAgeMs: Long): List<String> =
        if (System.currentTimeMillis() - lastCapturedAtMs <= maxAgeMs) latestLines else emptyList()

    @JvmStatic
    fun clear() {
        latestLines = emptyList()
        lastCapturedAtMs = 0L
    }
}
