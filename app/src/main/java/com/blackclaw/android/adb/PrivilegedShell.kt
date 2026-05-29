package com.blackclaw.android.adb

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.shizuku.ShizukuManager
import kotlinx.coroutines.runBlocking

/**
 * Single entry point for "give me adb-shell-level power", regardless of HOW we
 * got it. BlackClaw has two independent backends:
 *
 *   1. Shizuku  — if the user installed the Shizuku app and granted us access.
 *   2. Self-ADB — if the user paired BlackClaw with its own adbd over loopback
 *                 (Wireless Debugging). No extra app, no PC.
 *
 * Tools shouldn't care which one is live. They call [exec] / [execFast] and we
 * route to whatever's ready, preferring Shizuku (slightly lower latency since
 * there's no per-call TLS stream setup) and falling back to self-ADB.
 *
 * This is what lets `fast_tap`, `fast_swipe`, `shell_exec`, `force_stop_app`
 * work on a device like the user's where Shizuku isn't available but Wireless
 * Debugging self-pairing is.
 */
object PrivilegedShell {

    enum class Backend { NONE, SHIZUKU, ADB }

    /** Which backend, if any, can run a command right now. */
    fun activeBackend(): Backend = when {
        ShizukuManager.isReady() -> Backend.SHIZUKU
        AdbController.isPaired() -> Backend.ADB   // may need a (cheap) reconnect
        else -> Backend.NONE
    }

    fun isAvailable(): Boolean = activeBackend() != Backend.NONE

    /**
     * Run a command and return combined stdout+stderr, or null if no backend is
     * usable (or the command failed).
     */
    fun exec(command: String, timeoutMs: Long = 8_000L): String? {
        // Prefer Shizuku — it's synchronous and has no stream-setup cost.
        if (ShizukuManager.isReady()) {
            return ShizukuManager.sh(command, timeoutMs)
        }
        if (AdbController.isPaired()) {
            val ctx = ClawApplication.instance
            return runBlocking { AdbController.shell(ctx, command, timeoutMs) }
        }
        return null
    }

    /**
     * Fire-and-forget variant for latency-sensitive input events (taps/swipes)
     * where we don't need to read output. Returns true if dispatched.
     */
    fun execFast(command: String): Boolean {
        if (ShizukuManager.isReady()) {
            ShizukuManager.sh(command, 2_000L)
            return true
        }
        if (AdbController.isPaired()) {
            val ctx = ClawApplication.instance
            return runBlocking { AdbController.shellFast(ctx, command) }
        }
        return false
    }

    /** Human-readable status for the UI / agent error messages. */
    fun describe(): String = when (activeBackend()) {
        Backend.SHIZUKU -> "Shizuku activo"
        Backend.ADB -> AdbController.describe()
        Backend.NONE -> "Sin acceso privilegiado (ni Shizuku ni ADB emparejado)"
    }
}
