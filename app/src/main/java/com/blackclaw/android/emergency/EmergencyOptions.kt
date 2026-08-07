package com.blackclaw.android.emergency

enum class EmergencyMode { EMERGENCY, DISCREET }
enum class EmergencyCameras { NONE, FRONT, BACK, BOTH }

data class EmergencyStartOptions(
    val mode: EmergencyMode,
    val cameras: EmergencyCameras,
    val sendLocation: Boolean,
    val recipient: String = "",
) {
    val silent: Boolean get() = mode == EmergencyMode.DISCREET
}

/** Deterministic Spanish/English parser used before the LLM for safety commands. */
object EmergencyCommandParser {
    fun parse(command: String): EmergencyStartOptions? {
        val text = normalize(command)
        val isEmergency = Regex("\\b(emergencia|emergency|auxilio|peligro)\\b").containsMatchIn(text)
        val isDiscreet = Regex("\\b(discreto|discreta|discreet|silencioso|silenciosa)\\b").containsMatchIn(text)
        if (!isEmergency && !isDiscreet) return null
        if (!Regex("\\b(activa|activar|inicia|iniciar|enciende|start)\\b").containsMatchIn(text)) return null

        val both = listOf(
            "ambas camaras", "ambas cámara", "las dos camaras", "las 2 camaras",
            "dos camaras", "2 camaras", "frontal y trasera", "trasera y frontal",
            "front and back", "both cameras",
        ).any(text::contains)
        val front = Regex("\\b(frontal|delantera|selfie|front)\\b").containsMatchIn(text)
        val back = Regex("\\b(trasera|posterior|principal|back)\\b").containsMatchIn(text)
        val explicitlyNoCamera = Regex("\\b(sin camara|solo audio|audio solamente)\\b").containsMatchIn(text)
        val cameras = when {
            explicitlyNoCamera -> EmergencyCameras.NONE
            both || (front && back) -> EmergencyCameras.BOTH
            front -> EmergencyCameras.FRONT
            back -> EmergencyCameras.BACK
            isDiscreet -> EmergencyCameras.BACK
            else -> EmergencyCameras.BACK
        }
        val noLocation = Regex("\\b(sin ubicacion|no envies ubicacion|no compartir ubicacion)\\b").containsMatchIn(text)
        return EmergencyStartOptions(
            mode = if (isDiscreet) EmergencyMode.DISCREET else EmergencyMode.EMERGENCY,
            cameras = cameras,
            sendLocation = !noLocation,
        )
    }

    private fun normalize(value: String): String = java.text.Normalizer
        .normalize(value.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
