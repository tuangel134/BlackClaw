package com.blackclaw.android.tool.impl

import android.os.Environment
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import java.io.File

/**
 * Write text to a file in the app's external Documents folder.
 *
 * Path is resolved as: <ext>/Documents/BlackClaw/<filename>. The agent cannot
 * escape that directory: '/', '..' and absolute paths are stripped from the
 * filename, and we never accept arbitrary paths even if we could resolve them.
 */
class WriteFileTool : BaseTool() {
    override fun getName() = "write_file"
    override fun getDisplayName() = "Escribir archivo"
    override fun getDescriptionEN() =
        "Save text to a file under Documents/BlackClaw/. The user can find it in the file manager. " +
        "Use for exports, notes, drafts. mode='append' adds to the end, 'overwrite' replaces."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("filename", "string", "File name (no path), e.g. 'notes.txt'.", true),
        ToolParameter("content", "string", "Text content to write.", true),
        ToolParameter("mode", "string", "append (default) | overwrite", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val rawName = requireString(params, "filename").trim()
        val content = requireString(params, "content")
        val mode = optionalString(params, "mode", "append").lowercase()
        // Sanitize filename
        val safeName = rawName.replace(Regex("[/\\\\\\u0000]"), "_")
            .removePrefix(".")
            .ifBlank { "note-${System.currentTimeMillis()}.txt" }

        val ctx = ClawApplication.instance
        val baseDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: ctx.filesDir
        val dir = File(baseDir, "BlackClaw").apply { mkdirs() }
        val file = File(dir, safeName)
        return try {
            when (mode) {
                "overwrite", "replace" -> file.writeText(content)
                else -> file.appendText(content + "\n")
            }
            ToolResult.success("Escrito ${content.length} caracteres en ${file.absolutePath}")
        } catch (e: Exception) {
            ToolResult.error("No se pudo escribir el archivo: ${e.message}")
        }
    }
}
