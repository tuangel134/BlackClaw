package com.blackclaw.android.terminal

import android.content.Context
import com.blackclaw.android.adb.PrivilegedShell
import com.blackclaw.android.adb.RemoteAdb

/**
 * The brain of BlackClaw's internal terminal.  Its LOCAL backend is a fixed,
 * offline Linux userland, so it behaves like a small Termux-style environment
 * without needing Shizuku, ADB, root, or a second app.
 *
 *  - Keeps a working directory across commands.
 *  - Starts in LOCAL (fixed Linux) and lets the manual console opt into
 *    PRIVILEGED (Shizuku / self-paired ADB — full `pm`, `settings`, `am`…).
 *  - An `adb` router lets the session pair/connect/shell to OTHER devices over
 *    Wireless Debugging (adb-over-wifi) via [RemoteAdb].
 *
 * The UI and agent deliberately have different working directories and the agent
 * is permanently local-only.  Privileged and remote-ADB functionality remains a
 * manual-console feature.
 */
object TerminalEngine {

    private const val TAG = "TerminalEngine"
    private const val MAX_OUTPUT = 16_000

    enum class Backend { AUTO, LOCAL, PRIVILEGED }

    @Volatile var backend: Backend = Backend.LOCAL
    @Volatile var cwd: String = "/home/blackclaw"
        private set

    /** The agent never shares cwd/backend/remote-ADB state with the manual console. */
    private var agentCwd: String = "/home/blackclaw"

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

    /**
     * Run the agent's isolated, unprivileged session.
     *
     * Its working directory persists across agent calls, but it can neither select the
     * privileged backend nor pair/connect to another Android device. The temporary
     * swap is guarded by the same monitor as [run], so the manual console cannot see
     * or inherit this state while a command is executing.
     */
    @Synchronized
    fun runForAgent(context: Context, rawCommand: String): String {
        val command = rawCommand.trim()
        val verb = command.takeWhile { !it.isWhitespace() }.lowercase()
        if (verb == "adb") return "adb no está disponible en la terminal del agente."
        if (verb == "backend") return "El agente usa siempre el Linux local aislado."

        val manualBackend = backend
        val manualCwd = cwd
        backend = Backend.LOCAL
        cwd = agentCwd
        return try {
            run(context, command)
        } finally {
            agentCwd = cwd
            cwd = manualCwd
            backend = manualBackend
        }
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
        val home = if (effectiveBackend() == Backend.LOCAL) "/home/blackclaw" else "/sdcard"
        val targetDir = if (arg.isBlank() || arg == "~") home else arg
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
            else -> execLocal(context, scoped)
        }
    }

    /** Fixed, unprivileged Linux userland.  This path never calls Android's sh. */
    private fun execLocal(context: Context, command: String): String =
        FixedTerminalEnvironment.execute(context, cwd, command)

    private fun helpText(): String = """
        Terminal Linux local de BlackClaw. Comandos:
          <cmd>             ejecuta en Linux aislado (bash, python3, git, curl, jq…)
          cd <dir> / pwd    navegación (la sesión recuerda el directorio)
          backend [auto|local|privileged]   cambia el backend manual opcional
          whoami            usuario efectivo
          clear             limpia la pantalla
          adb ...           control por Wireless Debugging (ver 'adb')
        Local no requiere Shizuku ni ADB. Backend privileged requiere Modo Pro.
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
