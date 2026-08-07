package com.blackclaw.android.ui.assist

import android.app.assist.AssistStructure
import android.graphics.Bitmap
import com.blackclaw.android.perception.ScreenOcr
import com.blackclaw.android.utils.XLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The short-lived context Android hands to a default assistant invocation.
 *
 * It deliberately keeps only extracted text and never persists the screenshot or
 * [AssistStructure].  That makes the context useful to QuickAssist after its own
 * activity takes focus, without turning screen contents into app data.
 */
object AssistInvocationContext {
    private const val TAG = "AssistContext"
    private const val MAX_AGE_MS = 30_000L
    private const val MAX_TREE_NODES = 180

    data class Snapshot(
        val packageName: String?,
        val accessibilityTree: String?,
        val ocrLines: List<String>,
        val capturedAt: Long,
    )

    private data class MutableSnapshot(
        val invocationId: Long,
        val packageName: String?,
        val accessibilityTree: String?,
        val ocrLines: List<String>,
        val capturedAt: Long,
    )

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "assist-context-ocr").apply { isDaemon = true }
    }
    private val nextInvocationId = AtomicLong(0L)

    @Volatile private var latest: MutableSnapshot? = null

    /** Start a new system-assistant invocation and discard unrelated prior text. */
    @JvmStatic
    fun beginInvocation(): Long {
        val invocationId = nextInvocationId.incrementAndGet()
        latest = MutableSnapshot(invocationId, null, null, emptyList(), System.currentTimeMillis())
        return invocationId
    }

    @JvmStatic
    fun recordStructure(invocationId: Long, structure: AssistStructure?) {
        if (structure == null) return
        val tree = runCatching { flattenStructure(structure) }
            .onFailure { XLog.w(TAG, "Could not read AssistStructure: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        val packageName = structure.activityComponent?.packageName
        synchronized(this) {
            val current = latest ?: return
            if (current.invocationId != invocationId) return
            latest = current.copy(
                packageName = packageName ?: current.packageName,
                accessibilityTree = tree ?: current.accessibilityTree,
                capturedAt = System.currentTimeMillis(),
            )
        }
    }

    /** OCR is intentionally asynchronous so a large screenshot never blocks the assistant UI. */
    @JvmStatic
    fun recordScreenshot(invocationId: Long, screenshot: Bitmap?) {
        if (screenshot == null || screenshot.isRecycled) return
        val stableCopy = runCatching { screenshot.copy(screenshot.config ?: Bitmap.Config.ARGB_8888, false) }
            .onFailure { XLog.w(TAG, "Could not copy assistant screenshot: ${it.message}") }
            .getOrNull() ?: return
        executor.execute {
            try {
                val lines = ScreenOcr.readingOrder(ScreenOcr.recognizeScreen(stableCopy), limit = 80)
                synchronized(this) {
                    val current = latest ?: return@synchronized
                    if (current.invocationId != invocationId) return@synchronized
                    latest = current.copy(ocrLines = lines, capturedAt = System.currentTimeMillis())
                }
            } catch (error: Exception) {
                XLog.w(TAG, "Assistant screenshot OCR failed: ${error.message}")
            } finally {
                if (!stableCopy.isRecycled) stableCopy.recycle()
            }
        }
    }

    @JvmStatic
    fun recent(maxAgeMs: Long = MAX_AGE_MS): Snapshot? {
        val current = latest ?: return null
        if (System.currentTimeMillis() - current.capturedAt > maxAgeMs) return null
        if (current.accessibilityTree.isNullOrBlank() && current.ocrLines.isEmpty()) return null
        return Snapshot(
            packageName = current.packageName,
            accessibilityTree = current.accessibilityTree,
            ocrLines = current.ocrLines,
            capturedAt = current.capturedAt,
        )
    }

    private fun flattenStructure(structure: AssistStructure): String = buildString {
        var index = 1
        fun appendNode(node: AssistStructure.ViewNode) {
            if (index <= MAX_TREE_NODES) {
                val label = sequenceOf(
                    node.text?.toString(),
                    node.contentDescription?.toString(),
                    node.hint,
                ).map { it?.trim().orEmpty() }
                    .firstOrNull { it.isNotEmpty() }
                if (!label.isNullOrEmpty()) {
                    append("[n").append(index++).append("] \"")
                    append(label.replace("\"", "'").replace('\n', ' ').take(240))
                    append("\" [").append(node.left).append(',').append(node.top)
                    append(" - ").append(node.left + node.width).append(',').append(node.top + node.height)
                    append(']')
                    if (node.isClickable) append(" clickable")
                    if (node.isFocusable) append(" focusable")
                    append('\n')
                }
            }
            for (childIndex in 0 until node.childCount) {
                if (index > MAX_TREE_NODES) return
                appendNode(node.getChildAt(childIndex))
            }
        }
        for (windowIndex in 0 until structure.windowNodeCount) {
            if (index > MAX_TREE_NODES) break
            appendNode(structure.getWindowNodeAt(windowIndex).rootViewNode)
        }
    }
}
