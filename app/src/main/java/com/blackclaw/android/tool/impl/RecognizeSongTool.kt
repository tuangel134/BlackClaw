package com.blackclaw.android.tool.impl

import android.content.Context
import android.os.Build
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.cards.AssistCard
import com.blackclaw.android.cards.AssistCardCodec
import com.blackclaw.android.cards.SummaryKind
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import com.blackclaw.android.utils.XLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RecognizeSongTool : BaseTool() {

    override fun getName() = "recognize_song"
    override fun getDisplayName() = "Reconocer Canción"
    override fun getDescriptionEN() = "Recognize a song playing nearby using the device's built-in music recognition (Android 14+). Returns the song title and artist."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters(): List<ToolParameter> = emptyList()

    override fun execute(params: Map<String, Any>): ToolResult {
        if (Build.VERSION.SDK_INT < 34) {
            return ToolResult.error("El reconocimiento de canciones requiere Android 14+. Tu dispositivo tiene Android ${Build.VERSION.RELEASE}.")
        }
        return try {
            recognizeViaSystemApi()
        } catch (e: Exception) {
            XLog.w("RecognizeSong", "System music recognition failed: ${e.message}")
            ToolResult.error("No pude reconocer la canción: ${e.message}")
        }
    }

    private fun recognizeViaSystemApi(): ToolResult {
        val ctx = ClawApplication.instance
        val mrmClass = Class.forName("android.media.MusicRecognitionManager")
        val mrm = ctx.getSystemService("music_recognition") ?: return ToolResult.error(
            "Este dispositivo no tiene servicio de reconocimiento de música.")

        val supportedMethod = mrmClass.getMethod("isMusicRecognitionSupported")
        val supported = supportedMethod.invoke(mrm) as? Boolean ?: false
        if (!supported) {
            return ToolResult.error("Este dispositivo no soporta reconocimiento de música nativo.")
        }

        val latch = CountDownLatch(1)
        var resultText: String? = null
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var errorText: String? = null

        val requestClass = Class.forName("android.media.MusicRecognitionRequest")
        val builderClass = Class.forName("android.media.MusicRecognitionRequest\$Builder")
        val request = builderClass.getDeclaredConstructor().newInstance()
            .let { builderClass.getMethod("build").invoke(it) }

        val callbackClass = Class.forName("android.media.MusicRecognitionSessionCallback")
        val callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
            callbackClass.classLoader, arrayOf(callbackClass)
        ) { _, method, args ->
            when (method.name) {
                "onRecognitionSucceeded" -> {
                    val result = args?.get(0)
                    val metadata = result?.javaClass?.getMethod("getMetadata")?.invoke(result)
                    title = (metadata as? android.media.MediaMetadata)
                        ?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "Desconocida"
                    artist = (metadata as? android.media.MediaMetadata)
                        ?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "Artista desconocido"
                    album = (metadata as? android.media.MediaMetadata)
                        ?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)
                    resultText = buildString {
                        append("🎵 $title — $artist")
                        if (!album.isNullOrBlank()) append(" ($album)")
                    }
                    latch.countDown()
                }
                "onRecognitionFailed" -> {
                    val code = args?.get(0) as? Int ?: -1
                    errorText = "No pude identificar la canción (código $code). Intenta con la música más clara."
                    latch.countDown()
                }
            }
            null
        }

        mrmClass.getMethod("createMusicRecognitionSession",
            requestClass, java.util.concurrent.Executor::class.java, callbackClass
        ).invoke(mrm, request, ctx.mainExecutor, callbackProxy)

        if (!latch.await(15, TimeUnit.SECONDS)) {
            return ToolResult.error("Se agotó el tiempo. Acerca el teléfono a la música e intenta de nuevo.")
        }

        return resultText?.let {
            ToolResult.successWithCards(it, AssistCardCodec.encode(listOf(AssistCard.Summary(
                SummaryKind.SONG,
                "Canción identificada",
                title.orEmpty().ifBlank { "Desconocida" },
                listOfNotNull(artist, album).joinToString(" · "),
            ))))
        }
            ?: ToolResult.error(errorText ?: "No se pudo reconocer la canción.")
    }
}
