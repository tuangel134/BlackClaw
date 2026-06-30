package com.blackclaw.android.agent

import com.blackclaw.android.utils.XLog
import org.json.JSONObject

/**
 * Context compaction layer — Headroom-style token reduction applied to tool
 * outputs before they enter the LLM message history.
 *
 * Techniques:
 *  1. JSON envelope minification — drops the verbose {"isSuccess":...,"data":...}
 *     wrapper for successful results, keeping just the payload. Errors keep a
 *     compact "ERR: ..." prefix.
 *  2. Repetitive-list collapsing — accessibility trees of feeds / long lists
 *     contain runs of structurally similar lines. We keep the first few and
 *     last few of a run and replace the middle with a "… N similar rows" marker.
 *  3. Whitespace / blank-line trimming.
 *  4. Reversible — the last full (uncompacted) screen is retained so an
 *     expand_context tool (or the loop) can recover detail if needed.
 *
 * All of this is best-effort and conservative: it never removes node IDs that
 * the model needs to act, and it only collapses when the saving is meaningful.
 */
object ContextCompactor {

    private const val TAG = "ContextCompactor"

    /** Only collapse a run of similar lines when it's at least this long. */
    private const val COLLAPSE_THRESHOLD = 8
    /** Keep this many lines at the head and tail of a collapsed run. */
    private const val KEEP_HEAD = 3
    private const val KEEP_TAIL = 2

    /** Last full screen text, for reversible expansion. */
    @Volatile private var lastFullScreen: String = ""

    fun lastFullScreen(): String = lastFullScreen

    /**
     * Compact a tool-result JSON string for storage in the message history.
     * Returns a leaner string while preserving the information the model needs.
     */
    fun compactToolResult(toolName: String, resultJson: String): String {
        if (resultJson.length < 200) return resultJson  // already small

        return try {
            val obj = JSONObject(resultJson)
            val isSuccess = obj.optBoolean("isSuccess", true)
            if (!isSuccess) {
                val err = obj.optString("error", "")
                return "ERR: " + err.take(300)
            }
            var data = obj.optString("data", "")
            if (data.isBlank()) return resultJson

            // Screen-like payloads benefit from list collapsing.
            if (looksLikeScreenTree(data)) {
                lastFullScreen = data
                data = collapseRepetitiveLines(data)
            } else {
                data = trimBlankLines(data)
            }
            data
        } catch (e: Exception) {
            // Not JSON or unexpected shape — fall back to whitespace trim.
            trimBlankLines(resultJson)
        }
    }

    /**
     * Heuristic: does this look like an accessibility tree / screen dump?
     * (Many lines starting with the "[nN]" node-id marker.)
     */
    private fun looksLikeScreenTree(text: String): Boolean {
        val nodeLines = text.lineSequence().take(20).count { it.trimStart().startsWith("[n") }
        return nodeLines >= 3
    }

    /**
     * Collapse runs of structurally-similar lines. Similarity is based on a
     * "shape signature": indentation depth + the set of action flags
     * (tap/edit/scroll) + whether it has quoted text — ignoring the actual text
     * and coordinates. A long run of look-alike rows (a feed, a contact list)
     * gets its middle replaced by a marker.
     */
    fun collapseRepetitiveLines(text: String): String {
        val lines = text.split("\n")
        if (lines.size < COLLAPSE_THRESHOLD) return text

        val out = StringBuilder()
        var runStart = 0
        var prevSig: String? = null

        fun flushRun(endExclusive: Int) {
            val runLen = endExclusive - runStart
            if (runLen >= COLLAPSE_THRESHOLD) {
                // Keep head and tail, collapse the middle.
                for (i in runStart until runStart + KEEP_HEAD) out.append(lines[i]).append("\n")
                val hidden = runLen - KEEP_HEAD - KEEP_TAIL
                out.append("  … (+$hidden filas similares omitidas)\n")
                for (i in endExclusive - KEEP_TAIL until endExclusive) out.append(lines[i]).append("\n")
            } else {
                for (i in runStart until endExclusive) out.append(lines[i]).append("\n")
            }
        }

        for (i in lines.indices) {
            val sig = lineSignature(lines[i])
            if (sig != prevSig) {
                if (prevSig != null) flushRun(i)
                runStart = i
                prevSig = sig
            }
        }
        flushRun(lines.size)

        val result = out.toString().trimEnd('\n')
        if (result.length < text.length) {
            XLog.d(TAG, "Collapsed screen tree: ${text.length}→${result.length} chars")
        }
        return result
    }

    /**
     * A structural signature ignoring text content and coordinates, so two list
     * rows like `[n5] "Ana" tap (..)` and `[n6] "Bob" tap (..)` share a signature
     * and can be collapsed.
     */
    private fun lineSignature(line: String): String {
        val indent = line.takeWhile { it == ' ' }.length / 2
        val hasText = line.contains('"')
        val flags = buildString {
            if (line.contains(" tap")) append("T")
            if (line.contains(" edit")) append("E")
            if (line.contains(" scroll")) append("S")
            if (line.contains(" on") || line.contains(" off")) append("C")
        }
        // Lines without a node id (headers, separators) keep their own raw shape
        // so we don't collapse meaningful unique lines.
        return if (line.trimStart().startsWith("[n")) "$indent|$hasText|$flags"
        else "RAW:${line.trim().take(20)}"
    }

    private fun trimBlankLines(text: String): String =
        text.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
}
