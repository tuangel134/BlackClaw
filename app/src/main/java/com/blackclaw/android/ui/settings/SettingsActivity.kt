package com.blackclaw.android.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.blackclaw.android.AppCapabilityCoordinator
import com.blackclaw.android.AppRequirement
import com.blackclaw.android.BuildConfig
import com.blackclaw.android.agent.skill.UserSkillStore
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.scheduler.ScheduledTaskManager
import com.blackclaw.android.tool.ToolRegistry
import com.blackclaw.android.ui.chat.BlackClawColors
import com.blackclaw.android.ui.chat.ThemeManager
import com.blackclaw.android.ui.chat.ThemeManager.toComposeColors
import com.blackclaw.android.ui.scheduled.ScheduledTasksActivity
import com.blackclaw.android.ui.skills.SkillsActivity
import com.blackclaw.android.ui.tools.ToolBrowserActivity
import com.blackclaw.android.utils.KVUtils

/**
 * Modern Compose settings — replaces the old XML-based SettingsActivity.
 * Layout: gradient hero + grouped section cards + smooth row click ripple.
 */
class SettingsActivity : BaseActivity() {

    private val tickHandler = Handler(Looper.getMainLooper())
    private val tick = mutableStateOf(0L)
    private val ticker = object : Runnable {
        override fun run() {
            tick.value = System.currentTimeMillis()
            tickHandler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        window.navigationBarColor = tc.bg
        val colors = with(ThemeManager) { tc.toComposeColors() }

        setContent {
            // Re-evaluate capability state on every tick
            val tickValue by tick
            val ctx = this
            val caps by remember(tickValue) {
                mutableStateOf(AppCapabilityCoordinator.snapshot(ctx))
            }
            ModernSettingsScreen(
                colors = colors,
                caps = caps,
                onBack = { finish() },
                onOpenAccessibility = { AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.ACCESSIBILITY) },
                onRequestNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && !AppCapabilityCoordinator.isNotificationPermissionGranted(this)) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
                    }
                },
                onOpenNotificationAccess = { AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.NOTIFICATION_ACCESS) },
                onOpenOverlay = { AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.OVERLAY) },
                onOpenBattery = { AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.BATTERY_OPTIMIZATION) },
                onOpenStorage = { AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.STORAGE) },
                onOpenLlmConfig = { startActivity(Intent(this, LlmConfigActivity::class.java)) },
                onOpenTheme = { startActivity(Intent(this, ThemeActivity::class.java)) },
                onOpenSkills = { startActivity(Intent(this, SkillsActivity::class.java)) },
                onOpenScheduled = { startActivity(Intent(this, ScheduledTasksActivity::class.java)) },
                onOpenToolBrowser = { startActivity(Intent(this, ToolBrowserActivity::class.java)) },
                onOpenAutoReplies = { startActivity(Intent(this, com.blackclaw.android.ui.autoreply.AutoRepliesActivity::class.java)) },
                onOpenProactive = { startActivity(Intent(this, com.blackclaw.android.ui.assistant.AssistantActivity::class.java)) },
                onOpenShizuku = { startActivity(Intent(this, com.blackclaw.android.ui.shizuku.ShizukuSetupActivity::class.java)) },
                onOpenAdbPro = { startActivity(Intent(this, com.blackclaw.android.ui.adb.AdbProActivity::class.java)) },
                onOpenTelegram = { ChannelConfigActivity.start(this, ChannelConfigActivity.ChannelType.TELEGRAM) },
                onToggleExternalAutomation = {
                    val newState = !KVUtils.isExternalAutomationEnabled()
                    KVUtils.setExternalAutomationEnabled(newState)
                    tick.value = System.currentTimeMillis()
                },
                onReportBug = { SettingsActions.reportBug(this) },
                onShareDebugReport = { SettingsActions.shareDebugReport(this) },
                onOpenGitHub = {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/tuangel134/BlackClaw")))
                },
                onEditGlobalPrompt = { showGlobalPromptDialog() },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        tickHandler.removeCallbacks(ticker)
        tickHandler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        tickHandler.removeCallbacks(ticker)
    }

    private fun showGlobalPromptDialog() {
        val current = KVUtils.getGlobalPrompt()
        com.blackclaw.android.widget.InputDialog.show(
            context = this,
            title = getString(com.blackclaw.android.R.string.global_prompt_dialog_title),
            presetText = current,
            hint = getString(com.blackclaw.android.R.string.global_prompt_hint),
            maxLength = 2000,
        ) { text ->
            KVUtils.setGlobalPrompt(text)
            tick.value = System.currentTimeMillis()
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Compose UI
// ──────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernSettingsScreen(
    colors: BlackClawColors,
    caps: com.blackclaw.android.AppCapabilitySnapshot,
    onBack: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenLlmConfig: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenScheduled: () -> Unit,
    onOpenToolBrowser: () -> Unit,
    onOpenAutoReplies: () -> Unit,
    onOpenProactive: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenAdbPro: () -> Unit,
    onOpenTelegram: () -> Unit,
    onToggleExternalAutomation: () -> Unit,
    onReportBug: () -> Unit,
    onShareDebugReport: () -> Unit,
    onOpenGitHub: () -> Unit,
    onEditGlobalPrompt: () -> Unit,
) {
    val themeId = remember { KVUtils.getString("THEME_ID", "blackclaw_dark") }
    val themeName = remember(themeId) {
        ThemeManager.allThemes.firstOrNull { it.first == themeId }?.second ?: themeId
    }
    val skillCount = remember { UserSkillStore.all().size }
    val scheduledCount = remember { ScheduledTaskManager.listAll().size }
    val toolCount = remember { ToolRegistry.getInstance().getAllTools().size }
    val autoReplyCount = remember { com.blackclaw.android.autoreply.AutoReplyProfileStore.all().size }
    val activeAutoReplies = remember {
        com.blackclaw.android.autoreply.AutoReplyProfileStore.all().count { it.enabled }
    }
    val externalAutomation = remember { KVUtils.isExternalAutomationEnabled() }
    val globalPromptLen = remember { KVUtils.getGlobalPrompt().length }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Ajustes",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Hero card with gradient + status pills ─────────────────────────
            HeroCard(colors = colors, caps = caps)

            Spacer(Modifier.height(8.dp))

            // ── Permisos ───────────────────────────────────────────────────────
            SettingsSection(title = "Permisos", colors = colors) {
                StatusRow(
                    icon = Icons.Outlined.Accessibility,
                    title = "Servicio de accesibilidad",
                    subtitle = "Para automatizar el control del teléfono",
                    status = caps.accessibilityStatusLabel,
                    statusOk = caps.canRunInteractiveTask,
                    colors = colors,
                    onClick = onOpenAccessibility,
                )
                Divider(colors)
                StatusRow(
                    icon = Icons.Outlined.Notifications,
                    title = "Notificaciones",
                    subtitle = "Mostrar progreso de tareas",
                    status = caps.notificationPermissionStatusLabel,
                    statusOk = caps.notificationPermissionGranted,
                    colors = colors,
                    onClick = onRequestNotifications,
                )
                Divider(colors)
                StatusRow(
                    icon = Icons.Outlined.NotificationsActive,
                    title = "Acceso a notificaciones",
                    subtitle = "Leer notificaciones para auto-respuesta",
                    status = caps.notificationAccessStatusLabel,
                    statusOk = caps.notificationAccessState == com.blackclaw.android.ServiceBindingState.READY,
                    colors = colors,
                    onClick = onOpenNotificationAccess,
                )
                Divider(colors)
                StatusRow(
                    icon = Icons.Outlined.Layers,
                    title = "Ventana flotante",
                    subtitle = "Panel de estado superpuesto",
                    status = if (caps.overlayGranted) "Activado" else "Desactivado",
                    statusOk = caps.overlayGranted,
                    colors = colors,
                    onClick = onOpenOverlay,
                )
                Divider(colors)
                StatusRow(
                    icon = Icons.Outlined.BatteryChargingFull,
                    title = "Lista blanca de batería",
                    subtitle = "Evita que se cierre en segundo plano",
                    status = if (caps.batteryOptimizationIgnored) "Sin restricciones" else "Restringido",
                    statusOk = caps.batteryOptimizationIgnored,
                    colors = colors,
                    onClick = onOpenBattery,
                )
                Divider(colors)
                StatusRow(
                    icon = Icons.Outlined.Folder,
                    title = "Acceso a archivos",
                    subtitle = "Leer y escribir archivos del dispositivo",
                    status = if (caps.storageAccessGranted) "Activado" else "Desactivado",
                    statusOk = caps.storageAccessGranted,
                    colors = colors,
                    onClick = onOpenStorage,
                )
            }

            // ── Modelo ─────────────────────────────────────────────────────────
            SettingsSection(title = "Modelo IA", colors = colors) {
                NavRow(
                    icon = Icons.Outlined.SmartToy,
                    title = "Configurar LLM",
                    trailing = "API + modelos",
                    colors = colors,
                    onClick = onOpenLlmConfig,
                )
                Divider(colors)
                NavRow(
                    icon = Icons.Outlined.Edit,
                    title = "Instrucciones globales",
                    trailing = if (globalPromptLen == 0) "Vacío" else "$globalPromptLen chars",
                    colors = colors,
                    onClick = onEditGlobalPrompt,
                )
            }

            // ── Apariencia ─────────────────────────────────────────────────────
            SettingsSection(title = "Apariencia", colors = colors) {
                NavRow(
                    icon = Icons.Outlined.Palette,
                    title = "Tema",
                    trailing = themeName,
                    trailingHighlight = true,
                    colors = colors,
                    onClick = onOpenTheme,
                )
            }

            // ── Herramientas ───────────────────────────────────────────────────
            SettingsSection(title = "Herramientas", colors = colors) {
                NavRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "Mis skills",
                    subtitle = "Automatizaciones reutilizables",
                    trailing = if (skillCount == 0) "Crear" else "$skillCount",
                    trailingHighlight = skillCount > 0,
                    colors = colors,
                    onClick = onOpenSkills,
                )
                Divider(colors)
                NavRow(
                    icon = Icons.Outlined.Schedule,
                    title = "Tareas programadas",
                    subtitle = "Recordatorios y crones",
                    trailing = if (scheduledCount == 0) "Ninguna" else "$scheduledCount",
                    trailingHighlight = scheduledCount > 0,
                    colors = colors,
                    onClick = onOpenScheduled,
                )
                Divider(colors)
                NavRow(
                    icon = Icons.Outlined.Build,
                    title = "Catálogo de herramientas",
                    subtitle = "Explorar todas las tools del agente",
                    trailing = "$toolCount",
                    colors = colors,
                    onClick = onOpenToolBrowser,
                )
                Divider(colors)
                NavRow(
                    icon = Icons.Outlined.SmartToy,
                    title = "Auto-respuestas",
                    subtitle = "La IA responde tus mensajes por ti",
                    trailing = if (autoReplyCount == 0) "Crear"
                               else "$activeAutoReplies/$autoReplyCount activas",
                    trailingHighlight = activeAutoReplies > 0,
                    colors = colors,
                    onClick = onOpenAutoReplies,
                )
                Divider(colors)
                NavRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "Asistente",
                    subtitle = "Recordatorios, notas, alarmas, finanzas + modo proactivo",
                    trailing = if (com.blackclaw.android.proactive.ProactiveConfig.enabled) "Proactivo ON" else "Abrir",
                    trailingHighlight = com.blackclaw.android.proactive.ProactiveConfig.enabled,
                    colors = colors,
                    onClick = onOpenProactive,
                )
            }

            // ── Avanzado ───────────────────────────────────────────────────────
            val ctx = androidx.compose.ui.platform.LocalContext.current
            SettingsSection(title = "Avanzado", colors = colors) {
                val shizukuState = remember { com.blackclaw.android.shizuku.ShizukuManager.state(ctx) }
                NavRow(
                    icon = Icons.Outlined.Bolt,
                    title = "Shizuku",
                    subtitle = "Acciones 10× más rápidas + control en juegos",
                    trailing = when (shizukuState) {
                        com.blackclaw.android.shizuku.ShizukuManager.State.READY -> "Activo"
                        com.blackclaw.android.shizuku.ShizukuManager.State.NOT_INSTALLED -> "No instalado"
                        com.blackclaw.android.shizuku.ShizukuManager.State.INSTALLED_OFF -> "Apagado"
                        com.blackclaw.android.shizuku.ShizukuManager.State.RUNNING_NO_PERM -> "Sin permiso"
                    },
                    trailingHighlight = shizukuState == com.blackclaw.android.shizuku.ShizukuManager.State.READY,
                    colors = colors,
                    onClick = onOpenShizuku,
                )
                Divider(colors)
                NavRow(
                    icon = Icons.Outlined.Speed,
                    title = "Modo Pro (ADB integrado)",
                    subtitle = "Acciones rápidas sin Shizuku ni PC",
                    trailing = "Beta",
                    trailingHighlight = false,
                    colors = colors,
                    onClick = onOpenAdbPro,
                )
            }

            // ── Control remoto ─────────────────────────────────────────────────
            SettingsSection(title = "Control remoto", colors = colors) {
                NavRow(
                    icon = Icons.Outlined.Send,
                    title = "Bot de Telegram",
                    trailing = if (KVUtils.getTelegramBotToken().isNotEmpty()) "Conectado" else "No conectado",
                    trailingHighlight = KVUtils.getTelegramBotToken().isNotEmpty(),
                    colors = colors,
                    onClick = onOpenTelegram,
                )
                Divider(colors)
                NavRow(
                    icon = Icons.Outlined.Code,
                    title = "Automatización externa",
                    subtitle = "Para Tasker, MacroDroid, ADB",
                    trailing = if (externalAutomation) "Activado" else "Desactivado",
                    trailingHighlight = externalAutomation,
                    colors = colors,
                    onClick = onToggleExternalAutomation,
                )
            }

            // ── Acerca de ──────────────────────────────────────────────────────
            SettingsSection(title = "Acerca de", colors = colors) {
                NavRow(
                    icon = Icons.Outlined.Info,
                    title = "BlackClaw",
                    subtitle = "v${BuildConfig.VERSION_NAME} · Beta",
                    trailing = "v${BuildConfig.VERSION_NAME}",
                    colors = colors,
                    onClick = {},
                )
                Divider(colors)
                NavRow(
                    icon = Icons.Outlined.BugReport,
                    title = "Reportar un fallo",
                    subtitle = "Abre un issue en GitHub y adjunta el informe de depuración (ZIP)",
                    trailing = "GitHub",
                    colors = colors,
                    onClick = onReportBug,
                )
                Divider(colors)
                NavRow(
                    icon = Icons.Outlined.Share,
                    title = "Compartir informe de depuración",
                    trailing = "ZIP",
                    colors = colors,
                    onClick = onShareDebugReport,
                )
                Divider(colors)
                NavRow(
                    icon = Icons.Outlined.Public,
                    title = "GitHub",
                    trailing = "tuangel134/BlackClaw",
                    colors = colors,
                    onClick = onOpenGitHub,
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun HeroCard(
    colors: BlackClawColors,
    caps: com.blackclaw.android.AppCapabilitySnapshot,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "hero")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        colors.accent.copy(alpha = 0.18f),
                        colors.surface,
                    )
                )
            )
            .border(
                width = 0.5.dp,
                color = colors.aiBubbleBorder,
                shape = RoundedCornerShape(20.dp),
            )
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(colors.accent.copy(alpha = pulse * 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(colors.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("BC", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = colors.background)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        "BlackClaw",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Text(
                        "Agente IA para tu teléfono",
                        fontSize = 12.sp, color = colors.textSecondary,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            // Quick status pills
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(
                    label = if (caps.canRunInteractiveTask) "A11y ✓" else "A11y ⚠",
                    ok = caps.canRunInteractiveTask,
                    colors = colors,
                )
                StatusPill(
                    label = if (caps.overlayGranted) "Overlay ✓" else "Overlay ⚠",
                    ok = caps.overlayGranted,
                    colors = colors,
                )
                StatusPill(
                    label = if (caps.batteryOptimizationIgnored) "Bat ✓" else "Bat ⚠",
                    ok = caps.batteryOptimizationIgnored,
                    colors = colors,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, ok: Boolean, colors: BlackClawColors) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (ok) colors.accent.copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp,
            if (ok) colors.accent.copy(alpha = 0.4f) else Color(0xFFEF4444).copy(alpha = 0.4f),
        ),
    ) {
        Text(
            label,
            fontSize = 10.sp, fontWeight = FontWeight.Medium,
            color = if (ok) colors.accent else Color(0xFFFF6B6B),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    colors: BlackClawColors,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(
            title.uppercase(),
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = colors.textTertiary,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Surface(
            color = colors.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    trailingHighlight: Boolean = false,
    colors: BlackClawColors,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
            )
            if (subtitle != null) {
                Text(subtitle, fontSize = 11.sp, color = colors.textTertiary)
            }
        }
        if (trailing != null) {
            Text(
                trailing,
                fontSize = 12.sp,
                fontWeight = if (trailingHighlight) FontWeight.SemiBold else FontWeight.Normal,
                color = if (trailingHighlight) colors.accent else colors.textTertiary,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.NavigateNext,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    status: String,
    statusOk: Boolean,
    colors: BlackClawColors,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (statusOk) colors.accent.copy(alpha = 0.12f)
                    else Color(0xFFEF4444).copy(alpha = 0.10f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, contentDescription = null,
                tint = if (statusOk) colors.accent else Color(0xFFFF6B6B),
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = colors.textPrimary)
            Text(subtitle, fontSize = 11.sp, color = colors.textTertiary)
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (statusOk) colors.accent.copy(alpha = 0.15f)
                    else Color(0xFFEF4444).copy(alpha = 0.15f),
        ) {
            Text(
                status,
                fontSize = 10.sp, fontWeight = FontWeight.Medium,
                color = if (statusOk) colors.accent else Color(0xFFFF6B6B),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun Divider(colors: BlackClawColors) {
    HorizontalDivider(
        color = colors.divider,
        thickness = 0.5.dp,
        modifier = Modifier.padding(start = 60.dp),
    )
}
