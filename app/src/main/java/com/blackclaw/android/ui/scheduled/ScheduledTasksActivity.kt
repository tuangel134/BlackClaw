package com.blackclaw.android.ui.scheduled

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.scheduler.ScheduledTaskManager
import com.blackclaw.android.automation.AutomationEngine
import com.blackclaw.android.automation.AutomationRuleStore
import com.blackclaw.android.ui.chat.BlackClawColors
import com.blackclaw.android.ui.chat.ThemeManager
import com.blackclaw.android.ui.chat.ThemeManager.toComposeColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScheduledTasksActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        val colors = with(ThemeManager) { tc.toComposeColors() }
        setContent {
            ScheduledTasksScreen(colors = colors, onBack = { finish() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduledTasksScreen(colors: BlackClawColors, onBack: () -> Unit) {
    var tasks by remember { mutableStateOf(ScheduledTaskManager.listAll()) }
    var rules by remember { mutableStateOf(AutomationRuleStore.list()) }
    var selected by remember { mutableStateOf(0) }
    var showAddRule by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text("Automatizaciones",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = colors.textPrimary)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás", tint = colors.textPrimary)
                    }
                },
                actions = {
                    if (selected == 1) {
                        IconButton(onClick = { showAddRule = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Nueva regla", tint = colors.accent)
                        }
                    } else if (tasks.isNotEmpty()) {
                        IconButton(onClick = {
                            tasks.forEach { ScheduledTaskManager.cancel(ctx, it.id) }
                            tasks = ScheduledTaskManager.listAll()
                        }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Eliminar todas", tint = colors.textTertiary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface,
                    titleContentColor = colors.textPrimary,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(12.dp))
                .background(colors.surface)) {
                listOf("⏰ Horarios" to tasks.size, "⚡ Si → entonces" to rules.size).forEachIndexed { index, item ->
                    TextButton(onClick = { selected = index }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (selected == index) colors.accent.copy(alpha = .16f) else androidx.compose.ui.graphics.Color.Transparent)) {
                        Text("${item.first}  ${item.second}", color = if (selected == index) colors.accent else colors.textSecondary)
                    }
                }
            }
            if (selected == 0) {
                if (tasks.isEmpty()) EmptyState(colors, Modifier.weight(1f)) else LazyColumn(
                    modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(tasks, key = { it.id }) { task -> TaskCard(task, colors) {
                        ScheduledTaskManager.cancel(ctx, task.id); tasks = ScheduledTaskManager.listAll()
                    } }
                }
            } else {
                if (rules.isEmpty()) RuleEmptyState(colors, Modifier.weight(1f)) else LazyColumn(
                    modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(rules, key = { it.id }) { rule -> RuleCard(rule, colors,
                        onToggle = { AutomationRuleStore.setEnabled(rule.id, it); rules = AutomationRuleStore.list() },
                        onRun = { AutomationEngine.fire(ctx, rule) },
                        onDelete = { AutomationRuleStore.delete(rule.id); rules = AutomationRuleStore.list() }) }
                }
            }
        }
    }
    if (showAddRule) AddRuleDialog(colors, onDismiss = { showAddRule = false }) {
        rules = AutomationRuleStore.list(); showAddRule = false
    }
}

@Composable
private fun RuleEmptyState(colors: BlackClawColors, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⚡", fontSize = 42.sp); Spacer(Modifier.height(12.dp))
            Text("Sin reglas todavía", color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
            Text("Crea reglas por notificación o ubicación con +\no pídele a BlackClaw: “si pasa X, haz Y”",
                color = colors.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun RuleCard(rule: AutomationRuleStore.Rule, colors: BlackClawColors,
                     onToggle: (Boolean) -> Unit, onRun: () -> Unit, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = colors.surface), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (rule.trigger == AutomationRuleStore.Trigger.NOTIFICATION) "🔔" else "📍", fontSize = 24.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(rule.name, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text(rule.trigger.name.lowercase().replace('_', ' '), color = colors.accent, fontSize = 11.sp)
                }
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            if (rule.match.isNotBlank()) Text("SI contiene: ${rule.match}", color = colors.textSecondary, fontSize = 12.sp)
            Text("ENTONCES: ${rule.actionText}", color = colors.textPrimary, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRun) { Text("Probar", color = colors.accent) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Eliminar", tint = colors.textTertiary) }
            }
        }
    }
}

@Composable
private fun AddRuleDialog(colors: BlackClawColors, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var name by remember { mutableStateOf("") }; var match by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("") }; var pkg by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }; var lon by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf(AutomationRuleStore.Trigger.NOTIFICATION) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = colors.surface,
        title = { Text("Nueva automatización", color = colors.textPrimary) },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AutomationRuleStore.Trigger.entries.forEach { t -> FilterChip(selected = trigger == t,
                    onClick = { trigger = t }, label = { Text(when(t) {
                        AutomationRuleStore.Trigger.NOTIFICATION -> "Notificación"
                        AutomationRuleStore.Trigger.LOCATION_ENTER -> "Llegar"
                        AutomationRuleStore.Trigger.LOCATION_EXIT -> "Salir"
                    }, fontSize = 11.sp) }) }
            }
            if (trigger == AutomationRuleStore.Trigger.NOTIFICATION) {
                OutlinedTextField(match, { match = it }, label = { Text("Contacto o texto") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pkg, { pkg = it }, label = { Text("Paquete opcional (com.whatsapp)") }, modifier = Modifier.fillMaxWidth())
            } else {
                OutlinedTextField(lat, { lat = it }, label = { Text("Latitud") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lon, { lon = it }, label = { Text("Longitud") }, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(action, { action = it }, label = { Text("Acciones en orden") }, minLines = 3,
                placeholder = { Text("Apaga datos móviles, enciende Wi-Fi y verifica") }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank() && action.isNotBlank() &&
            (trigger == AutomationRuleStore.Trigger.NOTIFICATION || (lat.toDoubleOrNull() != null && lon.toDoubleOrNull() != null)),
            onClick = {
                AutomationRuleStore.create(name, trigger, match, pkg, action,
                    lat.toDoubleOrNull() ?: 0.0, lon.toDoubleOrNull() ?: 0.0)
                onSaved()
            }) { Text("Guardar", color = colors.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun EmptyState(colors: BlackClawColors, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(72.dp).clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Text("⏰", fontSize = 36.sp) }
            Spacer(Modifier.height(20.dp))
            Text("Sin tareas programadas", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text("Pídele a la IA \"recuérdame X mañana a las 9\"\ny aparecerán aquí.",
                fontSize = 13.sp, color = colors.textSecondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TaskCard(
    task: ScheduledTaskManager.ScheduledTask,
    colors: BlackClawColors,
    onCancel: () -> Unit,
) {
    val df = SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault())
    val recurStr = when (task.recurrence) {
        ScheduledTaskManager.Recurrence.ONCE -> "una vez"
        ScheduledTaskManager.Recurrence.HOURLY -> "cada hora"
        ScheduledTaskManager.Recurrence.DAILY -> "cada día"
        ScheduledTaskManager.Recurrence.WEEKLY -> "cada semana"
        ScheduledTaskManager.Recurrence.INTERVAL -> "cada ${task.intervalMs / 60_000}m"
    }
    val modeIcon = if (task.mode == ScheduledTaskManager.Mode.TASK) "🤖" else "💬"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.aiBubbleBorder),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                        .background(colors.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { Text(modeIcon, fontSize = 22.sp) }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(df.format(Date(task.triggerAtMs)),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    Text(recurStr, fontSize = 11.sp, color = colors.accent)
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancelar", tint = colors.textTertiary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(color = colors.aiBubble, shape = RoundedCornerShape(8.dp)) {
                Text("\"${task.text}\"", fontSize = 12.sp, color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}
