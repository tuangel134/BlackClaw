package com.blackclaw.android.emergency

import com.blackclaw.android.utils.KVUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EmergencyConfig {
    private const val KEY_NAME = "emergency_contact_name"
    private const val KEY_PHONE = "emergency_contact_phone"
    private const val KEY_MESSAGE = "emergency_message"
    private const val KEY_AUDIO = "emergency_record_audio"
    private const val KEY_TORCH = "emergency_low_light_torch"

    var contactName: String
        get() = KVUtils.getString(KEY_NAME)
        set(value) { KVUtils.putString(KEY_NAME, value.trim()) }

    var phone: String
        get() = KVUtils.getString(KEY_PHONE)
        set(value) { KVUtils.putString(KEY_PHONE, normalizePhone(value)) }

    var message: String
        get() = KVUtils.getString(KEY_MESSAGE, "Necesito ayuda. Esta es una alerta de emergencia enviada por BlackClaw.")
        set(value) { KVUtils.putString(KEY_MESSAGE, value.trim()) }

    var recordAudio: Boolean
        get() = KVUtils.getBoolean(KEY_AUDIO, true)
        set(value) { KVUtils.putBoolean(KEY_AUDIO, value) }

    /**
     * Turn the rear flash on while recording emergency video. Without it, footage
     * captured at night is unusable, but the light is obvious to anyone nearby —
     * so it is opt-in and is never applied in discreet mode.
     */
    var lowLightTorch: Boolean
        get() = KVUtils.getBoolean(KEY_TORCH, false)
        set(value) { KVUtils.putBoolean(KEY_TORCH, value) }

    val isReady: Boolean get() = phone.length >= 7 && message.isNotBlank()

    fun buildAlert(latitude: Double?, longitude: Double?, atMillis: Long = System.currentTimeMillis()): String {
        return formatAlert(message, contactName, latitude, longitude, atMillis)
    }

    fun buildLocationUpdate(latitude: Double?, longitude: Double?, atMillis: Long = System.currentTimeMillis()): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(atMillis))
        return buildString {
            append("📍 Actualización de ubicación · BlackClaw\nHora: ").append(time)
            if (latitude != null && longitude != null) {
                append("\nUbicación: https://maps.google.com/?q=").append(latitude).append(',').append(longitude)
            } else append("\nUbicación: no disponible en esta actualización")
        }
    }

    internal fun formatAlert(
        configuredMessage: String,
        configuredName: String,
        latitude: Double?,
        longitude: Double?,
        atMillis: Long,
    ): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(atMillis))
        return buildString {
            append("🚨 EMERGENCIA · BlackClaw\n")
            append(configuredMessage.ifBlank { "Necesito ayuda." })
            append("\nHora: ").append(time)
            if (latitude != null && longitude != null) {
                append("\nUbicación: https://maps.google.com/?q=")
                append(latitude).append(',').append(longitude)
            } else {
                append("\nUbicación: no disponible todavía")
            }
            append("\nContacto configurado: ").append(configuredName.ifBlank { "sin nombre" })
        }
    }

    internal fun normalizePhone(value: String): String {
        val trimmed = value.trim()
        val digits = trimmed.filter(Char::isDigit)
        return if (trimmed.startsWith("+") && digits.isNotEmpty()) "+$digits" else digits
    }
}
