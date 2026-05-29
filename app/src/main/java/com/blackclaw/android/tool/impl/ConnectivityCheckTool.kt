package com.blackclaw.android.tool.impl

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/** Comprehensive network state in one call. */
class ConnectivityCheckTool : BaseTool() {
    override fun getName() = "connectivity_check"
    override fun getDisplayName() = "Conectividad"
    override fun getDescriptionEN() =
        "Resume el estado de la red: tipo, validado, capabilities, sin conexión, etc."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters() = emptyList<ToolParameter>()

    override fun execute(params: Map<String, Any>): ToolResult {
        val cm = ClawApplication.instance.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
            ?: return ToolResult.success("Sin conexión (modo avión / fuera de cobertura).")
        val caps = cm.getNetworkCapabilities(net)
            ?: return ToolResult.success("Red activa pero sin capabilities (raro).")
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Móvil"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "?"
        }
        val downKbps = caps.linkDownstreamBandwidthKbps
        val upKbps = caps.linkUpstreamBandwidthKbps
        return ToolResult.success(buildString {
            append("Transporte: ").append(transport).append("\n")
            append("Validada por internet: ").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).append("\n")
            append("Sin restricciones: ").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).append("\n")
            if (downKbps > 0) append("Bajada estimada: ").append(downKbps / 1000).append(" Mbps\n")
            if (upKbps > 0) append("Subida estimada: ").append(upKbps / 1000).append(" Mbps\n")
            append("VPN: ").append(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        })
    }
}
