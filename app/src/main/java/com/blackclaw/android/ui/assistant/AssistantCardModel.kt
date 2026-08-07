package com.blackclaw.android.ui.assistant

import com.blackclaw.android.assistant.AssistantItem
import com.blackclaw.android.assistant.AssistantItemType

/**
 * Turns an [AssistantItem] into what a card should actually show.
 *
 * ## Why this is separate from the Composables
 *
 * The old card built its own strings inline, so the presentation rules — is this
 * overdue, does a finance row render as income or expense, what does an empty body
 * mean — were tangled into layout code and impossible to test. Everything here is a
 * pure function over the item, so the rules can be verified without a device, and
 * the Composables only decide where pixels go.
 */
object AssistantCardModel {

    /** How urgent an item is, which drives colour and emphasis. */
    enum class Urgency {
        /** Trigger time has passed and it is not done. */
        OVERDUE,

        /** Fires within the hour. */
        IMMINENT,

        /** Fires later. */
        SCHEDULED,

        /** No trigger time at all (a note, a shopping line). */
        NONE,

        /** Already handled. */
        DONE,
    }

    /** Everything a card needs, precomputed. */
    data class CardData(
        val title: String,
        val body: String,
        val urgency: Urgency,
        /** Relative time such as "en 20 min" / "hace 2 h", or empty. */
        val relativeTime: String,
        /** Absolute time such as "mañana 07:00", or empty. */
        val absoluteTime: String,
        /** Signed money string for finance rows, else empty. */
        val amountLabel: String,
        val isIncome: Boolean,
        /** True when the item repeats. */
        val repeats: Boolean,
        val repeatLabel: String,
        /** True when the AI created it, so the UI can mark it. */
        val fromAi: Boolean,
        /** True for a location-based reminder. */
        val hasGeofence: Boolean,
        /** True when an alarm has a dismissal challenge. */
        val hasChallenge: Boolean,
        /** True when the item rings even though its type is not ALARM. */
        val ringsLoudly: Boolean,
        /** Suggestions the proactive assistant produced, rendered differently. */
        val isSuggestion: Boolean,
        val isDraft: Boolean,
        /** Whether a checkbox makes sense for this type. */
        val checkable: Boolean,
    )

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    fun urgencyOf(item: AssistantItem, nowMs: Long): Urgency = when {
        item.done -> Urgency.DONE
        item.triggerAtMs <= 0L -> Urgency.NONE
        item.triggerAtMs < nowMs -> Urgency.OVERDUE
        item.triggerAtMs - nowMs <= HOUR -> Urgency.IMMINENT
        else -> Urgency.SCHEDULED
    }

    /**
     * Short relative time. Empty when the item has no schedule.
     *
     * Rounds toward the nearest useful unit rather than showing "en 89 min": a user
     * scanning a list wants "en 1 h", and the exact minute is available in
     * [absoluteTime] for anyone who needs it.
     */
    fun relativeTime(triggerAtMs: Long, nowMs: Long): String {
        if (triggerAtMs <= 0L) return ""
        val delta = triggerAtMs - nowMs
        val past = delta < 0
        val magnitude = kotlin.math.abs(delta)
        val unit = when {
            magnitude < MINUTE -> "ahora"
            magnitude < HOUR -> "${magnitude / MINUTE} min"
            magnitude < DAY -> "${magnitude / HOUR} h"
            magnitude < 7 * DAY -> "${magnitude / DAY} d"
            else -> "${magnitude / (7 * DAY)} sem"
        }
        if (unit == "ahora") return "ahora"
        return if (past) "hace $unit" else "en $unit"
    }

    fun repeatLabel(repeat: String): String = when (repeat.lowercase()) {
        "daily" -> "Cada día"
        "weekly" -> "Cada semana"
        "monthly" -> "Cada mes"
        else -> ""
    }

    /**
     * Money with an explicit sign and no currency symbol.
     *
     * The store keeps expenses negative, but showing "-45" next to a red label is
     * redundant and reads badly, so the sign is expressed once — as the label and
     * colour — and the number itself is always positive.
     */
    fun amountLabel(amount: Double): String =
        if (amount == 0.0) "" else "%,.2f".format(kotlin.math.abs(amount))

    fun of(item: AssistantItem, nowMs: Long = System.currentTimeMillis()): CardData {
        val urgency = urgencyOf(item, nowMs)
        return CardData(
            title = item.title.trim().ifEmpty { "(sin título)" },
            body = item.body.trim(),
            urgency = urgency,
            relativeTime = if (urgency == Urgency.DONE) "" else relativeTime(item.triggerAtMs, nowMs),
            absoluteTime = if (item.triggerAtMs > 0L) {
                com.blackclaw.android.assistant.AssistantTime.format(item.triggerAtMs)
            } else "",
            amountLabel = amountLabel(item.amount),
            isIncome = item.amount > 0.0,
            repeats = repeatLabel(item.repeat).isNotEmpty(),
            repeatLabel = repeatLabel(item.repeat),
            fromAi = item.source.equals("ai", ignoreCase = true),
            hasGeofence = item.radiusM > 0 && (item.lat != 0.0 || item.lon != 0.0),
            hasChallenge = item.challenge.isNotBlank() &&
                !item.challenge.equals("none", ignoreCase = true),
            ringsLoudly = item.ring && item.type != AssistantItemType.ALARM,
            isSuggestion = item.category == "habit" || item.title.startsWith("💡"),
            isDraft = item.category == "draft",
            checkable = item.type == AssistantItemType.REMINDER ||
                item.type == AssistantItemType.NOTE ||
                item.type == AssistantItemType.SHOPPING,
        )
    }

    /** Accent name for a type, resolved by [com.blackclaw.android.ui.design.ClawPalette]. */
    fun accentName(type: AssistantItemType): String = when (type) {
        AssistantItemType.REMINDER -> "reminder"
        AssistantItemType.ALARM -> "alarm"
        AssistantItemType.NOTE -> "note"
        AssistantItemType.EVENT -> "event"
        AssistantItemType.ALERT -> "alert"
        AssistantItemType.FINANCE -> "finance"
        AssistantItemType.SHOPPING -> "shopping"
    }

    fun emoji(type: AssistantItemType): String = when (type) {
        AssistantItemType.REMINDER -> "🔔"
        AssistantItemType.ALARM -> "⏰"
        AssistantItemType.NOTE -> "📝"
        AssistantItemType.EVENT -> "📅"
        AssistantItemType.ALERT -> "📢"
        AssistantItemType.FINANCE -> "💰"
        AssistantItemType.SHOPPING -> "🛒"
    }

    fun label(type: AssistantItemType): String = when (type) {
        AssistantItemType.REMINDER -> "Recordatorios"
        AssistantItemType.ALARM -> "Alarmas"
        AssistantItemType.NOTE -> "Notas"
        AssistantItemType.EVENT -> "Calendario"
        AssistantItemType.ALERT -> "Avisos"
        AssistantItemType.FINANCE -> "Finanzas"
        AssistantItemType.SHOPPING -> "Compras"
    }
}
