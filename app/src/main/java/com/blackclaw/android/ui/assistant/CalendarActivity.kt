package com.blackclaw.android.ui.assistant

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.blackclaw.android.ui.onboarding.PermissionExplanationDialog
import com.blackclaw.android.ui.onboarding.PermissionTopic
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Calendar / agenda view across ALL timed assistant items (alarms, reminders,
 * events). A month grid marks days that have something scheduled; tapping a day
 * shows its agenda below. This gives a single bird's-eye view of everything the
 * user (or the AI) has scheduled — the piece that was missing from the per-tab
 * Assistant hub.
 */
class CalendarActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        val colors = with(ThemeManager) { tc.toComposeColors() }
        setContent {
            CalendarScreen(colors = colors, onBack = { finish() })
        }
    }
}

/** The item types that have a meaningful place on a calendar. */
private val TIMED_TYPES = setOf(
    AssistantItemType.ALARM, AssistantItemType.REMINDER, AssistantItemType.EVENT)

private fun typeEmoji(t: AssistantItemType) = when (t) {
    AssistantItemType.ALARM -> "⏰"
    AssistantItemType.REMINDER -> "🔔"
    AssistantItemType.EVENT -> "📅"
    else -> "•"
}

private fun typeTint(t: AssistantItemType) = when (t) {
    AssistantItemType.ALARM -> Color(0xFFF59E0B)
    AssistantItemType.REMINDER -> Color(0xFF8B5CF6)
    AssistantItemType.EVENT -> Color(0xFFEC4899)
    else -> Color(0xFF8B5CF6)
}

private fun dayKey(ms: Long): Long {
    val c = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarScreen(colors: BlackClawColors, onBack: () -> Unit) {
    var refresh by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf(0) } // 0 = Mes, 1 = Agenda
    var rescheduleItem by remember { mutableStateOf<AssistantItem?>(null) }
    var showCalendarPermissionExplanation by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // System-calendar overlay (read-only). Starts on if permission already granted.
    var showSystem by remember {
        mutableStateOf(com.blackclaw.android.assistant.SystemCalendar.hasPermission(ctx))
    }
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> showSystem = granted; refresh++ }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Calendario", fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    color = colors.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = colors.textPrimary)
                    }
                },
                actions = {
                    // Toggle showing the device's system calendar events.
                    val on = showSystem
                    TextButton(onClick = {
                        if (on) { showSystem = false; refresh++ }
                        else if (com.blackclaw.android.assistant.SystemCalendar.hasPermission(ctx)) {
                            showSystem = true; refresh++
                        } else {
                            showCalendarPermissionExplanation = true
                        }
                    }) {
                        Text(if (on) "📆 Sistema ✓" else "📆 Sistema",
                            color = if (on) colors.accent else colors.textSecondary,
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background, titleContentColor = colors.textPrimary),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Mes / Agenda segmented toggle.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("📆 Mes", "📋 Agenda").forEachIndexed { i, label ->
                    val sel = i == mode
                    Surface(
                        color = if (sel) colors.accent else colors.surface,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f).clickable { mode = i },
                    ) {
                        Text(label, Modifier.padding(vertical = 10.dp), textAlign = TextAlign.Center,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = if (sel) colors.background else colors.textSecondary)
                    }
                }
            }
            if (mode == 0) {
                MonthMode(colors, refresh, showSystem) { rescheduleItem = it }
            } else {
                AgendaMode(colors, refresh, showSystem) { rescheduleItem = it }
            }
        }
    }

    if (showCalendarPermissionExplanation) {
        PermissionExplanationDialog(
            topic = PermissionTopic.CALENDAR,
            colors = colors,
            onDismiss = { showCalendarPermissionExplanation = false },
            onContinue = {
                showCalendarPermissionExplanation = false
                permLauncher.launch(android.Manifest.permission.READ_CALENDAR)
            },
        )
    }

    rescheduleItem?.let { item ->
        // System (read-only) items can't be rescheduled in-app.
        if (item.source == "system") { rescheduleItem = null; return@let }
        RescheduleDialog(
            item = item, colors = colors,
            onDismiss = { rescheduleItem = null },
            onSaved = { newMs ->
                AssistantScheduler.cancel(ctx, item.id)
                val updated = item.copy(triggerAtMs = newMs, done = false)
                AssistantStore.upsert(updated)
                AssistantScheduler.arm(ctx, updated)
                rescheduleItem = null
                refresh++
            },
            onDelete = {
                AssistantScheduler.cancel(ctx, item.id)
                AssistantStore.delete(item.id)
                rescheduleItem = null
                refresh++
            },
        )
    }
}

/** Merge native timed items with optional system-calendar events for a window. */
private fun collectItems(ctx: android.content.Context, showSystem: Boolean): List<AssistantItem> {
    val native = AssistantStore.all().filter { it.type in TIMED_TYPES && it.triggerAtMs > 0 }
    if (!showSystem) return native
    val from = System.currentTimeMillis() - 31L * 86_400_000
    val to = System.currentTimeMillis() + 365L * 86_400_000
    return native + com.blackclaw.android.assistant.SystemCalendar.events(ctx, from, to)
}

@Composable
private fun MonthMode(colors: BlackClawColors, refresh: Int, showSystem: Boolean,
                      onTapItem: (AssistantItem) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var monthCal by remember {
        mutableStateOf(Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        })
    }
    var selectedDay by remember { mutableStateOf(dayKey(System.currentTimeMillis())) }

    val itemsByDay = remember(refresh, showSystem) {
        collectItems(ctx, showSystem).groupBy { dayKey(it.triggerAtMs) }
    }
    val monthFmt = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    val agenda = remember(selectedDay, itemsByDay) {
        (itemsByDay[selectedDay] ?: emptyList()).sortedBy { it.triggerAtMs }
    }

    Column(Modifier.fillMaxSize()) {
        // Month switcher
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = {
                monthCal = (monthCal.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
            }) { Icon(Icons.Default.ChevronLeft, "Mes anterior", tint = colors.textPrimary) }
            Text(
                monthFmt.format(monthCal.time).replaceFirstChar { it.uppercase() },
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            IconButton(onClick = {
                monthCal = (monthCal.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            }) { Icon(Icons.Default.ChevronRight, "Mes siguiente", tint = colors.textPrimary) }
        }

        // Weekday header (Mon..Sun)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            listOf("L", "M", "X", "J", "V", "S", "D").forEach { d ->
                Text(d, Modifier.weight(1f), textAlign = TextAlign.Center,
                    fontSize = 12.sp, color = colors.textTertiary, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(4.dp))

        MonthGrid(
            monthCal = monthCal,
            selectedDay = selectedDay,
            itemsByDay = itemsByDay,
            colors = colors,
            onSelectDay = { selectedDay = it },
        )

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = colors.aiBubbleBorder)

        val dayLabel = SimpleDateFormat("EEEE dd 'de' MMMM", Locale.getDefault())
            .format(Date(selectedDay)).replaceFirstChar { it.uppercase() }
        Text(dayLabel, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)

        if (agenda.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nada programado este día", fontSize = 13.sp, color = colors.textTertiary)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(agenda, key = { it.id }) { item -> AgendaRow(item, colors, onTapItem) }
            }
        }
    }
}

/** Chronological "what's coming up" list grouped by day. */
@Composable
private fun AgendaMode(colors: BlackClawColors, refresh: Int, showSystem: Boolean,
                       onTapItem: (AssistantItem) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val now = System.currentTimeMillis()
    val upcoming = remember(refresh, showSystem) {
        collectItems(ctx, showSystem)
            .filter { it.triggerAtMs >= now && !it.done }
            .sortedBy { it.triggerAtMs }
            .take(120)
    }
    if (upcoming.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📋", fontSize = 40.sp)
                Spacer(Modifier.height(10.dp))
                Text("Agenda despejada", fontSize = 15.sp, color = colors.textPrimary,
                    fontWeight = FontWeight.Medium)
                Text("Dile a BlackClaw: \"tengo una reunión a las 7\"",
                    fontSize = 12.sp, color = colors.textTertiary)
            }
        }
        return
    }
    val grouped = remember(upcoming) { upcoming.groupBy { dayKey(it.triggerAtMs) } }
    val headerFmt = remember { SimpleDateFormat("EEEE dd MMM", Locale.getDefault()) }
    val today = dayKey(System.currentTimeMillis())
    val tomorrow = today + 86_400_000L

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        grouped.forEach { (day, dayItems) ->
            item(key = "h$day") {
                val label = when (day) {
                    today -> "HOY"
                    tomorrow -> "MAÑANA"
                    else -> headerFmt.format(Date(day)).uppercase()
                }
                Text(label, Modifier.padding(top = 10.dp, bottom = 2.dp),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.accent,
                    letterSpacing = 0.8.sp)
            }
            items(dayItems, key = { it.id }) { item -> AgendaRow(item, colors, onTapItem) }
        }
    }
}

@Composable
private fun MonthGrid(
    monthCal: Calendar,
    selectedDay: Long,
    itemsByDay: Map<Long, List<AssistantItem>>,
    colors: BlackClawColors,
    onSelectDay: (Long) -> Unit,
) {
    val daysInMonth = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
    // Offset so the 1st falls under the right weekday (Mon=0 .. Sun=6).
    val firstDow = (monthCal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val today = dayKey(System.currentTimeMillis())
    val cells = firstDow + daysInMonth
    val rows = (cells + 6) / 7

    Column(Modifier.padding(horizontal = 8.dp)) {
        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val cellIndex = row * 7 + col
                    val dayNum = cellIndex - firstDow + 1
                    if (dayNum in 1..daysInMonth) {
                        val cellCal = (monthCal.clone() as Calendar).apply {
                            set(Calendar.DAY_OF_MONTH, dayNum)
                        }
                        val key = cellCal.timeInMillis
                        val dayItems = itemsByDay[key] ?: emptyList()
                        val isSelected = key == selectedDay
                        val isToday = key == today
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).padding(3.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isSelected -> colors.accent
                                        isToday -> colors.accent.copy(alpha = 0.16f)
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable { onSelectDay(key) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "$dayNum", fontSize = 14.sp,
                                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        isSelected -> colors.background
                                        isToday -> colors.accent
                                        else -> colors.textPrimary
                                    },
                                )
                                // Up to 3 colored dots for scheduled items.
                                if (dayItems.isNotEmpty()) {
                                    Spacer(Modifier.height(2.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        dayItems.take(3).forEach { it2 ->
                                            Box(Modifier.size(5.dp).clip(CircleShape).background(
                                                if (isSelected) colors.background else typeTint(it2.type)))
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Box(Modifier.weight(1f).aspectRatio(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaRow(item: AssistantItem, colors: BlackClawColors, onTap: (AssistantItem) -> Unit) {
    val tint = typeTint(item.type)
    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.triggerAtMs))
    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable { onTap(item) },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(54.dp)) {
                Text(timeStr, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = tint)
                if (item.repeat != "none") {
                    Text(item.repeat, fontSize = 10.sp, color = colors.textTertiary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Text(if (item.ring) "🔔" else typeEmoji(item.type), fontSize = 17.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary)
                if (item.body.isNotBlank()) {
                    Text(item.body, fontSize = 12.sp, color = colors.textSecondary, lineHeight = 16.sp)
                }
                Row {
                    if (item.ring) Text("⏰ suena", fontSize = 11.sp, color = tint)
                    if (item.source == "ai") {
                        if (item.ring) Spacer(Modifier.width(8.dp))
                        Text("🐾 IA", fontSize = 11.sp, color = tint)
                    }
                    if (item.source == "system") {
                        Text("📆 Sistema", fontSize = 11.sp, color = colors.textTertiary)
                    }
                }
            }
            if (item.source != "system") {
                Icon(Icons.Default.Edit, "Reprogramar", tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RescheduleDialog(
    item: AssistantItem,
    colors: BlackClawColors,
    onDismiss: () -> Unit,
    onSaved: (Long) -> Unit,
    onDelete: () -> Unit,
) {
    val cal = remember { Calendar.getInstance().apply { timeInMillis = item.triggerAtMs } }
    var dateMs by remember { mutableStateOf(item.triggerAtMs) }
    var showDatePicker by remember { mutableStateOf(false) }
    val timeState = rememberTimePickerState(
        initialHour = cal.get(Calendar.HOUR_OF_DAY),
        initialMinute = cal.get(Calendar.MINUTE),
        is24Hour = true,
    )
    val dateLabel = SimpleDateFormat("EEE dd MMM yyyy", Locale.getDefault()).format(Date(dateMs))

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Reprogramar", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary)
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = colors.accent.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true },
                ) {
                    Text("📅 $dateLabel", Modifier.padding(12.dp), fontSize = 14.sp,
                        color = colors.accent, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePicker(state = timeState)
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, null, tint = Color(0xFFEF4444),
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Eliminar", color = Color(0xFFEF4444), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val c = Calendar.getInstance().apply {
                    timeInMillis = dateMs
                    set(Calendar.HOUR_OF_DAY, timeState.hour)
                    set(Calendar.MINUTE, timeState.minute)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                onSaved(c.timeInMillis)
            }) { Text("Guardar", color = colors.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = colors.textSecondary) }
        },
    )

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
