package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import java.util.concurrent.TimeUnit

/**
 * Remote shell execution — connects to a PC/server via SSH or raw TCP
 * and executes commands. Allows BlackClaw to diagnose and fix problems
 * on the user's computer directly from the phone.
 *
 * Usage scenarios:
 * - "Mi PC no conecta al wifi, arréglalo" → ssh → nmcli / netsh
 * - "Analiza mi computadora" → ssh → uptime, df, top
 * - "Mata el proceso que está usando toda la RAM" → ssh → kill
 * - "Reinicia el servidor web" → ssh → systemctl restart nginx
 *
 * Security: SSH credentials are encrypted at rest with an AndroidKeyStore-backed
 * AES-GCM key. Legacy MMKV credentials are migrated lazily and deleted only after
 * encrypted read-back succeeds. The user must explicitly configure the connection.
 */

class RemoteShellTool : BaseTool() {
    override fun getName() = "remote_shell"
    override fun getDisplayName() = "Shell remoto"
    override fun getDescriptionEN() =
        "Execute a command on a remote machine (PC/server) via SSH. " +
        "The user must have configured a connection first (remote_connect). " +
        "Use for diagnosing/fixing the user's PC: network issues, process management, " +
        "disk space, service restarts, etc. Output capped at 8KB."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "ejecuta un comando en un PC/servidor remoto via SSH"
    override fun getParameters() = listOf(
        ToolParameter("command", "string", "Shell command to execute on the remote machine.", true),
        ToolParameter("host", "string", "Optional: target host alias or IP. Default: last connected.", false),
        ToolParameter("timeout_seconds", "integer", "Command timeout. Default 30.", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val command = requireString(params, "command").trim()
        if (command.isEmpty()) return ToolResult.error("Comando vacío.")

        val hostAlias = optionalString(params, "host", "")
        val timeout = optionalInt(params, "timeout_seconds", 30).coerceIn(5, 120)

        val conn = if (hostAlias.isNotBlank()) {
            RemoteConnectionStore.getConnection(hostAlias)
        } else {
            RemoteConnectionStore.getDefaultConnection()
        } ?: return ToolResult.error(
            "No hay conexión remota configurada. El usuario debe configurarla primero con remote_connect " +
            "(necesita: IP del PC, usuario SSH, y contraseña o que el PC tenga un servidor SSH activo)."
        )

        return try {
            val output = SshExecutor.execute(conn, command, timeout)
            ToolResult.success(output.ifBlank { "(sin output)" })
        } catch (e: Exception) {
            XLog.w("RemoteShell", "Remote command failed: ${e.message}")
            ToolResult.error("Error ejecutando en ${conn.host}: ${e.message}")
        }
    }
}

class RemoteConnectTool : BaseTool() {
    override fun getName() = "remote_connect"
    override fun getDisplayName() = "Conectar PC"
    override fun getDescriptionEN() =
        "Configure a remote SSH connection to a PC/server. Stores credentials locally. " +
        "After this, use remote_shell to execute commands. " +
        "The target machine needs an SSH server running (Linux/Mac have it by default, " +
        "Windows needs OpenSSH server enabled)."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "configura conexión SSH a un PC/servidor"
    override fun getParameters() = listOf(
        ToolParameter("host", "string", "IP address or hostname of the target machine.", true),
        ToolParameter("user", "string", "SSH username.", true),
        ToolParameter("password", "string", "SSH password (stored locally, never transmitted elsewhere).", true),
        ToolParameter("port", "integer", "SSH port. Default 22.", false),
        ToolParameter("alias", "string", "Friendly name for this connection (e.g. 'mi-pc'). Default: host.", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val host = requireString(params, "host").trim()
        val user = requireString(params, "user").trim()
        val password = requireString(params, "password")
        val port = optionalInt(params, "port", 22)
        val alias = optionalString(params, "alias", host)

        val conn = RemoteConnectionStore.Connection(
            alias = alias, host = host, port = port, user = user, password = password,
        )

        // Test connection
        return try {
            val result = SshExecutor.executeDetailed(conn, "echo BlackClaw_connected && uname -a", 10)
            val testOutput = result.output
            if (testOutput.contains("BlackClaw_connected")) {
                // Store the fingerprint alongside the credentials, so every later
                // connect is checked against it and a substituted host is refused
                // before the password is sent.
                if (!RemoteConnectionStore.saveConnection(
                        conn.copy(hostKeyFingerprint = result.hostKeyFingerprint)
                    )
                ) {
                    return ToolResult.error(
                        "La conexión SSH funcionó, pero no pude guardar las credenciales de forma segura. " +
                            "Desbloquea el dispositivo e inténtalo de nuevo."
                    )
                }
                val systemInfo = testOutput.substringAfter("BlackClaw_connected").trim()
                // Surfaced, not hidden: first contact is inherently unverified, and
                // the user is the only one who can close that gap by comparing the
                // fingerprint against the machine itself.
                val pinNote = if (result.hostKeyFingerprint.isNotBlank()) {
                    "\n" + SshHostKeyPolicy.firstUseMessage(host, result.hostKeyFingerprint)
                } else {
                    ""
                }
                ToolResult.success("✓ Conectado a $host como $user. Sistema: $systemInfo$pinNote")
            } else {
                ToolResult.error("Conexión establecida pero el test falló. Output: ${testOutput.take(200)}")
            }
        } catch (e: Exception) {
            ToolResult.error("No pude conectar a $host:$port como $user. Error: ${e.message}. " +
                "Verifica que el SSH esté activo en el PC y que el teléfono esté en la misma red.")
        }
    }
}

class RemoteDisconnectTool : BaseTool() {
    override fun getName() = "remote_disconnect"
    override fun getDisplayName() = "Desconectar PC"
    override fun getDescriptionEN() = "Remove a saved remote connection."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "elimina una conexión remota guardada"
    override fun getParameters() = listOf(
        ToolParameter("alias", "string", "Connection alias or 'all' to remove all.", true),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val alias = requireString(params, "alias").trim()
        if (alias == "all") {
            return if (RemoteConnectionStore.clearAll()) {
                ToolResult.success("Todas las conexiones remotas eliminadas.")
            } else {
                ToolResult.error("No pude borrar las conexiones remotas del almacenamiento seguro.")
            }
        }
        if (RemoteConnectionStore.getConnection(alias) == null) {
            return ToolResult.error("No encontré conexión '$alias'.")
        }
        return if (RemoteConnectionStore.removeConnection(alias)) {
            ToolResult.success("Conexión '$alias' eliminada.")
        } else {
            ToolResult.error("No pude eliminar la conexión '$alias' del almacenamiento seguro.")
        }
    }
}

class RemoteListTool : BaseTool() {
    override fun getName() = "remote_list"
    override fun getDisplayName() = "Ver conexiones remotas"
    override fun getDescriptionEN() = "List saved remote SSH connections."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "lista las conexiones SSH guardadas"
    override fun getParameters() = emptyList<ToolParameter>()
    override fun execute(params: Map<String, Any>): ToolResult {
        val connections = RemoteConnectionStore.allConnections()
        if (connections.isEmpty()) return ToolResult.success("No hay conexiones remotas configuradas.")
        val sb = StringBuilder("Conexiones remotas:\n")
        connections.forEach { c ->
            sb.append("- ${c.alias}: ${c.user}@${c.host}:${c.port}\n")
        }
        return ToolResult.success(sb.toString().trim())
    }
}

/** Diagnose tool — runs multiple commands and returns a structured report. */
class RemoteDiagnoseTool : BaseTool() {
    override fun getName() = "remote_diagnose"
    override fun getDisplayName() = "Diagnosticar PC"
    override fun getDescriptionEN() =
        "Run a diagnostic check on the remote PC. Collects: OS info, uptime, disk usage, " +
        "memory, network status, running processes. Returns a structured report the AI can analyze."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "ejecuta diagnóstico completo del PC remoto (disco, red, RAM, procesos)"
    override fun getParameters() = listOf(
        ToolParameter("focus", "string", "Optional focus area: network|disk|memory|processes|all. Default all.", false),
        ToolParameter("host", "string", "Optional: which connection to use.", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val focus = optionalString(params, "focus", "all").lowercase()
        val hostAlias = optionalString(params, "host", "")
        val conn = if (hostAlias.isNotBlank()) {
            RemoteConnectionStore.getConnection(hostAlias)
        } else {
            RemoteConnectionStore.getDefaultConnection()
        } ?: return ToolResult.error("No hay conexión remota configurada.")

        // Detect OS first
        val osCheck = try {
            SshExecutor.execute(conn, "uname -s 2>/dev/null || echo WINDOWS", 10)
        } catch (e: Exception) {
            return ToolResult.error("No pude conectar: ${e.message}")
        }

        val isWindows = osCheck.trim().contains("WINDOWS") || osCheck.contains("MINGW") || osCheck.contains("MSYS")
        val commands = buildDiagCommands(focus, isWindows)

        val sb = StringBuilder("=== Diagnóstico del PC (${conn.host}) ===\n\n")
        for ((label, cmd) in commands) {
            val output = try {
                SshExecutor.execute(conn, cmd, 15)
            } catch (e: Exception) {
                "(error: ${e.message})"
            }
            sb.append("## $label\n")
            sb.append(output.take(1500))
            sb.append("\n\n")
        }
        return ToolResult.success(sb.toString().take(8000))
    }

    private fun buildDiagCommands(focus: String, isWindows: Boolean): List<Pair<String, String>> {
        val commands = mutableListOf<Pair<String, String>>()
        if (isWindows) {
            if (focus == "all" || focus == "network") {
                commands.add("Red" to "ipconfig & netsh wlan show interfaces & ping -n 1 8.8.8.8")
            }
            if (focus == "all" || focus == "disk") {
                commands.add("Disco" to "wmic logicaldisk get size,freespace,caption")
            }
            if (focus == "all" || focus == "memory") {
                commands.add("Memoria" to "wmic OS get FreePhysicalMemory,TotalVisibleMemorySize /Value")
            }
            if (focus == "all" || focus == "processes") {
                commands.add("Procesos (top CPU)" to "tasklist /FO CSV | sort /R")
            }
            if (focus == "all") {
                commands.add("Sistema" to "systeminfo | findstr /C:\"OS\" /C:\"System\" /C:\"Boot\"")
            }
        } else {
            // Linux/Mac
            if (focus == "all") {
                commands.add("Sistema" to "uname -a && uptime && cat /etc/os-release 2>/dev/null | head -3")
            }
            if (focus == "all" || focus == "network") {
                commands.add("Red" to "ip addr show 2>/dev/null || ifconfig; echo '---'; " +
                    "ping -c1 -W2 8.8.8.8 2>&1; echo '---'; " +
                    "cat /etc/resolv.conf 2>/dev/null; " +
                    "nmcli device status 2>/dev/null || networksetup -listallhardwareports 2>/dev/null")
            }
            if (focus == "all" || focus == "disk") {
                commands.add("Disco" to "df -h")
            }
            if (focus == "all" || focus == "memory") {
                commands.add("Memoria" to "free -h 2>/dev/null || vm_stat 2>/dev/null; echo '---'; swapon --show 2>/dev/null")
            }
            if (focus == "all" || focus == "processes") {
                commands.add("Procesos (top CPU)" to "ps aux --sort=-%cpu 2>/dev/null | head -15 || ps aux | head -15")
            }
        }
        return commands
    }
}

// ── Fixed local Linux terminal (no root, Shizuku, or ADB) ──

class LocalTerminalTool : BaseTool() {
    override fun getName() = "terminal"
    override fun getDisplayName() = "Terminal local"
    override fun getDescriptionEN() =
        "Run a command in BlackClaw's fixed, offline Linux terminal. It has a persistent " +
        "working directory and Bash, Python 3, Git, curl, jq and core Unix utilities. " +
        "It is always unprivileged: it cannot use Android shell commands, Shizuku, ADB, " +
        "or remote devices. Built-ins: cd, pwd, whoami, clear and help."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "terminal Linux fijo y aislado (sin Shizuku ni ADB)"
    override fun getParameters() = listOf(
        ToolParameter("command", "string", "Comando a ejecutar en la sesión del terminal.", true),
        ToolParameter("timeout_seconds", "integer", "Reservado (el engine gestiona el timeout).", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        if (!com.blackclaw.android.terminal.TerminalConfig.enabled) {
            return ToolResult.error("La terminal del agente está desactivada. Actívala en Ajustes → Terminal.")
        }
        val command = requireString(params, "command").trim()
        if (command.isEmpty()) return ToolResult.error("Comando vacío.")
        val out = com.blackclaw.android.terminal.TerminalEngine.runForAgent(
            com.blackclaw.android.ClawApplication.instance, command)
        val clean = out.replace("\u000C", "").trim()
        val capped = if (clean.length > 8000) clean.take(8000) + "\n…[truncado]" else clean
        return ToolResult.success(capped.ifBlank { "(exit 0, sin output)" })
    }
}

// ── Host key pinning glue ──

/**
 * JSch [com.jcraft.jsch.HostKeyRepository] backed by the single fingerprint pinned on
 * a stored connection.
 *
 * Wiring this in (rather than inspecting `session.hostKey` after the fact) is what
 * makes the check meaningful: JSch consults the repository during key exchange, so a
 * mismatch aborts the connection BEFORE [com.jcraft.jsch.Session.connect] proceeds to
 * authentication. Checking afterwards would mean the plaintext password had already
 * been handed to whoever answered — the exact thing being defended against.
 *
 * The repository holds at most one host, because a
 * [RemoteConnectionStore.Connection] is one host. Anything else JSch asks about is
 * not something we have an opinion on, so it is reported as unknown.
 */
private class PinnedHostKeyRepository(
    private val pinnedFingerprint: String,
) : com.jcraft.jsch.HostKeyRepository {

    /** Fingerprint the server actually offered, for reporting either way. */
    var presentedFingerprint: String = ""
        private set

    /** True once JSch accepted a first-contact key, i.e. there is something new to persist. */
    var learnedNewKey: Boolean = false
        private set

    private var lastVerdict: SshHostKeyPolicy.Verdict? = null

    /** Whether the most recent [check] was first contact, consulted by the UserInfo. */
    val lastCheckWasFirstContact: Boolean
        get() = lastVerdict == SshHostKeyPolicy.Verdict.TRUST_ON_FIRST_USE

    override fun check(host: String?, key: ByteArray?): Int {
        // No key at all is not a reason to trust anything.
        if (key == null || key.isEmpty()) {
            lastVerdict = SshHostKeyPolicy.Verdict.MISMATCH
            return com.jcraft.jsch.HostKeyRepository.CHANGED
        }
        val presented = SshHostKeyPolicy.fingerprint(key)
        presentedFingerprint = presented
        val verdict = SshHostKeyPolicy.verdict(pinnedFingerprint, presented)
        lastVerdict = verdict
        return when (verdict) {
            SshHostKeyPolicy.Verdict.MATCH -> com.jcraft.jsch.HostKeyRepository.OK
            SshHostKeyPolicy.Verdict.TRUST_ON_FIRST_USE ->
                com.jcraft.jsch.HostKeyRepository.NOT_INCLUDED
            SshHostKeyPolicy.Verdict.MISMATCH -> com.jcraft.jsch.HostKeyRepository.CHANGED
        }
    }

    override fun add(hostkey: com.jcraft.jsch.HostKey?, ui: com.jcraft.jsch.UserInfo?) {
        // Reached only on first contact, after the UserInfo consented. Recorded here
        // and persisted by the caller once the connection has actually succeeded —
        // pinning a key for a connection that then failed to authenticate would pin
        // whatever an attacker offered.
        learnedNewKey = true
    }

    // Removal is driven by the user deleting the stored connection (remote_disconnect),
    // never by the handshake. A repository that can forget a pin mid-handshake is a
    // repository that can be talked out of enforcing it.
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

    override fun getKnownHostsRepositoryID(): String = "blackclaw-pinned-host-key"
    override fun getHostKey(): Array<com.jcraft.jsch.HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<com.jcraft.jsch.HostKey> =
        emptyArray()
}

/**
 * Answers the two questions JSch asks during host key checking, and nothing else.
 *
 * It says yes to first contact and no to a changed key. The decision is taken from
 * the repository's own last verdict rather than by matching the prompt text, so a
 * reworded JSch message cannot turn a refusal into an acceptance.
 *
 * It must also implement [com.jcraft.jsch.UIKeyboardInteractive]: JSch skips
 * keyboard-interactive authentication entirely when a UserInfo is present that does
 * not implement it, and some servers (Windows OpenSSH in particular) offer only that
 * method. Without this, adding host key checking would have quietly broken login on
 * those hosts.
 */
private class HostKeyPinningUserInfo(
    private val repository: PinnedHostKeyRepository,
    private val password: String,
) : com.jcraft.jsch.UserInfo, com.jcraft.jsch.UIKeyboardInteractive {

    override fun getPassphrase(): String? = null
    override fun getPassword(): String = password
    override fun promptPassword(message: String?): Boolean = false
    override fun promptPassphrase(message: String?): Boolean = false

    /**
     * Yes only for the unknown-key prompt. On a changed key this returns false and
     * JSch aborts the connection, which is the whole point.
     */
    override fun promptYesNo(message: String?): Boolean = repository.lastCheckWasFirstContact

    override fun showMessage(message: String?) = Unit

    /**
     * Mirrors JSch's own no-UserInfo behaviour: answer a single hidden prompt with
     * the stored password, and decline anything more elaborate rather than replaying
     * the password into prompts we cannot interpret.
     */
    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<out String>?,
        echo: BooleanArray?,
    ): Array<String>? {
        if (prompt == null || prompt.size != 1) return null
        if (echo != null && echo.isNotEmpty() && echo[0]) return null
        return arrayOf(password)
    }
}

// ── SSH Executor (JSch-based, pure Java, no external binaries) ──

object SshExecutor {
    private const val TAG = "SshExecutor"

    /**
     * Outcome of a command run, including what the host key check concluded so the
     * calling tool can persist a first-contact pin and tell the user about it.
     */
    data class Result(
        val output: String,
        val hostKeyFingerprint: String,
        val pinnedOnThisConnect: Boolean,
    )

    /**
     * Execute a command over SSH using JSch (pure Java).
     * Works on all Android versions without external binaries.
     */
    fun execute(conn: RemoteConnectionStore.Connection, command: String, timeoutSec: Int): String =
        executeDetailed(conn, command, timeoutSec).output

    /**
     * As [execute], but reports the host key outcome too.
     *
     * Host key verification happens inside [com.jcraft.jsch.Session.connect], before
     * the password is sent. On a mismatch this throws and the command never runs.
     */
    fun executeDetailed(
        conn: RemoteConnectionStore.Connection,
        command: String,
        timeoutSec: Int,
    ): Result {
        val jsch = com.jcraft.jsch.JSch()
        var session: com.jcraft.jsch.Session? = null
        var channel: com.jcraft.jsch.ChannelExec? = null
        val hostKeys = PinnedHostKeyRepository(conn.hostKeyFingerprint)
        try {
            session = jsch.getSession(conn.user, conn.host, conn.port)
            session.setPassword(conn.password)
            session.hostKeyRepository = hostKeys
            session.setUserInfo(HostKeyPinningUserInfo(hostKeys, conn.password))
            val config = java.util.Properties()
            // "ask" rather than "no": it routes the decision through our repository
            // and UserInfo above instead of accepting any key. "yes" would reject
            // first contact outright, which would make a new connection impossible.
            config["StrictHostKeyChecking"] = "ask"
            config["PreferredAuthentications"] = "password,keyboard-interactive"
            session.setConfig(config)
            session.connect(timeoutSec * 1000)

            channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
            channel.setCommand(command)
            val input = channel.inputStream
            val errStream = java.io.ByteArrayOutputStream()
            channel.setErrStream(errStream)
            channel.connect()

            // Accumulate raw bytes, then decode once as UTF-8 at the end — decoding
            // each chunk separately corrupts multibyte chars split across reads.
            val outBytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutSec * 1000L
            while (true) {
                while (input.available() > 0) {
                    val read = input.read(buffer, 0, buffer.size)
                    if (read < 0) break
                    outBytes.write(buffer, 0, read)
                    if (outBytes.size() > 16000) break
                }
                if (channel.isClosed) {
                    if (input.available() > 0) continue
                    break
                }
                if (System.currentTimeMillis() > deadline) {
                    outBytes.write("\n…[timeout]".toByteArray())
                    break
                }
                Thread.sleep(100)
            }
            val output = StringBuilder(outBytes.toString("UTF-8"))

            val errText = errStream.toString()
            val exitCode = channel.exitStatus
            val combined = buildString {
                append(output.toString())
                if (errText.isNotBlank()) {
                    if (isNotBlank()) append("\n")
                    append("[stderr] ").append(errText)
                }
                if (exitCode != 0 && exitCode != -1) {
                    append("\n[exit code: $exitCode]")
                }
            }
            val capped =
                if (combined.length > 8000) combined.take(8000) + "\n…[truncado]" else combined

            // Persist the pin only now, once authentication and the command both
            // succeeded. Pinning earlier would record whatever key an attacker
            // offered on a connection that never worked.
            if (hostKeys.learnedNewKey && hostKeys.presentedFingerprint.isNotEmpty()) {
                RemoteConnectionStore.recordHostKeyFingerprint(
                    conn.alias,
                    hostKeys.presentedFingerprint,
                )
            }
            return Result(
                output = capped,
                hostKeyFingerprint = hostKeys.presentedFingerprint,
                pinnedOnThisConnect = hostKeys.learnedNewKey,
            )
        } catch (e: com.jcraft.jsch.JSchException) {
            val msg = e.message ?: ""
            throw RuntimeException(when {
                // Host key changed under a pin. Report it as an abort, not as a
                // generic SSH error: the user needs to know the password was NOT
                // sent and that the cause may be an active attacker.
                msg.contains("HostKey has been changed") || msg.contains("reject HostKey") ->
                    SshHostKeyPolicy.mismatchMessage(
                        host = conn.alias.ifBlank { conn.host },
                        pinned = conn.hostKeyFingerprint,
                        presented = hostKeys.presentedFingerprint,
                    )
                msg.contains("UnknownHostKey") ->
                    "ABORTADO: no pude verificar la clave del host ${conn.host} y no se envió " +
                        "la contraseña. Huella recibida: ${hostKeys.presentedFingerprint}."
                msg.contains("Auth fail") || msg.contains("auth") -> "Autenticación falló. Verifica usuario/contraseña."
                msg.contains("timeout") || msg.contains("Connection refused") ->
                    "No puedo conectar a ${conn.host}:${conn.port}. ¿El PC está encendido, en la misma red, y con SSH activo?"
                msg.contains("UnknownHost") -> "No encuentro el host ${conn.host}. Verifica la IP."
                else -> "Error SSH: $msg"
            })
        } finally {
            runCatching { channel?.disconnect() }
            runCatching { session?.disconnect() }
        }
    }
}

// ── Connection storage ──

object RemoteConnectionStore {
    private const val KEY = "remote_connections_v1"

    data class Connection(
        val alias: String,
        val host: String,
        val port: Int = 22,
        val user: String,
        val password: String,
        /**
         * Host key fingerprint pinned on first successful connect, empty until then.
         * See [SshHostKeyPolicy] for why this is the difference between an
         * authenticated SSH session and handing the password to whoever answered.
         * Connections saved before pinning existed have this empty and get pinned on
         * their next successful use.
         */
        val hostKeyFingerprint: String = "",
    )

    @Synchronized
    fun allConnections(): List<Connection> {
        val secure = com.blackclaw.android.utils.SecretStore.getString(KEY)
        val legacy = if (secure == null) KVUtils.getString(KEY, "") else ""
        val raw = secure ?: legacy
        if (raw.isBlank()) return emptyList()

        if (secure == null && legacy.isNotBlank()) {
            val migrated = com.blackclaw.android.utils.SecretStore.putString(KEY, legacy) &&
                com.blackclaw.android.utils.SecretStore.getString(KEY) == legacy
            if (migrated) {
                KVUtils.remove(KEY)
                KVUtils.sync()
                XLog.i("RemoteConnectionStore", "Migrated saved SSH credentials to encrypted storage")
            } else {
                XLog.w("RemoteConnectionStore", "SSH credential migration deferred; legacy data retained")
            }
        }

        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                Connection(
                    alias = o.optString("alias"),
                    host = o.optString("host"),
                    port = o.optInt("port", 22),
                    user = o.optString("user"),
                    password = o.optString("pass"),
                    hostKeyFingerprint = o.optString("hostkey_fp", ""),
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Record a first-contact fingerprint against an already-stored connection.
     *
     * No-op when the alias is unknown (the connect tool saves only after its test
     * command succeeds) and, deliberately, when a fingerprint is already pinned:
     * overwriting an existing pin from inside the connect path would turn the pin
     * into something an attacker can reset just by presenting a new key.
     */
    @Synchronized
    fun recordHostKeyFingerprint(alias: String, fingerprint: String): Boolean {
        if (alias.isBlank() || fingerprint.isBlank()) return false
        val list = allConnections().toMutableList()
        val index = list.indexOfFirst { it.alias.equals(alias, ignoreCase = true) }
        if (index < 0) return false
        if (list[index].hostKeyFingerprint.isNotBlank()) return true
        list[index] = list[index].copy(hostKeyFingerprint = fingerprint)
        val saved = saveAll(list)
        if (!saved) XLog.e("RemoteConnectionStore", "Could not persist SSH host-key pin")
        return saved
    }

    @Synchronized
    fun saveConnection(conn: Connection): Boolean {
        val list = allConnections().toMutableList()
        list.removeAll { it.alias == conn.alias }
        list.add(conn)
        if (!saveAll(list)) return false
        KVUtils.putString("remote_default_alias", conn.alias); KVUtils.sync()
        return true
    }

    fun getConnection(alias: String): Connection? =
        allConnections().firstOrNull { it.alias.equals(alias, ignoreCase = true) }

    fun getDefaultConnection(): Connection? {
        val defaultAlias = KVUtils.getString("remote_default_alias", "")
        return if (defaultAlias.isNotBlank()) getConnection(defaultAlias)
        else allConnections().firstOrNull()
    }

    @Synchronized
    fun removeConnection(alias: String): Boolean {
        val list = allConnections().toMutableList()
        val removed = list.removeAll { it.alias.equals(alias, ignoreCase = true) }
        if (!removed) return false
        return saveAll(list)
    }

    fun clearAll(): Boolean {
        if (!com.blackclaw.android.utils.SecretStore.remove(KEY)) {
            XLog.e("RemoteConnectionStore", "Could not clear encrypted SSH credentials")
            return false
        }
        KVUtils.remove(KEY)
        KVUtils.sync()
        return true
    }

    private fun saveAll(list: List<Connection>): Boolean {
        val arr = org.json.JSONArray()
        list.forEach { c ->
            arr.put(org.json.JSONObject().apply {
                put("alias", c.alias); put("host", c.host); put("port", c.port)
                put("user", c.user); put("pass", c.password)
                put("hostkey_fp", c.hostKeyFingerprint)
            })
        }
        val encoded = arr.toString()
        if (!com.blackclaw.android.utils.SecretStore.putString(KEY, encoded)) {
            XLog.e("RemoteConnectionStore", "Could not store SSH credentials securely; previous data retained")
            return false
        }
        // A successful secure write is the only point where an old plaintext copy
        // may be deleted.
        KVUtils.remove(KEY)
        KVUtils.sync()
        return true
    }
}
