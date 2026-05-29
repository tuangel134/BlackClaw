package com.blackclaw.android.ui.scheduled

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text("Tareas programadas",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = colors.textPrimary)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás", tint = colors.textPrimary)
                    }
                },
                actions = {
                    if (tasks.isNotEmpty()) {
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
        if (tasks.isEmpty()) {
            EmptyState(colors = colors, modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        colors = colors,
                        onCancel = {
                            ScheduledTaskManager.cancel(ctx, task.id)
                            tasks = ScheduledTaskManager.listAll()
                        },
                    )
                }
            }
        }
    }
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
