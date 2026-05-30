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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.Dialog
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
    var amount by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("none") }
    val needsTime = type == AssistantItemType.REMINDER || type == AssistantItemType.ALARM ||
        type == AssistantItemType.EVENT
    val canRepeat = type == AssistantItemType.ALARM || type == AssistantItemType.REMINDER
    val isFinance = type == AssistantItemType.FINANCE

    // Time state
    val cal = remember { java.util.Calendar.getInstance() }
    val timeState = rememberTimePickerState(
        initialHour = cal.get(java.util.Calendar.HOUR_OF_DAY),
        initialMinute = cal.get(java.util.Calendar.MINUTE),
        is24Hour = true,
    )
    var dateMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }

    val tabLabel = TABS.first { it.type == type }.label.lowercase().trimEnd('s')

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = colors.surface, shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
            ) {
                Text("Nuevo $tabLabel", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = colors.textPrimary)
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text(if (isFinance) "Descripción" else "Título") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(colors),
                )

                if (needsTime) {
                    Spacer(Modifier.height(16.dp))
                    // Date chip (for reminders/events; alarms default to next occurrence)
                    if (type != AssistantItemType.ALARM) {
                        AssistChipRow(
                            label = "📅 ${AssistantTime.format(stripTime(dateMs))}".substringBefore(" "),
                            fullLabel = java.text.SimpleDateFormat("EEE dd MMM", java.util.Locale.getDefault())
                                .format(java.util.Date(dateMs)),
                            colors = colors,
                        ) { showDatePicker = true }
                        Spacer(Modifier.height(16.dp))
                    }
                    Text("HORA", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = colors.textTertiary, letterSpacing = 0.8.sp)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TimePicker(
                            state = timeState,
                            colors = TimePickerDefaults.colors(
                                clockDialColor = colors.aiBubble,
                                clockDialSelectedContentColor = colors.background,
                                clockDialUnselectedContentColor = colors.textPrimary,
                                selectorColor = colors.accent,
                                periodSelectorBorderColor = colors.accent,
                                timeSelectorSelectedContainerColor = colors.accent.copy(alpha = 0.25f),
                                timeSelectorUnselectedContainerColor = colors.aiBubble,
                                timeSelectorSelectedContentColor = colors.textPrimary,
                                timeSelectorUnselectedContentColor = colors.textSecondary,
                            ),
                        )
                    }
                }

                if (canRepeat) {
                    Spacer(Modifier.height(8.dp))
                    Text("REPETIR", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = colors.textTertiary, letterSpacing = 0.8.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("none" to "Una vez", "daily" to "Diario", "weekly" to "Semanal")
                            .forEach { (value, lbl) ->
                                val sel = repeat == value
                                Surface(
                                    color = if (sel) colors.accent else colors.aiBubble,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.clickable { repeat = value },
                                ) {
                                    Text(lbl, fontSize = 12.sp,
                                        color = if (sel) colors.background else colors.textSecondary,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                                }
                            }
                    }
                }

                if (isFinance) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = amount, onValueChange = { amount = it },
                        label = { Text("Monto (negativo = gasto)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors(colors),
                    )
                }
                if (!isFinance && type != AssistantItemType.ALARM) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = body, onValueChange = { body = it },
                        label = { Text("Detalle (opcional)") },
                        modifier = Modifier.fillMaxWidth(), colors = fieldColors(colors),
                    )
                }

                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar", color = colors.textSecondary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (title.isBlank()) return@Button
                            val ts = if (needsTime) composeTrigger(type, dateMs, timeState.hour, timeState.minute) else 0L
                            val amt = if (isFinance) amount.toDoubleOrNull() ?: 0.0 else 0.0
                            val item = AssistantStore.create(
                                type = type, title = title.trim(), body = body.trim(),
                                triggerAtMs = ts, repeat = repeat, amount = amt, source = "user",
                            )
                            if (ts > 0) AssistantScheduler.arm(ctx, item)
                            onSave()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent, contentColor = colors.background),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Guardar", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = dateMs)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { dateMs = it }
                    showDatePicker = false
                }) { Text("OK", color = colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancelar", color = colors.textSecondary)
                }
            },
        ) { DatePicker(state = dateState) }
    }
}

/** Combine a chosen date (ms) + hour/minute into a trigger timestamp. For
 *  alarms with no date, pick the next occurrence of that time. */
private fun composeTrigger(type: AssistantItemType, dateMs: Long, hour: Int, minute: Int): Long {
    val cal = java.util.Calendar.getInstance()
    if (type != AssistantItemType.ALARM) cal.timeInMillis = dateMs
    cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
    cal.set(java.util.Calendar.MINUTE, minute)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    if (type == AssistantItemType.ALARM && cal.timeInMillis <= System.currentTimeMillis()) {
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
    }
    return cal.timeInMillis
}

private fun stripTime(ms: Long): Long = ms

@Composable
private fun AssistChipRow(label: String, fullLabel: String, colors: BlackClawColors, onClick: () -> Unit) {
    Surface(
        color = colors.aiBubble, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text("📅 $fullLabel", fontSize = 14.sp, color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
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
