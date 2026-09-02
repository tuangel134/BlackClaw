package com.blackclaw.android.ui.dashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackclaw.android.ui.chat.BlackClawColors
import com.blackclaw.android.ui.chat.ThemeManager
import com.blackclaw.android.ui.chat.ThemeManager.toComposeColors
import com.blackclaw.android.ui.design.ClawGlassBackdrop
import com.blackclaw.android.ui.design.ClawGlassCard
import com.blackclaw.android.ui.design.ClawReveal
import com.blackclaw.android.utils.ActivityTracker

/**
 * Dashboard Activity — shows a summary of the assistant's daily/weekly activity.
 * Helps the user trust the assistant by seeing what it does.
 */
class DashboardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        val theme = ThemeManager.getColors()
        val colors = with(ThemeManager) { theme.toComposeColors() }
        window.statusBarColor = theme.toolbarBg
        setContent {
            ClawGlassBackdrop(colors = colors) {
                DashboardScreen(colors = colors, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(colors: BlackClawColors, onBack: () -> Unit) {
    val today = ActivityTracker.today()
    val week = ActivityTracker.thisWeek()
    val topTools = ActivityTracker.todayTopTools()

    val accentColor = colors.accent
    val textPrimary = colors.textPrimary
    val textSecondary = colors.textSecondary

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("📊 Actividad", color = textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ClawReveal {
                ClawGlassCard(colors = colors, modifier = Modifier.fillMaxWidth(), radius = 26.dp) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Tu actividad", color = textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(ActivityTracker.todaySummary(), color = textSecondary, fontSize = 14.sp)
                        Text("Todo lo que BlackClaw ejecuta queda visible aquí.", color = accentColor, fontSize = 11.sp)
                    }
                }
            }

            // Today's stats grid
            Text("Hoy", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.PlayArrow,
                    label = "Tareas",
                    value = "${today.tasksRun}",
                    detail = "${today.tasksSuccess}✓ ${today.tasksFailed}✗",
                    colors = colors,
                    accentColor = accentColor,
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Notifications,
                    label = "Proactivo",
                    value = "${today.proactiveActions}",
                    detail = "${today.proactiveIgnored} ignoradas",
                    colors = colors,
                    accentColor = Color(0xFF4CAF50),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Alarm,
                    label = "Alarmas",
                    value = "${today.alarmsSet}",
                    detail = "configuradas hoy",
                    colors = colors,
                    accentColor = Color(0xFFFF9800),
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Token,
                    label = "Tokens",
                    value = if (today.tokensUsed > 1000) "${today.tokensUsed / 1000}k" else "${today.tokensUsed}",
                    detail = if (today.estimatedCost > 0) "${"$%.3f".format(today.estimatedCost)}" else "local",
                    colors = colors,
                    accentColor = Color(0xFF9C27B0),
                )
            }

            // Top tools used today
            if (topTools.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Herramientas más usadas", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                ClawGlassCard(colors = colors, modifier = Modifier.fillMaxWidth(), radius = 20.dp, elevated = false) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        topTools.forEach { (tool, count) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(tool, color = textPrimary, fontSize = 14.sp)
                                Text("×$count", color = accentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Weekly overview
            if (week.size > 1) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Esta semana", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                val weekTasks = week.sumOf { it.tasksRun }
                val weekProactive = week.sumOf { it.proactiveActions }
                val weekTokens = week.sumOf { it.tokensUsed }
                ClawGlassCard(colors = colors, modifier = Modifier.fillMaxWidth(), radius = 20.dp, elevated = false) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("$weekTasks tareas ejecutadas", color = textPrimary, fontSize = 14.sp)
                        Text("$weekProactive acciones proactivas", color = textPrimary, fontSize = 14.sp)
                        Text("${weekTokens / 1000}k tokens consumidos", color = textSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    detail: String,
    colors: BlackClawColors,
    accentColor: Color,
) {
    ClawGlassCard(colors = colors, modifier = modifier, accent = accentColor, radius = 20.dp, elevated = false) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.Start) {
            Icon(icon, label, tint = accentColor, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(label, color = colors.textSecondary, fontSize = 12.sp)
            Text(detail, color = colors.textSecondary, fontSize = 11.sp)
        }
    }
}
