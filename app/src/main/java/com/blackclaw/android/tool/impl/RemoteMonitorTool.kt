package com.blackclaw.android.tool.impl

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.assistant.AssistantReceiver
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import com.blackclaw.android.utils.XLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * PC co-pilot: persistent remote monitoring.
 *
 * Polls a command on the remote PC periodically (e.g. tail a log, check a
 * service, watch disk) and fires a push notification when a watched pattern
 * appears or a threshold is crossed. This turns the one-shot remote_shell into
 * a live "watch my PC and tell me if X happens" co-pilot.
 *
 * Examples:
 *   - "Vigila el log de nginx y avísame si aparece un error"
 *     → remote_monitor(command="tail -n 20 /var/log/nginx/error.log", match="error")
 *   - "Avísame si el disco pasa del 90%"
 *     → remote_monitor(command="df -h / | tail -1", match="9[0-9]%")
 */
class RemoteMonitorTool : BaseTool() {
    override fun getName() = "remote_monitor"
    override fun getDisplayName() = "Vigilar PC"
    override fun getDescriptionEN() =
        "Continuously monitor a remote PC by polling a command and alerting when a pattern " +
        "appears. Runs in the background and sends a push notification on a match. " +
        "Use match (regex) to define what to alert on. interval_seconds default 60."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "vigila un PC remoto y avisa cuando aparece un patrón (tail -f con alertas)"
    override fun getParameters() = listOf(
        ToolParameter("command", "string", "Command to poll on the remote PC.", true),
        ToolParameter("match", "string", "Regex to watch for in the output. Alerts when found.", true),
        ToolParameter("interval_seconds", "integer", "Polling interval. Default 60.", false),
        ToolParameter("host", "string", "Optional connection alias.", false),
        ToolParameter("label", "string", "Friendly name for this monitor.", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val command = requireString(params, "command").trim()
        val match = requireString(params, "match").trim()
        val interval = optionalInt(params, "interval_seconds", 60).coerceIn(15, 3600)
        val hostAlias = optionalString(params, "host", "")
        val label = optionalString(params, "label", "Monitor PC")

        val conn = (if (hostAlias.isNotBlank()) RemoteConnectionStore.getConnection(hostAlias)
            else RemoteConnectionStore.getDefaultConnection())
            ?: return ToolResult.error("No hay conexión remota configurada (usa remote_connect).")

        val id = RemoteMonitorManager.start(conn, command, match, interval, label)
        return ToolResult.success(
            "👁 Monitor '$label' iniciado (id=$id). Reviso cada ${interval}s y te aviso si aparece '$match'. " +
            "Usa stop_monitor(id=\"$id\") para detenerlo.")
    }
}

class StopMonitorTool : BaseTool() {
    override fun getName() = "stop_monitor"
    override fun getDisplayName() = "Detener vigilancia"
    override fun getDescriptionEN() = "Stop a remote PC monitor by id, or 'all' to stop everything."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "detiene un monitor de PC remoto"
    override fun getParameters() = listOf(
        ToolParameter("id", "string", "Monitor id, or 'all'.", true),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val id = requireString(params, "id").trim()
        val stopped = if (id == "all") RemoteMonitorManager.stopAll()
            else if (RemoteMonitorManager.stop(id)) 1 else 0
        return if (stopped > 0) ToolResult.success("Detenido(s) $stopped monitor(es).")
        else ToolResult.error("No encontré el monitor '$id'.")
    }
}

class ListMonitorsTool : BaseTool() {
    override fun getName() = "list_monitors"
    override fun getDisplayName() = "Ver vigilancias"
    override fun getDescriptionEN() = "List active remote PC monitors."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "lista los monitores de PC activos"
    override fun getParameters() = emptyList<ToolParameter>()
    override fun execute(params: Map<String, Any>): ToolResult {
        val active = RemoteMonitorManager.list()
        if (active.isEmpty()) return ToolResult.success("No hay monitores activos.")
        val sb = StringBuilder("Monitores activos:\n")
        active.forEach { sb.append("- [${it.id}] ${it.label}: '${it.match}' cada ${it.intervalSec}s\n") }
        return ToolResult.success(sb.toString().trim())
    }
}

/**
 * Manages background polling monitors. Each runs on a shared scheduler thread.
 */
object RemoteMonitorManager {
    private const val TAG = "RemoteMonitor"

    data class Monitor(
        val id: String,
        val label: String,
        val command: String,
        val match: String,
        val intervalSec: Int,
        val conn: RemoteConnectionStore.Connection,
        val future: ScheduledFuture<*>,
        var lastAlertAt: Long = 0,
    )

    private val monitors = ConcurrentHashMap<String, Monitor>()
    private val idCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val scheduler: ScheduledExecutorService =
        Executors.newScheduledThreadPool(2) { r ->
            Thread(r, "RemoteMonitor").apply { isDaemon = true }
        }
    // Don't re-alert for the same monitor more often than this.
    private const val ALERT_COOLDOWN_MS = 5 * 60_000L

    fun start(
        conn: RemoteConnectionStore.Connection,
        command: String, match: String, intervalSec: Int, label: String,
    ): String {
        val id = "mon" + idCounter.incrementAndGet()
        val regex = runCatching { Regex(match, RegexOption.IGNORE_CASE) }.getOrNull()
        val future = scheduler.scheduleWithFixedDelay({
            poll(id, conn, command, regex, match, label)
        }, intervalSec.toLong(), intervalSec.toLong(), TimeUnit.SECONDS)
        monitors[id] = Monitor(id, label, command, match, intervalSec, conn, future)
        XLog.i(TAG, "Started monitor $id: $label")
        return id
    }

    private fun poll(
        id: String, conn: RemoteConnectionStore.Connection,
        command: String, regex: Regex?, matchRaw: String, label: String,
    ) {
        try {
            val output = SshExecutor.execute(conn, command, 20)
            val hit = if (regex != null) regex.containsMatchIn(output)
                else output.contains(matchRaw, ignoreCase = true)
            if (hit) {
                val mon = monitors[id] ?: return
                val now = System.currentTimeMillis()
                if (now - mon.lastAlertAt < ALERT_COOLDOWN_MS) return
                mon.lastAlertAt = now
                // Extract the matching line for the alert body.
                val matchedLine = output.lineSequence().firstOrNull {
                    regex?.containsMatchIn(it) ?: it.contains(matchRaw, ignoreCase = true)
                } ?: output.take(120)
                AssistantReceiver.postNotification(
                    ClawApplication.instance,
                    "👁 $label",
                    "Detectado '$matchRaw' en ${conn.host}:\n${matchedLine.take(160)}",
                    highPriority = true,
                )
                XLog.i(TAG, "Monitor $id alerted: $matchedLine")
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Monitor $id poll failed: ${e.message}")
        }
    }

    fun stop(id: String): Boolean {
        val mon = monitors.remove(id) ?: return false
        mon.future.cancel(true)
        return true
    }

    fun stopAll(): Int {
        val n = monitors.size
        monitors.values.forEach { it.future.cancel(true) }
        monitors.clear()
        return n
    }

    fun list(): List<Monitor> = monitors.values.toList()
}
