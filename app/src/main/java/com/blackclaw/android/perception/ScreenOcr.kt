package com.blackclaw.android.perception

import android.graphics.Bitmap
import android.graphics.Rect
import com.blackclaw.android.utils.XLog
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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

    /** Returns the first block whose text contains [needle] (case-insensitive). */
    fun findBlock(blocks: List<TextBlock>, needle: String): TextBlock? {
        val n = needle.lowercase().trim()
        if (n.isEmpty()) return null
        return blocks.firstOrNull { it.text.lowercase().contains(n) }
    }

    /** Compact text representation for the agent prompt. */
    fun formatBlocks(blocks: List<TextBlock>, limit: Int = 80): String {
        if (blocks.isEmpty()) return "(no text detected)"
        val out = StringBuilder()
        for ((i, b) in blocks.withIndex()) {
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
}
