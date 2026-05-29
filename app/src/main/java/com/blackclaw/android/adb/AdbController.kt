package com.blackclaw.android.adb

import android.content.Context
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * High-level orchestrator for BlackClaw's *self-ADB* — the app pairs and
 * connects to its OWN device's `adbd` over the loopback interface (127.0.0.1)
 * using the Wireless Debugging stack that ships in Android 11+.
 *
 * Why this works without a PC and without Shizuku:
 *
 *  - "Wireless debugging" makes `adbd` listen on a TLS port advertised over
 *    mDNS as `_adb-tls-connect._tcp`, and a separate pairing port advertised
 *    as `_adb-tls-pairing._tcp`.
 *  - Those services are reachable from the *same device* over loopback, so
 *    BlackClaw can pair + connect to itself. No router, no second machine.
 *  - After a one-time pairing (user types the 6-digit code shown by the OS),
 *    our RSA identity is trusted by adbd forever. Reconnect is silent.
 *
 * The actual TLS 1.3 + SPAKE2 handshake lives inside libadb-android; this class
 * is the glue + a friendly state machine for the UI and the shell tools.
 *
 * Threading: pairing/connect are blocking and must run off the main thread.
 * All public suspend fns hop to [Dispatchers.IO].
 */
object AdbController {

    private const val TAG = "AdbController"
    private const val KEY_PAIRED = "adb_self_paired"

    // How long to wait for mDNS to surface a port before giving up.
    private const val MDNS_TIMEOUT_MS = 8_000L
    private const val CONNECT_TIMEOUT_MS = 12_000L

    enum class State {
        /** No pairing has ever succeeded. User must run the pairing flow. */
        NOT_PAIRED,

        /** Paired before, but no live connection right now. */
        PAIRED_DISCONNECTED,

        /** Handshake in progress. */
        CONNECTING,

        /** Live TLS connection to adbd — shell() works. */
        CONNECTED,

        /** Last operation failed; see [lastError]. */
        ERROR,
    }

    @Volatile var state: State = State.NOT_PAIRED
        private set

    @Volatile var lastError: String? = null
        private set

    private var manager: AdbConnectionManager? = null

    /** Re-hydrate the "paired before" flag on first access. */
    fun init(context: Context) {
        if (KVUtils.getBoolean(KEY_PAIRED, false) && state == State.NOT_PAIRED) {
            state = State.PAIRED_DISCONNECTED
        }
    }

    fun isPaired(): Boolean = KVUtils.getBoolean(KEY_PAIRED, false)

    fun isConnected(): Boolean =
        state == State.CONNECTED && (manager?.isConnected() == true)

    private fun mgr(context: Context): AdbConnectionManager =
        manager ?: AdbConnectionManager.getInstance(context).also { manager = it }

    /**
     * Discover the pairing port that the OS advertises while the "Pair device
     * with pairing code" dialog is open. Returns null if nothing shows up in
     * [MDNS_TIMEOUT_MS] (dialog closed, wireless-debug off, or mDNS blocked).
     *
     * The user only ever sees a 6-digit code; we find the port for them so the
     * UX is "type the code, tap Pair".
     */
    suspend fun discoverPairingPort(context: Context): Int? = withContext(Dispatchers.IO) {
        discoverPort(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING)
    }

    private fun discoverPort(context: Context, serviceType: String): Int? {
        val portRef = AtomicInteger(-1)
        val hostRef = AtomicReference<InetAddress?>(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        val mdns = AdbMdns(context, serviceType) { host, port ->
            if (port > 0) {
                hostRef.set(host)
                portRef.set(port)
                latch.countDown()
            }
        }
        mdns.start()
        try {
            latch.await(MDNS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        } finally {
            mdns.stop()
        }
        val p = portRef.get()
        return if (p > 0) p else null
    }

    /**
     * One-time pairing. [code] is the 6-digit code from the OS dialog. If
     * [port] is null we try to discover it via mDNS first.
     *
     * On success we flag the identity as paired and immediately try to connect.
     */
    suspend fun pair(context: Context, code: String, port: Int? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            state = State.CONNECTING
            lastError = null
            try {
                val pairingPort = port ?: discoverPort(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING)
                if (pairingPort == null) {
                    state = State.ERROR
                    lastError = "No se encontró el puerto de emparejamiento. " +
                        "Mantén abierto el diálogo 'Vincular dispositivo con código' en Ajustes."
                    return@withContext Result.failure(IOException(lastError))
                }
                XLog.i(TAG, "Pairing on 127.0.0.1:$pairingPort")
                val ok = mgr(context).pair("127.0.0.1", pairingPort, code.trim())
                if (!ok) {
                    state = State.ERROR
                    lastError = "Emparejamiento rechazado. Verifica el código de 6 dígitos."
                    return@withContext Result.failure(IOException(lastError))
                }
                KVUtils.putBoolean(KEY_PAIRED, true)
                XLog.i(TAG, "Pairing OK — attempting first connect")
                // Best-effort connect; pairing success is what matters here.
                runCatching { connectBlocking(context) }
                Result.success(Unit)
            } catch (e: Throwable) {
                state = State.ERROR
                lastError = e.message ?: e.javaClass.simpleName
                XLog.e(TAG, "Pairing failed", e)
                Result.failure(e)
            }
        }

    /**
     * Fully automatic pairing: opens the system "Pair device with pairing code"
     * dialog, reads the 6-digit code + port off the screen via accessibility,
     * and pairs — the user never has to copy or type the code (which is
     * impossible anyway, since leaving the dialog regenerates it).
     *
     * Requires BlackClaw's accessibility service to be running. The caller is
     * responsible for having opened the Wireless Debugging screen first; here we
     * tap into "Pair device with pairing code" and read the resulting dialog.
     *
     * Returns a human-readable progress/result. On success [state] becomes
     * CONNECTED (or PAIRED_DISCONNECTED if the post-pair connect failed).
     */
    suspend fun autoPair(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        state = State.CONNECTING
        lastError = null
        val svc = com.blackclaw.android.service.ClawAccessibilityService.getConnectedInstance(2_000L)
        if (svc == null) {
            state = State.ERROR
            lastError = "El servicio de accesibilidad de BlackClaw no está activo. Actívalo y reintenta."
            return@withContext Result.failure(IllegalStateException(lastError))
        }

        // Give the pairing dialog time to render, then poll for the code/port.
        // We poll for a long window (~40 s) because the user needs to navigate
        // into Settings → Wireless debugging → "Pair device with pairing code"
        // AFTER tapping our button. The reader only sees the code once that
        // system dialog is the foreground (active) window — which is fine, the
        // actual pairing runs over loopback and doesn't need us in foreground.
        var code: String? = null
        var port = 0
        for (i in 0 until 80) { // ~40 s total
            val info = svc.readWirelessPairingInfo()
            if (info != null) {
                val parts = info.split(":")
                val c = parts.getOrNull(0)?.takeIf { it.length == 6 }
                val p = parts.getOrNull(1)?.toIntOrNull() ?: 0
                if (c != null) {
                    code = c
                    port = p
                    break
                }
            }
            Thread.sleep(500)
        }

        val finalCode = code
        if (finalCode == null) {
            state = State.ERROR
            lastError = "No pude leer el código en pantalla. Asegúrate de que el diálogo " +
                "'Vincular dispositivo con código' está abierto y visible."
            return@withContext Result.failure(IOException(lastError))
        }

        // If accessibility couldn't read the port, fall back to mDNS discovery.
        val pairingPort = if (port > 0) port
            else discoverPort(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) ?: 0
        if (pairingPort == 0) {
            state = State.ERROR
            lastError = "Leí el código ($finalCode) pero no encontré el puerto. Reintenta."
            return@withContext Result.failure(IOException(lastError))
        }

        XLog.i(TAG, "Auto-pairing with read code on 127.0.0.1:$pairingPort")
        try {
            val ok = mgr(context).pair("127.0.0.1", pairingPort, finalCode)
            if (!ok) {
                state = State.ERROR
                lastError = "Emparejamiento rechazado por adbd (código $finalCode)."
                return@withContext Result.failure(IOException(lastError))
            }
            KVUtils.putBoolean(KEY_PAIRED, true)
            runCatching { connectBlocking(context) }
            Result.success(Unit)
        } catch (e: Throwable) {
            state = State.ERROR
            lastError = e.message ?: e.javaClass.simpleName
            XLog.e(TAG, "Auto-pair failed", e)
            Result.failure(e)
        }
    }

    /**
     * Establish (or re-establish) the TLS connection. Uses mDNS auto-discovery
     * of the `_adb-tls-connect._tcp` port. Safe to call when already connected.
     */
    suspend fun connect(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        if (isConnected()) return@withContext Result.success(Unit)
        if (!isPaired()) {
            return@withContext Result.failure(IllegalStateException("Empareja primero el dispositivo."))
        }
        state = State.CONNECTING
        lastError = null
        try {
            connectBlocking(context)
            Result.success(Unit)
        } catch (e: Throwable) {
            state = State.ERROR
            lastError = e.message ?: e.javaClass.simpleName
            XLog.e(TAG, "Connect failed", e)
            Result.failure(e)
        }
    }

    private fun connectBlocking(context: Context) {
        val connected = mgr(context).autoConnect(context, CONNECT_TIMEOUT_MS)
        state = if (connected) State.CONNECTED else State.PAIRED_DISCONNECTED
        if (!connected) {
            lastError = "No se pudo conectar. Asegúrate de que 'Depuración inalámbrica' está activada."
            throw IOException(lastError)
        }
        XLog.i(TAG, "ADB self-connection established")
    }

    /**
     * Run a shell command over the live ADB connection and return its combined
     * output. Auto-connects if we're paired but disconnected.
     *
     * Returns null when ADB isn't usable (not paired, or connect failed) so the
     * caller can fall back to another backend.
     */
    suspend fun shell(context: Context, command: String, timeoutMs: Long = 8_000L): String? =
        withContext(Dispatchers.IO) {
            if (!isPaired()) return@withContext null
            if (!isConnected()) {
                runCatching { connectBlocking(context) }.getOrElse { return@withContext null }
            }
            try {
                shellBlocking(context, command, timeoutMs)
            } catch (e: Throwable) {
                XLog.w(TAG, "shell('$command') failed: ${e.message}")
                // Connection may have dropped — mark disconnected so next call retries.
                state = State.PAIRED_DISCONNECTED
                null
            }
        }

    private fun shellBlocking(context: Context, command: String, timeoutMs: Long): String {
        val stream: AdbStream = mgr(context).openStream("shell:$command")
        val out = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        stream.use { s ->
            val input = s.openInputStream()
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                if (s.isClosed && input.available() == 0) break
                val avail = input.available()
                if (avail <= 0) {
                    Thread.sleep(8)
                    if (s.isClosed) break
                    continue
                }
                val n = input.read(buf, 0, minOf(buf.size, avail))
                if (n < 0) break
                out.append(String(buf, 0, n, Charsets.UTF_8))
            }
        }
        return out.toString().trim()
    }

    /** Fire-and-forget shell (taps/swipes) — doesn't wait for output. */
    suspend fun shellFast(context: Context, command: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isPaired()) return@withContext false
            if (!isConnected()) {
                runCatching { connectBlocking(context) }.getOrElse { return@withContext false }
            }
            try {
                mgr(context).openStream("shell:$command").use { /* close immediately */ }
                true
            } catch (e: Throwable) {
                XLog.w(TAG, "shellFast('$command') failed: ${e.message}")
                state = State.PAIRED_DISCONNECTED
                false
            }
        }

    fun disconnect() {
        runCatching { manager?.disconnect() }
        if (state == State.CONNECTED) state = State.PAIRED_DISCONNECTED
    }

    /** Wipe the paired flag (the cert identity stays; user can re-pair). */
    fun forgetPairing() {
        disconnect()
        KVUtils.putBoolean(KEY_PAIRED, false)
        state = State.NOT_PAIRED
    }

    fun describe(): String = when (state) {
        State.NOT_PAIRED -> "ADB sin emparejar"
        State.PAIRED_DISCONNECTED -> "ADB emparejado · desconectado"
        State.CONNECTING -> "ADB conectando…"
        State.CONNECTED -> "ADB conectado · acciones rápidas activas"
        State.ERROR -> "ADB error: ${lastError ?: "desconocido"}"
    }
}
