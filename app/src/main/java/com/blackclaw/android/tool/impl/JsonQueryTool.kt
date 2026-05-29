package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tiny path-based JSON reader. Supports:
 *   foo.bar.baz          object navigation
 *   list[0].name         array index
 *   foo.bar[*].name      wildcard (returns array)
 */
class JsonQueryTool : BaseTool() {
    override fun getName() = "json_query"
    override fun getDisplayName() = "JSON query"
    override fun getDescriptionEN() =
        "Extrae datos de un JSON. Path como 'data.items[0].name' o 'items[*].id'."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("json", "string", "JSON crudo a inspeccionar.", true),
        ToolParameter("path", "string", "Ruta dot/bracket. Vacío = raíz.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val raw = requireString(params, "json").trim()
        val path = optionalString(params, "path", "").trim()
        return try {
            val root: Any = when {
                raw.startsWith("[") -> JSONArray(raw)
                raw.startsWith("{") -> JSONObject(raw)
                else -> return ToolResult.error("JSON debe empezar por { o [")
            }
            val result = navigate(root, path)
            val text = when (result) {
                is JSONObject -> result.toString(2)
                is JSONArray -> result.toString(2)
                else -> result?.toString() ?: "null"
            }
            ToolResult.success(text.take(8000))
        } catch (e: Exception) {
            ToolResult.error("Query falló: ${e.message}")
        }
    }

    private fun navigate(node: Any?, path: String): Any? {
        if (path.isEmpty()) return node
        var current: Any? = node
        // tokenize: foo.bar[0].baz → [foo, bar, [0], baz]
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < path.length) {
            val c = path[i]
            when (c) {
                '.' -> { if (sb.isNotEmpty()) { parts.add(sb.toString()); sb.clear() } }
                '[' -> {
                    if (sb.isNotEmpty()) { parts.add(sb.toString()); sb.clear() }
                    val end = path.indexOf(']', i)
                    if (end < 0) throw IllegalArgumentException("[ sin cerrar")
                    parts.add("[" + path.substring(i + 1, end) + "]")
                    i = end
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) parts.add(sb.toString())

        for (part in parts) {
            current = when {
                current == null -> return null
                part.startsWith("[") && part.endsWith("]") -> {
                    val inner = part.substring(1, part.length - 1)
                    val arr = current as? JSONArray ?: return null
                    if (inner == "*") {
                        val all = JSONArray()
                        for (k in 0 until arr.length()) all.put(arr.get(k))
                        all
                    } else {
                        arr.opt(inner.toInt())
                    }
                }
                else -> {
                    when (current) {
                        is JSONObject -> (current as JSONObject).opt(part)
                        is JSONArray -> {
                            // wildcard projection: pick `part` from every element
                            val all = JSONArray()
                            val arr = current as JSONArray
                            for (k in 0 until arr.length()) {
                                val el = arr.opt(k)
                                if (el is JSONObject) all.put(el.opt(part))
                            }
                            all
                        }
                        else -> null
                    }
                }
            }
        }
        return current
    }
}
