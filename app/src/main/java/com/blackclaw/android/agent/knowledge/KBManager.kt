package com.blackclaw.android.agent.knowledge

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.utils.XLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Knowledge Base Manager — reads and writes the local MD vault.
 *
 * Vault root: /storage/emulated/0/Android/data/com.blackclaw.android/files/vault/
 *
 * All paths passed to public methods are relative to the vault root.
 * Path traversal (../) is stripped before resolving.
 */
object KBManager {

    private const val TAG = "KBManager"
    private const val VAULT_SUBDIR = "vault"

    data class SearchResult(val path: String, val snippet: String, val modified: Long)

    // ── Vault root ────────────────────────────────────────────────────────────

    private fun vaultDir(): File {
        val dir = File(ClawApplication.instance.getExternalFilesDir(null), VAULT_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Resolve a caller-supplied relative path safely (no traversal). */
    private fun resolve(path: String): File {
        val clean = path.trimStart('/').replace("..", "")
        return File(vaultDir(), clean)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Write (create or overwrite) a markdown file.
     * Automatically prepends frontmatter built from [frontmatter] map.
     */
    fun write(path: String, frontmatter: Map<String, Any>, content: String): Result<String> {
        return try {
            val file = resolve(path)
            file.parentFile?.mkdirs()
            val text = buildFrontmatter(frontmatter) + "\n\n" + content
            file.writeText(text)
            XLog.i(TAG, "kb_write: $path (${text.length} chars)")
            Result.success("Written: $path")
        } catch (e: Exception) {
            XLog.e(TAG, "kb_write failed: $path", e)
            Result.failure(e)
        }
    }

    /** Read the full content of a file. */
    fun read(path: String): Result<String> {
        return try {
            val file = resolve(path)
            if (!file.exists()) return Result.failure(Exception("File not found: $path"))
            val text = file.readText()
            XLog.i(TAG, "kb_read: $path (${text.length} chars)")
            Result.success(text)
        } catch (e: Exception) {
            XLog.e(TAG, "kb_read failed: $path", e)
            Result.failure(e)
        }
    }

    /**
     * Full-text search across all .md files in the vault.
     * Returns up to 10 results, newest-modified first.
     */
    fun search(query: String): Result<List<SearchResult>> {
        return try {
            val vault = vaultDir()
            val results = vault.walkTopDown()
                .filter { it.isFile && it.extension == "md" }
                .mapNotNull { file ->
                    val content = file.readText()
                    if (content.contains(query, ignoreCase = true)) {
                        val rel = file.relativeTo(vault).path
                        SearchResult(rel, extractSnippet(content, query), file.lastModified())
                    } else null
                }
                .sortedByDescending { it.modified }
                .take(10)
                .toList()
            XLog.i(TAG, "kb_search: queryChars=${query.length} results=${results.size}")
            Result.success(results)
        } catch (e: Exception) {
            XLog.e(TAG, "kb_search failed: queryChars=${query.length} type=${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    /** Append content to an existing file (does not overwrite). */
    fun append(path: String, content: String): Result<String> {
        return try {
            val file = resolve(path)
            if (!file.exists()) {
                return Result.failure(Exception("File not found: $path — use kb_write to create it first."))
            }
            file.appendText(content)
            XLog.i(TAG, "kb_append: $path (+${content.length} chars)")
            Result.success("Appended to: $path")
        } catch (e: Exception) {
            XLog.e(TAG, "kb_append failed: $path", e)
            Result.failure(e)
        }
    }

    /** List files and sub-folders inside a vault folder. */
    fun list(folder: String): Result<List<String>> {
        return try {
            val dir = resolve(folder)
            if (!dir.exists() || !dir.isDirectory) {
                return Result.failure(Exception("Folder not found: $folder"))
            }
            val entries = dir.listFiles()
                ?.map { if (it.isDirectory) it.name + "/" else it.name }
                ?.sorted()
                ?: emptyList()
            Result.success(entries)
        } catch (e: Exception) {
            XLog.e(TAG, "kb_list failed: $folder", e)
            Result.failure(e)
        }
    }

    /**
     * Add a todo item to today's todo file.
     * File: todos/YYYY-MM-DD.md — created automatically if absent.
     */
    fun addTodo(text: String, due: String?, priority: String?): Result<String> {
        return try {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val todoPath = "todos/$today.md"
            val file = resolve(todoPath)
            file.parentFile?.mkdirs()

            if (!file.exists()) {
                val fm = buildFrontmatter(mapOf(
                    "type" to "todo",
                    "date" to today,
                    "tags" to "[todo]"
                ))
                file.writeText("$fm\n\n# Todos — $today\n\n")
            }

            val duePart = if (!due.isNullOrBlank()) " <!-- due: $due -->" else ""
            val priorityPart = when (priority?.lowercase()) {
                "high" -> " [HIGH]"
                "medium" -> " [MED]"
                "low" -> " [LOW]"
                else -> ""
            }
            file.appendText("- [ ]$priorityPart $text$duePart\n")
            XLog.i(TAG, "kb_add_todo: textChars=${text.length} path=$todoPath")
            Result.success("Added todo to $todoPath")
        } catch (e: Exception) {
            XLog.e(TAG, "kb_add_todo failed: textChars=${text.length} type=${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildFrontmatter(data: Map<String, Any>): String {
        val sb = StringBuilder("---\n")
        data.forEach { (k, v) -> sb.appendLine("$k: $v") }
        sb.append("---")
        return sb.toString()
    }

    private fun extractSnippet(content: String, query: String, contextChars: Int = 120): String {
        val idx = content.indexOf(query, ignoreCase = true)
        if (idx < 0) return content.take(contextChars)
        val start = maxOf(0, idx - contextChars / 2)
        val end = minOf(content.length, idx + query.length + contextChars / 2)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < content.length) "…" else ""
        return prefix + content.substring(start, end) + suffix
    }
}
