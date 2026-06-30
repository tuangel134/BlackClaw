package com.blackclaw.android.tool.impl

import com.blackclaw.android.assistant.VoiceInputManager
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult

/**
 * Tools to control hands-free voice mode (wake word + STT).
 */
class VoiceModeTool : BaseTool() {
    override fun getName() = "voice_mode"
    override fun getDisplayName() = "Modo voz"
    override fun getDescriptionEN() =
        "Enable or disable hands-free voice mode. When on, say the wake word " +
        "('Hey BlackClaw') followed by a command and BlackClaw listens and acts without touching " +
        "the phone. action: on | off. Optionally set a custom wake_word."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "activa/desactiva el modo manos libres con palabra de activación"
    override fun getParameters() = listOf(
        ToolParameter("action", "string", "on | off.", true),
        ToolParameter("wake_word", "string", "Optional custom wake word (default 'blackclaw').", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        if (!VoiceInputManager.isAvailable()) {
            return ToolResult.error("Este dispositivo no tiene reconocimiento de voz disponible.")
        }
        val action = requireString(params, "action").lowercase()
        val wakeWord = optionalString(params, "wake_word", "")
        if (wakeWord.isNotBlank()) VoiceInputManager.wakeWord = wakeWord

        return when (action) {
            "on" -> {
                VoiceInputManager.wakeEnabled = true
                ToolResult.success("🎤 Modo voz activado. Di '${VoiceInputManager.wakeWord}' seguido de tu orden " +
                    "(mientras la app esté abierta). Ej: '${VoiceInputManager.wakeWord}, manda un mensaje a mamá'.")
            }
            "off" -> {
                VoiceInputManager.wakeEnabled = false
                VoiceInputManager.stopWakeLoop()
                ToolResult.success("🔇 Modo voz desactivado.")
            }
            else -> ToolResult.error("action debe ser 'on' u 'off'.")
        }
    }
}
