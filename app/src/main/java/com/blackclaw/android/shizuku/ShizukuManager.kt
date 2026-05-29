package com.blackclaw.android.shizuku

import android.content.Context
import android.content.pm.PackageManager
import com.blackclaw.android.utils.XLog
import rikka.shizuku.Shizuku
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Single-source-of-truth for Shizuku state.
 *
 * BlackClaw never depends on Shizuku — when it's not present we use the regular
 * accessibility/MediaProjection paths. When it IS present and the user has
 * granted us permission, we get adb-shell-level access:
 *
 *  - `input tap x y` in ~15 ms (vs ~200 ms via accessibility GestureDescription)
 *  - `am force-stop com.app` (real kill, not just HOME)
 *  - `pm install` / `pm uninstall` without consent dialogs
 *  - dumpsys / getprop / settings get
 *
 * State machine the UI cares about:
 *
 *  NOT_INSTALLED   → Shizuku app isn't on the device
 *  INSTALLED_OFF   → app is there but the user hasn't started the service
 *                    (either via wireless-debug self-pair or via PC)
 *  RUNNING_NO_PERM → service is up but BlackClaw hasn't been granted access yet
 *  READY           → granted, we can call sh()
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    enum class State { NOT_INSTALLED, INSTALLED_OFF, RUNNING_NO_PERM, READY }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        XLog.i(TAG, "Shizuku permission result: $grantResult")
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        XLog.i(TAG, "Shizuku binder received")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        XLog.w(TAG, "Shizuku binder dead")
    }

    @Volatile private var registered = false

    /** Hook our listeners. Call from Application.onCreate so we can react to
     *  Shizuku service start/stop without polling. */
    fun init() {
        if (registered) return
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            registered = true
        } catch (e: Throwable) {
            // Library missing or wrong version — non-fatal
            XLog.w(TAG, "Shizuku init failed: ${e.message}")
        }
    }

    fun isAppInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun state(context: Context): State {
        if (!isAppInstalled(context)) return State.NOT_INSTALLED
        val pingOk = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (!pingOk) return State.INSTALLED_OFF
        return try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) State.READY
            else State.RUNNING_NO_PERM
        } catch (_: Throwable) {
            State.RUNNING_NO_PERM
        }
    }

    fun isReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    /** Asynchronous permission request. Result arrives in [permissionListener]. */
    fun requestPermission(requestCode: Int = 9135) {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                XLog.w(TAG, "User previously denied Shizuku — must grant from notification panel")
                return
            }
            Shizuku.requestPermission(requestCode)
        } catch (e: Throwable) {
            XLog.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    /**
     * Run an arbitrary shell command and capture stdout+stderr.
     *
     * Returns null when Shizuku isn't ready. Caller should handle that and
     * fall back to whatever path doesn't need shell access.
     *
     * Implementation: route via /system/bin/sh through Shizuku's privileged
     * Process API. Reflection avoids the @hide-marked direct call.
     */
    fun sh(command: String, timeoutMs: Long = 8_000L): String? {
        if (!isReady()) return null
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val process = method.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null,
            ) as Process

            val out = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                while (reader.ready()) out.append(reader.readLine() ?: "").append('\n')
                while (errReader.ready()) out.append(errReader.readLine() ?: "").append('\n')
                if (!process.isAlive) break
                Thread.sleep(10)
            }
            try { process.waitFor() } catch (_: InterruptedException) {}
            try { process.destroy() } catch (_: Throwable) {}
            out.toString().trim()
        } catch (e: Throwable) {
            XLog.w(TAG, "Shizuku sh('$command') failed: ${e.message}")
            null
        }
    }

    /** Friendly status line for the UI. */
    fun describe(context: Context): String = when (state(context)) {
        State.NOT_INSTALLED -> "Shizuku no instalado"
        State.INSTALLED_OFF -> "Shizuku instalado · servicio apagado"
        State.RUNNING_NO_PERM -> "Shizuku activo · sin permiso para BlackClaw"
        State.READY -> "Shizuku listo · acciones rápidas activas"
    }
}
