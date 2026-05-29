package com.blackclaw.android.tool.impl

import android.os.Environment
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import java.io.File

/**
 * Read a text file from Documents/BlackClaw/ (or list the folder if no filename).
 * Strips path separators from the filename for safety; cannot read arbitrary paths.
 */
class ReadFileTool : BaseTool() {
    override fun getName() = "read_file"
    override fun getDisplayName() = "Leer archivo"
    override fun getDescriptionEN() =
        "Read a text file under Documents/BlackClaw/. Without filename, returns the file list. " +
        "Max 32 KB returned to keep tokens sane."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("filename", "string", "File to read; omit to list the folder.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val ctx = ClawApplication.instance
        val baseDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir
        val dir = File(baseDir, "BlackClaw").apply { mkdirs() }
        val name = optionalString(params, "filename", "").trim()
            .replace(Regex("[/\\\\\\u0000]"), "_")
        if (name.isEmpty()) {
            val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
            if (files.isEmpty()) return ToolResult.success("(carpeta vacía)")
            return ToolResult.success("Archivos:\n" + files.joinToString("\n") {
                "- ${it.name} (${it.length() / 1024}KB)"
            })
        }
        val file = File(dir, name)
        if (!file.exists() || !file.isFile) return ToolResult.error("Archivo no encontrado: $name")
        return try {
            val limit = 32 * 1024
            val text = file.readText().take(limit)
            val truncated = if (file.length() > limit) "\n…[truncado a 32 KB]" else ""
            ToolResult.success("[${file.name}]\n$text$truncated")
        } catch (e: Exception) {
            ToolResult.error("Error de lectura: ${e.message}")
        }
    }
}
