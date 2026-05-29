package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Quick download speed probe. Pulls 1 MB from Cloudflare's speed test endpoint
 * (or a configurable mirror) and reports throughput.
 */
class NetworkSpeedTool : BaseTool() {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override fun getName() = "network_speed"
    override fun getDisplayName() = "Velocidad red"
    override fun getDescriptionEN() =
        "Mide la velocidad de descarga descargando 1 MB de un endpoint público. " +
        "No mide subida; útil para comprobar si la red va lenta."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters() = emptyList<ToolParameter>()

    override fun execute(params: Map<String, Any>): ToolResult {
        // Cloudflare speed-test endpoint, returns a chosen size of random bytes
        val url = "https://speed.cloudflare.com/__down?bytes=1048576"
        val req = Request.Builder().url(url).get().build()
        return try {
            val t0 = System.nanoTime()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ToolResult.error("HTTP ${resp.code}")
                val bytes = resp.body?.bytes()?.size ?: 0
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
                if (bytes <= 0) return ToolResult.error("Sin datos")
                val mbps = (bytes * 8.0 / 1024 / 1024) / (elapsedMs / 1000.0)
                ToolResult.success(
                    "Descarga: %.0f KB en %.0f ms = %.2f Mbps".format(
                        bytes / 1024.0, elapsedMs, mbps
                    )
                )
            }
        } catch (e: Exception) {
            ToolResult.error("Test falló: ${e.message}")
        }
    }
}
