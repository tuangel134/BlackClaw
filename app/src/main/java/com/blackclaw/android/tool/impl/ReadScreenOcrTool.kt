package com.blackclaw.android.tool.impl

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.perception.ScreenCaptureService
import com.blackclaw.android.perception.ScreenCapturePermissionActivity
import com.blackclaw.android.perception.ScreenOcr
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Reads visible text from the screen using MediaProjection + on-device OCR.
 *
 * Why this exists: AccessibilityService can't see anything drawn on a Surface
 * (games, video, WebView with custom rendering, drawing apps). OCR fills that gap.
 *
 * If the screen-capture service isn't running yet, we kick off the consent
 * activity and tell the agent to retry in a couple of seconds.
 */
class ReadScreenOcrTool : BaseTool() {
    override fun getName() = "read_screen_ocr"
    override fun getDisplayName() = "Leer pantalla (OCR)"
    override fun getDescriptionEN() =
        "Read all visible text on screen using OCR (works with games, videos, " +
        "WebView surfaces — anything accessibility can't see). " +
        "First call requires the user to grant screen-capture once. " +
        "Returns a list of text blocks with their on-screen bounds."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters() = emptyList<ToolParameter>()

    override fun execute(params: Map<String, Any>): ToolResult {
        if (!ScreenCaptureService.isRunning()) {
            ScreenCapturePermissionActivity.start(ClawApplication.instance)
            return ToolResult.error(
                "Pidiendo permiso de captura de pantalla. Espera 2 segundos y vuelve a llamar a esta herramienta."
            )
        }
        val bmp = ScreenCaptureService.captureBitmap()
            ?: return ToolResult.error("No pude capturar la pantalla (todavía no hay frames).")
        val blocks = ScreenOcr.recognizeScreen(bmp)
        val ordered = ScreenOcr.readingOrder(blocks, limit = 40)
        return ToolResult.success(
            "Resolución captura: ${bmp.width}x${bmp.height}\n" +
            "Texto en orden de lectura:\n" +
            ordered.joinToString("\n") { "• $it" } + "\n\n" +
            ScreenOcr.formatBlocks(blocks)
        )
    }
}
