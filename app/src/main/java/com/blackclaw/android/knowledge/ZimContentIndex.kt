package com.blackclaw.android.knowledge

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/** Persistent FTS4 index derived from one ZIM file. Safe to delete and rebuild at any time. */
class ZimContentIndex private constructor(
    private val dbFile: File,
    private val database: SQLiteDatabase,
) : Closeable {
    data class Hit(val title: String, val path: String, val snippet: String)
    data class Status(
        val position: Long,
        val total: Long,
        val indexed: Long,
        val skipped: Long,
        val complete: Boolean,
        val updatedAt: Long,
    ) {
        val percent: Int get() = if (total <= 0) 0 else ((position * 100) / total).toInt().coerceIn(0, 100)
    }

    companion object {
        private const val SCHEMA_VERSION = "1"

        fun databaseFile(context: Context, zimFile: File): File {
            val canonical = runCatching { zimFile.canonicalPath }.getOrDefault(zimFile.absolutePath)
            val identity = "$canonical|${zimFile.length()}|${zimFile.lastModified()}"
            val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                .take(12).joinToString("") { "%02x".format(it) }
            return File(context.filesDir, "zim_indexes/$digest.db")
        }

        fun exists(context: Context, zimFile: File): Boolean = databaseFile(context, zimFile).isFile

        fun open(context: Context, zimFile: File, total: Long): ZimContentIndex {
            val file = databaseFile(context, zimFile)
            file.parentFile?.mkdirs()
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            runCatching { db.enableWriteAheadLogging() }
            val index = ZimContentIndex(file, db)
            index.ensureSchema(zimFile, total)
            return index
        }

        fun delete(context: Context, zimFile: File) {
            val file = databaseFile(context, zimFile)
            listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))
                .forEach { runCatching { if (it.exists()) it.delete() } }
        }

        internal fun matchExpression(query: String): String =
            Regex("[\\p{L}\\p{N}]+").findAll(ZimText.normalize(query))
                .map { it.value }.filter { it.length > 1 }.take(12)
                .joinToString(" AND ") { "$it*" }
    }

    private fun ensureSchema(zimFile: File, total: Long) {
        database.execSQL("CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS docs USING fts4(title, path, body, tokenize=unicode61)")
        if (state("schema") == null) {
            transaction {
                putState("schema", SCHEMA_VERSION)
                putState("source_path", zimFile.absolutePath)
                putState("source_size", zimFile.length().toString())
                putState("source_modified", zimFile.lastModified().toString())
                putState("position", "0")
                putState("total", total.toString())
                putState("indexed", "0")
                putState("skipped", "0")
                putState("complete", "0")
                putState("updated", System.currentTimeMillis().toString())
            }
        }
    }

    fun status(): Status = Status(
        position = stateLong("position"), total = stateLong("total"),
        indexed = stateLong("indexed"), skipped = stateLong("skipped"),
        complete = state("complete") == "1", updatedAt = stateLong("updated"),
    )

    /** Adds one atomic batch and advances the checkpoint in the same transaction. */
    fun appendBatch(articles: List<DirectZimReader.Article>, nextPosition: Long, skippedDelta: Long) {
        transaction {
            val insert = database.compileStatement("INSERT INTO docs(title,path,body) VALUES(?,?,?)")
            insert.use { statement ->
                articles.forEach { article ->
                    statement.clearBindings()
                    statement.bindString(1, article.title)
                    statement.bindString(2, article.path)
                    statement.bindString(3, article.text)
                    statement.executeInsert()
                }
            }
            putState("position", nextPosition.toString())
            putState("indexed", (stateLong("indexed") + articles.size).toString())
            putState("skipped", (stateLong("skipped") + skippedDelta).toString())
            putState("complete", if (nextPosition >= stateLong("total")) "1" else "0")
            putState("updated", System.currentTimeMillis().toString())
        }
    }

    fun search(query: String, limit: Int): List<Hit> {
        val expression = matchExpression(query)
        if (expression.isBlank()) return emptyList()
        val hits = ArrayList<Hit>()
        database.rawQuery(
            "SELECT title,path,snippet(docs,'[',']',' … ',2,24) FROM docs WHERE docs MATCH ? LIMIT ?",
            arrayOf(expression, limit.coerceIn(1, 20).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                hits += Hit(cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty())
            }
        }
        return hits.distinctBy { it.path }.take(limit)
    }

    fun documentCount(): Long = DatabaseUtils.longForQuery(database, "SELECT count(*) FROM docs", null)

    private fun state(key: String): String? = database.rawQuery(
        "SELECT value FROM state WHERE key=?", arrayOf(key),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun stateLong(key: String): Long = state(key)?.toLongOrNull() ?: 0L

    private fun putState(key: String, value: String) {
        database.insertWithOnConflict("state", null, ContentValues().apply {
            put("key", key); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private inline fun transaction(block: () -> Unit) {
        database.beginTransaction()
        try { block(); database.setTransactionSuccessful() } finally { database.endTransaction() }
    }

    override fun close() = database.close()
}
