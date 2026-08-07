package com.blackclaw.android.cards

/**
 * A structured result the assistant can draw instead of describing in a sentence.
 *
 * ## Why this exists
 *
 * The tools already hold everything a card needs and then throw it away. `WeatherTool`
 * reads temperature, apparent temperature, humidity, wind, the WMO condition code, the
 * day/night flag, the rain probability curve **and** the coordinates it geocoded — and
 * emits one Spanish sentence. `WebAnswerTool` models its results as title/snippet/url,
 * which is exactly a link card, and flattens them into a numbered text blob. Whatever
 * survives is then truncated to 300 characters by the orchestrator.
 *
 * So the panel could only ever guess: today it matches substrings like "humedad" against
 * the model's prose and reprints that same prose with an emoji beside it. A card built
 * that way cannot be interactive, because it has no fields — only a paragraph.
 *
 * These types are the contract that carries the real values through instead.
 *
 * ## Deliberately not a general-purpose schema
 *
 * Every variant here exists because a tool can actually produce it from data it already
 * has. Adding a card type without a producer would just be a promise the assistant
 * cannot keep.
 *
 * Pure Kotlin, no Android and no Compose, so the mapping and the maths around it are
 * unit-testable.
 */
sealed interface AssistCard {

    /**
     * Current conditions for one place.
     *
     * @param condition human wording, already localised by the tool that produced it.
     *   The UI must not re-derive this from [conditionCode]: the tool owns the WMO
     *   code-to-words table and a second copy in the UI would drift from it.
     * @param conditionCode the WMO code, kept so the icon is chosen from data rather
     *   than by matching words in [condition].
     * @param lat coordinates when known, which is what lets the card offer a map.
     */
    data class Weather(
        val place: String,
        val tempC: Double,
        val conditionCode: Int,
        val condition: String,
        val isDay: Boolean,
        val feelsLikeC: Double? = null,
        val humidityPct: Int? = null,
        val windKph: Double? = null,
        val rainChancePct: Int? = null,
        val lat: Double? = null,
        val lon: Double? = null,
    ) : AssistCard

    /** A point on the map. Renders a real tile and opens the map app when tapped. */
    data class Place(
        val name: String,
        val lat: Double,
        val lon: Double,
        val detail: String = "",
    ) : AssistCard

    /**
     * A search result that carries a price.
     *
     * @param priceLabel the price **verbatim** as the source wrote it. Never reformatted:
     *   the thousands and decimal separators differ by locale, so "re-formatting"
     *   `1.299,00 €` without knowing which convention it follows is how a price silently
     *   turns into `1.30 €`. Quoting it keeps the card honest.
     */
    data class Offer(
        val title: String,
        val priceLabel: String,
        val url: String,
        val merchant: String = "",
        val snippet: String = "",
    ) : AssistCard

    /** A search result with no price. The plain sibling of [Offer]. */
    data class Link(
        val title: String,
        val url: String,
        val snippet: String = "",
    ) : AssistCard

    /** A confirmed device/action result produced directly by a tool. */
    data class Summary(
        val kind: SummaryKind,
        val label: String,
        val value: String,
        val detail: String = "",
    ) : AssistCard
}

/** Visual meaning for [AssistCard.Summary]; it is data, never inferred from prose. */
enum class SummaryKind { BATTERY, MUSIC, TIMER, SONG }
