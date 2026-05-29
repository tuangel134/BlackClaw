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

        fun start(context: Context) {
            val i = Intent(context, ScreenCapturePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(i)
        }
    }

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
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
            Toast.makeText(this, "Captura de pantalla rechazada", Toast.LENGTH_SHORT).show()
        }
        finish()
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
