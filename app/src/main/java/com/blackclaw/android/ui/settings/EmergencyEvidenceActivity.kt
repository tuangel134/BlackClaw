package com.blackclaw.android.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.emergency.EmergencyEvidenceVault
import com.blackclaw.android.emergency.EmergencyEvidenceVault.EvidenceItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
class EmergencyEvidenceActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EmergencyEvidenceVault.cleanupTemporary(this)
        setContent {
            var evidence by remember { mutableStateOf(EmergencyEvidenceVault.listEvidence(this)) }
            var busyId by remember { mutableStateOf<String?>(null) }
            var deleteTarget by remember { mutableStateOf<EvidenceItem?>(null) }

            fun refresh() { evidence = EmergencyEvidenceVault.listEvidence(this) }
            fun play(item: EvidenceItem) {
                busyId = item.id
                lifecycleScope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            EmergencyEvidenceVault.decryptToCache(this@EmergencyEvidenceActivity, item, "play")
                        }
                    }
                    busyId = null
                    result.onSuccess { plain ->
                        startActivity(Intent(this@EmergencyEvidenceActivity, EmergencyEvidencePlayerActivity::class.java)
                            .putExtra(EmergencyEvidencePlayerActivity.EXTRA_PATH, plain.absolutePath)
                            .putExtra(EmergencyEvidencePlayerActivity.EXTRA_TITLE, item.title()))
                    }.onFailure { Toast.makeText(this@EmergencyEvidenceActivity,
                        "No se pudo abrir: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }
            fun share(item: EvidenceItem) {
                busyId = item.id
                lifecycleScope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            EmergencyEvidenceVault.decryptToCache(this@EmergencyEvidenceActivity, item, "share")
                        }
                    }
                    busyId = null
                    result.onSuccess { plain ->
                        val uri = FileProvider.getUriForFile(
                            this@EmergencyEvidenceActivity,
                            "$packageName.fileprovider",
                            plain,
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = if (item.mediaType == EmergencyEvidenceVault.MediaType.VIDEO) "video/mp4" else "audio/mp4"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            startActivity(Intent.createChooser(intent, "Exportar evidencia"))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(this@EmergencyEvidenceActivity, "No hay una app para compartir", Toast.LENGTH_LONG).show()
                        }
                    }.onFailure { Toast.makeText(this@EmergencyEvidenceActivity,
                        "No se pudo exportar: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }

            EvidenceTheme {
                Scaffold(
                    containerColor = Color(0xFF07050C),
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text("Evidencias", fontWeight = FontWeight.Bold)
                                    Text("Cifradas en este dispositivo", style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF998DAD))
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color(0xFF100B18),
                                titleContentColor = Color.White,
                                navigationIconContentColor = Color.White,
                            ),
                        )
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).background(
                        Brush.verticalGradient(listOf(Color(0xFF17102A), Color(0xFF07050C))))) {
                        if (evidence.isEmpty()) {
                            Column(
                                Modifier.align(Alignment.Center).padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text("Aún no hay evidencias", color = Color.White,
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("Los segmentos de audio y video aparecerán aquí al detenerse o rotar una grabación.",
                                    color = Color(0xFFA89DB9), style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                item {
                                    Text("${evidence.size} segmentos protegidos", color = Color(0xFFCCBAFF),
                                        style = MaterialTheme.typography.labelLarge)
                                }
                                items(evidence, key = EvidenceItem::id) { item ->
                                    EvidenceCard(
                                        item = item,
                                        busy = busyId == item.id,
                                        onPlay = { play(item) },
                                        onShare = { share(item) },
                                        onDelete = { deleteTarget = item },
                                    )
                                }
                            }
                        }
                    }
                }

                deleteTarget?.let { item ->
                    AlertDialog(
                    onDismissRequest = { deleteTarget = null },
                    title = { Text("¿Eliminar evidencia?") },
                    text = { Text("Se borrará definitivamente el segmento cifrado. Esta acción no se puede deshacer.") },
                    confirmButton = {
                        TextButton(onClick = {
                            deleteTarget = null
                            lifecycleScope.launch {
                                val deleted = withContext(Dispatchers.IO) {
                                    runCatching { EmergencyEvidenceVault.deleteEvidence(this@EmergencyEvidenceActivity, item) }
                                        .getOrDefault(false)
                                }
                                if (!deleted) Toast.makeText(this@EmergencyEvidenceActivity,
                                    "No se pudo eliminar", Toast.LENGTH_LONG).show()
                                refresh()
                            }
                        }) { Text("ELIMINAR", color = Color(0xFFFF6F7D)) }
                    },
                    dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("CANCELAR") } },
                    )
                }
            }
        }
    }
}

@Composable
private fun EvidenceCard(
    item: EvidenceItem,
    busy: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xE6211930)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title(), color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(item.formattedDate(), color = Color(0xFFA89DB9), style = MaterialTheme.typography.bodySmall)
                }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else AssistChip(onClick = {}, enabled = false,
                    label = { Text(formatBytes(item.bytes)) })
            }
            Text(if (item.backedUp) "Respaldo completado · original cifrado" else "Pendiente de respaldo · original cifrado",
                color = if (item.backedUp) Color(0xFF61D5B0) else Color(0xFFFFC46B),
                style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onPlay, enabled = !busy) {
                    Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("VER")
                }
                TextButton(onClick = onShare, enabled = !busy) {
                    Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(4.dp)); Text("EXPORTAR")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(Icons.Outlined.Delete, "Eliminar", tint = Color(0xFFFF8792))
                }
            }
        }
    }
}

@Composable
internal fun EvidenceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF9D7CFF), secondary = Color(0xFF39C8E8),
            background = Color(0xFF07050C), surface = Color(0xFF181222),
            surfaceVariant = Color(0xFF241B33), onPrimary = Color.White,
            onBackground = Color(0xFFF4EEFF), onSurface = Color(0xFFF4EEFF),
        ),
        content = content,
    )
}

private fun EvidenceItem.title(): String = when {
    mediaType == EmergencyEvidenceVault.MediaType.AUDIO -> "Audio de emergencia"
    lens == "front" -> "Video · cámara frontal"
    lens == "back" -> "Video · cámara trasera"
    else -> "Video de emergencia"
}

private fun EvidenceItem.formattedDate(): String =
    SimpleDateFormat("d MMM yyyy · HH:mm:ss", Locale.getDefault()).format(Date(capturedAt))

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    else -> "${bytes / 1024} KB"
}
