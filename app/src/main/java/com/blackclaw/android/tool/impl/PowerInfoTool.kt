package com.blackclaw.android.tool.impl

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Detailed power / battery telemetry: voltage, current, temperature, technology,
 * health, charge counter, energy counter. Goes deeper than get_device_info(battery).
 */
class PowerInfoTool : BaseTool() {
    override fun getName() = "power_info"
    override fun getDisplayName() = "Energía"
    override fun getDescriptionEN() =
        "Detailed battery telemetry: capacity, voltage, current, temperature, " +
        "health, technology, charging plug type, energy counter."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters() = emptyList<ToolParameter>()

    override fun execute(params: Map<String, Any>): ToolResult {
        val ctx = ClawApplication.instance
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val sb = StringBuilder()

        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        sb.append("Carga: ").append(pct).append("%\n")

        val current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000
        sb.append("Corriente: ").append(current).append(" mA\n")

        val avgCurrent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) / 1000
        sb.append("Corriente media: ").append(avgCurrent).append(" mA\n")

        val charge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) / 1000
        sb.append("Contador de carga: ").append(charge).append(" mAh\n")

        intent?.let {
            val voltage = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            if (voltage > 0) sb.append("Voltaje: ").append(voltage).append(" mV\n")
            val tempRaw = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            sb.append("Temperatura: %.1f°C\n".format(tempRaw / 10.0))
            val tech = it.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
            if (!tech.isNullOrBlank()) sb.append("Tecnología: ").append(tech).append("\n")
            val health = when (it.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Buena"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Sobrecalentada"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Muerta"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Voltaje alto"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Fallo no especificado"
                BatteryManager.BATTERY_HEALTH_COLD -> "Fría"
                else -> "Desconocida"
            }
            sb.append("Estado: ").append(health).append("\n")
            val plug = when (it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_AC -> "Cargador"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Inalámbrica"
                else -> "No conectada"
            }
            sb.append("Conexión: ").append(plug).append("\n")
            val status = when (it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Cargando"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Descargando"
                BatteryManager.BATTERY_STATUS_FULL -> "Llena"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "No carga"
                else -> "Desconocido"
            }
            sb.append("Status: ").append(status)
        }
        return ToolResult.success(sb.toString())
    }
}
