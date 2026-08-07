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
        /** Resume the wake loop after the assist panel closes. */
        const val ACTION_RESUME_WAKE = "com.blackclaw.android.RESUME_WAKE"
        @Volatile private var liveInstance: VoiceWakeService? = null

        fun decisionHandled(id: String) {
            liveInstance?.takeIf { it.pendingDecisionId == id }?.apply {
                pendingDecisionId = null
                pendingConfirm = null
            }
        }

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
    @Volatile private var pendingConfirm: String? = null   // destructive command awaiting "sí"
    @Volatile private var pendingConfirmWhisper = false
    @Volatile private var pendingDecisionId: String? = null

    private var telephonyManager: TelephonyManager? = null
    private var phoneListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        liveInstance = this
        startForegroundNotif()
        registerCallStateWatcher()
        VoiceInputManager.setStateListener { state -> updateNotif(state) }
        startListening()
        XLog.i(TAG, "VoiceWakeService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Resume the wake loop after the assist panel handed the mic back.
        if (intent?.action == ACTION_RESUME_WAKE) {
            runningTask = false
            runCatching { startListening() }
        }
        // Sticky so the OS restarts it if killed while voice mode is on.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (liveInstance === this) liveInstance = null
        runCatching { VoiceInputManager.setStateListener(null) }
        runCatching { VoiceInputManager.stopWakeLoop() }
        unregisterCallStateWatcher()
        XLog.i(TAG, "VoiceWakeService destroyed")
    }

    // ── Listening ──

    private fun startListening() {
        if (pausedForCall) return
        // Don't grab the mic if a call/communication audio mode is active.
        if (isCallActive()) { pausedForCall = true; return }
        // Battery guard: don't keep the mic + recognizer running when critically
        // low and unplugged (continuous listening is power-hungry).
        if (!batteryOk()) {
            updateNotif("paused_battery")
            return
        }
        VoiceInputManager.startWakeLoop(
            onCommand = { command, whisper -> onVoiceCommand(command, whisper) },
            onError = { XLog.d(TAG, "wake error: $it") },
        )
    }

    private fun batteryOk(): Boolean {
        return runCatching {
            val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            val charging = bm?.isCharging ?: true
            charging || level > 10
        }.getOrDefault(true)
    }

    private fun onVoiceCommand(command: String, whisper: Boolean) {
        // Resolve a pending destructive-confirmation first.
        val pending = pendingConfirm
        if (pending != null) {
            pendingConfirm = null
            pendingDecisionId?.let { id ->
                com.blackclaw.android.assistant.AssistantDecisionStore.discard(id)
                (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(id.hashCode())
            }
            pendingDecisionId = null
            val low = command.lowercase()
            val yes = listOf("sí", "si", "confirmo", "confirma", "hazlo", "dale", "adelante", "ok", "vale", "claro")
            val confirmed = yes.any { low.contains(it) }
            if (confirmed) {
                say("De acuerdo, jefe.", pendingConfirmWhisper)
                runCommand(pending, pendingConfirmWhisper)
            } else {
                say("Cancelado.", whisper)
            }
            return
        }

        if (runningTask) {
            say("Un momento, jefe.", whisper)
            return
        }

        // Destructive-action confirmation for hands-free safety.
        if (isDestructive(command)) {
            pendingConfirm = command
            pendingConfirmWhisper = whisper
            say("¿Seguro que quieres que haga eso, jefe? Dime sí para continuar.", whisper)
            pendingDecisionId = com.blackclaw.android.assistant.AssistantReceiver.postDecisionNotification(
                this,
                "BlackClaw necesita confirmación",
                command.take(180),
                command,
            )
            VoiceInputManager.armFollowUp(8000L)  // listen for the yes/no
            return
        }

        say(if (whisper) JarvisVoice.wakeAck() else JarvisVoice.commandAck(), whisper)
        // Visual panel: open the full-screen assist UI and let it run the command
        // (and continue the conversation). Hands the mic to the panel. Falls back
        // to the background flow if the panel can't be launched.
        if (VoiceInputManager.panelOnWake && !whisper && launchAssistPanel(command)) {
            runCatching { VoiceInputManager.stopWakeLoop() }
            runningTask = false
            updateNotif("idle")
            return
        }
        runCommand(command, whisper)
    }

    /** Launch the full-screen assist panel pre-loaded with [command]. */
    private fun launchAssistPanel(command: String): Boolean = runCatching {
        val i = Intent(this, com.blackclaw.android.ui.assist.QuickAssistActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(com.blackclaw.android.ui.assist.QuickAssistActivity.EXTRA_COMMAND, command)
        }
        startActivity(i)
        true
    }.getOrElse { XLog.w(TAG, "launchAssistPanel failed: ${it.message}"); false }

    private fun runCommand(command: String, whisper: Boolean) {
        runningTask = true
        updateNotif("processing")
        val taskId = "voice-" + UUID.randomUUID().toString().take(8)
        runCatching {
            appViewModel.startTask(command, taskId, autoReturnToChat = false,
                surface = com.blackclaw.android.conversation.ConversationRepository.Surface.VOICE) { event ->
                when (event) {
                    is TaskEvent.Completed -> { speakResult(event.answer, whisper); runningTask = false }
                    is TaskEvent.Failed -> {
                        say("No pude completarlo, jefe.", whisper)
                        runningTask = false; updateNotif("idle")
                    }
                    is TaskEvent.Cancelled, is TaskEvent.Blocked -> { runningTask = false; updateNotif("idle") }
                    else -> Unit
                }
            }
        }.onFailure {
            XLog.w(TAG, "startTask failed: ${it.message}")
            runningTask = false; updateNotif("idle")
        }
    }

    private fun say(text: String, whisper: Boolean) {
        if (whisper) Speaker.speakWhisper(text) else Speaker.speak(text)
    }

    /** Heuristic destructive-intent check for spoken commands (ES/EN). */
    private fun isDestructive(command: String): Boolean {
        val c = command.lowercase()
        val patterns = listOf(
            "borra todo", "borrar todo", "elimina todo", "eliminar todo",
            "formatea", "formatear", "restablece", "restablecer de fábrica", "restablecer de fabrica",
            "desinstala", "desinstalar", "envía dinero", "envia dinero", "transfiere", "transferir",
            "paga ", "pagar ", "confirma la compra", "borra todos", "elimina todos",
            "delete all", "factory reset", "uninstall", "send money", "transfer ",
        )
        return patterns.any { c.contains(it) }
    }

    private fun speakResult(text: String, whisper: Boolean) {
        val clean = text
            .replace(Regex("[*_#`>]+"), " ")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        updateNotif("speaking")
        val toSay = clean.take(500)
        if (toSay.isBlank()) { armFollowUpAndIdle(); return }
        // After speaking the result, open a follow-up window (continuous convo).
        Speaker.speak(toSay, whisper) {
            armFollowUpAndIdle()
        }
    }

    private fun armFollowUpAndIdle() {
        VoiceInputManager.armFollowUp(7000L)
        updateNotif("listening_followup")
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

    private var notifBuilder: NotificationCompat.Builder? = null

    private fun updateNotif(state: String) {
        val text = when (state) {
            "processing" -> "Procesando tu orden…"
            "speaking" -> "Respondiendo…"
            "heard" -> "Te escuché…"
            "listening_followup" -> "Escuchando (sigue hablando)…"
            "paused_battery" -> "Pausado (batería baja)"
            else -> "Di \"garra\" seguido de tu orden"
        }
        val b = notifBuilder ?: return
        b.setContentText(text)
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, b.build())
        }
        // Auto-return to idle text a bit after transient states.
        if (state == "heard") {
            android.os.Handler(mainLooper).postDelayed({ if (!runningTask) updateNotif("idle") }, 4000)
        }
    }

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
            .also { notifBuilder = it }
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
