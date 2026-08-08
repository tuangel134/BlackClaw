package com.blackclaw.android.perception

import android.content.Context
import android.net.Uri
import android.util.Base64

/** A bounded, caller-owned image payload suitable for a multimodal model request. */
data class VisionImage(
    val bytes: ByteArray,
    val mimeType: String,
    val ocrText: String,
) {
    val base64: String by lazy { Base64.encodeToString(bytes, Base64.NO_WRAP) }
}

/**
 * Loads the original image for vision while preserving OCR as auxiliary context.
 * The explicit cap avoids turning a malformed gallery URI into an unbounded heap
 * allocation or an unexpectedly huge cloud request.
 */
object VisionImageLoader {
    private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024

    fun load(context: Context, uri: Uri): VisionImage {
        val mimeType = context.contentResolver.getType(uri)
            ?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            readBounded(input)
        } ?: throw IllegalStateException("No pude abrir la imagen seleccionada")
        val ocrText = runCatching { ImageOcr.recognizeUri(uri) }.getOrDefault("")
        return VisionImage(bytes = bytes, mimeType = mimeType, ocrText = ocrText)
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > MAX_IMAGE_BYTES) {
                throw IllegalArgumentException("La imagen supera el límite de 12 MB")
            }
            output.write(buffer, 0, read)
        }
        if (output.size() == 0) throw IllegalArgumentException("La imagen está vacía")
        return output.toByteArray()
    }
}
