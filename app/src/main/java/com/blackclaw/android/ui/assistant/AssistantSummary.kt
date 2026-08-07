package com.blackclaw.android.ui.assistant

import com.blackclaw.android.assistant.AssistantItem
import com.blackclaw.android.assistant.AssistantItemType
import com.blackclaw.android.assistant.AssistantStore

/**
 * What the hero card says about a category.
 *
 * Extracted from the Composable so the wording rules are testable. The old version
 * built these strings inline inside the layout, which meant the several special cases
 * (finance shows a balance, shopping counts pending, timed types show the next
 * trigger) could only be checked by looking at the screen.
 */
object AssistantSummary {

    /**
     * @param progress 0..1 completion, or a negative value when completion is not a
     *   meaningful notion for the category (finance has no "done").
     */
    data class Summary(
        val headline: String,
        val subtitle: String,
        val progress: Float,
    )

    /** Hidden progress bar. */
    const val NO_PROGRESS = -1f

    fun of(
        type: AssistantItemType,
        items: List<AssistantItem>,
        nowMs: Long = System.currentTimeMillis(),
    ): Summary = when (type) {
        AssistantItemType.FINANCE -> financeSummary()
        AssistantItemType.SHOPPING -> shoppingSummary(items)
        else -> timedSummary(items, nowMs)
    }

    /**
     * Counting-based categories.
     *
     * Overdue is surfaced first when present: it is the only state in this screen
     * that means the user already missed something, so burying it under a generic
     * "3 pendientes" would hide the one fact worth acting on.
     */
    internal fun timedSummary(items: List<AssistantItem>, nowMs: Long): Summary {
        val pending = items.filter { !it.done }
        val overdue = pending.count { it.triggerAtMs in 1 until nowMs }
        val total = items.size
        val done = total - pending.size
        val progress = if (total == 0) NO_PROGRESS else done.toFloat() / total.toFloat()

        val headline = when {
            total == 0 -> "Nada aún"
            pending.isEmpty() -> "Todo al día"
            else -> "${pending.size} pendiente${plural(pending.size)}"
        }

        val next = pending
            .filter { it.triggerAtMs > nowMs }
            .minByOrNull { it.triggerAtMs }

        val subtitle = when {
            overdue > 0 -> "$overdue vencido${plural(overdue)} · requiere atención"
            next != null -> {
                val rel = AssistantCardModel.relativeTime(next.triggerAtMs, nowMs)
                "Próximo $rel · ${next.title.take(40)}"
            }
            pending.isNotEmpty() -> "Sin hora asignada"
            total > 0 -> "$done completado${plural(done)}"
            else -> "Pídeselo a la IA o toca +"
        }
        return Summary(headline, subtitle, progress)
    }

    internal fun shoppingSummary(items: List<AssistantItem>): Summary {
        val pending = items.count { !it.done }
        val total = items.size
        val progress = if (total == 0) NO_PROGRESS else (total - pending).toFloat() / total.toFloat()
        return Summary(
            headline = if (pending == 0 && total > 0) "Lista completa"
                else if (total == 0) "Lista vacía"
                else "$pending por comprar",
            subtitle = if (total == 0) "Añade lo que necesites"
                else "${total - pending} de $total en el carrito",
            progress = progress,
        )
    }

    /**
     * Finance reads from the store rather than the item list because balance and
     * budget are aggregates the store already maintains, and recomputing them here
     * would risk the hero disagreeing with the rest of the app.
     */
    internal fun financeSummary(): Summary {
        val balance = runCatching { AssistantStore.financeBalance() }.getOrDefault(0.0)
        val budget = runCatching { AssistantStore.monthlyBudget }.getOrDefault(0.0)
        val spent = runCatching { AssistantStore.monthExpenses() }.getOrDefault(0.0)

        val headline = formatMoney(balance)
        return if (budget > 0.0) {
            val used = (spent / budget).toFloat().coerceIn(0f, 1f)
            Summary(
                headline = headline,
                subtitle = "Mes: ${formatMoney(spent)} de ${formatMoney(budget)}" +
                    if (spent > budget) " · presupuesto excedido" else "",
                // Here the bar means "budget consumed", so a full bar is bad news, not
                // an achievement. The subtitle says so explicitly for that reason.
                progress = used,
            )
        } else {
            Summary(
                headline = headline,
                subtitle = "Gastado este mes: ${formatMoney(spent)} · sin presupuesto fijado",
                progress = NO_PROGRESS,
            )
        }
    }

    private fun plural(n: Int): String = if (n == 1) "" else "s"

    /** Two decimals, grouped, no currency symbol (the app is locale-agnostic here). */
    internal fun formatMoney(value: Double): String = "%,.2f".format(value)
}
