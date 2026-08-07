package com.blackclaw.android.emergency

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.BufferedOutputStream
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-bound AES-256-GCM vault for emergency evidence. Encrypted segments form
 * a durable offline upload queue; plaintext is removed only after an atomic file
 * replacement succeeds.
 */
object EmergencyEvidenceVault {
    private const val KEY_ALIAS = "blackclaw_emergency_evidence_v1"
    private const val MAGIC = 0x42434531 // BCE1
    private const val BUFFER_SIZE = 64 * 1024

    data class QueueStatus(val segments: Int, val bytes: Long)

    enum class MediaType { VIDEO, AUDIO }

    data class EvidenceItem(
        val id: String,
        val fileName: String,
        val capturedAt: Long,
        val mediaType: MediaType,
        val lens: String?,
        val bytes: Long,
        val backedUp: Boolean,
    )

    internal data class EvidenceDescriptor(
        val capturedAt: Long,
        val mediaType: MediaType,
        val lens: String?,
    )

    fun directory(context: Context): File =
        File(context.noBackupFilesDir, "emergency_evidence").apply { mkdirs() }

    fun newPlainSegment(context: Context, stamp: String): File =
        File(directory(context), safeSegmentName(stamp) + ".pending.m4a")

    fun newPlainVideoSegment(context: Context, stamp: String, lens: String): File =
        File(directory(context), safeSegmentName("${stamp}_${lens}") + ".pending.mp4")

    /** Encrypt a completed recorder output and return the durable queue file. */
    @Synchronized
    fun seal(context: Context, plain: File): File {
        require(plain.isFile && plain.length() > 0) { "Segmento de evidencia vacío" }
        val base = plain.name.removeSuffix(".pending.m4a").removeSuffix(".pending.mp4")
        val target = File(plain.parentFile, "$base.bcenc")
        val temporary = File(target.path + ".tmp")
        runCatching { temporary.delete() }

        try {
            encryptFile(plain, temporary, secretKey())
            require(temporary.length() > plain.length()) { "Salida cifrada incompleta" }
            if (target.exists()) require(target.delete()) { "No se pudo reemplazar el segmento" }
            require(temporary.renameTo(target)) { "No se pudo publicar el segmento cifrado" }
            require(plain.delete()) { "El segmento se cifró, pero no se pudo retirar el original" }
            return target
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
    }

    /** Seal recorder remnants left by a process/device interruption. */
    fun recoverPending(context: Context): Int {
        var recovered = 0
        directory(context).listFiles { file ->
            file.name.endsWith(".pending.m4a") || file.name.endsWith(".pending.mp4")
        }
            .orEmpty().filter { it.length() > 0 }.forEach {
                if (runCatching { seal(context, it) }.isSuccess) recovered++
            }
        return recovered
    }

    fun queueStatus(context: Context): QueueStatus {
        val files = directory(context).listFiles { file -> file.extension == "bcenc" }.orEmpty()
        return QueueStatus(files.size, files.sumOf(File::length))
    }

    /** List both pending and successfully backed-up ciphertext without exposing paths. */
    fun listEvidence(context: Context): List<EvidenceItem> {
        val root = directory(context)
        val uploaded = File(root, "uploaded")
        val pendingFiles = root.listFiles { file -> file.isFile && file.extension == "bcenc" }.orEmpty()
        val uploadedFiles = uploaded.listFiles { file -> file.isFile && file.extension == "bcenc" }.orEmpty()
        return (pendingFiles.map { it to false } + uploadedFiles.map { it to true })
            .map { (file, backedUp) ->
                val descriptor = parseDescriptor(file.name, file.lastModified())
                EvidenceItem(
                    id = (if (backedUp) "uploaded/" else "") + file.name,
                    fileName = file.name,
                    capturedAt = descriptor.capturedAt,
                    mediaType = descriptor.mediaType,
                    lens = descriptor.lens,
                    bytes = file.length(),
                    backedUp = backedUp,
                )
            }
            .sortedByDescending(EvidenceItem::capturedAt)
    }

    /**
     * Decrypt one selected item into app cache. The returned file is never stored
     * in media collections and callers must remove playback copies when finished.
     */
    fun decryptToCache(context: Context, item: EvidenceItem, purpose: String): File {
        val encrypted = resolveItem(context, item)
        val extension = if (item.mediaType == MediaType.VIDEO) "mp4" else "m4a"
        val cache = File(context.cacheDir, "emergency_evidence").apply { mkdirs() }
        cleanupTemporary(context)
        val safePurpose = purpose.filter(Char::isLetterOrDigit).take(16).ifEmpty { "preview" }
        val output = File(cache, "${safePurpose}_${System.nanoTime()}.$extension")
        val temporary = File(output.path + ".tmp")

        try {
            decryptFile(encrypted, temporary, secretKey())
            require(temporary.length() > 0) { "Evidencia descifrada vacía" }
            require(temporary.renameTo(output)) { "No se pudo preparar la evidencia" }
            return output
        } catch (error: Exception) {
            temporary.delete()
            output.delete()
            throw error
        }
    }

    fun deleteEvidence(context: Context, item: EvidenceItem): Boolean =
        resolveItem(context, item).delete()

    /** Remove abandoned plaintext previews, while allowing share targets time to read them. */
    fun cleanupTemporary(context: Context, olderThanMs: Long = 24L * 60L * 60L * 1000L): Int {
        val cutoff = System.currentTimeMillis() - olderThanMs
        var removed = 0
        File(context.cacheDir, "emergency_evidence").listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.lastModified() < cutoff && file.delete()) removed++
        }
        return removed
    }

    internal fun parseDescriptor(fileName: String, fallbackTime: Long): EvidenceDescriptor {
        val lower = fileName.lowercase()
        val lens = when {
            "_front.bcenc" in lower -> "front"
            "_back.bcenc" in lower -> "back"
            else -> null
        }
        val stamp = Regex("(\\d{8}_\\d{6}_\\d{3})").find(fileName)?.groupValues?.get(1)
        val captured = stamp?.let {
            runCatching {
                java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).apply {
                    isLenient = false
                }.parse(it)?.time
            }.getOrNull()
        } ?: fallbackTime
        return EvidenceDescriptor(
            capturedAt = captured,
            mediaType = if (lens != null) MediaType.VIDEO else MediaType.AUDIO,
            lens = lens,
        )
    }

    internal fun encryptFile(plain: File, encrypted: File, key: SecretKey) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        DataOutputStream(BufferedOutputStream(FileOutputStream(encrypted))).use { output ->
            output.writeInt(MAGIC)
            output.writeByte(cipher.iv.size)
            output.write(cipher.iv)
            CipherOutputStream(output, cipher).use { cipherOutput ->
                FileInputStream(plain).use { input -> input.copyTo(cipherOutput, BUFFER_SIZE) }
            }
        }
    }

    /**
     * Decrypt a sealed segment, failing loudly if it does not authenticate.
     *
     * Deliberately not `CipherInputStream`: that class catches `AEADBadTagException` on
     * close and reports a normal end of stream
     * instead. Evidence that had been truncated or altered would come back as a short
     * but "successful" file that still passes a length check — which defeats the entire
     * reason for choosing an authenticated cipher. Driving the cipher directly lets
     * `doFinal` throw, and the caller deletes the partial output.
     */
    internal fun decryptFile(encrypted: File, plain: File, key: SecretKey) {
        DataInputStream(BufferedInputStream(FileInputStream(encrypted))).use { input ->
            require(input.readInt() == MAGIC) { "Formato de evidencia desconocido" }
            val ivSize = input.readUnsignedByte()
            require(ivSize in 12..32) { "Cabecera de evidencia inválida" }
            val iv = ByteArray(ivSize)
            input.readFully(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            BufferedOutputStream(FileOutputStream(plain)).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    cipher.update(buffer, 0, read)?.let(output::write)
                }
                // Streamed rather than buffered whole: a 30 s segment is several MB and
                // there is no reason to hold it in memory. The tag check happens here.
                cipher.doFinal()?.let(output::write)
            }
        }
    }

    private fun resolveItem(context: Context, item: EvidenceItem): File {
        require(item.id == item.fileName || item.id == "uploaded/${item.fileName}") { "Identificador inválido" }
        require(item.fileName.endsWith(".bcenc") && '/' !in item.fileName && '\\' !in item.fileName) {
            "Nombre de evidencia inválido"
        }
        val root = directory(context).canonicalFile
        val candidate = File(root, item.id).canonicalFile
        require(candidate.path.startsWith(root.path + File.separator) && candidate.isFile) {
            "La evidencia ya no está disponible"
        }
        return candidate
    }

    internal fun safeSegmentName(stamp: String): String =
        "emergency_" + stamp.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(48)

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build())
        return generator.generateKey()
    }
}
