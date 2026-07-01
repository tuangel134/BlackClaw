package com.blackclaw.android.tool.impl

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Enumerate the visible, tappable list items / options on screen IN ORDER, each
 * with an index and tap coordinates. This is the reliable way to pick the RIGHT
 * item from a list (search results, contacts, menu options) — the model reads
 * the ordered list and taps the intended one (e.g. #1 = nearest/top result)
 * instead of guessing from a flat screen dump.
 *
 * Reduces the "tapped the wrong result" failure: after a search, call
 * list_options() then tap(x, y) on the chosen entry's coordinates.
 */
class ListOptionsTool : BaseTool() {
    override fun getName() = "list_options"
    override fun getDisplayName() = "Listar opciones"
    override fun getDescriptionEN() =
        "List the visible tappable items/options on screen IN ORDER (top→bottom), each with an index " +
        "and tap coordinates. Use to reliably pick the right entry from a list (search results, " +
        "contacts, menu). The FIRST item is usually the top/nearest/most relevant result. " +
        "Optional 'filter' keeps only items whose text contains it. After choosing, tap(x, y) on " +
        "the entry's coordinates."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "lista los elementos tocables en orden con índice y coordenadas para elegir el correcto"
    override fun getParameters() = listOf(
        ToolParameter("filter", "string", "Optional: only include items whose text contains this.", false),
        ToolParameter("limit", "string", "Max items to return (default 20).", false),
    )

    private data class Option(val label: String, val cx: Int, val cy: Int, val top: Int, val left: Int)

    override fun execute(params: Map<String, Any>): ToolResult {
        val service = requireAccessibilityService()
            ?: return ToolResult.error("El servicio de accesibilidad no está activo.")
        val filter = optionalString(params, "filter", "").trim().lowercase()
        val limit = optionalString(params, "limit", "20").toIntOrNull()?.coerceIn(1, 40) ?: 20
        val root = service.rootInActiveWindow
            ?: return ToolResult.error("No pude leer la pantalla.")

        val out = ArrayList<Option>()
        val seen = HashSet<String>()
        collect(root, out, seen)

        var items = out
            .distinctBy { "${it.cx}x${it.cy}" }
            .sortedWith(compareBy({ it.top }, { it.left }))
        if (filter.isNotBlank()) items = items.filter { it.label.lowercase().contains(filter) }
        if (items.isEmpty()) {
            return ToolResult.success("No detecté opciones tocables con etiqueta en pantalla. " +
                "Prueba read_screen_ocr si es una app tipo canvas.")
        }

        val sb = StringBuilder("Opciones en pantalla (toca con tap(x, y)):\n")
        items.take(limit).forEachIndexed { i, o ->
            sb.append("${i + 1}. [${o.cx},${o.cy}] ${o.label.take(80)}\n")
        }
        return ToolResult.success(sb.toString().trim())
    }

    /** Depth-first collect visible, actionable nodes with a usable label. */
    private fun collect(node: AccessibilityNodeInfo?, out: ArrayList<Option>, seen: HashSet<String>, depth: Int = 0) {
        if (node == null || depth > 40 || out.size > 200) return
        runCatching {
            if (node.isVisibleToUser && (node.isClickable || node.isLongClickable)) {
                val label = labelFor(node)
                if (label.isNotBlank()) {
                    val b = Rect(); node.getBoundsInScreen(b)
                    if (b.width() > 0 && b.height() > 0) {
                        val key = "${b.centerX()}x${b.centerY()}"
                        if (seen.add(key)) {
                            out.add(Option(label, b.centerX(), b.centerY(), b.top, b.left))
                        }
                    }
                }
            }
        }
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out, seen, depth + 1)
        }
    }

    /** Node's own text, else content-description, else aggregated child text. */
    private fun labelFor(node: AccessibilityNodeInfo): String {
        node.text?.toString()?.trim()?.let { if (it.isNotBlank()) return it }
        node.contentDescription?.toString()?.trim()?.let { if (it.isNotBlank()) return it }
        val sb = StringBuilder()
        aggregateText(node, sb, 0)
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun aggregateText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 6 || sb.length > 120) return
        node.text?.toString()?.trim()?.let { if (it.isNotBlank()) { sb.append(it).append(' ') } }
        if (sb.isEmpty()) {
            node.contentDescription?.toString()?.trim()?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
        }
        for (i in 0 until node.childCount) aggregateText(node.getChild(i), sb, depth + 1)
    }
}
