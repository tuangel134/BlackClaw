package com.blackclaw.android.ui.assist

import android.content.Context
import android.content.Intent
import android.os.Build
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

    /** Snapshot of the two Android settings that control assistant routing.
     *
     * OEMs can leave the role holder and the active VoiceInteractionService out
     * of sync (Honor did exactly that after the BlackClaw update).  Keeping the
     * values separate lets the UI explain the real problem instead of claiming
     * that the assistant is ready when the power-button event still goes to
     * Google.
     */
    data class Status(
        val roleHolder: String?,
        val activeService: String?,
        val roleHeld: Boolean,
        val serviceActive: Boolean,
    ) {
        val isReady: Boolean get() = roleHeld && (serviceActive || activeService.isNullOrBlank())
        val needsRepair: Boolean get() = roleHeld && !serviceActive && !activeService.isNullOrBlank()
    }

    private const val SECURE_ASSISTANT = "assistant"
    private const val SECURE_VOICE_SERVICE = "voice_interaction_service"

    fun status(context: Context): Status {
        val packageName = context.packageName
        val roleHolder = readSecure(context, SECURE_ASSISTANT)
        val activeService = readSecure(context, SECURE_VOICE_SERVICE)
        // Android 8/9 OEM builds sometimes expose only this legacy key.
        val legacyHolder = readSecure(context, "selected_spoken_assist")
        val roleManagerHeld = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.getSystemService(android.app.role.RoleManager::class.java)
                    ?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
            }.getOrNull() == true
        } else {
            false
        }
        return Status(
            roleHolder = roleHolder,
            activeService = activeService,
            roleHeld = roleManagerHeld || roleHolder.matchesPackage(packageName) || legacyHolder.matchesPackage(packageName),
            serviceActive = activeService.matchesPackage(packageName),
        )
    }

    /** True if BlackClaw is the current default assist app. */
    fun isDefault(context: Context): Boolean {
        val state = status(context)
        // voice_interaction_service is the setting Android actually binds for
        // assist gestures.  On older/OEM builds where it is absent, retain the
        // role-holder fallback so we do not regress those devices.
        return state.isReady || (
            state.activeService.isNullOrBlank() && state.roleHeld
        )
    }

    /** True when Android displays BlackClaw as selected but routes events elsewhere. */
    fun needsRepair(context: Context): Boolean = status(context).needsRepair

    private fun readSecure(context: Context, key: String): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, key) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun String?.matchesPackage(packageName: String): Boolean =
        !this.isNullOrBlank() && (this == packageName || this.startsWith("$packageName/"))

    /**
     * Open the system screen where the user can pick the default assistant.
     * Tries the most specific intents first and falls back gracefully.
     */
    fun openSettings(context: Context): Boolean {
        // RoleManager is the supported API on Android 10+. It gives the user a
        // confirmation screen and lets the OEM update both role + active service.
        // It cannot be used silently: voice_interaction_service is a protected
        // setting and ordinary apps are not allowed to write it directly.
        val current = status(context)
        if (!current.needsRepair && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
            if (roleManager?.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) == true) {
                val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                    return true
                } catch (e: Exception) {
                    XLog.w(TAG, "role settings failed: ${e.message}")
                }
            }
        }
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
