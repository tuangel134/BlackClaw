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
    private var lockDismiss = false

    @Deprecated("Back must not bypass a challenge alarm")
    override fun onBackPressed() {
        // If this is a challenge ("important") alarm, ignore Back so the user
        // can't escape without solving it. Normal alarms allow back = dismiss.
        if (lockDismiss) return
        super.onBackPressed()
    }

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
        val challengeKind = itemId?.let { AssistantStore.find(it)?.challenge } ?: "none"
        lockDismiss = challengeKind.isNotBlank() && challengeKind != "none"

        startRinging()

        setContent {
            AlarmRingScreen(
                title = title,
                challengeKind = challengeKind,
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
private fun AlarmRingScreen(
    title: String,
    challengeKind: String,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
) {
    val now = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
    val hasChallenge = challengeKind.isNotBlank() && challengeKind != "none"
    var solving by remember { mutableStateOf(false) }
    val challenge = remember { if (hasChallenge) AlarmChallenge.create(challengeKind) else null }
    var answer by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

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
            Spacer(Modifier.height(56.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (hasChallenge) "🔒⏰" else "⏰", fontSize = 64.sp)
                Spacer(Modifier.height(14.dp))
                Text(now, fontSize = 60.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text(title, fontSize = 20.sp, color = Color(0xFFB9A7E0),
                    fontWeight = FontWeight.Medium)
                if (hasChallenge && !solving) {
                    Spacer(Modifier.height(8.dp))
                    Text("Alarma importante · resuelve un reto para apagarla",
                        fontSize = 13.sp, color = Color(0xFF8B7BB0))
                }
            }

            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (hasChallenge && solving && challenge != null) {
                    // Challenge gate
                    Text(challenge.prompt, fontSize = 18.sp, color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { answer = it; error = false },
                        modifier = Modifier.fillMaxWidth(),
                        isError = error,
                        singleLine = true,
                        placeholder = { Text("Tu respuesta", color = Color(0xFF6B5B90)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF7C3AED),
                            unfocusedBorderColor = Color(0xFF3A2E55),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF7C3AED),
                            errorBorderColor = Color(0xFFEF4444),
                        ),
                    )
                    if (error) {
                        Spacer(Modifier.height(6.dp))
                        Text("Incorrecto, vuelve a intentarlo", fontSize = 13.sp, color = Color(0xFFEF4444))
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            if (challenge.check(answer)) onDismiss() else { error = true; answer = "" }
                        },
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = RoundedCornerShape(29.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF7C3AED), contentColor = Color.White),
                    ) { Text("Comprobar", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.height(40.dp))
                } else {
                    Button(
                        onClick = { if (hasChallenge) solving = true else onDismiss() },
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        shape = RoundedCornerShape(30.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF7C3AED), contentColor = Color.White),
                    ) {
                        Text(if (hasChallenge) "Resolver para apagar" else "Descartar",
                            fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
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
}
