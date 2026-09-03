package com.blackclaw.android.ui.autoreply

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import com.blackclaw.android.autoreply.AutoReplyProfile
import com.blackclaw.android.autoreply.AutoReplyProfileStore
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.scheduler.ScheduledTaskManager
import com.blackclaw.android.ui.chat.BlackClawColors
import com.blackclaw.android.ui.chat.ThemeManager
import com.blackclaw.android.ui.chat.ThemeManager.toComposeColors

class AutoRepliesActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        val colors = with(ThemeManager) { tc.toComposeColors() }

        setContent {
            AutoRepliesScreen(
                colors = colors,
                onBack = { finish() },
                onScheduleCron = { profile -> scheduleCron(profile) },
                onCancelCron = { profile -> cancelCron(profile) },
            )
        }
    }

    private fun scheduleCron(profile: AutoReplyProfile) {
        // Re-arm a fresh interval cron for this profile.
        cancelCron(profile)
        val now = System.currentTimeMillis()
        val intervalMs = profile.cronIntervalMinutes.toLong().coerceAtLeast(5L) * 60_000L
        val scheduled = ScheduledTaskManager.schedule(
            context = this,
            mode = ScheduledTaskManager.Mode.TASK,
            text = "[autoreply:${profile.id}] " + profile.composeAgentPrompt(),
            triggerAtMs = now + intervalMs,
            recurrence = ScheduledTaskManager.Recurrence.INTERVAL,
            intervalMs = intervalMs,
        )
        if (scheduled == null) {
            android.widget.Toast.makeText(
                this,
                "No se pudo programar la auto-respuesta de forma segura.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun cancelCron(profile: AutoReplyProfile) {
        // The schedule store doesn't index by profile id. We tag tasks with
        // "[autoreply:<id>] " so we can find and remove them here.
        val all = ScheduledTaskManager.listAll()
        val tag = "[autoreply:${profile.id}]"
        all.filter { it.text.startsWith(tag) }.forEach {
            ScheduledTaskManager.cancel(this, it.id)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoRepliesScreen(
    colors: BlackClawColors,
    onBack: () -> Unit,
    onScheduleCron: (AutoReplyProfile) -> Unit,
    onCancelCron: (AutoReplyProfile) -> Unit,
) {
    var profiles by remember { mutableStateOf(AutoReplyProfileStore.all()) }
    var editing by remember { mutableStateOf<AutoReplyProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Auto-respuestas", fontSize = 18.sp,
                            fontWeight = FontWeight.Bold, color = colors.textPrimary)
                        Text("La IA responde por ti", fontSize = 11.sp,
                            color = colors.textSecondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás",
                            tint = colors.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface,
                    titleContentColor = colors.textPrimary,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { creating = true },
                containerColor = colors.accent,
                contentColor = colors.background,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Crear")
            }
        },
    ) { padding ->
        if (profiles.isEmpty()) {
            EmptyState(colors = colors, modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        colors = colors,
                        onEdit = { editing = profile },
                        onToggle = {
                            val updated = AutoReplyProfileStore.toggleEnabled(profile.id)
                            if (updated != null) {
                                if (updated.enabled && updated.cronEnabled) onScheduleCron(updated)
                                else onCancelCron(updated)
                                profiles = AutoReplyProfileStore.all()
                            } else {
                                android.widget.Toast.makeText(context, "No se pudo guardar el cambio de forma segura.", android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        onDelete = {
                            if (AutoReplyProfileStore.delete(profile.id)) {
                                onCancelCron(profile)
                                profiles = AutoReplyProfileStore.all()
                            } else {
                                android.widget.Toast.makeText(context, "No se pudo eliminar el perfil de forma segura.", android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                }
            }
        }
    }

    if (creating) {
        ProfileEditorDialog(
            initial = AutoReplyProfile.blank(),
            colors = colors,
            onSave = { p ->
                val saved = AutoReplyProfileStore.upsert(p)
                if (saved != null) {
                    if (saved.cronEnabled && saved.enabled) onScheduleCron(saved)
                    profiles = AutoReplyProfileStore.all()
                    creating = false
                } else {
                    android.widget.Toast.makeText(context, "No se pudo guardar el perfil de forma segura.", android.widget.Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { creating = false },
        )
    }
    editing?.let { p ->
        ProfileEditorDialog(
            initial = p,
            colors = colors,
            onSave = { updated ->
                val saved = AutoReplyProfileStore.upsert(updated)
                if (saved != null) {
                    onCancelCron(p)
                    if (saved.cronEnabled && saved.enabled) onScheduleCron(saved)
                    profiles = AutoReplyProfileStore.all()
                    editing = null
                } else {
                    android.widget.Toast.makeText(context, "No se pudo guardar el perfil de forma segura.", android.widget.Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun EmptyState(colors: BlackClawColors, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)) {
            Box(
                modifier = Modifier.size(80.dp).clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Text("💬", fontSize = 40.sp) }
            Spacer(Modifier.height(20.dp))
            Text("Aún no hay auto-respuestas",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                "Crea perfiles para que la IA responda automáticamente a contactos concretos. " +
                "Puedes pegar tu historial de conversación para que aprenda tu estilo.",
                fontSize = 13.sp, color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Text("Pulsa + para crear el primero",
                fontSize = 12.sp, color = colors.accent)
        }
    }
}

@Composable
private fun ProfileCard(
    profile: AutoReplyProfile,
    colors: BlackClawColors,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(16.dp),
        border = if (profile.enabled)
            androidx.compose.foundation.BorderStroke(1.dp, colors.accent.copy(alpha = 0.5f))
        else
            androidx.compose.foundation.BorderStroke(0.5.dp, colors.aiBubbleBorder),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                        .background(
                            if (profile.app == "Telegram")
                                Color(0xFF0088CC).copy(alpha = 0.18f)
                            else
                                Color(0xFF25D366).copy(alpha = 0.18f)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (profile.app == "Telegram") "✈️" else "💬",
                        fontSize = 22.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(profile.contactName.ifBlank { "(sin contacto)" },
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (profile.enabled) {
                            Box(
                                modifier = Modifier.size(7.dp).clip(CircleShape)
                                    .background(colors.accent.copy(alpha = pulseAlpha)),
                            )
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            "${profile.app} · " + (
                                if (profile.cronEnabled) "cada ${profile.cronIntervalMinutes}m"
                                else "manual"
                            ),
                            fontSize = 11.sp,
                            color = if (profile.enabled) colors.accent else colors.textTertiary,
                        )
                    }
                }
                Switch(
                    checked = profile.enabled,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.background,
                        checkedTrackColor = colors.accent,
                        uncheckedThumbColor = colors.textTertiary,
                        uncheckedTrackColor = colors.aiBubble,
                    ),
                )
            }
            if (profile.personality.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(color = colors.aiBubble, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        profile.personality.take(140),
                        fontSize = 11.sp, color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.aiBubbleBorder),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.Edit, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Editar", fontSize = 12.sp, color = colors.textPrimary)
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Eliminar", tint = colors.textTertiary)
                }
            }
        }
    }
}

@Composable
private fun ProfileEditorDialog(
    initial: AutoReplyProfile,
    colors: BlackClawColors,
    onSave: (AutoReplyProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var contactName by remember { mutableStateOf(initial.contactName) }
    var app by remember { mutableStateOf(initial.app) }
    var personality by remember { mutableStateOf(initial.personality) }
    var conversationContext by remember { mutableStateOf(initial.conversationContext) }
    var cronEnabled by remember { mutableStateOf(initial.cronEnabled) }
    var intervalMinutes by remember { mutableStateOf(initial.cronIntervalMinutes.toString()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f),
            shape = RoundedCornerShape(20.dp),
            color = colors.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                ) {
                    Text(
                        if (initial.contactName.isBlank()) "Nueva auto-respuesta" else "Editar perfil",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = colors.textPrimary, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Cerrar", tint = colors.textTertiary)
                    }
                }
                HorizontalDivider(color = colors.divider)

                // Body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    FieldLabel("Contacto", colors)
                    OutlinedTextField(
                        value = contactName,
                        onValueChange = { contactName = it },
                        singleLine = true,
                        placeholder = { Text("Nombre del contacto (ej. Mamá)", color = colors.textTertiary) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors(colors),
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    Spacer(Modifier.height(12.dp))

                    FieldLabel("App", colors)
                    AppSelector(
                        selected = app,
                        options = listOf("WhatsApp", "Telegram"),
                        onSelected = { app = it },
                        colors = colors,
                    )
                    Spacer(Modifier.height(14.dp))

                    FieldLabel("Cómo debe responder", colors)
                    Text(
                        "Describe libremente la personalidad, el tono, el formato. La IA seguirá estas instrucciones cada vez que responda.",
                        fontSize = 11.sp, color = colors.textTertiary,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    OutlinedTextField(
                        value = personality,
                        onValueChange = { personality = it },
                        placeholder = {
                            Text(
                                "Ej: Responde en español, con frases cortas y emojis ocasionales. Si pregunta cómo estoy, di que bien. Nunca uses puntos finales.",
                                color = colors.textTertiary,
                            )
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        colors = textFieldColors(colors),
                        shape = RoundedCornerShape(10.dp),
                        maxLines = 8,
                    )
                    Spacer(Modifier.height(14.dp))

                    FieldLabel("Historial de conversación (opcional)", colors)
                    Text(
                        "Importa el archivo de exportación de WhatsApp (.txt o .zip) para que la IA aprenda tu estilo de respuesta. También puedes pegarlo manualmente.",
                        fontSize = 11.sp, color = colors.textTertiary,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )

                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val toaster = remember { mutableStateOf<String?>(null) }
                    val importLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.GetContent()
                    ) { uri ->
                        if (uri != null) {
                            // Persist read access
                            runCatching {
                                ctx.contentResolver.takePersistableUriPermission(
                                    uri,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                            }
                            val msgs = com.blackclaw.android.autoreply.WhatsAppExportParser.parse(ctx, uri)
                            if (msgs.isEmpty()) {
                                toaster.value = "No se encontraron mensajes. ¿Es un export de WhatsApp?"
                            } else {
                                val rendered = com.blackclaw.android.autoreply.WhatsAppExportParser
                                    .renderForPrompt(msgs)
                                conversationContext = rendered
                                val stats = com.blackclaw.android.autoreply.WhatsAppExportParser.stats(msgs)
                                toaster.value = "Importados ${stats.messageCount} mensajes ✓"
                            }
                        }
                    }
                    toaster.value?.let { msg ->
                        LaunchedEffect(msg) {
                            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                            toaster.value = null
                        }
                    }

                    // Import button row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                // GetContent supports text/* and application/zip; allow */* for safety
                                importLauncher.launch("*/*")
                            },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.accent.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Default.UploadFile, null, tint = colors.accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Importar export de WhatsApp", fontSize = 12.sp, color = colors.accent)
                        }
                    }

                    // Helper hint with steps
                    Surface(
                        color = colors.aiBubble,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        Text(
                            "📲 Cómo exportar: WhatsApp → abre el chat → menú (⋮) → Más → Exportar chat → Sin multimedia → comparte el .txt/.zip a BlackClaw o guárdalo en Documentos.",
                            fontSize = 10.sp, color = colors.textSecondary, lineHeight = 14.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }

                    OutlinedTextField(
                        value = conversationContext,
                        onValueChange = { conversationContext = it },
                        placeholder = {
                            Text(
                                "Yo: hola q tal\nMamá: bien y tu\nYo: bien tambien...",
                                color = colors.textTertiary,
                            )
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                        colors = textFieldColors(colors),
                        shape = RoundedCornerShape(10.dp),
                        maxLines = 14,
                    )
                    Spacer(Modifier.height(14.dp))

                    // Cron toggle
                    Surface(
                        color = colors.aiBubble,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Programar revisión periódica",
                                        fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                        color = colors.textPrimary)
                                    Text(
                                        "La IA abrirá la app y revisará mensajes cada X minutos automáticamente.",
                                        fontSize = 11.sp, color = colors.textTertiary,
                                    )
                                }
                                Switch(
                                    checked = cronEnabled,
                                    onCheckedChange = { cronEnabled = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = colors.background,
                                        checkedTrackColor = colors.accent,
                                    ),
                                )
                            }
                            if (cronEnabled) {
                                Spacer(Modifier.height(10.dp))
                                FieldLabel("Cada cuántos minutos", colors)
                                OutlinedTextField(
                                    value = intervalMinutes,
                                    onValueChange = { v -> intervalMinutes = v.filter { it.isDigit() }.take(4) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                                    ),
                                    suffix = { Text("min", color = colors.textTertiary, fontSize = 12.sp) },
                                    modifier = Modifier.fillMaxWidth(0.4f),
                                    colors = textFieldColors(colors),
                                    shape = RoundedCornerShape(10.dp),
                                )
                                Text(
                                    "Mínimo recomendado: 5 minutos. Demasiado frecuente puede agotar la batería.",
                                    fontSize = 10.sp, color = colors.textTertiary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = colors.divider)
                // Footer
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar", color = colors.textSecondary)
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = {
                            val mins = intervalMinutes.toIntOrNull() ?: 15
                            onSave(initial.copy(
                                contactName = contactName.trim(),
                                app = app,
                                personality = personality.trim(),
                                conversationContext = conversationContext.trim(),
                                cronEnabled = cronEnabled,
                                cronIntervalMinutes = mins.coerceIn(1, 1440),
                                enabled = true,
                                updatedAtMs = System.currentTimeMillis(),
                            ))
                        },
                        enabled = contactName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            contentColor = colors.background,
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Guardar", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppSelector(
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    colors: BlackClawColors,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            val isSel = opt == selected
            val emoji = if (opt == "Telegram") "✈️" else "💬"
            Surface(
                onClick = { onSelected(opt) },
                shape = RoundedCornerShape(12.dp),
                color = if (isSel) colors.accent.copy(alpha = 0.15f) else colors.aiBubble,
                border = if (isSel)
                    androidx.compose.foundation.BorderStroke(1.dp, colors.accent)
                else null,
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(emoji, fontSize = 16.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(opt, fontSize = 13.sp,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSel) colors.accent else colors.textPrimary)
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String, colors: BlackClawColors) {
    Text(
        text,
        fontSize = 11.sp, fontWeight = FontWeight.Medium,
        color = colors.textSecondary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun textFieldColors(c: BlackClawColors) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = c.textPrimary,
    unfocusedTextColor = c.textPrimary,
    focusedBorderColor = c.accent,
    unfocusedBorderColor = c.inputBorder,
    cursorColor = c.accent,
    focusedContainerColor = c.background,
    unfocusedContainerColor = c.background,
)
