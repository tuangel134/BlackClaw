package com.blackclaw.android.perception

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.utils.XLog

/**
 * OCR over an arbitrary image (gallery photo, screenshot, downloaded file),
 * tuned for offline document/photo reading with ML Kit.
 *
 * Improvements over a naive single pass:
 *  - EXIF-correct orientation (phone photos are often rotated).
 *  - Upscales tiny images so small text is legible to the recognizer.
 *  - Reconstructs READING ORDER from line bounding boxes (top→bottom, left→
 *    right, grouped into rows) instead of trusting ML Kit's raw block order.
 *  - A second contrast/grayscale-enhanced pass for faint receipts/low-light
 *    photos, keeping whichever pass read more text.
 */
object ImageOcr {
    private const val TAG = "ImageOcr"
    private const val MAX_DIM = 2200      // cap huge photos (speed/memory)
    private const val MIN_DIM = 1000      // upscale tiny images for small text

    fun recognizeUri(uri: Uri): String {
        val bmp = loadBitmap(uri) ?: return ""
        return recognizeBitmap(bmp)
    }

    fun recognizeFile(path: String): String {
        val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return ""
        val oriented = applyExif(bmp, exifFromFile(path))
        return recognizeBitmap(normalize(oriented))
    }

    /**
     * Two-pass recognition: plain + enhanced; keeps the richer result.
     *
     * MEMORY: both [normalize] and [enhance] allocate full-size ARGB_8888 copies
     * (up to 2200x2200x4B ≈ 19 MB each). Neither used to be recycled, and the
     * `plain.length >= 40` fast path returned without freeing `prepared` at all,
     * so a few OCR calls in a row were enough to blow the heap. We recycle every
     * intermediate we created ourselves — but NEVER the caller's [bmp], which the
     * caller still owns (normalize() returns the input unchanged when no scaling
     * is needed, hence the identity checks).
     */
    fun recognizeBitmap(bmp: Bitmap): String {
        val prepared = normalize(bmp)
        try {
            val plain = assemble(ScreenOcr.recognize(prepared, timeoutMs = 8000))
            // If the plain pass already read a healthy amount of text, trust it.
            if (plain.length >= 40) return plain
            // Otherwise retry on a contrast-boosted grayscale copy (faint/low light).
            val boosted = enhance(prepared)
            val enhanced = try {
                assemble(ScreenOcr.recognize(boosted, timeoutMs = 8000))
            } finally {
                // enhance() falls back to returning `src` if the allocation failed.
                if (boosted !== prepared && !boosted.isRecycled) boosted.recycle()
            }
            return if (enhanced.length > plain.length) enhanced else plain
        } finally {
            if (prepared !== bmp && !prepared.isRecycled) prepared.recycle()
        }
    }

    // ── Reading-order reconstruction ──────────────────────────────────────────

    /**
     * Turn unordered OCR line-boxes into human reading order: group lines whose
     * vertical centers fall in the same band (≈ one text row), sort each band
     * left→right, and emit bands top→bottom. Handles scattered/multi-column text
     * far better than ML Kit's native block order.
     */
    private fun assemble(blocks: List<ScreenOcr.TextBlock>): String {
        if (blocks.isEmpty()) return ""
        val heights = blocks.map { it.bounds.height() }.sorted()
        val medianH = heights[heights.size / 2].coerceAtLeast(8)
        val band = (medianH * 0.7f).coerceAtLeast(10f)

        // Bucket by vertical band.
        val sorted = blocks.sortedBy { it.centerY() }
        val rows = ArrayList<MutableList<ScreenOcr.TextBlock>>()
        var currentTop = Int.MIN_VALUE
        for (b in sorted) {
            if (rows.isEmpty() || b.centerY() - currentTop > band) {
                rows.add(mutableListOf(b))
                currentTop = b.centerY()
            } else {
                rows.last().add(b)
            }
        }
        return rows.joinToString("\n") { row ->
            row.sortedBy { it.bounds.left }.joinToString(" ") { it.text }
        }.trim()
    }

    // ── Image loading & preprocessing ─────────────────────────────────────────

    private fun loadBitmap(uri: Uri): Bitmap? = runCatching {
        val ctx = ClawApplication.instance
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies EXIF orientation automatically.
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            normalize(ImageDecoder.decodeBitmap(src) { dec, _, _ ->
                dec.isMutableRequired = false
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            })
        } else {
            @Suppress("DEPRECATION")
            val raw = android.provider.MediaStore.Images.Media.getBitmap(ctx.contentResolver, uri)
            val exif = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
            }.getOrNull()
            normalize(applyExif(raw, exif))
        }
    }.getOrElse { XLog.w(TAG, "loadBitmap failed: ${it.message}"); null }

    private fun exifFromFile(path: String): ExifInterface? =
        runCatching { ExifInterface(path) }.getOrNull()

    private fun applyExif(bmp: Bitmap, exif: ExifInterface?): Bitmap {
        exif ?: return bmp
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return bmp
        }
        return runCatching {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }.getOrDefault(bmp)
    }

    /** Scale into the [MIN_DIM, MAX_DIM] range so text is neither tiny nor huge. */
    private fun normalize(bmp: Bitmap): Bitmap {
        val w = bmp.width; val h = bmp.height
        if (w == 0 || h == 0) return bmp
        val max = maxOf(w, h)
        val scale = when {
            max > MAX_DIM -> MAX_DIM.toFloat() / max
            max < MIN_DIM -> MIN_DIM.toFloat() / max   // upscale small images
            else -> 1f
        }
        if (scale == 1f) return bmp
        return runCatching {
            Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true)
        }.getOrDefault(bmp)
    }

    /** Grayscale + contrast stretch to rescue faint / low-contrast text. */
    private fun enhance(src: Bitmap): Bitmap {
        val out = runCatching {
            Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return src
        val canvas = Canvas(out)
        val contrast = 1.6f                 // >1 boosts contrast
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val cm = ColorMatrix().apply {
            setSaturation(0f)               // grayscale
            postConcat(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }
}
