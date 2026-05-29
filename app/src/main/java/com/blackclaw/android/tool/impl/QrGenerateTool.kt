package com.blackclaw.android.tool.impl

import android.graphics.Bitmap
import android.graphics.Color
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream

/**
 * Generate a QR code PNG for the given content. Saves it to the app cache and
 * returns the absolute path so the agent can attach it via send_file or share_text.
 */
class QrGenerateTool : BaseTool() {
    override fun getName() = "qr_generate"
    override fun getDisplayName() = "Generar QR"
    override fun getDescriptionEN() =
        "Generate a QR code PNG with the given text/URL/wifi-config. Saves to cache and " +
        "returns the file path. Combine with send_file or share_text to deliver."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("content", "string", "Text or URL to encode.", true),
        ToolParameter("size", "integer", "QR pixel size (200..1024). Default 512.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val content = requireString(params, "content")
        if (content.isEmpty()) return ToolResult.error("content cannot be empty")
        val size = optionalInt(params, "size", 512).coerceIn(200, 1024)
        return try {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            val dir = File(ClawApplication.instance.cacheDir, "qr").apply { mkdirs() }
            val file = File(dir, "qr-${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ToolResult.success("QR generado: ${file.absolutePath}")
        } catch (e: Exception) {
            ToolResult.error("Fallo generando QR: ${e.message}")
        }
    }
}
