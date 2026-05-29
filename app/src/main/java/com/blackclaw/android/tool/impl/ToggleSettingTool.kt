package com.blackclaw.android.tool.impl

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Toggle one of: wifi, bluetooth, airplane_mode, dnd, location, mobile_data.
 *
 * Android 10+ removed direct programmatic WiFi/BT toggles for non-system apps.
 * This tool will:
 *  1) Try the safe API path where it still exists (e.g. AudioManager DND).
 *  2) Fall back to opening the relevant Settings panel so the agent can use
 *     accessibility tools to flip the switch from there.
 *
 * Either way the model gets a clear text result describing what happened.
 */
class ToggleSettingTool : BaseTool() {
    override fun getName() = "toggle_setting"
    override fun getDisplayName() = "Toggle Setting"
    override fun getDescriptionEN() =
        "Toggle a system radio or mode. setting: wifi|bluetooth|airplane|dnd|location|mobile_data. " +
        "On Android 10+ many of these open the corresponding Settings panel rather than flipping " +
        "directly; combine with tap/find_and_tap to confirm."
    override fun getDescriptionCN() = getDescriptionEN()

    override fun getParameters() = listOf(
        ToolParameter("setting", "string",
            "wifi | bluetooth | airplane | dnd | location | mobile_data", true),
        ToolParameter("state", "string",
            "Optional: 'on' or 'off'. If omitted the relevant settings page is opened.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val setting = requireString(params, "setting").lowercase().trim()
        val state = optionalString(params, "state", "").lowercase().trim()
        val ctx = ClawApplication.instance
        return when (setting) {
            "wifi" -> handleWifi(ctx, state)
            "bluetooth", "bt" -> openPanel(ctx, Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth")
            "airplane", "airplane_mode", "flight" -> openPanel(ctx, Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Airplane mode")
            "dnd", "do_not_disturb" -> openPanel(ctx, Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS, "Do Not Disturb")
            "location", "gps" -> openPanel(ctx, Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Location")
            "mobile_data", "data" -> openPanel(ctx, Settings.ACTION_DATA_ROAMING_SETTINGS, "Mobile data")
            else -> ToolResult.error("Unknown setting '$setting'. Use wifi|bluetooth|airplane|dnd|location|mobile_data")
        }
    }

    @Suppress("DEPRECATION")
    private fun handleWifi(ctx: Context, state: String): ToolResult {
        // Android 10+ blocks direct WifiManager.setWifiEnabled. Try anyway on older builds.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val newState = when (state) {
                    "on", "enable", "true" -> true
                    "off", "disable", "false" -> false
                    "" -> !wm.isWifiEnabled
                    else -> return ToolResult.error("state must be on|off")
                }
                wm.isWifiEnabled = newState
                ToolResult.success("WiFi ${if (newState) "enabled" else "disabled"}")
            } catch (e: Exception) {
                openPanel(ctx, Settings.ACTION_WIFI_SETTINGS, "WiFi")
            }
        }
        return openPanel(ctx, Settings.ACTION_WIFI_SETTINGS, "WiFi")
    }

    private fun openPanel(ctx: Context, action: String, label: String): ToolResult {
        return try {
            val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ctx.startActivity(intent)
            ToolResult.success(
                "$label settings opened. Use tap/find_and_tap to flip the switch, " +
                "or system_key('back') to return."
            )
        } catch (e: Exception) {
            ToolResult.error("Failed to open $label settings: ${e.message}")
        }
    }
}
