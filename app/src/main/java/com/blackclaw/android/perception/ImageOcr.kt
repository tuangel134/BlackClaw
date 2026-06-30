package com.blackclaw.android.perception

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.utils.XLog
import java.io.File

/**
 * OCR over an arbitrary image (gallery photo, screenshot, downloaded file).
 * Loads a downscaled Bitmap and runs the shared ML Kit recognizer.
 *
 * Used by the photo-OCR tool ("¿qué dice esta imagen?") and the receipt scanner.
 */
object ImageOcr {
    private const val TAG = "ImageOcr"
    private const val MAX_DIM = 2200  // downscale huge photos for speed/memory

    /** OCR an image given by content/file Uri. Returns the recognized lines joined. */
    fun recognizeUri(uri: Uri): String {
        val bmp = loadBitmap(uri) ?: return ""
        return recognizeBitmap(bmp)
    }

    fun recognizeFile(path: String): String {
        val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return ""
        return recognizeBitmap(downscale(bmp))
    }

    fun recognizeBitmap(bmp: Bitmap): String {
        val blocks = ScreenOcr.recognize(bmp, timeoutMs = 8000)
        return blocks.joinToString("\n") { it.text }.trim()
    }

    private fun loadBitmap(uri: Uri): Bitmap? = runCatching {
        val ctx = ClawApplication.instance
        val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { dec, _, _ ->
                dec.isMutableRequired = false
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            android.provider.MediaStore.Images.Media.getBitmap(ctx.contentResolver, uri)
        }
        downscale(bmp)
    }.getOrElse { XLog.w(TAG, "loadBitmap failed: ${it.message}"); null }

    private fun downscale(bmp: Bitmap): Bitmap {
        val w = bmp.width; val h = bmp.height
        val max = maxOf(w, h)
        if (max <= MAX_DIM) return bmp
        val scale = MAX_DIM.toFloat() / max
        return Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true)
    }
}
