package com.blackclaw.android.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.blackclaw.android.BuildConfig
import com.blackclaw.android.support.DebugReportManager
import com.blackclaw.android.utils.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shared helpers for both the legacy XML SettingsActivity and the new Compose
 * settings screen. Keeps bug-report / debug-report logic in one place.
 */
object SettingsActions {

    fun reportBug(activity: androidx.appcompat.app.AppCompatActivity) {
        buildSupportBundle(activity, "Preparando informe de fallo…") { report ->
            // Open GitHub issue page directly
            openGitHubIssue(activity, report)
        }
    }

    fun shareDebugReport(activity: androidx.appcompat.app.AppCompatActivity) {
        buildSupportBundle(activity, "Preparando informe de depuración…") { report ->
            shareReportFile(
                activity = activity,
                report = report,
                chooserTitle = "Compartir informe de depuración",
                subject = "BlackClaw debug report ${BuildConfig.VERSION_NAME}",
                body = "Adjunta este informe al reportar un fallo de BlackClaw.",
            )
        }
    }

    private fun buildSupportBundle(
        activity: androidx.appcompat.app.AppCompatActivity,
        preparingToast: String,
        onReady: (File) -> Unit,
    ) {
        activity.lifecycleScope.launch {
            Toast.makeText(activity, preparingToast, Toast.LENGTH_SHORT).show()
            runCatching {
                withContext(Dispatchers.IO) { DebugReportManager.buildReport(activity) }
            }.onSuccess { report ->
                onReady(report)
            }.onFailure { e ->
                XLog.e("SettingsActions", "Failed to build debug report", e)
                Toast.makeText(activity, "Error al preparar el informe", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openGitHubIssue(activity: Activity, report: File) {
        val uri = "https://github.com/tuangel134/BlackClaw/issues/new".toUri()
            .buildUpon()
            .appendQueryParameter("title", "[Bug] ${Build.MANUFACTURER} ${Build.MODEL} - ")
            .appendQueryParameter("body", buildIssueBody(report))
            .build()
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
            Toast.makeText(
                activity,
                "Adjunta ${report.name} a la issue después de abrir la página",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "No hay app para abrir GitHub", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildIssueBody(report: File): String = """
        ## ¿Qué ha pasado?
        -

        ## ¿Qué esperabas?
        -

        ## Pasos exactos para reproducirlo
        1.
        2.
        3.

        ## Dispositivo
        - Fabricante: ${Build.MANUFACTURER}
        - Modelo: ${Build.MODEL}
        - Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})

        ## Adjuntos
        - Adjunta este ZIP de BlackClaw: `${report.name}`

        Generado por BlackClaw ${BuildConfig.VERSION_NAME}.
    """.trimIndent()

    private fun shareReportFile(
        activity: Activity,
        report: File,
        chooserTitle: String,
        subject: String,
        body: String,
    ) {
        val uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", report
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(Intent.createChooser(intent, chooserTitle))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "No hay app para compartir", Toast.LENGTH_LONG).show()
        }
    }
}
