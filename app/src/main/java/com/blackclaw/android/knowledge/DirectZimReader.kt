package com.blackclaw.android.knowledge

import android.os.Build
import android.os.Environment
import com.github.luben.zstd.ZstdInputStream
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale
import java.util.zip.InflaterInputStream

/** Direct ZIM v5/v6 reader used by both interactive reads and the resumable local indexer. */
class DirectZimReader(private val file: File) : Closeable {
    companion object {
        private const val MAGIC = 0x044D495AL
        private const val HEADER_SIZE = 80L
        private const val MAX_CLUSTER_BYTES = 128 * 1024 * 1024
        private const val MAX_CACHED_CLUSTER_BYTES = 8 * 1024 * 1024
        private const val MAX_STRING_BYTES = 1 * 1024 * 1024
    }

    data class SearchHit(val title: String, val path: String, val mimeType: String)
    data class Article(val title: String, val path: String, val mimeType: String, val text: String)

    private data class Header(
        val version: Int,
        val entryCount: Long,
        val clusterCount: Long,
        val pathPtrPos: Long,
        val titleIdxPos: Long,
        val clusterPtrPos: Long,
        val mimeListPos: Long,
        val checksumPos: Long,
    )

    private data class Dirent(
        val index: Long,
        val mimeIndex: Int,
        val namespace: Char,
        val path: String,
        val title: String,
        val cluster: Long = -1,
        val blob: Long = -1,
        val redirect: Long = -1,
    ) {
        val longPath: String get() = "$namespace/$path"
        val displayTitle: String get() = title.ifBlank { path }
        val isRedirect: Boolean get() = mimeIndex == 0xFFFF
        val isArticle: Boolean get() = mimeIndex in 0..0xFFFC
    }

    private data class DecodedCluster(
        val bytes: ByteArray,
        val offsetWidth: Int,
        val firstBlobOffset: Long,
        val blobCount: Long,
    )

    private interface TitleIndex {
        val size: Long
        fun entryIndex(position: Long): Long
    }

    private val raf = RandomAccessFile(file, "r")
    private val header: Header
    private val mimeTypes: List<String>
    private val titleIndex: TitleIndex
    private var cachedClusterNumber = -1L
    private var cachedCluster: DecodedCluster? = null

    init {
        require(file.isFile && file.canRead()) { "No se puede leer ${file.absolutePath}" }
        header = readHeader()
        mimeTypes = readMimeTypes()
        titleIndex = loadTitleIndex()
    }

    @Synchronized
    fun searchTitles(query: String, limit: Int = 5): List<SearchHit> {
        val needle = normalize(query)
        require(needle.isNotBlank()) { "Consulta vacía" }
        val max = limit.coerceIn(1, 20)
        val start = lowerBound(needle)
        val hits = LinkedHashMap<String, SearchHit>()
        var position = (start - 16).coerceAtLeast(0)
        val end = (start + 800).coerceAtMost(titleIndex.size)
        while (position < end && hits.size < max) {
            val entry = dirent(titleIndex.entryIndex(position))
            val title = entry.displayTitle
            val normalized = normalize(title)
            if (normalized.startsWith(needle) || normalized.contains(needle)) {
                val resolved = resolve(entry)
                if (resolved.isArticle && isTextMime(resolved.mimeIndex)) {
                    val hit = SearchHit(title, resolved.longPath, mimeOf(resolved.mimeIndex))
                    hits.putIfAbsent(hit.path, hit)
                }
            } else if (position > start + 128 && normalized > needle && !normalized.startsWith(needle.take(1))) {
                break
            }
            position++
        }
        return hits.values.toList()
    }

    @Synchronized
    fun readArticle(titleOrPath: String, maxChars: Int = 5_000): Article {
        val requested = titleOrPath.trim()
        require(requested.isNotBlank()) { "Título o ruta vacíos" }
        val initial = if (requested.length > 2 && requested[1] == '/') {
            findByPath(requested) ?: error("No existe la ruta '$requested' en ${file.name}")
        } else {
            findExactTitle(requested)
                ?: searchTitles(requested, 1).firstOrNull()?.let { findByPath(it.path) }
                ?: error("No encontré el artículo '$requested' en ${file.name}")
        }
        return decodeArticle(resolve(initial), initial.displayTitle, maxChars)
    }

    /** Number of entries in the archive's ordered title listing. */
    val titleEntryCount: Long get() = titleIndex.size

    /** Detects the conventional embedded full-text entry without loading its (potentially huge) blob. */
    @Synchronized
    fun hasEmbeddedFullTextIndex(): Boolean =
        findByPath("X/fulltext/xapian") != null || findByPath("Z//fulltextIndex/xapian") != null

    /** Read one title-listing position for incremental content indexing. Non-text entries return null. */
    @Synchronized
    fun readArticleAtTitlePosition(position: Long, maxChars: Int = 100_000): Article? {
        require(position in 0 until titleIndex.size) { "Posición de título fuera de rango" }
        val listed = dirent(titleIndex.entryIndex(position))
        val resolved = resolve(listed)
        if (!resolved.isArticle || !isTextMime(resolved.mimeIndex)) return null
        return decodeArticle(resolved, listed.displayTitle, maxChars)
    }

    fun libraryInfo(): String = "${file.name} · ZIM v${header.version} · ${header.entryCount} entradas · ${mimeTypes.size} MIME"

    private fun decodeArticle(entry: Dirent, displayTitle: String, maxChars: Int): Article {
        require(entry.isArticle) { "La entrada no contiene un artículo" }
        val mime = mimeOf(entry.mimeIndex)
        require(mime.startsWith("text/") || mime.contains("html")) { "La entrada es $mime, no texto" }
        val raw = readBlob(entry.cluster, entry.blob)
        val decoded = raw.toString(Charsets.UTF_8)
        val text = if (mime.contains("html")) htmlToText(decoded) else decoded
        return Article(displayTitle.ifBlank { entry.displayTitle }, entry.longPath, mime,
            text.trim().take(maxChars.coerceIn(200, 250_000)))
    }

    private fun readHeader(): Header {
        require(raf.length() >= HEADER_SIZE) { "Archivo demasiado pequeño para ser ZIM" }
        require(u32(0) == MAGIC) { "Firma ZIM inválida" }
        val major = u16(4)
        require(major == 5 || major == 6) { "Versión ZIM no soportada: $major" }
        val entries = u32(24)
        val clusters = u32(28)
        val pathPos = u64(32)
        val titlePos = u64(40)
        val clusterPos = u64(48)
        val mimePos = u64(56)
        val checksum = u64(72)
        require(entries in 1..50_000_000 && clusters in 1..entries) { "Contadores ZIM inválidos" }
        require(pathPos in HEADER_SIZE until raf.length()) { "Índice de rutas inválido" }
        require(clusterPos in HEADER_SIZE until raf.length()) { "Índice de clusters inválido" }
        // The common layout puts MIME data immediately after the header, but valid
        // writers may place optional data first. It only has to precede path pointers.
        require(mimePos in HEADER_SIZE until pathPos) { "Lista MIME inválida" }
        return Header(major, entries, clusters, pathPos, titlePos, clusterPos, mimePos, checksum)
    }

    private fun readMimeTypes(): List<String> {
        raf.seek(header.mimeListPos)
        val out = ArrayList<String>()
        while (raf.filePointer < header.pathPtrPos && out.size < 65_536) {
            val value = readCString(512)
            if (value.isEmpty()) break
            out += value
        }
        require(out.isNotEmpty()) { "ZIM sin lista MIME" }
        return out
    }

    private fun loadTitleIndex(): TitleIndex {
        // Zero, -1 and UINT64_MAX mean there is no header title listing. Such ZIMs
        // are still readable through X/listing/titleOrdered/v1.
        if (header.titleIdxPos != 0L && header.titleIdxPos != -1L && header.titleIdxPos != Long.MAX_VALUE) {
            val pos = header.titleIdxPos
            require(pos in HEADER_SIZE until raf.length()) { "Índice de títulos inválido" }
            return object : TitleIndex {
                override val size = header.entryCount
                override fun entryIndex(position: Long) = u32(pos + position * 4)
            }
        }
        val listing = findByPath("X/listing/titleOrdered/v1")
            ?: error("Este ZIM no contiene un índice de títulos compatible")
        val bytes = readBlob(listing.cluster, listing.blob)
        require(bytes.size % 4 == 0) { "Índice de títulos ZIM corrupto" }
        return object : TitleIndex {
            override val size = bytes.size.toLong() / 4
            override fun entryIndex(position: Long): Long = leUnsigned(bytes, (position * 4).toInt(), 4)
        }
    }

    private fun lowerBound(needle: String): Long {
        var low = 0L
        var high = titleIndex.size
        while (low < high) {
            val mid = (low + high) ushr 1
            val value = normalize(dirent(titleIndex.entryIndex(mid)).displayTitle)
            if (value < needle) low = mid + 1 else high = mid
        }
        return low
    }

    private fun findExactTitle(title: String): Dirent? {
        val needle = normalize(title)
        val start = lowerBound(needle)
        for (position in (start - 8).coerceAtLeast(0) until (start + 64).coerceAtMost(titleIndex.size)) {
            val entry = dirent(titleIndex.entryIndex(position))
            if (normalize(entry.displayTitle) == needle) return entry
        }
        return null
    }

    private fun findByPath(longPath: String): Dirent? {
        var low = 0L
        var high = header.entryCount
        while (low < high) {
            val mid = (low + high) ushr 1
            val entry = dirent(mid)
            val comparison = entry.longPath.compareTo(longPath)
            when {
                comparison < 0 -> low = mid + 1
                comparison > 0 -> high = mid
                else -> return entry
            }
        }
        return null
    }

    private fun resolve(original: Dirent): Dirent {
        var current = original
        repeat(12) {
            if (!current.isRedirect) return current
            require(current.redirect in 0 until header.entryCount) { "Redirección ZIM inválida" }
            current = dirent(current.redirect)
        }
        error("Demasiadas redirecciones ZIM")
    }

    private fun dirent(index: Long): Dirent {
        require(index in 0 until header.entryCount) { "Índice de entrada fuera de rango" }
        val offset = u64(header.pathPtrPos + index * 8)
        require(offset in HEADER_SIZE until raf.length()) { "Puntero de entrada inválido" }
        raf.seek(offset)
        val mime = readU16()
        val parameterLength = raf.readUnsignedByte()
        val namespace = raf.readUnsignedByte().toChar()
        readU32() // revision
        var cluster = -1L
        var blob = -1L
        var redirect = -1L
        when (mime) {
            0xFFFF -> redirect = readU32()
            0xFFFE, 0xFFFD -> Unit
            else -> { cluster = readU32(); blob = readU32() }
        }
        val path = readCString(MAX_STRING_BYTES)
        val title = readCString(MAX_STRING_BYTES)
        if (parameterLength > 0) raf.skipBytes(parameterLength)
        return Dirent(index, mime, namespace, path, title, cluster, blob, redirect)
    }

    private fun readBlob(clusterNumber: Long, blobNumber: Long): ByteArray {
        require(clusterNumber in 0 until header.clusterCount) { "Cluster fuera de rango" }
        val cluster = (if (cachedClusterNumber == clusterNumber) cachedCluster else null)
            ?: decodeCluster(clusterNumber).also {
                if (it.bytes.size <= MAX_CACHED_CLUSTER_BYTES) {
                    cachedClusterNumber = clusterNumber
                    cachedCluster = it
                } else {
                    cachedClusterNumber = -1L
                    cachedCluster = null
                }
            }
        require(blobNumber in 0 until cluster.blobCount) { "Blob fuera de rango" }
        val blobStart = leUnsigned(cluster.bytes, (blobNumber * cluster.offsetWidth).toInt(), cluster.offsetWidth)
        val blobEnd = leUnsigned(cluster.bytes, ((blobNumber + 1) * cluster.offsetWidth).toInt(), cluster.offsetWidth)
        require(blobStart in cluster.firstBlobOffset..blobEnd && blobEnd <= cluster.bytes.size) { "Rango de blob inválido" }
        return cluster.bytes.copyOfRange(blobStart.toInt(), blobEnd.toInt())
    }

    private fun decodeCluster(clusterNumber: Long): DecodedCluster {
        val start = u64(header.clusterPtrPos + clusterNumber * 8)
        val end = if (clusterNumber + 1 < header.clusterCount) {
            u64(header.clusterPtrPos + (clusterNumber + 1) * 8)
        } else if (header.checksumPos > start) header.checksumPos else raf.length()
        require(start >= HEADER_SIZE && end > start && end <= raf.length()) { "Rango de cluster inválido" }
        raf.seek(start)
        val info = raf.readUnsignedByte()
        val compressedSize = end - start - 1
        require(compressedSize in 0..MAX_CLUSTER_BYTES.toLong()) { "Cluster demasiado grande" }
        val payload = ByteArray(compressedSize.toInt())
        raf.readFully(payload)
        val compression = info and 0x0F
        val extended = info and 0x10 != 0
        val decoded = when (compression) {
            0, 1 -> payload
            // Historical ZIM "Zip" clusters are zlib streams. Supporting them
            // prevents intact older libraries being reported as unreadable.
            2 -> InflaterInputStream(ByteArrayInputStream(payload)).use(::readLimited)
            4 -> XZInputStream(ByteArrayInputStream(payload)).use(::readLimited)
            5 -> ZstdInputStream(ByteArrayInputStream(payload)).use(::readLimited)
            3 -> error("Compresión BZip2 ZIM histórica no soportada")
            else -> error("Compresión ZIM desconocida: $compression")
        }
        val width = if (extended) 8 else 4
        require(decoded.size >= width) { "Cabecera de cluster incompleta" }
        val firstOffset = leUnsigned(decoded, 0, width)
        require(firstOffset % width == 0L && firstOffset in (2L * width)..decoded.size.toLong()) { "Tabla de blobs inválida" }
        val blobCount = firstOffset / width - 1
        return DecodedCluster(decoded, width, firstOffset, blobCount)
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_CLUSTER_BYTES) { "Cluster descomprimido demasiado grande" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun mimeOf(index: Int): String = mimeTypes.getOrElse(index) { "application/octet-stream" }
    private fun isTextMime(index: Int): Boolean = mimeOf(index).let { it.startsWith("text/") || it.contains("html") }

    private fun readCString(maxBytes: Int): String {
        val out = ByteArrayOutputStream()
        repeat(maxBytes) {
            val value = raf.read()
            require(value >= 0) { "Cadena ZIM truncada" }
            if (value == 0) return out.toString(Charsets.UTF_8.name())
            out.write(value)
        }
        error("Cadena ZIM demasiado larga")
    }

    private fun u16(position: Long): Int { raf.seek(position); return readU16() }
    private fun u32(position: Long): Long { raf.seek(position); return readU32() }
    private fun u64(position: Long): Long {
        raf.seek(position)
        val bytes = ByteArray(8); raf.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
    }
    private fun readU16(): Int = raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)
    private fun readU32(): Long = readU16().toLong() or (readU16().toLong() shl 16)

    override fun close() {
        cachedCluster = null
        cachedClusterNumber = -1L
        raf.close()
    }

    private fun normalize(value: String): String = ZimText.normalize(value)
    private fun leUnsigned(bytes: ByteArray, offset: Int, width: Int): Long = ZimText.leUnsigned(bytes, offset, width)
    internal fun htmlToText(html: String): String = ZimText.htmlToText(html)
}

internal object ZimText {
        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT).trim()

        fun htmlToText(html: String): String {
            var text = html
                .replace(Regex("(?is)<(script|style|svg)[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?i)<br\\s*/?>|</(p|div|li|h[1-6]|tr)>"), "\n")
                .replace(Regex("(?s)<[^>]+>"), " ")
            text = decodeEntities(text)
            return text.lines().map { it.replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotBlank() }.joinToString("\n")
        }

        private fun decodeEntities(value: String): String {
            var text = value.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'")
            text = Regex("&#(x[0-9a-fA-F]+|[0-9]+);").replace(text) { match ->
                val token = match.groupValues[1]
                val code = if (token.startsWith("x", true)) token.drop(1).toIntOrNull(16) else token.toIntOrNull()
                code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: match.value
            }
            return text
        }

        fun leUnsigned(bytes: ByteArray, offset: Int, width: Int): Long {
            require(width == 4 || width == 8)
            require(offset >= 0 && offset + width <= bytes.size)
            var result = 0L
            for (i in 0 until width) result = result or ((bytes[offset + i].toLong() and 0xFF) shl (i * 8))
            return result
        }
}

object DirectZimLibrary {
    data class Resolution(val file: File?, val message: String)

    /**
     * @param archives readable ZIM archives, sorted by name.
     * @param splitPartNames names of split-archive pieces, which are not readable on
     *   their own but must be reported so "nothing found" is not the answer while the
     *   user is staring at files with "zim" in the name.
     * @param hasFullStorageAccess false when Android is hiding shared storage from us,
     *   in which case an empty result says nothing about what is on disk.
     */
    data class Scan(
        val archives: List<File>,
        val splitPartNames: List<String>,
        val hasFullStorageAccess: Boolean,
    )

    /**
     * True when we can actually read shared storage.
     *
     * A `.zim` is not an image, video or audio file, so the granular media permissions
     * introduced in Android 13 do not cover it: reading one from another app's
     * `Android/media` folder needs All-files access and nothing less. Without this check
     * an empty scan is indistinguishable from an empty phone.
     */
    fun hasFullStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            true
        }

    /**
     * Storage volumes to search.
     *
     * `/storage` lists the primary volume plus any removable card by UUID. `emulated`
     * and `self` are skipped because they are the indirection that leads back to the
     * primary volume we already have.
     */
    private fun volumeRoots(): List<String> {
        val roots = linkedSetOf<String>()
        runCatching { Environment.getExternalStorageDirectory()?.absolutePath }
            .getOrNull()?.let { roots += it }
        runCatching {
            File("/storage").listFiles()?.forEach { entry ->
                val name = entry.name
                if (entry.isDirectory && name != "emulated" && name != "self" && entry.canRead()) {
                    roots += entry.absolutePath
                }
            }
        }
        return roots.toList()
    }

    fun scan(): Scan {
        val archives = LinkedHashMap<String, File>()
        val splitParts = LinkedHashSet<String>()

        ZimDiscovery.searchRoots(volumeRoots()).forEach { root ->
            val directory = File(root.path)
            if (!directory.isDirectory) return@forEach
            runCatching {
                directory.walkTopDown()
                    .maxDepth(root.depth)
                    // Pruning is what makes a deeper search affordable.
                    .onEnter { !ZimDiscovery.shouldSkipDirectory(it.name) }
                    .onFail { _, _ -> }
                    .forEach { candidate ->
                        if (!candidate.isFile) return@forEach
                        when {
                            ZimDiscovery.isArchive(candidate.name) -> {
                                val key = runCatching { candidate.canonicalPath }
                                    .getOrDefault(candidate.absolutePath)
                                if (archives.size < ZimDiscovery.MAX_RESULTS) {
                                    archives.putIfAbsent(key, candidate)
                                }
                            }
                            ZimDiscovery.isSplitArchivePart(candidate.name) ->
                                splitParts += candidate.name
                        }
                    }
            }
        }

        return Scan(
            archives = archives.values.sortedBy { it.name.lowercase() },
            splitPartNames = splitParts.toList(),
            hasFullStorageAccess = hasFullStorageAccess(),
        )
    }

    fun discover(): List<File> = scan().archives

    fun resolve(library: String?): Resolution {
        val requested = library?.trim().orEmpty()
        if (requested.isNotEmpty()) {
            val direct = File(requested)
            if (direct.isFile && ZimDiscovery.isArchive(direct.name)) return Resolution(direct, "")
        }
        val result = scan()
        val found = result.archives
        if (requested.isNotEmpty()) {
            found.firstOrNull { it.name.equals(requested, true) || it.nameWithoutExtension.equals(requested, true) }
                ?.let { return Resolution(it, "") }
            if (found.isEmpty()) {
                return Resolution(
                    null,
                    ZimDiscovery.explainEmptyResult(result.hasFullStorageAccess, result.splitPartNames),
                )
            }
            return Resolution(null, "No encontré la biblioteca '$requested'. Disponibles: ${found.joinToString { it.name }}")
        }
        return when (found.size) {
            0 -> Resolution(
                null,
                ZimDiscovery.explainEmptyResult(result.hasFullStorageAccess, result.splitPartNames),
            )
            1 -> Resolution(found.first(), "")
            else -> Resolution(null, "Hay varias bibliotecas ZIM; especifica library. Disponibles: ${found.joinToString { it.name }}")
        }
    }
}
