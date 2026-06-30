package com.blackclaw.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.blackclaw.android.R
import com.blackclaw.android.appViewModel
import com.blackclaw.android.TaskEvent
import com.blackclaw.android.agent.DefaultAgentService
import com.blackclaw.android.assistant.JarvisVoice
import com.blackclaw.android.assistant.Speaker
import com.blackclaw.android.assistant.VoiceInputManager
import com.blackclaw.android.utils.XLog
import java.util.UUID

/**
 * Always-listening voice wake service.
 *
 * Owns the wake-word loop as a foreground service so "garra, …" works even when
 * the app is closed or the screen is off. On wake + command it runs the command
 * as a task and speaks the result back.
 *
 * Microphone etiquette during phone calls: the recognizer holds the mic, which
 * would conflict with a call. We watch the telephony call state AND the audio
 * mode; when a call is ringing/active we release the mic (stop listening) and
 * resume automatically when the call ends.
 */
class VoiceWakeService : Service() {

    companion object {
        private const val TAG = "VoiceWakeService"
        private const val CHANNEL_ID = "voice_wake"
        private const val NOTIF_ID = 73010

        fun start(ctx: Context) {
            val i = Intent(ctx, VoiceWakeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, VoiceWakeService::class.java))
        }
    }

    @Volatile private var pausedForCall = false
    @Volatile private var runningTask = false

    private var telephonyManager: TelephonyManager? = null
    private var phoneListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotif()
        registerCallStateWatcher()
        startListening()
        XLog.i(TAG, "VoiceWakeService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sticky so the OS restarts it if killed while voice mode is on.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { VoiceInputManager.stopWakeLoop() }
        unregisterCallStateWatcher()
        XLog.i(TAG, "VoiceWakeService destroyed")
    }

    // ── Listening ──

    private fun startListening() {
        if (pausedForCall) return
        // Don't grab the mic if a call/communication audio mode is active.
        if (isCallActive()) { pausedForCall = true; return }
        VoiceInputManager.startWakeLoop(
            onCommand = { command, whisper -> onVoiceCommand(command, whisper) },
            onError = { XLog.d(TAG, "wake error: $it") },
        )
    }

    private fun onVoiceCommand(command: String, whisper: Boolean) {
        if (runningTask) {
            if (whisper) Speaker.speakWhisper("Un momento, jefe.") else Speaker.speak("Un momento, jefe, sigo con lo anterior.")
            return
        }
        if (whisper) Speaker.speakWhisper(JarvisVoice.wakeAck()) else Speaker.speak(JarvisVoice.commandAck())
        runningTask = true
        val taskId = "voice-" + UUID.randomUUID().toString().take(8)
        runCatching {
            appViewModel.startTask(command, taskId) { event ->
                when (event) {
                    is TaskEvent.Completed -> { speakResult(event.answer, whisper); runningTask = false }
                    is TaskEvent.Failed -> {
                        if (whisper) Speaker.speakWhisper("No pude completarlo.") else Speaker.speak("No pude completarlo, jefe.")
                        runningTask = false
                    }
                    is TaskEvent.Cancelled, is TaskEvent.Blocked -> { runningTask = false }
                    else -> Unit
                }
            }
        }.onFailure {
            XLog.w(TAG, "startTask failed: ${it.message}")
            runningTask = false
        }
    }

    private fun speakResult(text: String, whisper: Boolean) {
        val clean = text
            .replace(Regex("[*_#`>]+"), " ")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isNotBlank()) {
            if (whisper) Speaker.speakWhisper(clean.take(500)) else Speaker.speak(clean.take(500))
        }
    }

    // ── Phone-call mic etiquette ──

    private fun isCallActive(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val mode = am.mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    private fun onCallStateChanged(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (!pausedForCall) {
                    XLog.i(TAG, "Call active → releasing mic")
                    pausedForCall = true
                    runCatching { VoiceInputManager.stopWakeLoop() }
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (pausedForCall) {
                    XLog.i(TAG, "Call ended → resuming listening")
                    pausedForCall = false
                    // Small delay so the call audio fully releases first.
                    android.os.Handler(mainLooper).postDelayed({ startListening() }, 1500)
                }
            }
        }
    }

    private fun registerCallStateWatcher() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = this@VoiceWakeService.onCallStateChanged(state)
                }
                telephonyCallback = cb
                telephonyManager?.registerTelephonyCallback(mainExecutor, cb)
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) =
                        this@VoiceWakeService.onCallStateChanged(state)
                }
                phoneListener = listener
                @Suppress("DEPRECATION")
                telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Call state watcher registration failed: ${e.message}")
        }
    }

    private fun unregisterCallStateWatcher() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let { telephonyManager?.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                phoneListener?.let { telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        }
        telephonyCallback = null
        phoneListener = null
    }

    // ── Foreground notification ──

    private fun startForegroundNotif() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Modo voz", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Escucha la palabra de activación"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
        val tapIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = android.app.PendingIntent.getActivity(
            this, 0, tapIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("BlackClaw escuchando")
            .setContentText("Di \"garra\" seguido de tu orden")
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
