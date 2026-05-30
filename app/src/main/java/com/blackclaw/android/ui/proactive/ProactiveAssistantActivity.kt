package com.blackclaw.android.ui.proactive

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.proactive.ProactiveAssistantManager
import com.blackclaw.android.proactive.ProactiveConfig
import com.blackclaw.android.ui.chat.BlackClawColors
import com.blackclaw.android.ui.chat.ThemeManager
import com.blackclaw.android.ui.chat.ThemeManager.toComposeColors

/**
 * Settings for the Proactive Assistant: the user toggles it on, edits the
 * natural-language guidance for what counts as "important", chooses which
 * autonomous actions are allowed, and sees a log of what the assistant did.
 */
class ProactiveAssistantActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        val colors = with(ThemeManager) { tc.toComposeColors() }
        setContent { ProactiveScreen(colors = colors, onBack = { finish() }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProactiveScreen(colors: BlackClawColors, onBack: () -> Unit) {
    var enabled by remember { mutableStateOf(ProactiveConfig.enabled) }
    var instructions by remember { mutableStateOf(ProactiveConfig.instructions) }
    var allowAlarms by remember { mutableStateOf(ProactiveConfig.allowAlarms) }
    var allowReminders by remember { mutableStateOf(ProactiveConfig.allowReminders) }
    var allowNotes by remember { mutableStateOf(ProactiveConfig.allowNotes) }
    var allowCalendar by remember { mutableStateOf(ProactiveConfig.allowCalendar) }
    var watchAll by remember { mutableStateOf(ProactiveConfig.watchAllApps) }
    var log by remember { mutableStateOf(ProactiveAssistantManager.recentLog()) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Asistente proactivo", fontSize = 18.sp,
                            fontWeight = FontWeight.Bold, color = colors.textPrimary)
                        Text("La IA actúa sola con tus notificaciones",
                            fontSize = 11.sp, color = colors.textSecondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = colors.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface, titleContentColor = colors.textPrimary),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            // Hero / master toggle
            Surface(
                color = colors.surface, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.35f)),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(42.dp).clip(CircleShape)
                                .background(colors.accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, tint = colors.accent,
                                modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Activar asistente proactivo", fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                            Text("Cada notificación despierta a la IA para decidir si actúa",
                                fontSize = 12.sp, color = colors.textSecondary, lineHeight = 16.sp)
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { enabled = it; ProactiveConfig.enabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.background,
                                checkedTrackColor = colors.accent),
                        )
                    }
                    if (enabled) {
                        Spacer(Modifier.height(10.dp))
                        Surface(color = colors.aiBubble, shape = RoundedCornerShape(8.dp)) {
                            Text(
                                "Requiere acceso a notificaciones activado. La IA hace una " +
                                "consulta corta por notificación, así que casi no gasta tokens.",
                                fontSize = 11.sp, color = colors.textSecondary,
                                lineHeight = 15.sp, modifier = Modifier.padding(10.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // Instructions
            Text("QUÉ CONSIDERAR IMPORTANTE", fontSize = 11.sp,
                fontWeight = FontWeight.Bold, color = colors.textTertiary, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = instructions,
                onValueChange = { instructions = it; ProactiveConfig.instructions = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                placeholder = { Text("Describe qué es importante y qué debe hacer la IA…") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.aiBubbleBorder,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    cursorColor = colors.accent,
                ),
            )

            Spacer(Modifier.height(18.dp))

            // Allowed actions
            Text("ACCIONES PERMITIDAS", fontSize = 11.sp,
                fontWeight = FontWeight.Bold, color = colors.textTertiary, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(8.dp))
            Surface(color = colors.surface, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column {
                    ToggleRow("⏰ Poner alarmas", "Crea una alarma si detecta una hora a la que debes estar en un sitio",
                        allowAlarms, colors) { allowAlarms = it; ProactiveConfig.allowAlarms = it }
                    DividerLine(colors)
                    ToggleRow("🔔 Crear recordatorios", "Programa un aviso para una fecha/hora futura",
                        allowReminders, colors) { allowReminders = it; ProactiveConfig.allowReminders = it }
                    DividerLine(colors)
                    ToggleRow("📝 Guardar notas", "Apunta cosas importantes en tu lista",
                        allowNotes, colors) { allowNotes = it; ProactiveConfig.allowNotes = it }
                    DividerLine(colors)
                    ToggleRow("📅 Eventos de calendario", "Crea eventos (abre el calendario para confirmar)",
                        allowCalendar, colors) { allowCalendar = it; ProactiveConfig.allowCalendar = it }
                }
            }

            Spacer(Modifier.height(18.dp))

            // Apps to watch
            Text("QUÉ VIGILAR", fontSize = 11.sp,
                fontWeight = FontWeight.Bold, color = colors.textTertiary, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(8.dp))
            Surface(color = colors.surface, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()) {
                ToggleRow(
                    "Vigilar todas las apps",
                    if (watchAll) "Revisa notificaciones de cualquier app"
                    else "Solo apps de mensajería (WhatsApp, Telegram, SMS)",
                    watchAll, colors) { watchAll = it; ProactiveConfig.watchAllApps = it }
            }

            Spacer(Modifier.height(18.dp))

            // Activity log
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.NotificationsActive, null, tint = colors.textTertiary,
                    modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("ACTIVIDAD RECIENTE", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = colors.textTertiary, letterSpacing = 0.8.sp, modifier = Modifier.weight(1f))
                if (log.isNotEmpty()) {
                    Text("Limpiar", fontSize = 12.sp, color = colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(colors.accent.copy(alpha = 0.10f))
                            .clickable { ProactiveAssistantManager.clearLog(); log = emptyList() }
                            .padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(color = colors.surface, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()) {
                if (log.isEmpty()) {
                    Text("Aún no hay actividad. Cuando la IA actúe por una notificación, aparecerá aquí.",
                        fontSize = 12.sp, color = colors.textSecondary,
                        modifier = Modifier.padding(14.dp), lineHeight = 17.sp)
                } else {
                    Column(Modifier.padding(12.dp)) {
                        log.forEach { line ->
                            Text(line, fontSize = 12.sp, color = colors.textSecondary,
                                lineHeight = 18.sp, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ToggleRow(
    title: String, subtitle: String, checked: Boolean,
    colors: BlackClawColors, onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = colors.textPrimary, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 11.sp, color = colors.textSecondary, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.background, checkedTrackColor = colors.accent),
        )
    }
}

@Composable
private fun DividerLine(colors: BlackClawColors) {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.aiBubbleBorder))
}
