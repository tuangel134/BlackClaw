package com.blackclaw.android.assistant

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.blackclaw.android.utils.XLog

/**
 * Quick Settings tile that opens the Assistant hub straight from the
 * notification shade — one swipe + tap to reach reminders/alarms/notes.
 * The tile label shows how many reminders are pending.
 */
class AssistantTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val pending = runCatching {
            AssistantStore.countPending(AssistantItemType.REMINDER) +
                AssistantStore.countPending(AssistantItemType.ALARM)
        }.getOrDefault(0)
        tile.label = if (pending > 0) "Asistente ($pending)" else "Asistente"
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent().setClassName(
            packageName, "com.blackclaw.android.ui.assistant.AssistantActivity"
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(
                        this, 0, intent,
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            XLog.w("AssistantTile", "Failed to open hub: ${e.message}")
        }
    }
}
