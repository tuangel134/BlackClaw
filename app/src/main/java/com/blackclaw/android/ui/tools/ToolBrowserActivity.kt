package com.blackclaw.android.ui.tools

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.tool.BaseTool
import com.blackclaw.android.tool.ToolRegistry
import com.blackclaw.android.ui.chat.BlackClawColors
import com.blackclaw.android.ui.chat.ThemeManager
import com.blackclaw.android.ui.chat.ThemeManager.toComposeColors

class ToolBrowserActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        val colors = with(ThemeManager) { tc.toComposeColors() }
        setContent {
            ToolBrowserScreen(colors = colors, onBack = { finish() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolBrowserScreen(colors: BlackClawColors, onBack: () -> Unit) {
    val all = remember { ToolRegistry.getInstance().getAllTools().sortedBy { it.getName() } }
    var query by remember { mutableStateOf("") }

    val filtered = remember(query) {
        if (query.isBlank()) all
        else all.filter {
            it.getName().contains(query, ignoreCase = true) ||
            it.getDisplayName().contains(query, ignoreCase = true) ||
            it.getDescriptionEN().contains(query, ignoreCase = true)
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text("Herramientas (${all.size})",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = colors.textPrimary)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás", tint = colors.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface,
                    titleContentColor = colors.textPrimary,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Search bar
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Buscar herramienta…", color = colors.textTertiary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = colors.textTertiary) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.inputBorder,
                    cursorColor = colors.accent,
                ),
                shape = RoundedCornerShape(12.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.getName() }) { tool ->
                    ToolCard(tool = tool, colors = colors)
                }
            }
        }
    }
}

@Composable
private fun ToolCard(tool: BaseTool, colors: BlackClawColors) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.aiBubbleBorder),
        onClick = { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape)
                        .background(colors.accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(toolEmoji(tool.getName()), fontSize = 18.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(tool.getDisplayName(), fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    Text(tool.getName(), fontSize = 11.sp, color = colors.accent)
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = colors.textTertiary,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                Text(tool.getDescriptionEN(), fontSize = 12.sp, color = colors.textSecondary,
                    lineHeight = 17.sp)
                if (tool.getParameters().isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Parámetros:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.textTertiary)
                    tool.getParameters().forEach { p ->
                        Row(modifier = Modifier.padding(top = 4.dp)) {
                            Text(
                                "${p.name}${if (p.isRequired) "*" else ""} : ${p.type}",
                                fontSize = 11.sp, color = colors.accent,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            Text(p.description, fontSize = 11.sp, color = colors.textSecondary,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private fun toolEmoji(name: String): String = when {
    "screen" in name || name == "find_node_info" -> "📺"
    "tap" in name || "click" in name -> "👆"
    "swipe" in name || "scroll" in name -> "👋"
    "input" in name || "type" in name -> "⌨️"
    "open" in name -> "📲"
    "send" in name && "sms" in name -> "💬"
    "send" in name && "message" in name -> "✉️"
    "send" in name -> "📤"
    "call" in name || "make_call" in name -> "📞"
    "sms" in name -> "💬"
    "calendar" in name -> "📅"
    "alarm" in name || "schedule" in name -> "⏰"
    "remember" in name || "recall" in name || "forget" in name -> "🧠"
    "skill" in name -> "✨"
    "media" in name -> "🎵"
    "flash" in name -> "🔦"
    "vibrate" in name -> "📳"
    "speak" in name -> "🔊"
    "volume" in name -> "🔉"
    "bright" in name -> "🔆"
    "battery" in name || "device_info" in name -> "🔋"
    "camera" in name -> "📷"
    "screenshot" in name -> "📸"
    "url" in name || "search" in name || "http" in name -> "🌐"
    "share" in name -> "📤"
    "math" in name -> "🧮"
    "kb_" in name || "kb_read" in name -> "📚"
    "notif" in name || "notify" in name -> "🔔"
    "clipboard" in name -> "📋"
    "contact" in name -> "👤"
    "toggle" in name -> "🎛️"
    "wait" in name -> "⏳"
    "finish" in name -> "✅"
    "key" in name || "press" in name -> "⏹️"
    "dpad" in name -> "🎮"
    "foreground" in name || "recent" in name || "close" in name -> "📱"
    else -> "🛠️"
}
