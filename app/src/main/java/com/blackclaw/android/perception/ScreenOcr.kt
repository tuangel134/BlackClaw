package com.blackclaw.android.perception

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.blackclaw.android.utils.XLog
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.Normalizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device OCR via ML Kit's Latin text recognizer (offline, ~10 MB bundled).
 *
 * For non-Latin scripts there are separate models (Chinese, Korean, Devanagari,
 * Japanese). We only bundle Latin to keep the APK manageable; if a user really
 * needs CJK we can ship a `:text-recognition-chinese` flavor later.
 */
object ScreenOcr {
    private const val TAG = "ScreenOcr"

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    data class TextBlock(
        val text: String,
        val bounds: Rect,
    ) {
        fun centerX(): Int = (bounds.left + bounds.right) / 2
        fun centerY(): Int = (bounds.top + bounds.bottom) / 2
    }

    /** Runs OCR synchronously (blocks the calling thread up to 5s). */
    fun recognize(bitmap: Bitmap, timeoutMs: Long = 5_000L): List<TextBlock> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val latch = CountDownLatch(1)
        var blocks: List<TextBlock> = emptyList()
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val out = mutableListOf<TextBlock>()
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val rect = line.boundingBox ?: continue
                        val text = line.text.trim()
                        if (text.isNotBlank()) {
                            out.add(TextBlock(text, rect))
                        }
                    }
                }
                blocks = out
                latch.countDown()
            }
            .addOnFailureListener { e ->
                XLog.w(TAG, "OCR failed: ${e.message}")
                latch.countDown()
            }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return blocks
    }

    /**
     * OCR tuned for a live phone screen, not a document image.
     *
     * Flat UI themes, translucent overlays and video/game surfaces often make a
     * one-pass OCR miss short labels.  We first preserve the fast original pass;
     * only when it looks sparse do we retry with a high-contrast grayscale frame
     * and merge the non-duplicate results. Bounds remain in the original capture
     * coordinate system, so [TapOcrTool] can still tap them safely.
     */
    fun recognizeScreen(bitmap: Bitmap): List<TextBlock> {
        val primary = recognize(bitmap, timeoutMs = 4_000L)
        if (!needsRecovery(primary)) return primary.sortedReadingOrder()

        val enhanced = enhanceForScreen(bitmap)
        if (enhanced === bitmap) return primary.sortedReadingOrder()
        return try {
            merge(primary, recognize(enhanced, timeoutMs = 4_000L)).sortedReadingOrder()
        } finally {
            if (!enhanced.isRecycled) enhanced.recycle()
        }
    }

    /** Returns the strongest exact, substring, or token-level match for [needle]. */
    fun findBlock(blocks: List<TextBlock>, needle: String): TextBlock? {
        val n = normalized(needle)
        if (n.isEmpty()) return null
        return blocks.maxByOrNull { block -> matchScore(normalized(block.text), n) }
            ?.takeIf { matchScore(normalized(it.text), n) > 0 }
    }

    /** Text in visual reading order, appropriate for speaking or summarising. */
    fun readingOrder(blocks: List<TextBlock>, limit: Int = 80): List<String> =
        blocks.sortedReadingOrder().take(limit).map { it.text }

    /** Compact text representation for the agent prompt. */
    fun formatBlocks(blocks: List<TextBlock>, limit: Int = 80): String {
        if (blocks.isEmpty()) return "(no text detected)"
        val out = StringBuilder()
        for ((i, b) in blocks.sortedReadingOrder().withIndex()) {
            if (i >= limit) {
                out.append("…(+${blocks.size - i} más)")
                break
            }
            out.append("[${b.bounds.left},${b.bounds.top}-${b.bounds.right},${b.bounds.bottom}] ")
                .append(b.text)
                .append('\n')
        }
        return out.toString()
    }

    private fun needsRecovery(blocks: List<TextBlock>): Boolean =
        blocks.size < 3 || blocks.sumOf { it.text.length } < 32

    private fun enhanceForScreen(source: Bitmap): Bitmap {
        val out = runCatching {
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return source
        return try {
            val contrast = 1.85f
            val translate = (-0.5f * contrast + 0.5f) * 255f
            val matrix = ColorMatrix().apply {
                setSaturation(0f)
                postConcat(ColorMatrix(floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f,
                )))
            }
            Canvas(out).drawBitmap(source, 0f, 0f, Paint().apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            })
            out
        } catch (_: Exception) {
            out.recycle()
            source
        }
    }

    private fun merge(primary: List<TextBlock>, recovered: List<TextBlock>): List<TextBlock> {
        val merged = primary.toMutableList()
        recovered.forEach { candidate ->
            val duplicate = merged.any { existing ->
                val sameText = normalized(existing.text) == normalized(candidate.text)
                sameText && overlap(existing.bounds, candidate.bounds) >= 0.45f
            }
            if (!duplicate) merged += candidate
        }
        return merged
    }

    private fun overlap(a: Rect, b: Rect): Float {
        val width = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val height = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        val intersection = width * height
        val smallest = minOf(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1)
        return intersection.toFloat() / smallest
    }

    private fun List<TextBlock>.sortedReadingOrder(): List<TextBlock> {
        if (isEmpty()) return this
        val medianHeight = map { it.bounds.height() }.sorted()[size / 2].coerceAtLeast(8)
        val rowTolerance = (medianHeight * 0.7f).coerceAtLeast(10f)
        val rows = mutableListOf<MutableList<TextBlock>>()
        sortedBy { it.centerY() }.forEach { block ->
            val row = rows.lastOrNull()
            if (row == null || block.centerY() - row.first().centerY() > rowTolerance) {
                rows += mutableListOf(block)
            } else {
                row += block
            }
        }
        return rows.flatMap { it.sortedBy { block -> block.bounds.left } }
    }

    private fun matchScore(text: String, needle: String): Int = when {
        text == needle -> 1_000
        text.startsWith(needle) -> 800 + needle.length
        text.contains(needle) -> 600 + needle.length
        else -> {
            val wanted = needle.split(' ').filter { it.length >= 2 }.toSet()
            val found = text.split(' ').toSet()
            val shared = wanted.intersect(found).size
            if (shared > 0 && shared * 2 >= wanted.size) shared * 100 else 0
        }
    }

    private fun normalized(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}
