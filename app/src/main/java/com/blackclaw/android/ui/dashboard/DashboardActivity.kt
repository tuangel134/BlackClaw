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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackclaw.android.utils.ActivityTracker

/**
 * Dashboard Activity — shows a summary of the assistant's daily/weekly activity.
 * Helps the user trust the assistant by seeing what it does.
 */
class DashboardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            DashboardScreen(onBack = { finish() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(onBack: () -> Unit) {
    val today = ActivityTracker.today()
    val week = ActivityTracker.thisWeek()
    val topTools = ActivityTracker.todayTopTools()

    val bgColor = Color(0xFF0A0A0F)
    val surfaceColor = Color(0xFF141420)
    val accentColor = Color(0xFF00D4FF)
    val textPrimary = Color(0xFFC8D0E8)
    val textSecondary = Color(0xFF7A80A0)

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = { Text("📊 Actividad", color = textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = surfaceColor)
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
            // Header summary
            Text(
                text = ActivityTracker.todaySummary(),
                color = textSecondary,
                fontSize = 14.sp,
            )

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
                    surfaceColor = surfaceColor,
                    accentColor = accentColor,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Notifications,
                    label = "Proactivo",
                    value = "${today.proactiveActions}",
                    detail = "${today.proactiveIgnored} ignoradas",
                    surfaceColor = surfaceColor,
                    accentColor = Color(0xFF4CAF50),
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
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
                    surfaceColor = surfaceColor,
                    accentColor = Color(0xFFFF9800),
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Token,
                    label = "Tokens",
                    value = if (today.tokensUsed > 1000) "${today.tokensUsed / 1000}k" else "${today.tokensUsed}",
                    detail = if (today.estimatedCost > 0) "${"$%.3f".format(today.estimatedCost)}" else "local",
                    surfaceColor = surfaceColor,
                    accentColor = Color(0xFF9C27B0),
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                )
            }

            // Top tools used today
            if (topTools.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Herramientas más usadas", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(surfaceColor, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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

            // Weekly overview
            if (week.size > 1) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Esta semana", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                val weekTasks = week.sumOf { it.tasksRun }
                val weekProactive = week.sumOf { it.proactiveActions }
                val weekTokens = week.sumOf { it.tokensUsed }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(surfaceColor, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("$weekTasks tareas ejecutadas", color = textPrimary, fontSize = 14.sp)
                    Text("$weekProactive acciones proactivas", color = textPrimary, fontSize = 14.sp)
                    Text("${weekTokens / 1000}k tokens consumidos", color = textSecondary, fontSize = 13.sp)
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
    surfaceColor: Color,
    accentColor: Color,
    textPrimary: Color,
    textSecondary: Color,
) {
    Column(
        modifier = modifier
            .background(surfaceColor, RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Icon(icon, label, tint = accentColor, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.height(8.dp))
        Text(value, color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(label, color = textSecondary, fontSize = 12.sp)
        Text(detail, color = textSecondary, fontSize = 11.sp)
    }
}
