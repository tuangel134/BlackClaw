package com.blackclaw.android.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.blackclaw.android.assistant.VoiceInputManager

/**
 * Quick Settings tile to toggle hands-free voice mode without opening the app.
 * Tap → starts/stops the always-listening VoiceWakeService.
 */
class VoiceTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val enabling = !VoiceInputManager.wakeEnabled
        if (enabling) {
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                // Can't request runtime permission from a tile — open the app.
                val i = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val pending = PendingIntent.getActivity(
                            this,
                            0,
                            i,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                        startActivityAndCollapse(pending)
                    } else {
                        @Suppress("DEPRECATION")
                        startActivityAndCollapse(i)
                    }
                }
                return
            }
            VoiceInputManager.wakeEnabled = true
            VoiceWakeService.start(this)
        } else {
            VoiceInputManager.wakeEnabled = false
            VoiceInputManager.stopWakeLoop()
            VoiceWakeService.stop(this)
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = VoiceInputManager.wakeEnabled
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Voz BlackClaw"
        tile.contentDescription = if (on) "Escuchando" else "Desactivado"
        tile.updateTile()
    }
}
