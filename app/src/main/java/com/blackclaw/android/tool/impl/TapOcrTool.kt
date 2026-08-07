package com.blackclaw.android.tool.impl

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.perception.ScreenCaptureService
import com.blackclaw.android.perception.ScreenCapturePermissionActivity
import com.blackclaw.android.perception.ScreenOcr
import com.blackclaw.android.service.ClawAccessibilityService
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * OCR-based tap. Captures the screen, runs OCR, finds the first text block
 * matching the needle, and dispatches a tap on its center.
 *
 * Critical detail: MediaProjection captures at a downscaled resolution (max
 * 1080 wide). We scale the matched bounding box back up to display coords
 * before tapping, otherwise the tap lands in the wrong spot on high-DPI phones.
 */
class TapOcrTool : BaseTool() {
    override fun getName() = "tap_ocr"
    override fun getDisplayName() = "Pulsar texto (OCR)"
    override fun getDescriptionEN() =
        "Tap on a piece of visible text using OCR. Works in games, videos, " +
        "WebView surfaces — anything regular tap_node can't see. " +
        "First call requires screen-capture consent."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("text", "string",
            "Texto exacto o substring a buscar (case-insensitive).", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val needle = requireString(params, "text").trim()
        if (needle.isEmpty()) return ToolResult.error("text vacío")
        if (!ScreenCaptureService.isRunning()) {
            ScreenCapturePermissionActivity.start(ClawApplication.instance)
            return ToolResult.error(
                "Pidiendo permiso de captura. Espera 2 segundos y reintenta."
            )
        }
        val bmp = ScreenCaptureService.captureBitmap()
            ?: return ToolResult.error("No pude capturar la pantalla.")
        val blocks = ScreenOcr.recognizeScreen(bmp)
        val match = ScreenOcr.findBlock(blocks, needle)
            ?: return ToolResult.error("No encontré '$needle' en pantalla. Texto visible:\n" +
                ScreenOcr.formatBlocks(blocks, limit = 30))

        // Scale OCR bounds (capture-resolution) → real display coords
        val (realW, realH) = realDisplaySize()
        val sx = realW.toFloat() / bmp.width
        val sy = realH.toFloat() / bmp.height
        val cx = (match.centerX() * sx).toInt()
        val cy = (match.centerY() * sy).toInt()

        return performTap(cx, cy, match.text)
    }

    private fun realDisplaySize(): Pair<Int, Int> {
        val ctx = ClawApplication.instance
        val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        return Pair(m.widthPixels, m.heightPixels)
    }

    private fun performTap(x: Int, y: Int, label: String): ToolResult {
        val service = ClawAccessibilityService.getConnectedInstance(2_000L)
            ?: return ToolResult.error("Servicio de accesibilidad no conectado.")
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        val latch = CountDownLatch(1)
        var success = false
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { success = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { latch.countDown() }
        }, null)
        latch.await(2_000L, TimeUnit.MILLISECONDS)
        return if (success) ToolResult.success("Tap OCR en \"${label.take(40)}\" → ($x, $y)")
        else ToolResult.error("El gesto fue cancelado.")
    }
}
