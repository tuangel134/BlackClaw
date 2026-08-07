package com.blackclaw.android.cards

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Serialises [AssistCard]s so they can ride from a tool to the UI as one string.
 *
 * ## Why a string and not the objects themselves
 *
 * A tool returns a `ToolResult`, which crosses the agent loop, the orchestrator and a
 * `TaskEvent` before anything draws it. Threading a typed payload through all of that
 * would change four signatures that hundreds of call sites depend on; one opaque string
 * rides along untouched.
 *
 * ## Why Gson and not org.json
 *
 * Gson is a real dependency, so its classes work in JVM unit tests. `org.json` on the
 * unit-test classpath is an Android stub that returns defaults, which would make these
 * tests pass while proving nothing.
 *
 * ## Why decoding never throws
 *
 * A card is a nicer way to show an answer the user is already getting as text. If a
 * payload is malformed the right outcome is "no card", not a crash on the assistant
 * panel — that would trade a working screen for a cosmetic feature. Unreadable entries
 * are skipped individually so one bad result cannot take the good ones with it.
 */
object AssistCardCodec {

    private const val KEY_TYPE = "type"
    private const val WEATHER = "weather"
    private const val PLACE = "place"
    private const val OFFER = "offer"
    private const val LINK = "link"
    private const val SUMMARY = "summary"

    /** Upper bound on cards in one payload, so a runaway search cannot flood the panel. */
    const val MAX_CARDS = 8

    fun encode(cards: List<AssistCard>): String {
        val array = JsonArray()
        cards.take(MAX_CARDS).forEach { card ->
            array.add(
                when (card) {
                    is AssistCard.Weather -> JsonObject().apply {
                        addProperty(KEY_TYPE, WEATHER)
                        addProperty("place", card.place)
                        addProperty("tempC", card.tempC)
                        addProperty("conditionCode", card.conditionCode)
                        addProperty("condition", card.condition)
                        addProperty("isDay", card.isDay)
                        card.feelsLikeC?.let { addProperty("feelsLikeC", it) }
                        card.humidityPct?.let { addProperty("humidityPct", it) }
                        card.windKph?.let { addProperty("windKph", it) }
                        card.rainChancePct?.let { addProperty("rainChancePct", it) }
                        card.lat?.let { addProperty("lat", it) }
                        card.lon?.let { addProperty("lon", it) }
                    }

                    is AssistCard.Place -> JsonObject().apply {
                        addProperty(KEY_TYPE, PLACE)
                        addProperty("name", card.name)
                        addProperty("lat", card.lat)
                        addProperty("lon", card.lon)
                        addProperty("detail", card.detail)
                    }

                    is AssistCard.Offer -> JsonObject().apply {
                        addProperty(KEY_TYPE, OFFER)
                        addProperty("title", card.title)
                        addProperty("priceLabel", card.priceLabel)
                        addProperty("url", card.url)
                        addProperty("merchant", card.merchant)
                        addProperty("snippet", card.snippet)
                    }

                    is AssistCard.Link -> JsonObject().apply {
                        addProperty(KEY_TYPE, LINK)
                        addProperty("title", card.title)
                        addProperty("url", card.url)
                        addProperty("snippet", card.snippet)
                    }

                    is AssistCard.Summary -> JsonObject().apply {
                        addProperty(KEY_TYPE, SUMMARY)
                        addProperty("kind", card.kind.name)
                        addProperty("label", card.label)
                        addProperty("value", card.value)
                        addProperty("detail", card.detail)
                    }
                }
            )
        }
        return array.toString()
    }

    fun decode(payload: String?): List<AssistCard> {
        if (payload.isNullOrBlank()) return emptyList()
        val array = runCatching { JsonParser.parseString(payload).asJsonArray }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<AssistCard>()
        for (element in array) {
            if (out.size >= MAX_CARDS) break
            val obj = runCatching { element.asJsonObject }.getOrNull() ?: continue
            val card = runCatching { readCard(obj) }.getOrNull() ?: continue
            out += card
        }
        return out
    }

    private fun readCard(o: JsonObject): AssistCard? = when (o.str(KEY_TYPE)) {
        WEATHER -> {
            // A weather card without a temperature is not a weather card. Required
            // fields are checked so a half-populated payload becomes no card at all
            // rather than one showing "NaN°".
            val place = o.str("place")
            val temp = o.num("tempC")
            if (place.isBlank() || temp == null) null
            else AssistCard.Weather(
                place = place,
                tempC = temp,
                conditionCode = o.num("conditionCode")?.toInt() ?: -1,
                condition = o.str("condition"),
                isDay = o.bool("isDay") ?: true,
                feelsLikeC = o.num("feelsLikeC"),
                humidityPct = o.num("humidityPct")?.toInt(),
                windKph = o.num("windKph"),
                rainChancePct = o.num("rainChancePct")?.toInt(),
                lat = o.num("lat"),
                lon = o.num("lon"),
            )
        }

        PLACE -> {
            val lat = o.num("lat")
            val lon = o.num("lon")
            if (lat == null || lon == null || !validCoordinates(lat, lon)) null
            else AssistCard.Place(
                name = o.str("name").ifBlank { "Ubicación" },
                lat = lat,
                lon = lon,
                detail = o.str("detail"),
            )
        }

        OFFER -> {
            val title = o.str("title")
            val price = o.str("priceLabel")
            val url = o.str("url")
            // Without a price it is a link, not an offer. Falling back rather than
            // dropping keeps the result visible.
            when {
                title.isBlank() -> null
                price.isBlank() -> AssistCard.Link(title, url, o.str("snippet"))
                else -> AssistCard.Offer(title, price, url, o.str("merchant"), o.str("snippet"))
            }
        }

        LINK -> {
            val title = o.str("title")
            if (title.isBlank()) null else AssistCard.Link(title, o.str("url"), o.str("snippet"))
        }

        SUMMARY -> {
            val kind = runCatching { SummaryKind.valueOf(o.str("kind")) }.getOrNull()
            val value = o.str("value")
            if (kind == null || value.isBlank()) null
            else AssistCard.Summary(kind, o.str("label"), value, o.str("detail"))
        }

        else -> null
    }

    /** Guards against a swapped or garbage coordinate pair reaching a map request. */
    fun validCoordinates(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat >= -90.0 && lat <= 90.0 &&
            lon >= -180.0 && lon <= 180.0

    private fun JsonObject.str(key: String): String =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asString }.getOrNull().orEmpty()

    private fun JsonObject.num(key: String): Double? =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asDouble }.getOrNull()
            ?.takeIf { it.isFinite() }

    private fun JsonObject.bool(key: String): Boolean? =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asBoolean }.getOrNull()
}
