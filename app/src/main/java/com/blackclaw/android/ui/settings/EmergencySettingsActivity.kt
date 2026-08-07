package com.blackclaw.android.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.emergency.EmergencyConfig
import com.blackclaw.android.emergency.EmergencyEvidenceVault
import com.blackclaw.android.emergency.EmergencyCameraController
import com.blackclaw.android.emergency.EmergencyCameras
import com.blackclaw.android.emergency.EmergencyMode
import com.blackclaw.android.emergency.EmergencyStartOptions
import com.blackclaw.android.emergency.EmergencyService

@OptIn(ExperimentalMaterial3Api::class)
class EmergencySettingsActivity : BaseActivity() {
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var name by remember { mutableStateOf(EmergencyConfig.contactName) }
            var phone by remember { mutableStateOf(EmergencyConfig.phone) }
            var message by remember { mutableStateOf(EmergencyConfig.message) }
            var audio by remember { mutableStateOf(EmergencyConfig.recordAudio) }
            var torch by remember { mutableStateOf(EmergencyConfig.lowLightTorch) }
            var saved by remember { mutableStateOf(false) }
            var active by remember { mutableStateOf(EmergencyService.isActive) }
            var evidenceItems by remember { mutableStateOf(EmergencyEvidenceVault.listEvidence(this@EmergencySettingsActivity)) }
            val evidenceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                evidenceItems = EmergencyEvidenceVault.listEvidence(this@EmergencySettingsActivity)
            }
            val cameraCapability = remember { runCatching { EmergencyCameraController.inspect(this@EmergencySettingsActivity) }.getOrNull() }
            var cameraChoice by remember { mutableStateOf(EmergencyCameras.BACK) }
            val formReady = phone.filter(Char::isDigit).length >= 7 && message.isNotBlank()

            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF9D7CFF),
                secondary = Color(0xFF39C8E8),
                background = Color(0xFF07050C),
                surface = Color(0xFF181222),
                surfaceVariant = Color(0xFF241B33),
                onPrimary = Color.White,
                onBackground = Color(0xFFF4EEFF),
                onSurface = Color(0xFFF4EEFF),
            )) {
                Scaffold(
                    containerColor = Color(0xFF07050C),
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text("Protección personal", fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("Emergencia y modo discreto", style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF998DAD))
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = Color.White)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF100B18)),
                        )
                    },
                    bottomBar = {
                        Surface(
                            color = Color(0xFA100B18),
                            shadowElevation = 18.dp,
                        ) {
                            Column(
                                Modifier.fillMaxWidth().navigationBarsPadding().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (!formReady && !active) {
                                    Text(
                                        "Completa y guarda el contacto para habilitar la activación.",
                                        color = Color(0xFFFFC46B),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                if (active) {
                                    Button(
                                        onClick = {
                                            EmergencyService.stop(this@EmergencySettingsActivity)
                                            active = false
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB62F45)),
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                    ) { Text("DETENER PROTECCIÓN") }
                                } else {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Button(
                                            onClick = {
                                                active = EmergencyService.start(
                                                    this@EmergencySettingsActivity,
                                                    EmergencyStartOptions(EmergencyMode.EMERGENCY, cameraChoice, true),
                                                )
                                            },
                                            enabled = formReady,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF7C4DDB),
                                                disabledContainerColor = Color(0xFF2D2638),
                                            ),
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(16.dp),
                                        ) { Text("EMERGENCIA") }
                                        OutlinedButton(
                                            onClick = {
                                                active = EmergencyService.start(
                                                    this@EmergencySettingsActivity,
                                                    EmergencyStartOptions(EmergencyMode.DISCREET, cameraChoice, true),
                                                )
                                            },
                                            enabled = formReady,
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(16.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCCBAFF)),
                                        ) { Text("MODO DISCRETO") }
                                    }
                                }
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).background(
                        Brush.verticalGradient(listOf(Color(0xFF17102A), Color(0xFF07050C), Color(0xFF050409))))) {
                      Column(
                          Modifier.fillMaxSize().padding(horizontal = 18.dp).padding(top = 16.dp)
                              .verticalScroll(rememberScrollState()),
                          verticalArrangement = Arrangement.spacedBy(14.dp),
                      ) {
                        ElevatedCard(
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.elevatedCardColors(containerColor = Color(0xD9211930))) {
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Outlined.Warning, null, tint = Color(0xFFFFB45C))
                                Text(
                                    "Emergencia ofrece 5 segundos para cancelar y después actualiza tu ubicación cada 5 minutos. Discreto inicia sin voz, sonido ni vibración, con una notificación neutra y los indicadores de Android.",
                                    color = Color(0xFFE5DDEE),
                                )
                            }
                        }
                        Text("CONTACTO Y MENSAJE", color = Color(0xFF9D7CFF),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        OutlinedTextField(name, { name = it; saved = false }, label = { Text("Nombre del contacto de confianza") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(phone, { phone = it; saved = false }, label = { Text("Teléfono con código de país") }, supportingText = { Text("Ejemplo: +52 614 000 0000") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(message, { message = it; saved = false }, label = { Text("Mensaje de emergencia") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("Audio de evidencia", color = Color(0xFFF4EEFF))
                                Text("Segmentos protegidos de 30 segundos.", color = Color(0xFFA89DB9), style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(audio, { audio = it; saved = false })
                        }
                        Text("CÁMARAS", color = Color(0xFF9D7CFF),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = cameraChoice == EmergencyCameras.FRONT,
                                onClick = { cameraChoice = EmergencyCameras.FRONT },
                                label = { Text("Frontal") },
                                enabled = cameraCapability?.frontId != null,
                            )
                            FilterChip(
                                selected = cameraChoice == EmergencyCameras.BACK,
                                onClick = { cameraChoice = EmergencyCameras.BACK },
                                label = { Text("Trasera") },
                                enabled = cameraCapability?.backId != null,
                            )
                            FilterChip(
                                selected = cameraChoice == EmergencyCameras.BOTH,
                                onClick = { cameraChoice = EmergencyCameras.BOTH },
                                label = { Text("Ambas") },
                                enabled = cameraCapability?.concurrentFrontBack == true,
                            )
                        }
                        Text(
                            when {
                                cameraCapability == null -> "No pude consultar la capacidad de cámaras."
                                cameraCapability.concurrentFrontBack -> "Este teléfono declara soporte oficial para cámara frontal y trasera simultáneas."
                                else -> "Android no declara cámaras simultáneas; si pides ambas, BlackClaw usará la trasera y lo dejará registrado."
                            },
                            color = Color(0xFFA89DB9), style = MaterialTheme.typography.bodySmall,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("Linterna en poca luz", color = Color(0xFFF4EEFF))
                                Text(
                                    "Enciende el flash trasero al grabar. Sin esto el video de noche sale negro, pero la luz es visible para quien esté cerca. No se usa en modo discreto.",
                                    color = Color(0xFFA89DB9), style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(torch, { torch = it; saved = false })
                        }
                        Button(
                            onClick = {
                                EmergencyConfig.contactName = name
                                EmergencyConfig.phone = phone
                                EmergencyConfig.message = message
                                EmergencyConfig.recordAudio = audio
                                EmergencyConfig.lowLightTorch = torch
                                saved = true
                                requestEmergencyPermissions(audio, cameraChoice != EmergencyCameras.NONE)
                            },
                            enabled = formReady,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        ) { Text(if (saved) "Configuración guardada" else "GUARDAR Y PREPARAR") }

                        ElevatedCard(shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.elevatedCardColors(containerColor = Color(0xD91A1424))) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("Archivos protegidos", color = Color(0xFFF4EEFF), fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (evidenceItems.isEmpty()) "Todavía no hay segmentos guardados."
                                    else "${evidenceItems.size} segmentos protegidos · ${formatEvidenceBytes(evidenceItems.sumOf { it.bytes })}.",
                                    color = Color(0xFFA89DB9), style = MaterialTheme.typography.bodySmall,
                                )
                                Text("Los archivos completos nunca se adjuntan al contexto de la IA.",
                                    color = Color(0xFFA89DB9), style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(
                                    onClick = {
                                        evidenceLauncher.launch(Intent(
                                            this@EmergencySettingsActivity,
                                            EmergencyEvidenceActivity::class.java,
                                        ))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                ) { Text("VER EVIDENCIAS") }
                            }
                        }

                        Text(
                            "Android y BlackClaw muestran indicadores mientras cámara o micrófono están activos. El SMS puede tener costo según tu operador.",
                            color = Color(0xFF8F849F),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(32.dp))
                      }
                    }
                }
            }
        }
    }

    private fun requestEmergencyPermissions(audio: Boolean, camera: Boolean) {
        val permissions = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (audio) permissions += Manifest.permission.RECORD_AUDIO
        if (camera) permissions += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.distinct().filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}

private fun formatEvidenceBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    else -> "${bytes / 1024} KB"
}
