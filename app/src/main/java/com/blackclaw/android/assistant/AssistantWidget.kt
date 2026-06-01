package com.blackclaw.android.assistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.blackclaw.android.R

/**
 * Home-screen widget showing the next upcoming assistant item (reminder / alarm
 * / event) and a count of what's pending. Tapping it opens the Assistant hub.
 *
 * Refreshed when items change (call [refresh]) and on the system's periodic
 * update interval.
 */
class AssistantWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> render(context, mgr, id) }
    }

    companion object {
        /** Re-render all widgets (call after the hub changes). */
        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                android.content.ComponentName(context, AssistantWidget::class.java))
            ids.forEach { render(context, mgr, it) }
        }

        private fun render(context: Context, mgr: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_assistant)

            val now = System.currentTimeMillis()
            val next = AssistantStore.all()
                .filter { it.triggerAtMs > now && !it.done }
                .minByOrNull { it.triggerAtMs }
            val pending = AssistantStore.countPending(AssistantItemType.REMINDER) +
                AssistantStore.countPending(AssistantItemType.ALARM) +
                AssistantStore.countPending(AssistantItemType.NOTE)

            if (next != null) {
                val emoji = when (next.type) {
                    AssistantItemType.ALARM -> "⏰"
                    AssistantItemType.EVENT -> "📅"
                    else -> "🔔"
                }
                views.setTextViewText(R.id.widget_next_title, "$emoji ${next.title}")
                views.setTextViewText(R.id.widget_next_time, AssistantTime.format(next.triggerAtMs))
            } else {
                views.setTextViewText(R.id.widget_next_title, "Sin pendientes próximos")
                views.setTextViewText(R.id.widget_next_time, "")
            }
            views.setTextViewText(R.id.widget_count,
                if (pending > 0) "$pending pendiente${if (pending == 1) "" else "s"}" else "Todo al día")

            val intent = Intent().setClassName(
                context.packageName, "com.blackclaw.android.ui.assistant.AssistantActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.widget_root, pi)

            mgr.updateAppWidget(id, views)
        }
    }
}
