package com.blackclaw.android.perception

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.CalendarContract
import android.provider.MediaStore
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.impl.AppDeepLinks
import com.blackclaw.android.utils.XLog

/**
 * Auto-discovers what BlackClaw can actually DO on THIS device, by asking the
 * system PackageManager which installed apps handle each capability intent —
 * no root, no adb, no hardcoding. Also verifies which catalog deep links really
 * resolve here (custom schemes vary by app version/region).
 *
 * This lets the agent adapt to the user's real app set: which music players,
 * map apps, email clients, share targets, etc. are present and usable.
 */
object AppActionScanner {

    private const val TAG = "AppActionScanner"

    data class AppRef(val label: String, val pkg: String)

    /** A capability and the installed apps that can fulfil it. */
    data class Capability(val key: String, val label: String, val apps: List<AppRef>)

    private fun pm(): PackageManager = ClawApplication.instance.packageManager

    /** Apps that can handle [intent], as label+package (deduped by package). */
    private fun handlers(intent: Intent): List<AppRef> = runCatching {
        @Suppress("DEPRECATION")
        val resolved: List<ResolveInfo> = pm().queryIntentActivities(intent, 0)
        resolved.asSequence()
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                // Skip the system resolver/chooser placeholder.
                if (pkg == "android") return@mapNotNull null
                val label = runCatching { ri.loadLabel(pm()).toString() }.getOrDefault(pkg)
                AppRef(label, pkg)
            }
            .distinctBy { it.pkg }
            .toList()
    }.getOrElse { XLog.w(TAG, "handlers() failed: ${it.message}"); emptyList() }

    /** The capability intents we probe for. */
    private fun capabilityIntents(): List<Triple<String, String, Intent>> = listOf(
        Triple("music", "Reproducir música",
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)),
        Triple("navigate", "Navegar / mapas",
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=test"))),
        Triple("email", "Enviar correo",
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:test@example.com"))),
        Triple("sms", "Enviar SMS",
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:123"))),
        Triple("call", "Llamar",
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:123"))),
        Triple("browser", "Abrir webs / buscar",
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
        Triple("share", "Compartir texto",
            Intent(Intent.ACTION_SEND).apply { type = "text/plain" }),
        Triple("calendar", "Crear evento de calendario",
            Intent(Intent.ACTION_INSERT).apply { data = CalendarContract.Events.CONTENT_URI }),
        Triple("web_search", "Búsqueda web",
            Intent(Intent.ACTION_WEB_SEARCH)),
    )

    /** Scan all standard capabilities and which apps fulfil each. */
    fun scanCapabilities(): List<Capability> =
        capabilityIntents().map { (key, label, intent) ->
            Capability(key, label, handlers(intent))
        }

    /** True if [pkg] can handle ACTION_VIEW for [uri] (a deep-link probe). */
    fun resolves(uri: String, pkg: String? = null): Boolean = runCatching {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        if (pkg != null) i.setPackage(pkg)
        i.resolveActivity(pm()) != null || handlers(i).isNotEmpty()
    }.getOrDefault(false)

    /** Is a given package installed? */
    fun isInstalled(pkg: String): Boolean = runCatching {
        pm().getLaunchIntentForPackage(pkg) != null
    }.getOrDefault(false)

    /**
     * Which catalog deep-link entries are usable on THIS device: the app is
     * installed AND (its custom scheme resolves OR it has a web fallback).
     */
    fun verifiedCatalog(): List<Pair<AppDeepLinks.Entry, Boolean>> =
        AppDeepLinks.CATALOG.mapNotNull { e ->
            if (!isInstalled(e.pkg)) return@mapNotNull null
            val schemeWorks = e.openUri?.let { resolves(it, e.pkg) } ?: false
            e to schemeWorks
        }

    /** Human-readable report for the agent / UI. */
    fun report(): String {
        val sb = StringBuilder()
        sb.append("Capacidades detectadas en este teléfono:\n")
        for (cap in scanCapabilities()) {
            if (cap.apps.isEmpty()) continue
            val names = cap.apps.take(6).joinToString(", ") { it.label }
            sb.append("• ${cap.label}: $names\n")
        }
        val catalog = verifiedCatalog()
        if (catalog.isNotEmpty()) {
            sb.append("\nApps con deep link instaladas (").append(catalog.size).append("):\n")
            sb.append(catalog.joinToString(", ") { (e, scheme) ->
                e.key + if (scheme) "✓" else "~"
            })
        }
        return sb.toString().trim()
    }
}
