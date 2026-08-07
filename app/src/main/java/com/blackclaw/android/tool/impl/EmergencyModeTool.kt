package com.blackclaw.android.tool.impl

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.emergency.EmergencyConfig
import com.blackclaw.android.emergency.EmergencyService
import com.blackclaw.android.emergency.EmergencyMode
import com.blackclaw.android.emergency.EmergencyCameras
import com.blackclaw.android.emergency.EmergencyStartOptions
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

class EmergencyModeTool : BaseTool() {
    override fun getName() = "emergency_mode"
    override fun getDisplayName() = "Modo emergencia"
    override fun getDescriptionEN() =
        "Start, stop, or inspect emergency/discreet protection. Emergency sends location every five minutes and records evidence. Discreet starts silently with front, back, or both concurrent cameras when supported; Android privacy indicators and a neutral BlackClaw foreground notification remain visible."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters() = listOf(
        ToolParameter("action", "string", "start | stop | status", true),
        ToolParameter("mode", "string", "emergency | discreet. Default emergency.", false),
        ToolParameter("cameras", "string", "none | front | back | both. 'Ambas cámaras' MUST map to both.", false),
        ToolParameter("send_location", "boolean", "Send initial and five-minute location updates. Default true.", false),
        ToolParameter("recipient", "string", "Optional phone override for this session; otherwise trusted contact.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = requireString(params, "action").lowercase()
        val context = ClawApplication.instance
        return when (action) {
            "start", "activar", "iniciar" -> {
                if (!EmergencyConfig.isReady) {
                    ToolResult.error("Configura primero un contacto de confianza en Ajustes → Modo emergencia.")
                } else {
                    val mode = when (optionalString(params, "mode", "emergency").lowercase()) {
                        "discreet", "discreto", "discreta" -> EmergencyMode.DISCREET
                        else -> EmergencyMode.EMERGENCY
                    }
                    val cameras = when (optionalString(params, "cameras", "back").lowercase()) {
                        "both", "ambas", "ambos", "two", "2" -> EmergencyCameras.BOTH
                        "front", "frontal", "selfie" -> EmergencyCameras.FRONT
                        "none", "ninguna", "audio" -> EmergencyCameras.NONE
                        else -> EmergencyCameras.BACK
                    }
                    val options = EmergencyStartOptions(mode, cameras,
                        optionalBoolean(params, "send_location", true), optionalString(params, "recipient", ""))
                    if (EmergencyService.start(context, options)) {
                        ToolResult.success(if (mode == EmergencyMode.DISCREET) "Modo discreto activo."
                            else "Modo emergencia iniciándose. Tienes 5 segundos para cancelarlo desde la notificación.")
                    } else ToolResult.error("No se pudo iniciar el modo de protección.")
                }
            }
            "stop", "detener", "cancelar" -> {
                EmergencyService.stop(context)
                ToolResult.success("Modo emergencia detenido.")
            }
            "status", "estado" -> ToolResult.success(
                "Protección: ${if (EmergencyService.isActive) "activa" else "inactiva"}. " +
                    "Modo: ${EmergencyService.activeMode?.name?.lowercase() ?: "ninguno"}. " +
                    "Cámaras solicitadas: ${EmergencyService.activeCameras.name.lowercase()}. " +
                    "Configuración: ${if (EmergencyConfig.isReady) "lista" else "incompleta"}."
            )
            else -> ToolResult.error("action debe ser start, stop o status")
        }
    }
}
