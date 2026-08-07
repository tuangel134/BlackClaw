package com.blackclaw.android.emergency

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.CancellationSignal
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import com.blackclaw.android.R
import com.blackclaw.android.ui.settings.EmergencySettingsActivity
import com.blackclaw.android.utils.XLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class EmergencyService : Service() {
    companion object {
        const val ACTION_START = "com.blackclaw.android.EMERGENCY_START"
        const val ACTION_CANCEL = "com.blackclaw.android.EMERGENCY_CANCEL"
        const val ACTION_STOP = "com.blackclaw.android.EMERGENCY_STOP"
        const val CHANNEL_ID = "blackclaw_emergency"
        const val DISCREET_CHANNEL_ID = "blackclaw_protection"
        const val NOTIFICATION_ID = 9110
        private const val TAG = "EmergencyService"
        private const val COUNTDOWN_MS = 5_000L
        private const val SEGMENT_MS = 30_000L
        private const val LOCATION_INTERVAL_MS = 5 * 60_000L
        private const val EXTRA_MODE = "emergency_mode"
        private const val EXTRA_CAMERAS = "emergency_cameras"
        private const val EXTRA_LOCATION = "emergency_location"
        private const val EXTRA_RECIPIENT = "emergency_recipient"
        @Volatile var isActive = false
            private set
        @Volatile var activeMode: EmergencyMode? = null
            private set
        @Volatile var activeCameras: EmergencyCameras = EmergencyCameras.NONE
            private set

        fun start(context: Context, options: EmergencyStartOptions = EmergencyStartOptions(
            EmergencyMode.EMERGENCY, EmergencyCameras.BACK, true,
        )): Boolean {
            if (!EmergencyConfig.isReady) return false
            if (!hasRequiredPermissions(context, options)) {
                EmergencyEventLog.append(context, "start_rejected_missing_permissions mode=${options.mode} cameras=${options.cameras}")
                return false
            }
            val intent = Intent(context, EmergencyService::class.java).setAction(ACTION_START).apply {
                putExtra(EXTRA_MODE, options.mode.name)
                putExtra(EXTRA_CAMERAS, options.cameras.name)
                putExtra(EXTRA_LOCATION, options.sendLocation)
                putExtra(EXTRA_RECIPIENT, options.recipient)
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrDefault(false)
        }

        fun hasRequiredPermissions(context: Context, options: EmergencyStartOptions): Boolean {
            fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            if (options.cameras != EmergencyCameras.NONE && !granted(Manifest.permission.CAMERA)) return false
            if (EmergencyConfig.recordAudio && !granted(Manifest.permission.RECORD_AUDIO)) return false
            if (options.sendLocation && !granted(Manifest.permission.SEND_SMS)) return false
            if (options.sendLocation && !granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
                !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return false
            return true
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, EmergencyService::class.java).setAction(ACTION_STOP)) }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var evidenceFile: File? = null
    private var cameraController: EmergencyCameraController? = null
    private var alertSent = false
    private var options = EmergencyStartOptions(EmergencyMode.EMERGENCY, EmergencyCameras.BACK, true)
    private var recipient = ""
    private val activateRunnable = Runnable { activateNow() }
    private val rotateRunnable = Runnable { rotateAudioSegment() }
    private val locationRunnable = object : Runnable {
        override fun run() {
            if (!isActive || !options.sendLocation) return
            sendLocationUpdate()
            handler.postDelayed(this, LOCATION_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val recovered = runCatching { EmergencyEvidenceVault.recoverPending(this) }.getOrDefault(0)
        if (recovered > 0) EmergencyEventLog.append(this, "evidence_recovered segments=$recovered")
        EmergencyEvidenceUploader.retryQueue(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelBeforeActivation()
            ACTION_STOP -> stopEmergency("stopped_by_user")
            else -> {
                options = EmergencyStartOptions(
                    mode = runCatching { EmergencyMode.valueOf(intent?.getStringExtra(EXTRA_MODE).orEmpty()) }
                        .getOrDefault(EmergencyMode.EMERGENCY),
                    cameras = runCatching { EmergencyCameras.valueOf(intent?.getStringExtra(EXTRA_CAMERAS).orEmpty()) }
                        .getOrDefault(EmergencyCameras.BACK),
                    sendLocation = intent?.getBooleanExtra(EXTRA_LOCATION, true) ?: true,
                    recipient = intent?.getStringExtra(EXTRA_RECIPIENT).orEmpty(),
                )
                recipient = options.recipient.filter { it.isDigit() || it == '+' }.take(20)
                    .ifBlank { EmergencyConfig.phone }
                beginCountdown()
            }
        }
        return START_NOT_STICKY
    }

    private fun beginCountdown() {
        if (isActive) return
        if (!EmergencyConfig.isReady) {
            EmergencyEventLog.append(this, "start_rejected_not_configured")
            stopSelf()
            return
        }
        if (options.silent) {
            promote(discreetStatus(), false, false, false)
            EmergencyEventLog.append(this, "discreet_start cameras=${options.cameras} location=${options.sendLocation}")
            activateNow()
            return
        }
        promote("Activando emergencia en 5 segundos…", true, false, false)
        EmergencyEventLog.append(this, "countdown_started")
        handler.removeCallbacks(activateRunnable)
        handler.postDelayed(activateRunnable, COUNTDOWN_MS)
    }

    private fun cancelBeforeActivation() {
        if (alertSent) {
            stopEmergency("stopped_after_alert")
            return
        }
        handler.removeCallbacks(activateRunnable)
        EmergencyEventLog.append(this, "cancelled_during_countdown")
        isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun activateNow() {
        if (alertSent) return
        isActive = true
        activeMode = options.mode
        activeCameras = options.cameras
        val location = if (options.sendLocation) lastKnownLocation() else null
        alertSent = if (options.sendLocation) sendAlert(location, initial = true) else true
        // Promote with every requested sensor type before opening while-in-use
        // resources; Android 14+ validates this synchronously.
        promote(if (options.silent) discreetStatus() else "Alerta activa · preparando evidencia", false,
            EmergencyConfig.recordAudio, options.cameras != EmergencyCameras.NONE)
        startCameraEvidence()
        if (EmergencyConfig.recordAudio) startAudioEvidence()
        if (EmergencyConfig.recordAudio || options.cameras != EmergencyCameras.NONE) {
            handler.removeCallbacks(rotateRunnable)
            handler.postDelayed(rotateRunnable, SEGMENT_MS)
        }
        if (options.sendLocation) {
            handler.removeCallbacks(locationRunnable)
            handler.postDelayed(locationRunnable, LOCATION_INTERVAL_MS)
        }
        val status = when {
            alertSent && recorder != null -> "Alerta enviada · grabando audio y ubicación"
            alertSent -> "Alerta enviada · grabación no disponible"
            else -> "No se pudo enviar SMS · evidencia local activa"
        }
        promote(if (options.silent) discreetStatus() else status, false, recorder != null, options.cameras != EmergencyCameras.NONE)
        EmergencyEventLog.append(this, "activated mode=${options.mode} cameras=${options.cameras} alert_sent=$alertSent audio=${recorder != null} location=${location != null}")
    }

    private fun startCameraEvidence() {
        if (options.cameras == EmergencyCameras.NONE) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            EmergencyEventLog.append(this, "camera_failed_missing_permission")
            return
        }
        runCatching {
            val controller = EmergencyCameraController(
                context = this,
                onSegmentReady = { file, lens -> sealVideoSegment(file, lens) },
                onEvent = { event -> EmergencyEventLog.append(this, event) },
            )
            cameraController = controller
            // Torch is what makes night footage usable, but it announces the
            // recording — so never in discreet mode.
            val useTorch = EmergencyConfig.lowLightTorch && !options.silent
            val actual = controller.start(options.cameras, useTorch)
            activeCameras = actual
            EmergencyEventLog.append(this, "camera_selection requested=${options.cameras} actual=$actual torch=$useTorch")
        }.onFailure {
            XLog.e(TAG, "Emergency camera initialization failed", it)
            EmergencyEventLog.append(this, "camera_failed ${it.javaClass.simpleName}")
            cameraController = null
        }
    }

    private fun sealVideoSegment(file: File, lens: String) {
        Thread({
            runCatching { EmergencyEvidenceVault.seal(this, file) }
                .onSuccess { sealed ->
                    val queue = EmergencyEvidenceVault.queueStatus(this)
                    EmergencyEventLog.append(this, "video_segment encrypted=${sealed.name} lens=$lens queue=${queue.segments}")
                    EmergencyEvidenceUploader.enqueue(this, sealed)
                }
                .onFailure { error ->
                    XLog.e(TAG, "Unable to encrypt emergency video", error)
                    EmergencyEventLog.append(this, "video_encryption_failed lens=$lens error=${error.javaClass.simpleName}")
                }
        }, "BlackClawEvidenceSeal").start()
    }

    private fun sendAlert(location: Location?, initial: Boolean): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            EmergencyEventLog.append(this, "sms_failed_missing_permission")
            return false
        }
        return try {
            val text = if (initial) EmergencyConfig.buildAlert(location?.latitude, location?.longitude)
                else EmergencyConfig.buildLocationUpdate(location?.latitude, location?.longitude)
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            val parts = sms.divideMessage(text)
            sms.sendMultipartTextMessage(recipient, null, parts, null, null)
            EmergencyEventLog.append(this, "sms_queued initial=$initial parts=${parts.size}")
            true
        } catch (e: Exception) {
            XLog.e(TAG, "Emergency SMS failed", e)
            EmergencyEventLog.append(this, "sms_failed ${e.javaClass.simpleName}")
            false
        }
    }

    private fun sendLocationUpdate() {
        requestFreshLocation { location ->
            if (!isActive) return@requestFreshLocation
            val sent = sendAlert(location, initial = false)
            EmergencyEventLog.append(this, "location_update sent=$sent available=${location != null} age_ms=${location?.let { System.currentTimeMillis() - it.time } ?: -1}")
        }
    }

    @Suppress("MissingPermission")
    private fun requestFreshLocation(onResult: (Location?) -> Unit) {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { onResult(null); return }
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) { onResult(lastKnownLocation()); return }
        val provider = when {
            fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> lm.getProviders(true).firstOrNull()
        }
        if (provider == null) { onResult(lastKnownLocation()); return }
        val delivered = AtomicBoolean(false)
        val finish: (Location?) -> Unit = { location ->
            if (delivered.compareAndSet(false, true)) onResult(location ?: lastKnownLocation())
        }
        handler.postDelayed({ finish(null) }, 15_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                lm.getCurrentLocation(provider, CancellationSignal(), mainExecutor) { finish(it) }
            }.onFailure { finish(null) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                lm.requestSingleUpdate(provider, object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) = finish(location)
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                    @Deprecated("Deprecated in Android")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }, Looper.getMainLooper())
            }.onFailure { finish(null) }
        }
    }

    @Suppress("MissingPermission")
    private fun lastKnownLocation(): Location? {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return lm.getProviders(true).mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull(Location::getTime)
    }

    private fun startAudioEvidence() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            EmergencyEventLog.append(this, "audio_failed_missing_permission")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        evidenceFile = EmergencyEvidenceVault.newPlainSegment(this, stamp)
        try {
            promote(if (options.silent) discreetStatus() else "Alerta activa · iniciando evidencia", false, true,
                options.cameras != EmergencyCameras.NONE)
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(evidenceFile!!.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Emergency audio failed", e)
            runCatching { recorder?.release() }
            recorder = null
            evidenceFile?.delete()
            evidenceFile = null
            promote(if (options.silent) discreetStatus() else "Alerta activa · evidencia no disponible", false, false,
                options.cameras != EmergencyCameras.NONE)
            EmergencyEventLog.append(this, "audio_failed ${e.javaClass.simpleName}")
        }
    }

    private fun rotateAudioSegment() {
        if (!isActive) return
        if (recorder != null) finishCurrentSegment("segment_rotated")
        cameraController?.rotate()
        if (isActive && EmergencyConfig.recordAudio) startAudioEvidence()
        if (isActive && (EmergencyConfig.recordAudio || options.cameras != EmergencyCameras.NONE)) {
            handler.removeCallbacks(rotateRunnable)
            handler.postDelayed(rotateRunnable, SEGMENT_MS)
        }
    }

    private fun finishCurrentSegment(event: String) {
        handler.removeCallbacks(rotateRunnable)
        val currentRecorder = recorder
        val currentFile = evidenceFile
        recorder = null
        evidenceFile = null
        val stopped = runCatching { currentRecorder?.stop(); true }.getOrDefault(false)
        runCatching { currentRecorder?.release() }
        if (!stopped || currentFile == null || !currentFile.isFile || currentFile.length() == 0L) {
            runCatching { currentFile?.delete() }
            EmergencyEventLog.append(this, "$event empty_or_invalid")
            return
        }
        runCatching { EmergencyEvidenceVault.seal(this, currentFile) }
            .onSuccess { sealed ->
                val queue = EmergencyEvidenceVault.queueStatus(this)
                EmergencyEventLog.append(this, "$event encrypted=${sealed.name} queue=${queue.segments}")
                EmergencyEvidenceUploader.enqueue(this, sealed)
            }
            .onFailure { error ->
                XLog.e(TAG, "Unable to encrypt emergency segment", error)
                EmergencyEventLog.append(this, "$event encryption_failed=${error.javaClass.simpleName}")
            }
    }

    private fun promote(text: String, cancellable: Boolean, microphone: Boolean, camera: Boolean) {
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            (if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
            (if (camera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0) or
            (if (options.sendLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text, cancellable), type)
    }

    private fun stopEmergency(reason: String) {
        handler.removeCallbacks(activateRunnable)
        handler.removeCallbacks(rotateRunnable)
        handler.removeCallbacks(locationRunnable)
        finishCurrentSegment(reason)
        cameraController?.close()
        cameraController = null
        val queue = EmergencyEvidenceVault.queueStatus(this)
        EmergencyEventLog.append(this, "$reason encrypted_queue=${queue.segments} bytes=${queue.bytes}")
        isActive = false
        activeMode = null
        activeCameras = EmergencyCameras.NONE
        alertSent = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String, cancellable: Boolean): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, EmergencySettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val action = if (cancellable) ACTION_CANCEL else ACTION_STOP
        val label = if (cancellable) "CANCELAR" else "DETENER"
        val stop = PendingIntent.getBroadcast(
            this, 2, Intent(this, EmergencyReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val discreet = options.silent
        return NotificationCompat.Builder(this, if (discreet) DISCREET_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (discreet) "BlackClaw · Protección activa" else "BlackClaw · Modo emergencia")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(!cancellable)
            .setCategory(if (discreet) NotificationCompat.CATEGORY_SERVICE else NotificationCompat.CATEGORY_ALARM)
            .setPriority(if (discreet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MAX)
            .setSilent(discreet)
            .setOnlyAlertOnce(true)
            .addAction(0, label, stop)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Modo emergencia", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Estado, cancelación y evidencia del modo emergencia"
            setSound(null, null)
        })
        manager.createNotificationChannel(NotificationChannel(DISCREET_CHANNEL_ID, "Protección activa", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Estado silencioso de protección de BlackClaw"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
    }

    override fun onDestroy() {
        handler.removeCallbacks(activateRunnable)
        handler.removeCallbacks(rotateRunnable)
        handler.removeCallbacks(locationRunnable)
        if (recorder != null) finishCurrentSegment("service_destroyed")
        cameraController?.close()
        cameraController = null
        isActive = false
        activeMode = null
        activeCameras = EmergencyCameras.NONE
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun discreetStatus() = "Modo discreto activo · toca para administrar"
}
