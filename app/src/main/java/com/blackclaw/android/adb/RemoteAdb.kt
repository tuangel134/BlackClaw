package com.blackclaw.android.adb

import android.content.Context
import com.blackclaw.android.utils.XLog
import io.github.muntashirakon.adb.AdbStream

/**
 * Connect BlackClaw to OTHER devices/servers over Wireless Debugging
 * (adb-over-wifi), driving the terminal's `adb` commands: pair, connect, shell,
 * disconnect. Uses [RemoteAdbConnectionManager] so it stays isolated from the
 * self/loopback ADB connection.
 *
 * One remote target at a time (mirrors how `adb` keeps a single default
 * transport). Reconnecting to a new host replaces the previous session.
 */
object RemoteAdb {

    private const val TAG = "RemoteAdb"

    @Volatile private var mgr: RemoteAdbConnectionManager? = null
    @Volatile private var target: String? = null

    /** host:port we're currently connected to, or null. */
    fun connectedTarget(): String? = if (mgr?.isConnected == true) target else null

    private fun manager(context: Context): RemoteAdbConnectionManager =
        mgr ?: RemoteAdbConnectionManager.create(context).also { mgr = it }

    /**
     * Pair with a remote device (Android 11+ Wireless Debugging → "Pair device
     * with pairing code"). [hostPort] is "ip:pairingPort"; [code] the 6 digits.
     */
    fun pair(context: Context, hostPort: String, code: String): Result<String> {
        val (host, port) = parseHostPort(hostPort) ?: return Result.failure(
            IllegalArgumentException("Formato inválido. Usa host:puerto (ej. 192.168.1.50:37000)."))
        return try {
            val ok = manager(context).pair(host, port, code.trim())
            if (ok) Result.success("Emparejado con $host. Ahora usa: adb connect $host:<puerto-de-conexión>")
            else Result.failure(IllegalStateException("Emparejamiento rechazado. Revisa el código."))
        } catch (e: Throwable) {
            XLog.w(TAG, "pair failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** Connect to a remote device's adbd. [hostPort] is "ip:connectPort". */
    fun connect(context: Context, hostPort: String): Result<String> {
        val (host, port) = parseHostPort(hostPort) ?: return Result.failure(
            IllegalArgumentException("Formato inválido. Usa host:puerto (ej. 192.168.1.50:5555)."))
        return try {
            val m = manager(context)
            runCatching { m.disconnect() }
            val ok = m.connect(host, port)
            if (ok) {
                target = "$host:$port"
                Result.success("Conectado a $host:$port")
            } else {
                Result.failure(IllegalStateException(
                    "No se pudo conectar a $host:$port. ¿Depuración inalámbrica activa y emparejado?"))
            }
        } catch (e: Throwable) {
            XLog.w(TAG, "connect failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** Run a shell command on the connected remote device. */
    fun shell(command: String, timeoutMs: Long = 10_000L): String? {
        val m = mgr ?: return null
        if (!m.isConnected) return null
        return try {
            val stream: AdbStream = m.openStream("shell:$command")
            val out = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            stream.use { s ->
                val input = s.openInputStream()
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    if (s.isClosed && input.available() == 0) break
                    val avail = input.available()
                    if (avail <= 0) { Thread.sleep(8); if (s.isClosed) break; continue }
                    val n = input.read(buf, 0, minOf(buf.size, avail))
                    if (n < 0) break
                    out.append(String(buf, 0, n, Charsets.UTF_8))
                }
            }
            out.toString().trim()
        } catch (e: Throwable) {
            XLog.w(TAG, "remote shell('$command') failed: ${e.message}")
            null
        }
    }

    fun disconnect() {
        runCatching { mgr?.disconnect() }
        target = null
    }

    fun describe(): String =
        connectedTarget()?.let { "ADB remoto conectado a $it" } ?: "ADB remoto sin conexión"

    private fun parseHostPort(s: String): Pair<String, Int>? {
        val idx = s.lastIndexOf(':')
        if (idx <= 0 || idx == s.length - 1) return null
        val host = s.substring(0, idx).trim()
        val port = s.substring(idx + 1).trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return host to port
    }
}
