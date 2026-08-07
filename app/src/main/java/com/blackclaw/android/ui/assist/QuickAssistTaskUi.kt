package com.blackclaw.android.ui.assist

/** Friendly Spanish labels for the transient Quick Assist status. */
object QuickAssistTaskReducer {
    fun toolLabel(tool: String): String = when {
        tool.contains("open_app_action") || tool == "open_app" -> "Abriendo la app"
        tool.contains("play_music") -> "Poniendo música"
        tool.contains("send_message") || tool.contains("send_sms") -> "Enviando el mensaje"
        tool.contains("make_call") -> "Iniciando la llamada"
        tool.contains("appointment") || tool.contains("alarm") || tool.contains("reminder") || tool.contains("event") -> "Agendando"
        tool.contains("web") || tool.contains("http") -> "Buscando en internet"
        tool.contains("screen") || tool.contains("ocr") -> "Analizando la pantalla"
        tool.contains("tap") || tool.contains("input") || tool.contains("swipe") || tool.contains("scroll") -> "Interactuando con la pantalla"
        tool.contains("notification") -> "Revisando notificaciones"
        tool.contains("device_info") || tool.contains("battery") -> "Consultando el dispositivo"
        else -> tool.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }.ifBlank { "Ejecutando una acción" }
    }
}
