package com.blackclaw.android.ui.assist

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.blackclaw.android.utils.XLog

/**
 * Helpers to check/become the device's default digital assistant. When BlackClaw
 * holds the assistant role, the OS routes the assist gesture (power-button hold,
 * swipe-from-corner, etc.) to [QuickAssistActivity] — even over the lock screen.
 *
 * Android doesn't allow apps to grant themselves the assistant role; the user
 * must pick it. We detect the current state and deep-link to the right settings
 * screen, with fallbacks across OEMs.
 */
object AssistantRole {

    private const val TAG = "AssistantRole"

    /** True if BlackClaw is the current default assist app. */
    fun isDefault(context: Context): Boolean {
        val pkg = context.packageName
        // The OS stores the chosen assistant here (component or package).
        val keys = listOf("assistant", "voice_interaction_service", "selected_spoken_assist")
        for (k in keys) {
            val v = runCatching { Settings.Secure.getString(context.contentResolver, k) }.getOrNull()
            if (!v.isNullOrBlank() && v.contains(pkg)) return true
        }
        return false
    }

    /**
     * Open the system screen where the user can pick the default assistant.
     * Tries the most specific intents first and falls back gracefully.
     */
    fun openSettings(context: Context): Boolean {
        val intents = listOf(
            // Direct "Default digital assistant app" on most modern Android.
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            // Assist & voice input umbrella.
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            // Generic app settings as last resort.
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(context.packageManager) != null) {
                return try { context.startActivity(i); true }
                catch (e: Exception) { XLog.w(TAG, "open settings failed: ${e.message}"); false }
            }
        }
        return false
    }
}
