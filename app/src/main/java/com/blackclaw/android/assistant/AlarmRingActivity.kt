package com.blackclaw.android.assistant

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.utils.XLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen ringing alarm. Wakes the screen (even over the lock screen),
 * plays the alarm ringtone on loop, vibrates, and shows Dismiss / Snooze.
 *
 * This is what makes an "alarm" feel like a real clock alarm instead of a
 * passive notification.
 */
class AlarmRingActivity : BaseActivity() {

    companion object {
        const val EXTRA_TITLE = "alarm_title"
        const val EXTRA_ITEM_ID = "alarm_item_id"
        private const val TAG = "AlarmRingActivity"
        private const val SNOOZE_MIN = 5
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show over lock screen + turn the screen on.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
                ?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Alarma"
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID)

        startRinging()

        setContent {
            AlarmRingScreen(
                title = title,
                onDismiss = { stopAndFinish() },
                onSnooze = {
                    snooze(itemId, title)
                    stopAndFinish()
                },
            )
        }
    }

    private fun startRinging() {
        // Sound — use the user's alarm ringtone, fall back to notification.
        runCatching {
            val uri: Uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            player = MediaPlayer().apply {
                setDataSource(this@AlarmRingActivity, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { XLog.w(TAG, "Alarm sound failed: ${it.message}") }

        // Vibration — repeating pattern until dismissed.
        runCatching {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 800, 600)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
            }
        }
    }

    private fun snooze(itemId: String?, title: String) {
        val triggerAt = System.currentTimeMillis() + SNOOZE_MIN * 60_000L
        // Reuse the original item if present, else create a transient one.
        val base = itemId?.let { AssistantStore.find(it) }
        val snoozed = (base ?: AssistantStore.create(
            type = AssistantItemType.ALARM, title = title, triggerAtMs = triggerAt, source = "snooze",
        )).copy(triggerAtMs = triggerAt)
        AssistantStore.upsert(snoozed)
        AssistantScheduler.arm(this, snoozed)
        XLog.i(TAG, "Snoozed '$title' ${SNOOZE_MIN}m")
    }

    private fun stopAndFinish() {
        runCatching { player?.stop(); player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAndFinish()
    }
}

@Composable
private fun AlarmRingScreen(title: String, onDismiss: () -> Unit, onSnooze: () -> Unit) {
    val now = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0B0B12), Color(0xFF1A0E2E), Color(0xFF0B0B12)))
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Spacer(Modifier.height(60.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⏰", fontSize = 72.sp)
                Spacer(Modifier.height(16.dp))
                Text(now, fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text(title, fontSize = 20.sp, color = Color(0xFFB9A7E0),
                    fontWeight = FontWeight.Medium)
            }

            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    shape = RoundedCornerShape(30.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF7C3AED), contentColor = Color.White),
                ) { Text("Descartar", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(27.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF7C3AED)),
                ) { Text("Posponer 5 min", fontSize = 16.sp, color = Color(0xFFB9A7E0)) }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}
