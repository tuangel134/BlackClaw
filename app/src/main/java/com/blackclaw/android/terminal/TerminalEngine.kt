package com.blackclaw.android.terminal

import android.content.Context
import com.blackclaw.android.adb.PrivilegedShell
import com.blackclaw.android.adb.RemoteAdb
import com.blackclaw.android.utils.XLog
import java.util.concurrent.TimeUnit

/**
 * The brain of BlackClaw's internal terminal — one persistent shell session
 * shared by the UI ([com.blackclaw.android.ui.terminal.TerminalActivity]) and
 * the AI (the `terminal` tool). Termux-like feel without a second app:
 *
 *  - Keeps a working directory across commands (unprivileged `sh` doesn't, so
 *    we cd-prefix each command and track `cd`).
 *  - Picks a backend: LOCAL (app-uid shell, always available), PRIVILEGED
 *    (Shizuku / self-paired ADB — full `pm`, `settings`, `am`…), or AUTO.
 *  - An `adb` router lets the session pair/connect/shell to OTHER devices over
 *    Wireless Debugging (adb-over-wifi) via [RemoteAdb].
 *
 * Thread-safety: [run] is synchronized so the UI and the agent can't interleave
 * and corrupt the working directory.
 */
object TerminalEngine {

    private const val TAG = "TerminalEngine"
    private const val MAX_OUTPUT = 16_000

    enum class Backend { AUTO, LOCAL, PRIVILEGED }

    @Volatile var backend: Backend = Backend.AUTO
    @Volatile var cwd: String = "/sdcard"
        private set

    private val blocked = listOf("rm -rf /", "rm -rf /*", "mkfs", "dd if=/dev/zero", ":(){", "reboot", "shutdown")

    /** Effective backend right now (resolves AUTO). */
    fun effectiveBackend(): Backend = when (backend) {
        Backend.AUTO -> if (PrivilegedShell.isAvailable()) Backend.PRIVILEGED else Backend.LOCAL
        else -> backend
    }

    fun prompt(): String {
        val b = when (effectiveBackend()) { Backend.PRIVILEGED -> "#"; else -> "$" }
        RemoteAdb.connectedTarget()?.let { return "[$it]$b " }
        return "$cwd$b "
    }

    @Synchronized
    fun run(context: Context, rawCommand: String): String {
        val command = rawCommand.trim()
        if (command.isEmpty()) return ""

        if (blocked.any { command.lowercase().contains(it) })
            return "bloqueado por seguridad: '$command'"

        val parts = command.split(Regex("\\s+"), limit = 2)
        val verb = parts[0]
        val rest = parts.getOrElse(1) { "" }.trim()

        return when (verb) {
            "clear", "cls" -> "\u000C"   // form-feed; UI treats it as "clear screen"
            "help", "?" -> helpText()
            "pwd" -> cwd
            "whoami" -> execShell(context, "id -un 2>/dev/null || whoami").ifBlank { "app (uid del proceso)" }
            "backend" -> handleBackend(rest)
            "cd" -> handleCd(context, rest)
            "adb" -> handleAdb(context, rest)
            else -> execShell(context, command)
        }.let { if (it.length > MAX_OUTPUT) it.take(MAX_OUTPUT) + "\n…[truncado]" else it }
    }

    private fun handleBackend(arg: String): String {
        if (arg.isBlank()) return "backend actual: ${backend.name.lowercase()} " +
            "(efectivo: ${effectiveBackend().name.lowercase()}). Uso: backend auto|local|privileged"
        return when (arg.lowercase()) {
            "auto" -> { backend = Backend.AUTO; "backend → auto" }
            "local" -> { backend = Backend.LOCAL; "backend → local" }
            "privileged", "adb", "shizuku" -> {
                backend = Backend.PRIVILEGED
                if (PrivilegedShell.isAvailable()) "backend → privileged (${PrivilegedShell.describe()})"
                else "backend → privileged, pero NO hay acceso privilegiado activo (${PrivilegedShell.describe()})"
            }
            else -> "valor inválido. Uso: backend auto|local|privileged"
        }
    }

    private fun handleCd(context: Context, arg: String): String {
        val targetDir = if (arg.isBlank() || arg == "~") "/sdcard" else arg
        // Resolve + verify through the active backend so symlinks/perales resuelven bien.
        val probe = execShell(context, "cd \"$targetDir\" 2>/dev/null && pwd")
        val resolved = probe.lineSequence().firstOrNull { it.startsWith("/") }?.trim()
        return if (resolved != null) {
            cwd = resolved
            ""   // like a real shell: cd is silent on success
        } else {
            "cd: no se puede acceder a '$targetDir'"
        }
    }

    private fun handleAdb(context: Context, args: String): String {
        if (args.isBlank()) return adbHelp()
        val a = args.split(Regex("\\s+"), limit = 2)
        val sub = a[0].lowercase()
        val subRest = a.getOrElse(1) { "" }.trim()
        return when (sub) {
            "devices" -> buildString {
                appendLine("Local (este dispositivo): ${com.blackclaw.android.adb.AdbController.describe()}")
                append("Remoto: ${RemoteAdb.describe()}")
            }
            "pair" -> {
                val p = subRest.split(Regex("\\s+"))
                if (p.size < 2) "uso: adb pair <host:puerto> <código>"
                else RemoteAdb.pair(context, p[0], p[1]).fold({ it }, { "error: ${it.message}" })
            }
            "connect" -> {
                if (subRest.isBlank()) "uso: adb connect <host:puerto>"
                else RemoteAdb.connect(context, subRest).fold({ it }, { "error: ${it.message}" })
            }
            "disconnect" -> { RemoteAdb.disconnect(); "desconectado del ADB remoto" }
            "shell" -> {
                if (subRest.isBlank()) return "uso: adb shell <comando>"
                // Prefer a live remote target; else run on the local device via
                // the privileged backend (self-ADB / Shizuku).
                if (RemoteAdb.connectedTarget() != null) {
                    RemoteAdb.shell(subRest) ?: "error: el shell remoto falló o expiró"
                } else if (PrivilegedShell.isAvailable()) {
                    PrivilegedShell.exec(subRest) ?: "error: el shell falló"
                } else {
                    "no hay dispositivo remoto conectado ni acceso privilegiado local. " +
                        "Usa 'adb connect host:puerto' o activa Shizuku/ADB en Modo Pro."
                }
            }
            else -> adbHelp()
        }
    }

    /** Run a command through the chosen backend, honoring the working dir. */
    private fun execShell(context: Context, command: String): String {
        val scoped = "cd \"$cwd\" 2>/dev/null; $command"
        return when (effectiveBackend()) {
            Backend.PRIVILEGED -> PrivilegedShell.exec(scoped, 20_000L)
                ?: "error: shell privilegiado no disponible o falló"
            else -> execLocal(scoped, 20)
        }
    }

    /** Unprivileged local shell (app uid) with concurrent output draining. */
    private fun execLocal(command: String, timeoutSec: Long): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val outBuffer = StringBuilder()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { br ->
                        val buf = CharArray(4096)
                        var n = br.read(buf)
                        while (n >= 0) {
                            if (outBuffer.length < MAX_OUTPUT) outBuffer.append(buf, 0, n)
                            n = br.read(buf)
                        }
                    }
                }
            }.apply { isDaemon = true; start() }

            val completed = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly(); reader.join(500)
                return "timeout después de ${timeoutSec}s"
            }
            reader.join(1000)
            val exit = process.exitValue()
            val out = outBuffer.toString()
            if (exit == 0) out else "[exit $exit] $out"
        } catch (e: Exception) {
            XLog.w(TAG, "execLocal failed: ${e.message}")
            "error: ${e.message}"
        }
    }

    private fun helpText(): String = """
        Terminal interno de BlackClaw. Comandos:
          <cmd>             ejecuta en el shell (backend activo)
          cd <dir> / pwd    navegación (la sesión recuerda el directorio)
          backend [auto|local|privileged]   elige/consulta el backend
          whoami            usuario efectivo
          clear             limpia la pantalla
          adb ...           control por Wireless Debugging (ver 'adb')
        Backend privileged requiere Shizuku o ADB emparejado (Modo Pro).
    """.trimIndent()

    private fun adbHelp(): String = """
        adb (por Wireless Debugging, sin PC):
          adb devices                       estado local y remoto
          adb pair <host:puerto> <código>   emparejar con otro dispositivo
          adb connect <host:puerto>         conectar a un dispositivo remoto
          adb shell <comando>               shell en el remoto (o local si hay Shizuku/ADB)
          adb disconnect                    cerrar la conexión remota
    """.trimIndent()
}
