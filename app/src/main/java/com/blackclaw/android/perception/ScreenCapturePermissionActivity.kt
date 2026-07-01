package com.blackclaw.android.perception

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.blackclaw.android.utils.XLog

/**
 * Tiny transparent activity whose only job is to fire the system MediaProjection
 * consent dialog and, on success, start [ScreenCaptureService] with the result.
 *
 * The agent calls into here whenever it wants screen capture but the service
 * isn't running yet. After the user accepts once, future captures are silent.
 */
class ScreenCapturePermissionActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ScreenCapturePerm"

        // Anti-spam guard: avoid stacking consent dialogs when the agent calls
        // OCR/screenshot tools repeatedly. Only one request in flight, and a
        // cooldown after the user declines so we don't nag.
        @Volatile private var requesting = false
        @Volatile private var lastDeclineMs = 0L
        private const val DECLINE_COOLDOWN_MS = 60_000L

        /** True if a consent dialog is currently showing or recently declined. */
        fun isBusyOrCoolingDown(): Boolean =
            requesting || (System.currentTimeMillis() - lastDeclineMs < DECLINE_COOLDOWN_MS)

        fun start(context: Context) {
            // Don't fire another dialog if one is pending or the user just said no,
            // or if capture is already running.
            if (requesting || ScreenCaptureService.isRunning()) return
            if (System.currentTimeMillis() - lastDeclineMs < DECLINE_COOLDOWN_MS) {
                XLog.i(TAG, "Skipping consent — recently declined")
                return
            }
            requesting = true
            val i = Intent(context, ScreenCapturePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(i)
        }
    }

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        requesting = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svc = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
            Toast.makeText(this, "Captura de pantalla activada", Toast.LENGTH_SHORT).show()
        } else {
            XLog.i(TAG, "User declined MediaProjection consent")
            lastDeclineMs = System.currentTimeMillis()
            Toast.makeText(this, "Captura de pantalla rechazada", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        requesting = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            launcher.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to launch projection intent", e)
            Toast.makeText(this, "Tu dispositivo no soporta captura de pantalla", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
