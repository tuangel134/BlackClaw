package com.blackclaw.android.ui.assistant

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.blackclaw.android.assistant.AssistantItem
import com.blackclaw.android.assistant.AssistantItemType
import com.blackclaw.android.assistant.AssistantScheduler
import com.blackclaw.android.assistant.AssistantStore
import com.blackclaw.android.assistant.AssistantTime
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.ui.chat.BlackClawColors
import com.blackclaw.android.ui.chat.ThemeManager
import com.blackclaw.android.ui.chat.ThemeManager.toComposeColors
import com.blackclaw.android.ui.proactive.ProactiveAssistantActivity

/**
 * The native Assistant hub: reminders, notes, alarms, calendar events, alerts
 * and finances all live inside BlackClaw. Tabs switch between them. The AI
 * writes here via the assistant_* tools; the user can also add/complete/delete
 * items manually. A gear opens the Proactive Assistant settings.
 */
class AssistantActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        val colors = with(ThemeManager) { tc.toComposeColors() }
        setContent {
            AssistantScreen(
                colors = colors,
                onBack = { finish() },
                onOpenProactive = {
                    startActivity(android.content.Intent(this, ProactiveAssistantActivity::class.java))
                },
            )
        }
    }
}

private data class TabDef(val type: AssistantItemType, val label: String, val emoji: String)

private val TABS = listOf(
    TabDef(AssistantItemType.REMINDER, "Recordatorios", "🔔"),
    TabDef(AssistantItemType.ALARM, "Alarmas", "⏰"),
    TabDef(AssistantItemType.NOTE, "Notas", "📝"),
    TabDef(AssistantItemType.EVENT, "Calendario", "📅"),
    TabDef(AssistantItemType.ALERT, "Avisos", "📢"),
    TabDef(AssistantItemType.FINANCE, "Finanzas", "💰"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantScreen(
    colors: BlackClawColors,
    onBack: () -> Unit,
    onOpenProactive: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(0) }
    var refresh by remember { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }

    val tab = TABS[selectedTab]
    val items = remember(refresh, selectedTab) { AssistantStore.byType(tab.type) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Asistente", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            color = colors.textPrimary)
                        Text("Tu centro nativo: recordatorios, notas, finanzas…",
                            fontSize = 11.sp, color = colors.textSecondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = colors.textPrimary)
                    }
                },
                actions = {
                    TextButton(onClick = onOpenProactive) {
                        Text("Proactivo", color = colors.accent, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface, titleContentColor = colors.textPrimary),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAdd = true },
                containerColor = colors.accent, contentColor = colors.background,
            ) { Icon(Icons.Default.Add, "Añadir") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Tab chips
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TABS.forEachIndexed { i, t ->
                    val sel = i == selectedTab
                    Surface(
                        color = if (sel) colors.accent else colors.surface,
                        shape = RoundedCornerShape(20.dp),
                        border = if (sel) null else BorderStroke(0.5.dp, colors.aiBubbleBorder),
                        modifier = Modifier.clickable { selectedTab = i },
                    ) {
                        Text("${t.emoji} ${t.label}", fontSize = 13.sp,
                            color = if (sel) colors.background else colors.textSecondary,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
            }

            // Finance balance banner
            if (tab.type == AssistantItemType.FINANCE) {
                val bal = remember(refresh) { AssistantStore.financeBalance() }
                Surface(
                    color = if (bal >= 0) Color(0xFF22C55E).copy(alpha = 0.12f)
                            else Color(0xFFEF4444).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    Text("Balance: ${"%.2f".format(bal)}",
                        fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        color = if (bal >= 0) Color(0xFF22C55E) else Color(0xFFEF4444),
                        modifier = Modifier.padding(14.dp))
                }
            }

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(tab.emoji, fontSize = 40.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Sin ${tab.label.lowercase()} todavía",
                            fontSize = 14.sp, color = colors.textSecondary)
                        Text("Añade uno o deja que la IA lo haga por ti",
                            fontSize = 12.sp, color = colors.textTertiary)
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        ItemCard(
                            item = item, colors = colors,
                            onToggle = { AssistantStore.toggleDone(item.id); refresh++ },
                            onDelete = {
                                AssistantScheduler.cancel(ctx, item.id)
                                AssistantStore.delete(item.id); refresh++
                            },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddItemDialog(
            type = tab.type, colors = colors,
            onDismiss = { showAdd = false },
            onSave = { showAdd = false; refresh++ },
        )
    }
}

@Composable
private fun ItemCard(
    item: AssistantItem,
    colors: BlackClawColors,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = colors.surface, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(0.5.dp, colors.aiBubbleBorder),
    ) {
        Row(
            Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.type == AssistantItemType.REMINDER || item.type == AssistantItemType.NOTE) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape)
                        .background(if (item.done) colors.accent else Color.Transparent)
                        .border(1.5.dp, colors.accent, CircleShape)
                        .clickable { onToggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.done) Icon(Icons.Default.Check, null, tint = colors.background,
                        modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    item.title, fontSize = 15.sp, color = colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                )
                if (item.body.isNotBlank()) {
                    Text(item.body, fontSize = 12.sp, color = colors.textSecondary, lineHeight = 16.sp)
                }
                val meta = buildList {
                    if (item.triggerAtMs > 0) add(AssistantTime.format(item.triggerAtMs))
                    if (item.repeat != "none") add("· ${item.repeat}")
                    if (item.source == "ai") add("· IA")
                }.joinToString(" ")
                if (meta.isNotBlank()) {
                    Text(meta, fontSize = 11.sp, color = colors.textTertiary,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (item.type == AssistantItemType.FINANCE) {
                val sign = if (item.amount >= 0) "+" else ""
                Text("$sign${"%.2f".format(item.amount)}",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = if (item.amount >= 0) Color(0xFF22C55E) else Color(0xFFEF4444))
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Borrar", tint = colors.textTertiary,
                    modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddItemDialog(
    type: AssistantItemType,
    colors: BlackClawColors,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var whenStr by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    val needsTime = type == AssistantItemType.REMINDER || type == AssistantItemType.ALARM ||
        type == AssistantItemType.EVENT
    val isFinance = type == AssistantItemType.FINANCE

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Nuevo ${TABS.first { it.type == type }.label.lowercase().trimEnd('s')}",
            color = colors.textPrimary) },
        text = {
            Column {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text(if (isFinance) "Descripción" else "Título") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(colors),
                )
                if (needsTime) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = whenStr, onValueChange = { whenStr = it },
                        label = { Text("Cuándo (ej: tomorrow 09:00, in 2h, 07:30)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors(colors),
                    )
                }
                if (isFinance) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = amount, onValueChange = { amount = it },
                        label = { Text("Monto (negativo = gasto)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors(colors),
                    )
                }
                if (!isFinance) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = body, onValueChange = { body = it },
                        label = { Text("Detalle (opcional)") },
                        modifier = Modifier.fillMaxWidth(), colors = fieldColors(colors),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isBlank()) return@TextButton
                    val ts = if (needsTime) AssistantTime.parse(whenStr) else 0L
                    val amt = if (isFinance) amount.toDoubleOrNull() ?: 0.0 else 0.0
                    val item = AssistantStore.create(
                        type = type, title = title.trim(), body = body.trim(),
                        triggerAtMs = ts, amount = amt, source = "user",
                    )
                    if (ts > 0) AssistantScheduler.arm(ctx, item)
                    onSave()
                },
            ) { Text("Guardar", color = colors.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = colors.textSecondary) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun fieldColors(colors: BlackClawColors) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = colors.accent,
    unfocusedBorderColor = colors.aiBubbleBorder,
    focusedLabelColor = colors.accent,
    unfocusedLabelColor = colors.textSecondary,
    focusedTextColor = colors.textPrimary,
    unfocusedTextColor = colors.textPrimary,
    cursorColor = colors.accent,
)
