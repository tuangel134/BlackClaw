package com.blackclaw.android.tool.impl

import android.content.Intent
import android.net.Uri
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolParameter
import com.blackclaw.android.tool.ToolResult
import java.net.URLEncoder

/**
 * Jump straight into a popular app at the relevant screen via its deep link —
 * the fast path for "pide un Uber", "busca tacos en Uber Eats", "pon Bad Bunny
 * en Spotify", "navega a casa". Uses [AppDeepLinks]; falls back to launching the
 * app or opening a web URL when the scheme/app isn't available.
 *
 * After this, use accessibility tools (get_screen_info / tap_node / tap_ocr) to
 * finish the flow (confirm the ride, pick an option, hit pay).
 */
class OpenAppActionTool : BaseTool() {
    override fun getName() = "open_app_action"
    override fun getDisplayName() = "Abrir app (deep link)"
    override fun getDescriptionEN() =
        "Open a popular app directly at the right screen via deep link — much faster than tapping " +
        "through the UI. Supported keys include: uber, uber_eats, didi, rappi, lyft, cabify, bolt, " +
        "free_now, indrive, moovit, doordash, deliveroo, wolt, ifood, pedidosya, glovo, dominos, " +
        "spotify, youtube, maps, waze, netflix, disney_plus, max, prime_video, twitch, tiktok, " +
        "crunchyroll, whatsapp, telegram, instagram, facebook, messenger, x, snapchat, reddit, " +
        "discord, pinterest, linkedin, amazon, mercadolibre, aliexpress, ebay, shein, temu, walmart, " +
        "playstore, booking, airbnb, expedia, skyscanner, paypal, mercadopago, cashapp, googlepay, " +
        "gmail, gcalendar, gdrive, gkeep, translate, chrome, google, photos, meet, outlook, slack, " +
        "zoom, yelp, tripadvisor, tinder, bumble, duolingo. " +
        "Pass 'app' (a key or spoken name) and an optional 'query' (destination, food, song, product, " +
        "search term). Examples: open_app_action(app='uber', query='Aeropuerto de Madrid'); " +
        "open_app_action(app='ebay', query='teclado mecánico'); open_app_action(app='booking', query='Cancún'); " +
        "open_app_action(app='maps', query='casa'). " +
        "Then verify with get_screen_info and finish the flow with accessibility taps."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getBrief() = "abre apps populares (Uber, Uber Eats, Spotify, Maps…) directo a la pantalla útil vía deep link"
    override fun getParameters() = listOf(
        ToolParameter("app", "string", "App key or spoken name (e.g. 'uber', 'uber_eats', 'spotify', 'maps').", true),
        ToolParameter("query", "string", "Optional: destination / food / song / search term.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val appRaw = requireString(params, "app").trim()
        val query = optionalString(params, "query", "").trim()
        val entry = AppDeepLinks.byKey(appRaw) ?: AppDeepLinks.match(appRaw)
            ?: return ToolResult.error(
                "App '$appRaw' no está en el catálogo de deep links. Usa open_app(package_name=…) o " +
                "get_installed_apps(keyword=…) para encontrarla.")

        val ctx = ClawApplication.instance
        val encoded = if (query.isNotEmpty())
            runCatching { URLEncoder.encode(query, "UTF-8") }.getOrDefault(query) else ""

        // Maps + "nearest/cercano": open the distance-sorted SEARCH list instead of
        // google.navigation (which auto-routes to a single, not-always-closest match).
        if (entry.key == "maps" && query.isNotEmpty() &&
            Regex("cercan|cerca\\b|nearest|más cercano|mas cercano|cerca de mí|cerca de mi")
                .containsMatchIn(query.lowercase())) {
            val geo = "geo:0,0?q=$encoded"
            if (tryOpen(ctx, geo, entry.pkg)) {
                return ToolResult.success(
                    "Abrí el mapa con resultados de '$query' ordenados por cercanía. " +
                    "El primero es el más cercano; toca 'Cómo llegar' en ese para navegar.")
            }
        }

        // Build the candidate URIs in priority order.
        val candidates = buildList {
            if (query.isNotEmpty() && entry.searchUri != null) {
                // Some schemes (spotify:search:) want raw text, web wants encoded.
                add(entry.searchUri.replace("{q}", if (entry.searchUri.startsWith("http")) encoded else query))
            }
            entry.openUri?.let { add(it) }
            if (query.isNotEmpty() && entry.webFallback != null) add(entry.webFallback.replace("{q}", encoded))
            entry.webFallback?.let { add(it.replace("{q}", encoded)) }
        }.distinct()

        // Try each candidate: first targeting the app package, then without.
        // Skip custom schemes the app doesn't actually register on THIS device
        // (avoids opening a broken/blank target) — verified via the scanner.
        for (uri in candidates) {
            val isHttp = uri.startsWith("http")
            if (!isHttp && !com.blackclaw.android.perception.AppActionScanner.resolves(uri, entry.pkg)) {
                continue
            }
            if (tryOpen(ctx, uri, entry.pkg)) {
                val what = if (query.isNotEmpty()) " con '$query'" else ""
                return ToolResult.success("Abrí ${entry.key}$what. Revisa la pantalla y completa el flujo.")
            }
        }

        // Last resort: launch the app by package.
        val launch = ctx.packageManager.getLaunchIntentForPackage(entry.pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                ctx.startActivity(launch)
                ToolResult.success("Abrí ${entry.key} (sin deep link). Completa el flujo en pantalla.")
            } catch (e: Exception) {
                ToolResult.error("No pude abrir ${entry.key}: ${e.message}")
            }
        }
        return ToolResult.error("${entry.key} no está instalada. Puedes instalarla desde Play Store.")
    }

    /** Try ACTION_VIEW for [uri], first pinned to [pkg], then free, to avoid a chooser. */
    private fun tryOpen(ctx: android.content.Context, uri: String, pkg: String): Boolean {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        // Pinned to the package first.
        runCatching {
            val i = Intent(Intent.ACTION_VIEW, parsed).apply {
                setPackage(pkg); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (i.resolveActivity(ctx.packageManager) != null) { ctx.startActivity(i); return true }
        }
        // Free (no package) — lets a browser handle https fallbacks.
        if (uri.startsWith("http")) {
            runCatching {
                val i = Intent(Intent.ACTION_VIEW, parsed).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                if (i.resolveActivity(ctx.packageManager) != null) { ctx.startActivity(i); return true }
            }
        }
        return false
    }
}
