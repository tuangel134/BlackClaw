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

            val upcoming = AssistantStore.upcoming(limit = 4)
            val pending = AssistantStore.countPending(AssistantItemType.REMINDER) +
                AssistantStore.countPending(AssistantItemType.ALARM) +
                AssistantStore.countPending(AssistantItemType.EVENT)

            views.setTextViewText(R.id.widget_count,
                if (pending > 0) "$pending" else "✓")

            val rows = listOf(
                Triple(R.id.widget_row_0, R.id.widget_time_0, R.id.widget_title_0),
                Triple(R.id.widget_row_1, R.id.widget_time_1, R.id.widget_title_1),
                Triple(R.id.widget_row_2, R.id.widget_time_2, R.id.widget_title_2),
                Triple(R.id.widget_row_3, R.id.widget_time_3, R.id.widget_title_3),
            )

            if (upcoming.isEmpty()) {
                views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE)
                rows.forEach { views.setViewVisibility(it.first, android.view.View.GONE) }
            } else {
                views.setViewVisibility(R.id.widget_empty, android.view.View.GONE)
                rows.forEachIndexed { i, (rowId, timeId, titleId) ->
                    val item = upcoming.getOrNull(i)
                    if (item == null) {
                        views.setViewVisibility(rowId, android.view.View.GONE)
                    } else {
                        views.setViewVisibility(rowId, android.view.View.VISIBLE)
                        val emoji = when {
                            item.ring -> "🔔"
                            item.type == AssistantItemType.ALARM -> "⏰"
                            item.type == AssistantItemType.EVENT -> "📅"
                            else -> "🔔"
                        }
                        views.setTextViewText(timeId, shortTime(item.triggerAtMs))
                        views.setTextViewText(titleId, "$emoji ${item.title}")
                    }
                }
            }

            // Tap → open the Calendar/agenda view.
            val intent = Intent().setClassName(
                context.packageName, "com.blackclaw.android.ui.assistant.CalendarActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.widget_root, pi)

            mgr.updateAppWidget(id, views)
        }

        /** Compact relative time for the widget: "hoy 19:00", "mañ 07:30", "12 jul 10:00". */
        private fun shortTime(ms: Long): String {
            val now = java.util.Calendar.getInstance()
            val t = java.util.Calendar.getInstance().apply { timeInMillis = ms }
            val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
            val sameYear = now.get(java.util.Calendar.YEAR) == t.get(java.util.Calendar.YEAR)
            val dDay = t.get(java.util.Calendar.DAY_OF_YEAR) - now.get(java.util.Calendar.DAY_OF_YEAR)
            return when {
                sameYear && dDay == 0 -> hm
                sameYear && dDay == 1 -> "mañ $hm"
                else -> java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault())
                    .format(java.util.Date(ms))
            }
        }
    }
}
