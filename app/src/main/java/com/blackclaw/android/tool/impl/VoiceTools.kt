package com.blackclaw.android.tool.impl

import com.blackclaw.android.assistant.VoiceInputManager
import com.blackclaw.android.assistant.VoskModelManager
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
        "('garra' offline, or 'Hey BlackClaw' online) followed by a command and BlackClaw listens " +
        "and acts. action: on | off | download_offline (downloads the offline model ~40MB so it " +
        "works with no beep and no internet). Optionally set a custom wake_word."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "activa/desactiva el modo manos libres con palabra de activación"
    override fun getParameters() = listOf(
        ToolParameter("action", "string", "on | off | download_offline | status.", true),
        ToolParameter("wake_word", "string", "Optional custom wake word (online mode only).", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val action = requireString(params, "action").lowercase()
        val wakeWord = optionalString(params, "wake_word", "")
        if (wakeWord.isNotBlank()) VoiceInputManager.wakeWord = wakeWord

        return when (action) {
            "download_offline" -> {
                if (VoskModelManager.isReady()) return ToolResult.success("El modelo offline ya está instalado. La wake word offline es 'garra'.")
                if (VoskModelManager.downloading) return ToolResult.success("La descarga del modelo offline ya está en curso…")
                VoskModelManager.download(onDone = { ok ->
                    com.blackclaw.android.utils.XLog.i("VoiceMode", "Offline model download done: $ok")
                })
                ToolResult.success("📥 Descargando el modelo de voz offline (~40MB). Cuando termine, el modo voz funcionará sin beep y sin internet. Te lo activo solo al acabar.")
            }
            "status" -> {
                val sb = StringBuilder()
                sb.append("Modo voz: ${if (VoiceInputManager.wakeEnabled) "activado" else "desactivado"}\n")
                sb.append("Backend: ${if (VoskModelManager.isReady()) "offline (Vosk, wake='garra')" else "online (SpeechRecognizer, wake='${VoiceInputManager.wakeWord}')"}\n")
                sb.append("Reconocimiento disponible: ${VoiceInputManager.isAvailable()}")
                ToolResult.success(sb.toString())
            }
            "on" -> {
                VoiceInputManager.wakeEnabled = true
                runCatching { com.blackclaw.android.service.VoiceWakeService.start(com.blackclaw.android.ClawApplication.instance) }
                val backend = if (VoskModelManager.isReady())
                    "offline (di 'garra' + tu orden, sin beep ni internet, funciona en segundo plano)"
                else
                    "online (di '${VoiceInputManager.wakeWord}' + tu orden). Para el modo offline sin beep, usa download_offline."
                ToolResult.success("🎤 Modo voz activado — $backend")
            }
            "off" -> {
                VoiceInputManager.wakeEnabled = false
                VoiceInputManager.stopWakeLoop()
                runCatching { com.blackclaw.android.service.VoiceWakeService.stop(com.blackclaw.android.ClawApplication.instance) }
                ToolResult.success("🔇 Modo voz desactivado.")
            }
            else -> ToolResult.error("action: on | off | download_offline | status.")
        }
    }
}
