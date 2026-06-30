package com.blackclaw.android.tool.impl

import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Smart Home integration via configurable webhooks.
 * Supports Home Assistant, IFTTT, custom HTTP endpoints.
 *
 * Users configure devices/actions as webhook entries; the AI just calls
 * smart_home(action="turn on", device="living room light") and we resolve
 * it to the correct HTTP call.
 */

class SmartHomeTool : BaseTool() {
    override fun getName() = "smart_home"
    override fun getDisplayName() = "Smart Home"
    override fun getDescriptionEN() =
        "Control smart home devices. Actions: on, off, toggle, set, trigger. " +
        "Resolves the device name to a configured webhook and fires it. " +
        "Use list_devices to see available devices. Supports Home Assistant, IFTTT, and custom webhooks."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "controla dispositivos del hogar inteligente (luces, enchufes, etc.)"
    override fun getParameters() = listOf(
        ToolParameter("device", "string", "Device name (e.g. 'living room light', 'coffee maker').", true),
        ToolParameter("action", "string", "on | off | toggle | set | trigger. Default: toggle.", false),
        ToolParameter("value", "string", "For 'set': brightness 0-100, temperature, color, etc.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val deviceQuery = requireString(params, "device").trim().lowercase()
        val action = optionalString(params, "action", "toggle").lowercase()
        val value = optionalString(params, "value", "")

        val device = SmartHomeRegistry.findDevice(deviceQuery)
            ?: return ToolResult.error("Dispositivo '$deviceQuery' no configurado. Usa list_smart_devices para ver los disponibles, o configura uno nuevo en Ajustes → Smart Home.")

        return try {
            val response = SmartHomeRegistry.executeDevice(device, action, value)
            ToolResult.success("✓ ${device.name}: $action${if (value.isNotBlank()) " ($value)" else ""}. $response")
        } catch (e: Exception) {
            XLog.w("SmartHomeTool", "Smart home call failed: ${e.message}")
            ToolResult.error("Error controlando ${device.name}: ${e.message}")
        }
    }
}

class ListSmartDevicesTool : BaseTool() {
    override fun getName() = "list_smart_devices"
    override fun getDisplayName() = "Dispositivos Smart Home"
    override fun getDescriptionEN() = "List all configured smart home devices and their capabilities."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "lista los dispositivos smart home configurados"
    override fun getParameters() = emptyList<ToolParameter>()
    override fun execute(params: Map<String, Any>): ToolResult {
        val devices = SmartHomeRegistry.allDevices()
        if (devices.isEmpty()) return ToolResult.success("No hay dispositivos smart home configurados. El usuario puede añadirlos en Ajustes → Smart Home.")
        val sb = StringBuilder("Dispositivos disponibles:\n")
        devices.forEach { d ->
            sb.append("- ${d.name} (${d.type}) — acciones: ${d.actions.joinToString()}\n")
        }
        return ToolResult.success(sb.toString().trim())
    }
}

class AddSmartDeviceTool : BaseTool() {
    override fun getName() = "add_smart_device"
    override fun getDisplayName() = "Añadir dispositivo"
    override fun getDescriptionEN() =
        "Add a smart home device configuration. Needs a name, type, webhook URL, and HTTP method. " +
        "The webhook will be called when the user asks to control this device."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "configura un nuevo dispositivo smart home (webhook)"
    override fun getParameters() = listOf(
        ToolParameter("name", "string", "Device friendly name (e.g. 'Luz salón').", true),
        ToolParameter("type", "string", "Device type: light|switch|thermostat|lock|speaker|scene|custom.", true),
        ToolParameter("webhook_url", "string", "HTTP endpoint to call (e.g. Home Assistant API URL).", true),
        ToolParameter("method", "string", "HTTP method: GET|POST|PUT. Default POST.", false),
        ToolParameter("headers", "string", "Optional JSON headers (e.g. {\"Authorization\":\"Bearer xxx\"}).", false),
        ToolParameter("body_template", "string", "Optional body template. Use {action} and {value} as placeholders.", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val name = requireString(params, "name").trim()
        val type = requireString(params, "type").trim().lowercase()
        val url = requireString(params, "webhook_url").trim()
        val method = optionalString(params, "method", "POST").uppercase()
        val headers = optionalString(params, "headers", "")
        val bodyTemplate = optionalString(params, "body_template", "")

        val device = SmartHomeRegistry.SmartDevice(
            name = name, type = type, webhookUrl = url,
            method = method, headers = headers, bodyTemplate = bodyTemplate,
            actions = SmartHomeRegistry.defaultActionsForType(type),
        )
        SmartHomeRegistry.addDevice(device)
        return ToolResult.success("Dispositivo '${name}' añadido. Ahora puedes decir: 'enciende $name'.")
    }
}

/**
 * Registry of smart home devices and webhook execution.
 */
object SmartHomeRegistry {
    private const val TAG = "SmartHome"
    private const val KEY = "smart_home_devices_v1"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class SmartDevice(
        val name: String,
        val type: String,
        val webhookUrl: String,
        val method: String = "POST",
        val headers: String = "",
        val bodyTemplate: String = "",
        val actions: List<String> = listOf("on", "off", "toggle"),
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name); put("type", type); put("url", webhookUrl)
            put("method", method); put("headers", headers)
            put("body", bodyTemplate)
            put("actions", JSONArray(actions))
        }
        companion object {
            fun fromJson(o: JSONObject) = SmartDevice(
                name = o.optString("name"), type = o.optString("type"),
                webhookUrl = o.optString("url"), method = o.optString("method", "POST"),
                headers = o.optString("headers", ""),
                bodyTemplate = o.optString("body", ""),
                actions = (0 until (o.optJSONArray("actions")?.length() ?: 0)).map {
                    o.optJSONArray("actions")!!.getString(it)
                }.ifEmpty { listOf("on", "off", "toggle") },
            )
        }
    }

    fun defaultActionsForType(type: String): List<String> = when (type) {
        "light" -> listOf("on", "off", "toggle", "set")
        "thermostat" -> listOf("set", "on", "off")
        "lock" -> listOf("lock", "unlock")
        "speaker" -> listOf("play", "pause", "set")
        "scene" -> listOf("trigger")
        else -> listOf("on", "off", "toggle", "trigger")
    }

    @Synchronized
    fun allDevices(): List<SmartDevice> {
        val raw = KVUtils.getString(KEY, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { runCatching { SmartDevice.fromJson(arr.getJSONObject(it)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun addDevice(device: SmartDevice) {
        val list = allDevices().toMutableList()
        list.removeAll { it.name.equals(device.name, ignoreCase = true) }
        list.add(device)
        val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
        KVUtils.putString(KEY, arr.toString()); KVUtils.sync()
    }

    fun findDevice(query: String): SmartDevice? {
        val q = query.lowercase()
        val devices = allDevices()
        return devices.firstOrNull { it.name.lowercase() == q }
            ?: devices.firstOrNull { it.name.lowercase().contains(q) }
    }

    fun executeDevice(device: SmartDevice, action: String, value: String): String {
        // JSON-escape interpolated values so quotes/specials can't break the body.
        fun esc(s: String) = JSONObject.quote(s).let { it.substring(1, it.length - 1) }
        var body = device.bodyTemplate.ifBlank {
            """{"action":"${esc(action)}"${if (value.isNotBlank()) ",\"value\":\"${esc(value)}\"" else ""}}"""
        }
        body = body.replace("{action}", esc(action)).replace("{value}", esc(value))

        val reqBuilder = Request.Builder().url(device.webhookUrl)
        if (device.headers.isNotBlank()) {
            runCatching {
                val h = JSONObject(device.headers)
                h.keys().forEach { reqBuilder.addHeader(it, h.getString(it)) }
            }
        }
        val request = when (device.method.uppercase()) {
            "GET" -> reqBuilder.get().build()
            "PUT" -> reqBuilder.put(body.toRequestBody("application/json".toMediaType())).build()
            else -> reqBuilder.post(body.toRequestBody("application/json".toMediaType())).build()
        }

        // .use{} guarantees the response/connection is closed even if .string() throws.
        client.newCall(request).execute().use { response ->
            val code = response.code
            val responseBody = response.body?.string()?.take(200) ?: ""
            if (code in 200..299) {
                XLog.i(TAG, "Smart home OK: ${device.name} → $action ($code)")
                return "OK ($code)"
            } else {
                throw RuntimeException("HTTP $code: $responseBody")
            }
        }
    }
}
