package com.blackclaw.android.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.blackclaw.android.assistant.VoiceInputManager
import com.blackclaw.android.assistant.VoskModelManager
import com.blackclaw.android.assistant.WhisperMode
import com.blackclaw.android.base.BaseActivity

/**
 * Settings screen for hands-free voice mode: enable/disable + offline model
 * status. The offline model ships bundled in the APK and unpacks automatically;
 * this screen lets the user re-prepare it or see the current backend.
 */
class VoiceSettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { VoiceSettingsScreen(onBack = { finish() }, onEnable = { ensureMicPermission() }) }
    }

    private fun ensureMicPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 4202)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSettingsScreen(onBack: () -> Unit, onEnable: () -> Unit) {
    val bg = Color(0xFF0A0A0F)
    val surface = Color(0xFF141420)
    val accent = Color(0xFF00D4FF)
    val textPrimary = Color(0xFFC8D0E8)
    val textSecondary = Color(0xFF7A80A0)

    var enabled by remember { mutableStateOf(VoiceInputManager.wakeEnabled) }
    var whisper by remember { mutableStateOf(WhisperMode.enabled) }
    var modelReady by remember { mutableStateOf(VoskModelManager.isReady()) }
    var preparing by remember { mutableStateOf(VoskModelManager.preparing) }
    var progress by remember { mutableStateOf(0) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // Poll preparation progress.
    LaunchedEffect(preparing) {
        while (preparing) {
            kotlinx.coroutines.delay(500)
            modelReady = VoskModelManager.isReady()
            preparing = VoskModelManager.preparing
        }
        modelReady = VoskModelManager.isReady()
    }

    Scaffold(
        containerColor = bg,
        topBar = {
            TopAppBar(
                title = { Text("Modo voz", color = textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = surface),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Enable toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(surface, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Mic, null, tint = accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Activar modo voz", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        if (modelReady) "Di \"garra\" + tu orden (offline, sin beep)"
                        else "Di \"BlackClaw\" + tu orden (online)",
                        color = textSecondary, fontSize = 13.sp,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        VoiceInputManager.wakeEnabled = it
                        if (it) {
                            onEnable()
                            runCatching { com.blackclaw.android.service.VoiceWakeService.start(ctx) }
                        } else {
                            VoiceInputManager.stopWakeLoop()
                            runCatching { com.blackclaw.android.service.VoiceWakeService.stop(ctx) }
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = accent),
                )
            }

            // Offline model status
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(surface, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Reconocimiento offline", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val statusText = when {
                    modelReady -> "✓ Listo — funciona sin internet y sin beep (palabra: \"garra\")"
                    preparing -> "⏳ Preparando el modelo… $progress%"
                    else -> "El modelo offline no está listo. Mientras tanto se usa el reconocimiento del sistema (online)."
                }
                Text(statusText, color = textSecondary, fontSize = 13.sp)

                if (preparing) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = accent,
                    )
                } else if (!modelReady) {
                    Box(
                        modifier = Modifier
                            .background(accent.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                            .clickable {
                                preparing = true
                                VoskModelManager.download(
                                    onProgress = { progress = it },
                                    onDone = { preparing = false; modelReady = VoskModelManager.isReady() },
                                )
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text("Preparar modelo offline", color = accent, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Whisper mode toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(surface, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Modo susurro", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "Si le susurras, te responde susurrando (como Alexa). Requiere el modelo offline.",
                        color = textSecondary, fontSize = 13.sp,
                    )
                }
                Switch(
                    checked = whisper,
                    onCheckedChange = { whisper = it; WhisperMode.enabled = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = accent),
                )
            }

            // Help
            Text(
                "El modo voz escucha en segundo plano mientras BlackClaw está abierto. " +
                "Con el modelo offline no hace falta internet ni suena ningún pitido. " +
                "Ejemplos: \"garra, pon una alarma a las 7\", \"garra, manda un mensaje a mamá\".",
                color = textSecondary, fontSize = 12.sp,
            )
        }
    }
}
