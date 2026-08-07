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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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
                onOpenAutomations = {
                    startActivity(android.content.Intent(this, com.blackclaw.android.ui.scheduled.ScheduledTasksActivity::class.java))
                },
            )
        }
    }
}

private data class TabDef(val type: AssistantItemType, val label: String, val emoji: String, val tint: Color)

private val TABS = listOf(
    TabDef(AssistantItemType.REMINDER, "Recordatorios", "🔔", Color(0xFF8B5CF6)),
    TabDef(AssistantItemType.ALARM, "Alarmas", "⏰", Color(0xFFF59E0B)),
    TabDef(AssistantItemType.NOTE, "Notas", "📝", Color(0xFF38BDF8)),
    TabDef(AssistantItemType.EVENT, "Calendario", "📅", Color(0xFFEC4899)),
    TabDef(AssistantItemType.ALERT, "Avisos", "📢", Color(0xFFEF4444)),
    TabDef(AssistantItemType.FINANCE, "Finanzas", "💰", Color(0xFF22C55E)),
    TabDef(AssistantItemType.SHOPPING, "Compras", "🛒", Color(0xFF14B8A6)),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantScreen(
    colors: BlackClawColors,
    onBack: () -> Unit,
    onOpenProactive: () -> Unit,
    onOpenAutomations: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(0) }
    var refresh by remember { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var showBudget by remember { mutableStateOf(false) }

    val tab = TABS[selectedTab]
    val items = remember(refresh, selectedTab) { AssistantStore.byType(tab.type) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    // Constrained as a safety net. This bar carries four actions and a
                    // back button; when the Finanzas tab added a fifth the title had
                    // about 6 dp left and its letters collapsed into each other. If it
                    // ever runs out of room again it should truncate cleanly rather than
                    // turn into an unreadable smear.
                    Text(
                        "Asistente",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = colors.textPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenAutomations) {
                        Icon(Icons.Default.AutoAwesome, "Automatizaciones", tint = colors.accent)
                    }
                    if (tab.type == AssistantItemType.FINANCE) {
                        // An icon, not a text button. The word "Presupuesto" is about
                        // 110 dp wide and it only appears on this tab, which is why the
                        // title was readable everywhere else and unreadable here.
                        IconButton(onClick = { showBudget = true }) {
                            Icon(Icons.Default.Savings, "Presupuesto", tint = colors.accent)
                        }
                    }
                    IconButton(onClick = {
                        ctx.startActivity(android.content.Intent(
                            ctx, com.blackclaw.android.ui.assistant.CalendarActivity::class.java))
                    }) {
                        Icon(Icons.Default.CalendarMonth, "Calendario", tint = colors.textPrimary)
                    }
                    Surface(
                        color = colors.accent.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.padding(end = 10.dp).clickable { onOpenProactive() },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, tint = colors.accent,
                                modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Proactivo", color = colors.accent, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background, titleContentColor = colors.textPrimary),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAdd = true },
                containerColor = tab.tint, contentColor = Color.White,
                elevation = FloatingActionButtonDefaults.elevation(6.dp),
            ) { Icon(Icons.Default.Add, "Añadir ${tab.label}") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val accent = remember(tab.type) {
                com.blackclaw.android.ui.design.ClawPalette.forCategory(
                    AssistantCardModel.accentName(tab.type)
                )
            }
            val summary = remember(refresh, selectedTab, items) {
                AssistantSummary.of(tab.type, items)
            }

            AssistantHero(
                label = tab.label.uppercase(),
                emoji = tab.emoji,
                headline = summary.headline,
                subtitle = summary.subtitle,
                accent = accent,
                progress = summary.progress,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )

            // Category pills. Counts recompute on refresh so checking an item off is
            // reflected here immediately, which is what makes the tap feel connected.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TABS.forEachIndexed { i, t ->
                    val tAccent = remember(t.type) {
                        com.blackclaw.android.ui.design.ClawPalette.forCategory(
                            AssistantCardModel.accentName(t.type)
                        )
                    }
                    val count = remember(refresh, i) {
                        AssistantStore.byType(t.type).count { !it.done }
                    }
                    com.blackclaw.android.ui.design.ClawChip(
                        text = t.label,
                        selected = i == selectedTab,
                        onClick = { selectedTab = i },
                        accent = tAccent,
                        leading = t.emoji,
                        badgeCount = count,
                    )
                }
            }

            if (items.isEmpty()) {
                AssistantEmptyState(
                    emoji = tab.emoji,
                    title = "Sin ${tab.label.lowercase()} todavía",
                    hint = "Toca + o pídeselo a la IA en el chat",
                    accent = accent,
                )
            } else {
                val reduceMotion = com.blackclaw.android.ui.design.ClawAnimation.reduceMotion()
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 90.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                        com.blackclaw.android.ui.design.ClawReveal(
                            index = index,
                            enabled = !reduceMotion,
                        ) {
                            AssistantItemCard(
                                item = item,
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
    }

    if (showAdd) {
        AddItemDialog(
            type = tab.type, colors = colors,
            onDismiss = { showAdd = false },
            onSave = { showAdd = false; refresh++ },
        )
    }

    if (showBudget) {
        var budgetText by remember { mutableStateOf(
            AssistantStore.monthlyBudget.takeIf { it > 0 }?.let { "%.0f".format(it) } ?: "") }
        AlertDialog(
            onDismissRequest = { showBudget = false },
            containerColor = colors.surface,
            title = { Text("Presupuesto mensual", color = colors.textPrimary) },
            text = {
                Column {
                    Text("Te avisaré cuando tus gastos del mes se acerquen a este límite.",
                        fontSize = 12.sp, color = colors.textSecondary, lineHeight = 16.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = budgetText, onValueChange = { budgetText = it },
                        label = { Text("Monto (0 = sin límite)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors(colors),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AssistantStore.monthlyBudget = budgetText.toDoubleOrNull() ?: 0.0
                    showBudget = false; refresh++
                }) { Text("Guardar", color = colors.accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showBudget = false }) {
                    Text("Cancelar", color = colors.textSecondary)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    var challenge by remember { mutableStateOf("none") }
    val needsTime = type == AssistantItemType.REMINDER || type == AssistantItemType.ALARM ||
        type == AssistantItemType.EVENT
    val canRepeat = type == AssistantItemType.ALARM || type == AssistantItemType.REMINDER
    val isAlarm = type == AssistantItemType.ALARM
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

                if (isAlarm) {
                    Spacer(Modifier.height(12.dp))
                    Text("RETO PARA APAGAR (ALARMA IMPORTANTE)", fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, color = colors.textTertiary, letterSpacing = 0.8.sp)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("none" to "Sin reto", "math" to "🔢 Mate",
                               "memory" to "🧠 Memoria", "type" to "⌨️ Frase")
                            .forEach { (value, lbl) ->
                                val sel = challenge == value
                                Surface(
                                    color = if (sel) colors.accent else colors.aiBubble,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.clickable { challenge = value },
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
                                triggerAtMs = ts, repeat = repeat, amount = amt,
                                challenge = if (isAlarm) challenge else "none", source = "user",
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
