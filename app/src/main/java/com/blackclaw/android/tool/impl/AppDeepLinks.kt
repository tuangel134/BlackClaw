package com.blackclaw.android.tool.impl

/**
 * Catalog of deep links for popular apps so BlackClaw can jump STRAIGHT into the
 * right screen (ride request, food search, song, navigation…) instead of tapping
 * through the UI tap-by-tap. This is the same trick assistants like Gemini use to
 * "order an Uber fast": open the app via its URI scheme at the relevant flow.
 *
 * Each entry maps user-spoken aliases to:
 *  - [pkg]         the app's package (so we target it directly, no chooser)
 *  - [openUri]     opens the app (optionally to a sensible default screen)
 *  - [searchUri]   template with {q} replaced by the URL-encoded query
 *  - [webFallback] an https URL used if the app/scheme isn't available ({q} too)
 *
 * The accessibility layer then finishes whatever the deep link can't (confirming,
 * choosing options). Deep link gets us 80% of the way instantly.
 */
object AppDeepLinks {

    data class Entry(
        val key: String,
        val aliases: List<String>,
        val pkg: String,
        val openUri: String? = null,
        val searchUri: String? = null,
        val webFallback: String? = null,
    )

    val CATALOG = listOf(
        Entry(
            key = "uber",
            aliases = listOf("uber", "pide un uber", "pedir uber", "taxi uber"),
            pkg = "com.ubercab",
            openUri = "uber://?action=setPickup&pickup=my_location",
            // Uber accepts a dropoff nickname/address via the deep link.
            searchUri = "uber://?action=setPickup&pickup=my_location&dropoff[nickname]={q}&dropoff[formatted_address]={q}",
            webFallback = "https://m.uber.com/looking?drop[0]={q}",
        ),
        Entry(
            key = "uber_eats",
            aliases = listOf("uber eats", "ubereats", "comida uber", "uber comida"),
            pkg = "com.ubercab.eats",
            openUri = "ubereats://",
            searchUri = "ubereats://eats/search?q={q}",
            webFallback = "https://www.ubereats.com/search?q={q}",
        ),
        Entry(
            key = "didi",
            aliases = listOf("didi", "pide un didi", "didi taxi"),
            pkg = "com.didiglobal.passenger",
            openUri = "didi://",
            webFallback = "https://www.didiglobal.com/",
        ),
        Entry(
            key = "rappi",
            aliases = listOf("rappi"),
            pkg = "com.grability.rappi",
            openUri = "rappi://",
            searchUri = "rappi://search?q={q}",
            webFallback = "https://www.rappi.com.mx/",
        ),
        Entry(
            key = "lyft",
            aliases = listOf("lyft"),
            pkg = "me.lyft.android",
            openUri = "lyft://ridetype?id=lyft",
            webFallback = "https://www.lyft.com/",
        ),
        Entry(
            key = "cabify",
            aliases = listOf("cabify"),
            pkg = "com.cabify.rider",
            openUri = "cabify://",
            webFallback = "https://cabify.com/",
        ),
        Entry(
            key = "doordash",
            aliases = listOf("doordash", "door dash"),
            pkg = "com.dd.doordash",
            openUri = "doordash://",
            webFallback = "https://www.doordash.com/",
        ),
        Entry(
            key = "glovo",
            aliases = listOf("glovo"),
            pkg = "com.glovo",
            openUri = "glovoapp://",
            webFallback = "https://glovoapp.com/",
        ),
        Entry(
            key = "spotify",
            aliases = listOf("spotify", "pon musica", "pon música", "reproduce"),
            pkg = "com.spotify.music",
            openUri = "spotify://",
            searchUri = "spotify:search:{q}",
            webFallback = "https://open.spotify.com/search/{q}",
        ),
        Entry(
            key = "maps",
            aliases = listOf("maps", "google maps", "mapa", "navega a", "como llego a", "cómo llego a", "llévame a", "llevame a"),
            pkg = "com.google.android.apps.maps",
            openUri = "geo:0,0",
            searchUri = "google.navigation:q={q}",
            webFallback = "https://www.google.com/maps/search/{q}",
        ),
        Entry(
            key = "waze",
            aliases = listOf("waze"),
            pkg = "com.waze",
            openUri = "waze://",
            searchUri = "waze://?q={q}&navigate=yes",
            webFallback = "https://waze.com/ul?q={q}",
        ),
        Entry(
            key = "youtube",
            aliases = listOf("youtube", "you tube"),
            pkg = "com.google.android.youtube",
            openUri = "https://www.youtube.com",
            searchUri = "https://www.youtube.com/results?search_query={q}",
            webFallback = "https://www.youtube.com/results?search_query={q}",
        ),
        Entry(
            key = "whatsapp",
            aliases = listOf("whatsapp", "whats app", "wasap"),
            pkg = "com.whatsapp",
            openUri = "https://wa.me/",
            webFallback = "https://web.whatsapp.com/",
        ),
        Entry(
            key = "telegram",
            aliases = listOf("telegram"),
            pkg = "org.telegram.messenger",
            openUri = "tg://",
            searchUri = "tg://search?query={q}",
            webFallback = "https://web.telegram.org/",
        ),
        Entry(
            key = "instagram",
            aliases = listOf("instagram", "insta"),
            pkg = "com.instagram.android",
            openUri = "instagram://",
            webFallback = "https://www.instagram.com/",
        ),
        Entry(
            key = "amazon",
            aliases = listOf("amazon"),
            pkg = "com.amazon.mShop.android.shopping",
            openUri = "amazon://",
            searchUri = "https://www.amazon.com/s?k={q}",
            webFallback = "https://www.amazon.com/s?k={q}",
        ),
        Entry(
            key = "playstore",
            aliases = listOf("play store", "google play", "tienda de apps", "instalar app"),
            pkg = "com.android.vending",
            openUri = "market://",
            searchUri = "market://search?q={q}",
            webFallback = "https://play.google.com/store/search?q={q}",
        ),
        Entry(
            key = "netflix",
            aliases = listOf("netflix"),
            pkg = "com.netflix.mediaclient",
            openUri = "nflx://",
            searchUri = "nflx://www.netflix.com/search?q={q}",
            webFallback = "https://www.netflix.com/search?q={q}",
        ),

        // ── Transporte / movilidad ──
        Entry("bolt", listOf("bolt"), "ee.mtakso.client",
            openUri = "bolt://", webFallback = "https://bolt.eu/"),
        Entry("free_now", listOf("free now", "freenow", "mytaxi"), "taxi.android.client",
            openUri = "freenow://", webFallback = "https://free-now.com/"),
        Entry("indrive", listOf("indrive", "indriver"), "sinet.startup.inDriver",
            openUri = "indrive://", webFallback = "https://indrive.com/"),
        Entry("moovit", listOf("moovit", "transporte publico", "transporte público"), "com.tranzmate",
            openUri = "moovit://", webFallback = "https://moovit.com/"),

        // ── Comida / delivery ──
        Entry("deliveroo", listOf("deliveroo"), "com.deliveroo.orderapp",
            openUri = "deliveroo://", webFallback = "https://deliveroo.com/"),
        Entry("wolt", listOf("wolt"), "com.wolt.android",
            openUri = "wolt://", webFallback = "https://wolt.com/"),
        Entry("ifood", listOf("ifood"), "br.com.brainweb.ifood",
            openUri = "ifood://", webFallback = "https://www.ifood.com.br/"),
        Entry("pedidosya", listOf("pedidos ya", "pedidosya"), "com.pedidosya",
            openUri = "pedidosya://", webFallback = "https://www.pedidosya.com/"),
        Entry("dominos", listOf("dominos", "domino's", "pizza dominos"), "com.dominospizza",
            openUri = "dominos://", webFallback = "https://www.dominos.com/"),

        // ── Streaming de vídeo ──
        Entry("disney_plus", listOf("disney plus", "disney+", "disney"), "com.disney.disneyplus",
            openUri = "disneyplus://", webFallback = "https://www.disneyplus.com/search?q={q}"),
        Entry("max", listOf("max", "hbo", "hbo max"), "com.wbd.stream",
            openUri = "hbomax://", webFallback = "https://play.max.com/search?q={q}"),
        Entry("prime_video", listOf("prime video", "amazon prime", "primevideo"),
            "com.amazon.avod.thirdpartyclient",
            openUri = "primevideo://", webFallback = "https://www.primevideo.com/search/ref=atv_nb_sug?phrase={q}"),
        Entry("twitch", listOf("twitch"), "tv.twitch.android.app",
            openUri = "twitch://", webFallback = "https://www.twitch.tv/search?term={q}"),
        Entry("tiktok", listOf("tiktok", "tik tok"), "com.zhiliaoapp.musically",
            openUri = "snssdk1233://", webFallback = "https://www.tiktok.com/search?q={q}"),
        Entry("crunchyroll", listOf("crunchyroll", "anime"), "com.crunchyroll.crunchyroid",
            openUri = "crunchyroll://", webFallback = "https://www.crunchyroll.com/search?q={q}"),

        // ── Social / mensajería ──
        Entry("facebook", listOf("facebook", "face"), "com.facebook.katana",
            openUri = "fb://", webFallback = "https://www.facebook.com/"),
        Entry("messenger", listOf("messenger", "mensajero de facebook"), "com.facebook.orca",
            openUri = "fb-messenger://", webFallback = "https://www.messenger.com/"),
        Entry("x", listOf("twitter", " x ", "tuitear", "tweet"), "com.twitter.android",
            openUri = "twitter://", searchUri = "twitter://search?query={q}",
            webFallback = "https://twitter.com/search?q={q}"),
        Entry("snapchat", listOf("snapchat", "snap"), "com.snapchat.android",
            openUri = "snapchat://", webFallback = "https://www.snapchat.com/"),
        Entry("reddit", listOf("reddit"), "com.reddit.frontpage",
            openUri = "reddit://", searchUri = "https://www.reddit.com/search/?q={q}",
            webFallback = "https://www.reddit.com/search/?q={q}"),
        Entry("discord", listOf("discord"), "com.discord",
            openUri = "discord://", webFallback = "https://discord.com/app"),
        Entry("pinterest", listOf("pinterest"), "com.pinterest",
            openUri = "pinterest://", searchUri = "https://www.pinterest.com/search/pins/?q={q}",
            webFallback = "https://www.pinterest.com/search/pins/?q={q}"),
        Entry("linkedin", listOf("linkedin"), "com.linkedin.android",
            openUri = "linkedin://", webFallback = "https://www.linkedin.com/search/results/all/?keywords={q}"),

        // ── Compras ──
        Entry("mercadolibre", listOf("mercado libre", "mercadolibre", "meli"), "com.mercadolibre",
            openUri = "meli://", searchUri = "https://listado.mercadolibre.com.mx/{q}",
            webFallback = "https://listado.mercadolibre.com.mx/{q}"),
        Entry("aliexpress", listOf("aliexpress", "ali express"), "com.alibaba.aliexpresshd",
            openUri = "aliexpress://", searchUri = "https://www.aliexpress.com/wholesale?SearchText={q}",
            webFallback = "https://www.aliexpress.com/wholesale?SearchText={q}"),
        Entry("ebay", listOf("ebay"), "com.ebay.mobile",
            openUri = "ebay://", searchUri = "https://www.ebay.com/sch/i.html?_nkw={q}",
            webFallback = "https://www.ebay.com/sch/i.html?_nkw={q}"),
        Entry("shein", listOf("shein"), "com.zzkko",
            openUri = "shein://", webFallback = "https://www.shein.com/pdsearch/{q}"),
        Entry("temu", listOf("temu"), "com.einnovation.temu",
            openUri = "temu://", webFallback = "https://www.temu.com/search_result.html?search_key={q}"),
        Entry("walmart", listOf("walmart"), "com.walmart.android",
            openUri = "walmart://", searchUri = "https://www.walmart.com/search?q={q}",
            webFallback = "https://www.walmart.com/search?q={q}"),

        // ── Viajes / hospedaje ──
        Entry("booking", listOf("booking", "hotel"), "com.booking",
            openUri = "booking://", searchUri = "https://www.booking.com/searchresults.html?ss={q}",
            webFallback = "https://www.booking.com/searchresults.html?ss={q}"),
        Entry("airbnb", listOf("airbnb"), "com.airbnb.android",
            openUri = "airbnb://", searchUri = "https://www.airbnb.com/s/{q}",
            webFallback = "https://www.airbnb.com/s/{q}"),
        Entry("expedia", listOf("expedia"), "com.expedia.bookings",
            openUri = "expedia://", webFallback = "https://www.expedia.com/"),
        Entry("skyscanner", listOf("skyscanner", "vuelos", "vuelo"), "net.skyscanner.android.main",
            openUri = "skyscanner://", webFallback = "https://www.skyscanner.net/"),

        // ── Pagos ──
        Entry("paypal", listOf("paypal"), "com.paypal.android.p2pmobile",
            openUri = "paypal://", webFallback = "https://www.paypal.com/"),
        Entry("mercadopago", listOf("mercado pago", "mercadopago"), "com.mercadopago.wallet",
            openUri = "mercadopago://", webFallback = "https://www.mercadopago.com/"),
        Entry("cashapp", listOf("cash app", "cashapp"), "com.squareup.cash",
            openUri = "cashapp://", webFallback = "https://cash.app/"),
        Entry("googlepay", listOf("google pay", "gpay"), "com.google.android.apps.nbu.paisa.user",
            openUri = "tez://", webFallback = "https://pay.google.com/"),

        // ── Google / productividad ──
        Entry("gmail", listOf("gmail", "correo", "email", "mail"), "com.google.android.gm",
            openUri = "googlegmail://", webFallback = "https://mail.google.com/"),
        Entry("gcalendar", listOf("google calendar", "calendario de google"), "com.google.android.calendar",
            openUri = "content://com.android.calendar/time/", webFallback = "https://calendar.google.com/"),
        Entry("gdrive", listOf("google drive", "drive"), "com.google.android.apps.docs",
            openUri = "googledrive://", webFallback = "https://drive.google.com/"),
        Entry("gkeep", listOf("google keep", "keep", "notas de google"), "com.google.android.keep",
            openUri = "googlekeep://", webFallback = "https://keep.google.com/"),
        Entry("translate", listOf("traduce", "traductor", "translate", "traducir"),
            "com.google.android.apps.translate",
            openUri = "googletranslate://", webFallback = "https://translate.google.com/?op=translate&text={q}"),
        Entry("chrome", listOf("chrome", "navegador", "browser"), "com.android.chrome",
            openUri = "https://www.google.com", searchUri = "https://www.google.com/search?q={q}",
            webFallback = "https://www.google.com/search?q={q}"),
        Entry("google", listOf("google", "buscar en google", "busca en google"),
            "com.google.android.googlequicksearchbox",
            openUri = "https://www.google.com", searchUri = "https://www.google.com/search?q={q}",
            webFallback = "https://www.google.com/search?q={q}"),
        Entry("photos", listOf("google photos", "fotos", "galeria", "galería"),
            "com.google.android.apps.photos",
            openUri = "googlephotos://", webFallback = "https://photos.google.com/"),
        Entry("meet", listOf("google meet", "meet", "videollamada"), "com.google.android.apps.tachyon",
            openUri = "https://meet.google.com/", webFallback = "https://meet.google.com/"),
        Entry("outlook", listOf("outlook"), "com.microsoft.office.outlook",
            openUri = "ms-outlook://", webFallback = "https://outlook.live.com/"),
        Entry("slack", listOf("slack"), "com.Slack",
            openUri = "slack://open", webFallback = "https://slack.com/"),
        Entry("zoom", listOf("zoom"), "us.zoom.videomeetings",
            openUri = "zoomus://", webFallback = "https://zoom.us/"),

        // ── Otros útiles ──
        Entry("yelp", listOf("yelp"), "com.yelp.android",
            openUri = "yelp://", searchUri = "https://www.yelp.com/search?find_desc={q}",
            webFallback = "https://www.yelp.com/search?find_desc={q}"),
        Entry("tripadvisor", listOf("tripadvisor", "trip advisor"), "com.tripadvisor.tripadvisor",
            openUri = "tripadvisor://", webFallback = "https://www.tripadvisor.com/Search?q={q}"),
        Entry("tinder", listOf("tinder"), "com.tinder",
            openUri = "tinder://", webFallback = "https://tinder.com/"),
        Entry("bumble", listOf("bumble"), "com.bumble.app",
            openUri = "bumble://", webFallback = "https://bumble.com/"),
        Entry("duolingo", listOf("duolingo", "aprende idioma"), "com.duolingo",
            openUri = "duolingo://", webFallback = "https://www.duolingo.com/"),

        // ── Sonata (reproductor del usuario) ──
        Entry("sonata", listOf("sonata"), "com.sonata.player",
            openUri = "sonata://",
            searchUri = "sonata://play?query={q}",
            webFallback = null),

        // ── Música (ampliación) ──
        Entry("youtube_music", listOf("youtube music", "yt music", "ytmusic"),
            "com.google.android.apps.youtube.music",
            openUri = "https://music.youtube.com",
            searchUri = "https://music.youtube.com/search?q={q}",
            webFallback = "https://music.youtube.com/search?q={q}"),
        Entry("deezer", listOf("deezer"), "deezer.android.app",
            openUri = "deezer://",
            searchUri = "deezer://search/{q}",
            webFallback = "https://www.deezer.com/search/{q}"),
        Entry("soundcloud", listOf("soundcloud", "sound cloud"), "com.soundcloud.android",
            openUri = "soundcloud://",
            searchUri = "soundcloud://search?query={q}",
            webFallback = "https://soundcloud.com/search?q={q}"),
        Entry("apple_music", listOf("apple music"), "com.apple.android.music",
            openUri = "music://",
            searchUri = "music://search?term={q}",
            webFallback = "https://music.apple.com/search?term={q}"),
        Entry("musicolet", listOf("musicolet"), "in.krosbits.musicolet",
            openUri = null, webFallback = null),
        Entry("poweramp", listOf("poweramp"), "com.maxmpz.audioplayer",
            openUri = "poweramp://", webFallback = null),

        // ── Compras (ampliación) ──
        Entry("shopee", listOf("shopee"), "com.shopee.mx",
            openUri = "shopee://",
            searchUri = "shopee://search?keyword={q}",
            webFallback = "https://shopee.com.mx/search?keyword={q}"),
        Entry("wish", listOf("wish"), "com.contextlogic.wish",
            openUri = "wish://",
            webFallback = "https://www.wish.com/search/{q}"),

        // ── Mensajería (ampliación) ──
        Entry("signal", listOf("signal"), "org.thoughtcrime.securesms",
            openUri = "sgnl://", webFallback = "https://signal.org/"),
        Entry("viber", listOf("viber"), "com.viber.voip",
            openUri = "viber://", webFallback = "https://www.viber.com/"),
        Entry("line", listOf("line"), "jp.naver.line.android",
            openUri = "line://", webFallback = "https://line.me/"),
        Entry("wechat", listOf("wechat", "we chat"), "com.tencent.mm",
            openUri = "weixin://", webFallback = "https://web.wechat.com/"),
        Entry("whatsapp_business", listOf("whatsapp business", "whatsapp negocie"),
            "com.whatsapp.w4b",
            openUri = "https://wa.me/", webFallback = null),

        // ── Productividad (ampliación) ──
        Entry("notion", listOf("notion"), "notion.id",
            openUri = "notion://", webFallback = "https://www.notion.so/"),
        Entry("todoist", listOf("todoist"), "com.todoist",
            openUri = "todoist://", webFallback = "https://todoist.com/"),
        Entry("ticktick", listOf("ticktick", "tick tick"), "com.ticktick.task",
            openUri = "ticktick://", webFallback = "https://ticktick.com/"),
        Entry("word", listOf("word", "microsoft word", "documento word"),
            "com.microsoft.office.word",
            openUri = "ms-word://", webFallback = "https://office.com/"),
        Entry("excel", listOf("excel", "microsoft excel", "hoja de calculo", "hoja de cálculo"),
            "com.microsoft.office.excel",
            openUri = "ms-excel://", webFallback = "https://office.com/"),
        Entry("powerpoint", listOf("powerpoint", "power point", "presentacion", "presentación"),
            "com.microsoft.office.powerpoint",
            openUri = "ms-powerpoint://", webFallback = "https://office.com/"),
        Entry("teams", listOf("teams", "microsoft teams"), "com.microsoft.teams",
            openUri = "msteams://", webFallback = "https://teams.microsoft.com/"),
        Entry("trello", listOf("trello"), "com.trello",
            openUri = "trello://", webFallback = "https://trello.com/"),

        // ── Fitness / salud ──
        Entry("strava", listOf("strava"), "com.strava",
            openUri = "strava://", webFallback = "https://www.strava.com/"),
        Entry("google_fit", listOf("google fit", "fit", "ejercicio google"),
            "com.google.android.apps.fitness",
            openUri = "googlefit://", webFallback = "https://fit.google.com/"),
        Entry("samsung_health", listOf("samsung health", "salud samsung"),
            "com.sec.android.app.shealth",
            openUri = "samsunghealth://", webFallback = null),

        // ── Versiones Lite (comunes en LatAm) ──
        Entry("tiktok_lite", listOf("tiktok lite", "tik tok lite"),
            "com.zhiliaoapp.musically.go",
            openUri = "snssdk1233://", webFallback = "https://www.tiktok.com/"),
        Entry("facebook_lite", listOf("facebook lite", "face lite"),
            "com.facebook.lite",
            openUri = "fb://", webFallback = "https://www.facebook.com/"),
        Entry("messenger_lite", listOf("messenger lite"),
            "com.facebook.mlite",
            openUri = "fb-messenger://", webFallback = null),
    )

    /** Find the catalog entry whose alias is contained in [text] (longest wins). */
    fun match(text: String): Entry? {
        val lower = text.lowercase()
        var best: Entry? = null
        var bestLen = 0
        for (e in CATALOG) {
            for (a in e.aliases) {
                if (lower.contains(a) && a.length > bestLen) { best = e; bestLen = a.length }
            }
        }
        return best
    }

    fun byKey(key: String): Entry? = CATALOG.firstOrNull { it.key == key.lowercase().trim() }
}
