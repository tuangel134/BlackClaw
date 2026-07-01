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
 * Security: credentials stored in local app storage (MMKV). The user must
 * explicitly configure the connection. No credentials leave the device.
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
            val testOutput = SshExecutor.execute(conn, "echo BlackClaw_connected && uname -a", 10)
            if (testOutput.contains("BlackClaw_connected")) {
                RemoteConnectionStore.saveConnection(conn)
                val systemInfo = testOutput.substringAfter("BlackClaw_connected").trim()
                ToolResult.success("✓ Conectado a $host como $user. Sistema: $systemInfo")
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
            RemoteConnectionStore.clearAll()
            return ToolResult.success("Todas las conexiones remotas eliminadas.")
        }
        val removed = RemoteConnectionStore.removeConnection(alias)
        return if (removed) ToolResult.success("Conexión '$alias' eliminada.")
        else ToolResult.error("No encontré conexión '$alias'.")
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

// ── Local Android terminal (no root, no Shizuku needed) ──

class LocalTerminalTool : BaseTool() {
    override fun getName() = "terminal"
    override fun getDisplayName() = "Terminal local"
    override fun getDescriptionEN() =
        "Run a command in BlackClaw's internal terminal — a PERSISTENT shell session " +
        "(remembers the working directory, chosen backend and adb connection). " +
        "Backends: local (app-level, no root), or privileged (Shizuku / self-paired ADB) " +
        "for pm/am/settings/input. Built-ins: cd, pwd, backend [auto|local|privileged], whoami. " +
        "adb over Wireless Debugging (no PC): 'adb pair <host:port> <code>', 'adb connect <host:port>', " +
        "'adb shell <cmd>', 'adb devices', 'adb disconnect'."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "terminal interno persistente (shell local/privilegiado + adb wifi)"
    override fun getParameters() = listOf(
        ToolParameter("command", "string", "Comando a ejecutar en la sesión del terminal.", true),
        ToolParameter("timeout_seconds", "integer", "Reservado (el engine gestiona el timeout).", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val command = requireString(params, "command").trim()
        if (command.isEmpty()) return ToolResult.error("Comando vacío.")
        val out = com.blackclaw.android.terminal.TerminalEngine.run(
            com.blackclaw.android.ClawApplication.instance, command)
        val clean = out.replace("\u000C", "").trim()
        val capped = if (clean.length > 8000) clean.take(8000) + "\n…[truncado]" else clean
        return ToolResult.success(capped.ifBlank { "(exit 0, sin output)" })
    }
}

// ── SSH Executor (JSch-based, pure Java, no external binaries) ──

object SshExecutor {
    private const val TAG = "SshExecutor"

    /**
     * Execute a command over SSH using JSch (pure Java).
     * Works on all Android versions without external binaries.
     */
    fun execute(conn: RemoteConnectionStore.Connection, command: String, timeoutSec: Int): String {
        val jsch = com.jcraft.jsch.JSch()
        var session: com.jcraft.jsch.Session? = null
        var channel: com.jcraft.jsch.ChannelExec? = null
        try {
            session = jsch.getSession(conn.user, conn.host, conn.port)
            session.setPassword(conn.password)
            val config = java.util.Properties()
            config["StrictHostKeyChecking"] = "no"
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
            return if (combined.length > 8000) combined.take(8000) + "\n…[truncado]" else combined
        } catch (e: com.jcraft.jsch.JSchException) {
            val msg = e.message ?: ""
            throw RuntimeException(when {
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
    )

    @Synchronized
    fun allConnections(): List<Connection> {
        val raw = KVUtils.getString(KEY, "")
        if (raw.isBlank()) return emptyList()
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
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun saveConnection(conn: Connection) {
        val list = allConnections().toMutableList()
        list.removeAll { it.alias == conn.alias }
        list.add(conn)
        saveAll(list)
        KVUtils.putString("remote_default_alias", conn.alias); KVUtils.sync()
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
        if (removed) saveAll(list)
        return removed
    }

    fun clearAll() {
        KVUtils.putString(KEY, ""); KVUtils.sync()
    }

    private fun saveAll(list: List<Connection>) {
        val arr = org.json.JSONArray()
        list.forEach { c ->
            arr.put(org.json.JSONObject().apply {
                put("alias", c.alias); put("host", c.host); put("port", c.port)
                put("user", c.user); put("pass", c.password)
            })
        }
        KVUtils.putString(KEY, arr.toString()); KVUtils.sync()
    }
}
