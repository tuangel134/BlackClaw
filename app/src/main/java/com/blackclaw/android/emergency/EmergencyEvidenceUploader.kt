package com.blackclaw.android.emergency

import android.content.Context
import android.net.Uri
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** Best-effort uploader. Only `.bcenc` ciphertext leaves the device. */
object EmergencyEvidenceUploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    fun enqueue(context: Context, encryptedFile: File) {
        if (!EmergencyBackupConfig.isReady || encryptedFile.extension != "bcenc") return
        Thread({ upload(context.applicationContext, encryptedFile) }, "BlackClawEvidenceUpload").start()
    }

    fun retryQueue(context: Context) {
        if (!EmergencyBackupConfig.isReady) return
        EmergencyEvidenceVault.directory(context).listFiles { file -> file.extension == "bcenc" }
            .orEmpty().sortedBy(File::lastModified).take(20).forEach { enqueue(context, it) }
    }

    private fun upload(context: Context, file: File) {
        if (!file.isFile || file.extension != "bcenc") return
        val remote = EmergencyBackupConfig.url.trimEnd('/') + "/" + Uri.encode(file.name)
        val builder = Request.Builder().url(remote)
            .put(file.asRequestBody("application/octet-stream".toMediaType()))
            .header("X-BlackClaw-Evidence", "encrypted-aes256-gcm")
        if (EmergencyBackupConfig.username.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(
                EmergencyBackupConfig.username, EmergencyBackupConfig.password))
        }
        runCatching {
            client.newCall(builder.build()).execute().use { response ->
                require(response.isSuccessful) { "HTTP ${response.code}" }
            }
            val uploadedDir = File(EmergencyEvidenceVault.directory(context), "uploaded").apply { mkdirs() }
            val archived = File(uploadedDir, file.name)
            if (archived.exists()) archived.delete()
            require(file.renameTo(archived)) { "No se pudo archivar el segmento respaldado" }
            EmergencyEventLog.append(context, "evidence_uploaded file=${archived.name}")
        }.onFailure {
            EmergencyEventLog.append(context, "evidence_upload_pending file=${file.name} error=${it.javaClass.simpleName}")
        }
    }
}
