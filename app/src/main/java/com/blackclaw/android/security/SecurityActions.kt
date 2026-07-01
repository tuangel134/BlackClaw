package com.blackclaw.android.security

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.adb.PrivilegedShell
import com.blackclaw.android.utils.XLog

/**
 * The actions BlackClaw can take against a misbehaving app. Where a privileged
 * backend (Shizuku / self-paired ADB) is available we act silently and for real
 * (`appops`, `am force-stop`, `pm disable-user`); otherwise we open the right
 * system screen so the user can do it in one tap. Uninstall always goes through
 * the system dialog (Android forbids silent uninstall of user apps).
 */
object SecurityActions {

    private const val TAG = "SecurityActions"
    private const val SELF = "com.blackclaw.android"

    /** Stop the ads NOW: revoke the overlay permission and force-stop the app. */
    fun neutralize(pkg: String): String {
        if (pkg.isBlank()) return "Paquete vacío."
        if (pkg == SELF) return "No voy a bloquearme a mí mismo."
        if (PrivilegedShell.isAvailable()) {
            val r1 = PrivilegedShell.exec("appops set $pkg SYSTEM_ALERT_WINDOW ignore")
            val r2 = PrivilegedShell.exec("am force-stop $pkg")
            XLog.i(TAG, "neutralize $pkg: appops=$r1 stop=$r2")
            return "Bloqueé $pkg: revoqué el permiso de superposición y forcé su detención. " +
                "Si vuelve a molestar, considera desinstalarla."
        }
        openOverlaySettings(pkg)
        return "Sin acceso privilegiado (Shizuku/ADB). Abrí los ajustes de superposición de $pkg " +
            "para que quites 'Mostrar sobre otras apps'. Actívalo también en Modo Pro para que pueda hacerlo solo."
    }

    fun forceStop(pkg: String): String {
        if (pkg == SELF) return "No voy a detenerme a mí mismo."
        if (!PrivilegedShell.isAvailable()) { openAppSettings(pkg); return "Sin privilegios: abrí los ajustes de $pkg para 'Forzar detención'." }
        PrivilegedShell.exec("am force-stop $pkg")
        return "Forcé la detención de $pkg."
    }

    fun revokeOverlay(pkg: String): String {
        if (!PrivilegedShell.isAvailable()) { openOverlaySettings(pkg); return "Sin privilegios: abrí los ajustes de superposición de $pkg." }
        PrivilegedShell.exec("appops set $pkg SYSTEM_ALERT_WINDOW ignore")
        return "Revoqué el permiso de superposición de $pkg."
    }

    /** Disable the app (keeps it installed but inert). Needs privileges. */
    fun disableApp(pkg: String): String {
        if (pkg == SELF) return "No voy a deshabilitarme a mí mismo."
        if (!PrivilegedShell.isAvailable()) { openAppSettings(pkg); return "Sin privilegios: no puedo deshabilitarla; abrí sus ajustes." }
        val r = PrivilegedShell.exec("pm disable-user --user 0 $pkg")
        return if (r != null) "Deshabilité $pkg (queda instalada pero inactiva)." else "No se pudo deshabilitar $pkg."
    }

    /** Uninstall — always via the system dialog (user confirms). */
    fun uninstall(pkg: String): String {
        if (pkg == SELF) return "No voy a desinstalarme a mí mismo."
        return try {
            ClawApplication.instance.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Abrí el desinstalador de $pkg. El usuario debe confirmar."
        } catch (e: Exception) {
            "No se pudo abrir el desinstalador: ${e.message}"
        }
    }

    fun openAppSettings(pkg: String) {
        runCatching {
            ClawApplication.instance.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun openOverlaySettings(pkg: String) {
        val opened = runCatching {
            ClawApplication.instance.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
        if (!opened) openAppSettings(pkg)
    }
}
