package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * TCP ping (uses InetAddress.isReachable + a TCP-connect fallback). Doesn't need
 * raw ICMP perms which Android 10+ blocks for non-system apps.
 */
class PingHostTool : BaseTool() {
    override fun getName() = "ping_host"
    override fun getDisplayName() = "Ping"
    override fun getDescriptionEN() =
        "Mide latencia a un host (TCP). Pasa 'host' (e.g. 'google.com') y opcional 'port' (default 80)."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("host", "string", "Hostname o IP.", true),
        ToolParameter("port", "integer", "Puerto TCP (default 80).", false),
        ToolParameter("count", "integer", "Cuántos pings (1..10, default 3).", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val host = requireString(params, "host").trim()
        if (host.isEmpty()) return ToolResult.error("host vacío")
        val port = optionalInt(params, "port", 80).coerceIn(1, 65535)
        val count = optionalInt(params, "count", 3).coerceIn(1, 10)

        return try {
            val resolved = InetAddress.getByName(host)
            val ip = resolved.hostAddress
            val timings = mutableListOf<Long>()
            for (i in 0 until count) {
                val t0 = System.nanoTime()
                val ok = runCatching {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(resolved, port), 3000)
                    }
                    true
                }.getOrDefault(false)
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                if (ok) timings.add(elapsed)
                Thread.sleep(50)
            }
            if (timings.isEmpty()) {
                return ToolResult.error("No respondió tras $count intentos.")
            }
            val avg = timings.average()
            val min = timings.min()
            val max = timings.max()
            ToolResult.success(
                "$host ($ip):$port — ${timings.size}/$count ok\n" +
                "min=%dms avg=%.0fms max=%dms".format(min, avg, max)
            )
        } catch (e: Exception) {
            ToolResult.error("Ping falló: ${e.message}")
        }
    }
}
