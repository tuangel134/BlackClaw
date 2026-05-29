package com.blackclaw.android.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.ui.chat.BlackClawColors
import com.blackclaw.android.ui.chat.ThemeManager
import com.blackclaw.android.ui.chat.ThemeManager.toComposeColors
import com.blackclaw.android.utils.KVUtils

class ThemeActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        val colors = with(ThemeManager) { tc.toComposeColors() }
        setContent {
            ThemeScreen(
                colors = colors,
                onBack = { finish() },
                onPicked = { id, isDark ->
                    KVUtils.putString("THEME_ID", id)
                    AppCompatDelegate.setDefaultNightMode(
                        if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                    )
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    intent?.addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                    startActivity(intent)
                    finishAffinity()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeScreen(
    colors: BlackClawColors,
    onBack: () -> Unit,
    onPicked: (id: String, isDark: Boolean) -> Unit,
) {
    val current = remember { KVUtils.getString("THEME_ID", "blackclaw_dark") }
    val all = remember { ThemeManager.allThemes }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Apariencia",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás", tint = colors.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface,
                    titleContentColor = colors.textPrimary,
                ),
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(all) { (id, name, desc) ->
                val isCurrent = id == current
                val isDark = id.endsWith("_dark") || id == "mono_dark"
                ThemeCard(
                    id = id, name = name, desc = desc,
                    isCurrent = isCurrent, isDark = isDark,
                    appColors = colors,
                    onClick = { onPicked(id, isDark) },
                )
            }
        }
    }
}

@Composable
private fun ThemeCard(
    id: String,
    name: String,
    desc: String,
    isCurrent: Boolean,
    isDark: Boolean,
    appColors: BlackClawColors,
    onClick: () -> Unit,
) {
    // Get the actual ChatColors to render the live preview
    val tcMap = remember { ThemeManager }
    val previewBg: Color
    val previewSurface: Color
    val previewBubble: Color
    val previewAccent: Color
    val previewGradStart: Color
    val previewGradEnd: Color

    // Pull preview colors from the saved theme catalog
    val savedId = KVUtils.getString("THEME_ID", "blackclaw_dark")
    KVUtils.putString("THEME_ID", id)
    val cc = ThemeManager.getColors()
    KVUtils.putString("THEME_ID", savedId) // restore
    previewBg = Color(cc.bg)
    previewSurface = Color(cc.toolbarBg)
    previewBubble = Color(cc.userBubble)
    previewAccent = Color(cc.sendColor)
    previewGradStart = Color(cc.gradientStart)
    previewGradEnd = Color(cc.gradientEnd)

    val borderWidth by animateFloatAsState(
        targetValue = if (isCurrent) 2f else 0f,
        animationSpec = tween(200), label = "border-w",
    )

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(180.dp),
        colors = CardDefaults.cardColors(containerColor = appColors.surface),
        shape = RoundedCornerShape(18.dp),
        border = if (isCurrent) androidx.compose.foundation.BorderStroke(borderWidth.dp, previewAccent) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = if (isCurrent) 4.dp else 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Live mini-preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .background(previewBg)
                    .padding(10.dp),
            ) {
                // Top bar with gradient accent
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(previewGradStart, previewGradEnd)
                            )
                        ),
                )
                Spacer(Modifier.height(0.dp))
                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // AI bubble preview
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(cc.aiBubble)),
                    )
                    // User bubble preview (accented)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .align(Alignment.End)
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(previewBubble),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.4f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(cc.aiBubble)),
                    )
                }

                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(previewAccent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null,
                            tint = previewBg, modifier = Modifier.size(12.dp))
                    }
                }
            }

            // Label area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = appColors.textPrimary,
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(previewAccent),
                    )
                }
                Text(
                    desc,
                    fontSize = 11.sp, color = appColors.textSecondary,
                    maxLines = 2,
                )
            }
        }
    }
}
