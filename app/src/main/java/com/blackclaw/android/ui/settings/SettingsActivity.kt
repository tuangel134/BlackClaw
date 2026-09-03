package com.blackclaw.android.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material.icons.automirrored.outlined.MenuBook
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
import com.blackclaw.android.automation.AutomationProfileStore
import com.blackclaw.android.automation.AutomationRuleStore
import com.blackclaw.android.tool.ToolRegistry
import com.blackclaw.android.ui.chat.BlackClawColors
import com.blackclaw.android.ui.chat.ThemeManager
import com.blackclaw.android.ui.chat.ThemeManager.toComposeColors
import com.blackclaw.android.ui.design.ClawGlassBackdrop
import com.blackclaw.android.ui.design.ClawGlassCard
import com.blackclaw.android.ui.onboarding.PermissionExplanationDialog
import com.blackclaw.android.ui.onboarding.PermissionOverviewDialog
import com.blackclaw.android.ui.onboarding.PermissionTopic
import com.blackclaw.android.ui.scheduled.ScheduledTasksActivity
import com.blackclaw.android.ui.skills.SkillsActivity
import com.blackclaw.android.ui.tools.ToolBrowserActivity
import com.blackclaw.android.utils.KVUtils

/**
 * Modern Compose settings — replaces the old XML-based SettingsActivity.
 * Layout: gradient hero + grouped section cards + smooth row click ripple.
 */
class SettingsActivity : BaseActivity() {

    // Capability/configuration state is refreshed when this screen becomes visible
    // or when an in-screen action changes it. A permanent 1.5 s polling loop used to
    // recompose the entire settings tree even while the phone was idle on this screen.
    private val tick = mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        window.navigationBarColor = tc.bg
        val colors = with(ThemeManager) { tc.toComposeColors() }

        setContent {
            // Re-evaluate capability state on every tick.
            val tickValue by tick
            val activity = this@SettingsActivity
            val caps by remember(tickValue) {
                mutableStateOf(AppCapabilityCoordinator.snapshot(activity))
            }
            var pendingPermissionTopic by remember { mutableStateOf<PermissionTopic?>(null) }
            var pendingPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
            var showPermissionOverview by remember { mutableStateOf(false) }
            fun explainPermission(topic: PermissionTopic, action: () -> Unit) {
                pendingPermissionTopic = topic
                pendingPermissionAction = action
            }
            ClawGlassBackdrop(colors = colors) {
                ModernSettingsScreen(
                    colors = colors,
                    caps = caps,
                    onBack = { activity.finish() },
                    onOpenOnboarding = { activity.startActivity(Intent(activity, com.blackclaw.android.ui.onboarding.OnboardingActivity::class.java)) },
                    onOpenPermissionOverview = { showPermissionOverview = true },
                    onOpenAccessibility = {
                        explainPermission(PermissionTopic.ACCESSIBILITY) {
                            AppCapabilityCoordinator.openSystemSettings(activity, AppRequirement.ACCESSIBILITY)
                        }
                    },
                    onRequestNotifications = {
                        explainPermission(PermissionTopic.NOTIFICATIONS) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                && !AppCapabilityCoordinator.isNotificationPermissionGranted(activity)) {
                                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
                            }
                        }
                    },
                    onOpenNotificationAccess = {
                        explainPermission(PermissionTopic.NOTIFICATION_ACCESS) {
                            AppCapabilityCoordinator.openSystemSettings(activity, AppRequirement.NOTIFICATION_ACCESS)
                        }
                    },
                    onOpenOverlay = {
                        explainPermission(PermissionTopic.OVERLAY) {
                            AppCapabilityCoordinator.openSystemSettings(activity, AppRequirement.OVERLAY)
                        }
                    },
                    onOpenBattery = {
                        explainPermission(PermissionTopic.BATTERY) {
                            AppCapabilityCoordinator.openSystemSettings(activity, AppRequirement.BATTERY_OPTIMIZATION)
                        }
                    },
                    onOpenStorage = {
                        explainPermission(PermissionTopic.FILES) {
                            AppCapabilityCoordinator.openSystemSettings(activity, AppRequirement.STORAGE)
                        }
                    },
                    onOpenLlmConfig = { activity.startActivity(Intent(activity, LlmConfigActivity::class.java)) },
                    onOpenTheme = { activity.startActivity(Intent(activity, ThemeActivity::class.java)) },
                    onOpenSkills = { activity.startActivity(Intent(activity, SkillsActivity::class.java)) },
                    onOpenScheduled = { activity.startActivity(Intent(activity, ScheduledTasksActivity::class.java)) },
                    onOpenDashboard = { activity.startActivity(Intent(activity, com.blackclaw.android.ui.dashboard.DashboardActivity::class.java)) },
                    onOpenToolBrowser = { activity.startActivity(Intent(activity, ToolBrowserActivity::class.java)) },
                    onOpenAutoReplies = { activity.startActivity(Intent(activity, com.blackclaw.android.ui.autoreply.AutoRepliesActivity::class.java)) },
                    onOpenVoice = { activity.startActivity(Intent(activity, VoiceSettingsActivity::class.java)) },
                    onOpenGuide = { activity.startActivity(Intent(activity, com.blackclaw.android.ui.guide.FeaturesGuideActivity::class.java)) },
                    onOpenProactive = { activity.startActivity(Intent(activity, com.blackclaw.android.ui.assistant.AssistantActivity::class.java)) },
                    onOpenShizuku = { activity.startActivity(Intent(activity, com.blackclaw.android.ui.shizuku.ShizukuSetupActivity::class.java)) },
                    onOpenAdbPro = { activity.startActivity(Intent(activity, com.blackclaw.android.ui.adb.AdbProActivity::class.java)) },
                    onOpenTerminal = { activity.startActivity(Intent(activity, com.blackclaw.android.ui.terminal.TerminalActivity::class.java)) },
                    onOpenSecurity = { activity.startActivity(Intent(activity, com.blackclaw.android.ui.security.SecurityActivity::class.java)) },
                    onOpenMemoryPrivacy = { activity.startActivity(Intent(activity, MemoryPrivacyActivity::class.java)) },
                    onOpenEmergency = { activity.startActivity(Intent(activity, EmergencySettingsActivity::class.java)) },
                    onOpenZimLibrary = { activity.startActivity(Intent(activity, com.blackclaw.android.knowledge.ZimLibraryActivity::class.java)) },
                    onOpenTelegram = { ChannelConfigActivity.start(activity, ChannelConfigActivity.ChannelType.TELEGRAM) },
                    onToggleExternalAutomation = {
                        val newState = !KVUtils.isExternalAutomationEnabled()
                        KVUtils.setExternalAutomationEnabled(newState)
                        tick.value = System.currentTimeMillis()
                    },
                    onReportBug = { SettingsActions.reportBug(activity) },
                    onShareDebugReport = { SettingsActions.shareDebugReport(activity) },
                    onOpenGitHub = {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/tuangel134/BlackClaw")))
                    },
                    onEditGlobalPrompt = { activity.showGlobalPromptDialog() },
                )
            }
            pendingPermissionTopic?.let { topic ->
                PermissionExplanationDialog(
                    topic = topic,
                    colors = colors,
                    onDismiss = {
                        pendingPermissionTopic = null
                        pendingPermissionAction = null
                    },
                    onContinue = {
                        val action = pendingPermissionAction
                        pendingPermissionTopic = null
                        pendingPermissionAction = null
                        action?.invoke()
                    },
                )
            }
            if (showPermissionOverview) {
                PermissionOverviewDialog(colors = colors, onDismiss = { showPermissionOverview = false })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tick.value = System.currentTimeMillis()
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
    onOpenOnboarding: () -> Unit,
    onOpenPermissionOverview: () -> Unit,
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
    onOpenDashboard: () -> Unit,
    onOpenToolBrowser: () -> Unit,
    onOpenAutoReplies: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenProactive: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenAdbPro: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenMemoryPrivacy: () -> Unit,
    onOpenEmergency: () -> Unit,
    onOpenZimLibrary: () -> Unit,
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
    val scheduledCount = remember {
        ScheduledTaskManager.listAll().size + AutomationRuleStore.list().size + AutomationProfileStore.list().size
    }
    val toolCount = remember { ToolRegistry.getInstance().getAllTools().size }
    val autoReplyCount = remember { com.blackclaw.android.autoreply.AutoReplyProfileStore.all().size }
    val activeAutoReplies = remember {
        com.blackclaw.android.autoreply.AutoReplyProfileStore.all().count { it.enabled }
    }
    val externalAutomation = remember { KVUtils.isExternalAutomationEnabled() }
    var remoteMemoryBridge by remember {
        mutableStateOf(com.blackclaw.android.conversation.ConversationRepository.remoteBridgeEnabled)
    }
    val sharedTurnCount = remember { com.blackclaw.android.conversation.ConversationRepository.all().count {
        it.trust == com.blackclaw.android.conversation.ConversationRepository.Trust.LOCAL } }
    val globalPromptLen = remember { KVUtils.getGlobalPrompt().length }
    val memoryItems = remember {
        com.blackclaw.android.memory.MemoryInventory.totalCount()
    }

    Scaffold(
        containerColor = Color.Transparent,
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
                    containerColor = Color.Transparent,
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

            // ── Quick guide ────────────────────────────────────────────────────
            SettingsSection(title = "Empezar", colors = colors) {
                NavRow(
                    icon = Icons.Outlined.Lightbulb,
                    title = "¿Qué puede hacer BlackClaw?",
                    subtitle = "Guía de funciones con ejemplos",
                    trailing = "Ver",
                    trailingHighlight = true,
                    colors = colors,
                    onClick = onOpenGuide,
                )
            }

            // ── Permisos ───────────────────────────────────────────────────────
            SettingsSection(title = "Permisos", colors = colors) {
                NavRow(
                    icon = Icons.Outlined.Info,
                    title = "¿Por qué necesita permisos?",
                    subtitle = "Qué usa, qué se desactiva y cómo trata tus datos",
                    trailing = "Ver todos",
                    trailingHighlight = true,
                    colors = colors,
                    onClick = onOpenPermissionOverview,
                )
                Divider(colors)
                NavRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "Configuración guiada",
                    subtitle = "Activa permisos paso a paso con explicación previa",
                    trailing = "Abrir",
                    trailingHighlight = true,
                    colors = colors,
                    onClick = onOpenOnboarding,
                )
                Divider(colors)
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

            // ── Privacidad ─────────────────────────────────────────────────────
            // Sits next to the model settings, not under "Avanzado": what the app has
            // learned is sent with every prompt, so it belongs beside the thing that
            // sends it — and a privacy control the user cannot find is not a control.
            SettingsSection(title = "Privacidad", colors = colors) {
                NavRow(
                    icon = Icons.Outlined.Shield,
                    title = "Lo que sé de ti",
                    subtitle = "Ver y borrar el perfil aprendido, hechos y resúmenes",
                    trailing = if (memoryItems == 0) "Vacío" else "$memoryItems",
                    trailingHighlight = memoryItems > 0,
                    colors = colors,
                    onClick = onOpenMemoryPrivacy,
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
                    title = "Automatizaciones",
                    subtitle = "Agenda, reglas y flujos creados contigo o con BlackClaw",
                    trailing = if (scheduledCount == 0) "Ninguna" else "$scheduledCount",
                    trailingHighlight = scheduledCount > 0,
                    colors = colors,
                    onClick = onOpenScheduled,
                )
                Divider(colors)
                NavRow(
                    icon = Icons.Outlined.Insights,
                    title = "Actividad del agente",
                    subtitle = "Tareas, herramientas y uso reciente",
                    trailing = "Ver",
                    colors = colors,
                    onClick = onOpenDashboard,
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
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = "Biblioteca offline ZIM",
                    subtitle = "Consulta Wikipedia y otras bibliotecas sin internet",
                    trailing = "Abrir",
                    trailingHighlight = true,
                    colors = colors,
                    onClick = onOpenZimLibrary,
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
                val voiceOn = com.blackclaw.android.assistant.VoiceInputManager.wakeEnabled
                val voiceReady = com.blackclaw.android.assistant.VoskModelManager.isReady()
                val assistantStatus = com.blackclaw.android.ui.assist.AssistantRole.status(
                    androidx.compose.ui.platform.LocalContext.current
                )
                NavRow(
                    icon = Icons.Outlined.Mic,
                    title = "Modo voz (manos libres)",
                    subtitle = when {
                        assistantStatus.needsRepair -> "Asistente desincronizado — toca para reparar"
                        voiceReady -> "Di 'garra' + tu orden — offline, sin beep"
                        else -> "Activación por voz"
                    },
                    trailing = when {
                        assistantStatus.needsRepair -> "Reparar"
                        voiceOn -> "Activado"
                        else -> "Desactivado"
                    },
                    trailingHighlight = voiceOn && !assistantStatus.needsRepair,
                    colors = colors,
                    onClick = onOpenVoice,
                )
            }

            // ── Avanzado ───────────────────────────────────────────────────────
            val ctx = androidx.compose.ui.platform.LocalContext.current
            SettingsSection(title = "Avanzado", colors = colors) {
                NavRow(
                    icon = Icons.Outlined.Warning,
                    title = "Modo emergencia",
                    subtitle = "Contacto de confianza, ubicación, SMS y evidencia de audio visible",
                    trailing = if (com.blackclaw.android.emergency.EmergencyConfig.isReady) "Listo" else "Configurar",
                    trailingHighlight = com.blackclaw.android.emergency.EmergencyConfig.isReady,
                    colors = colors,
                    onClick = onOpenEmergency,
                )
                Divider(colors)
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
                Divider(colors)
                var terminalOn by remember {
                    mutableStateOf(com.blackclaw.android.terminal.TerminalConfig.enabled)
                }
                SwitchRow(
                    icon = Icons.Outlined.Code,
                    title = "Terminal interno",
                    subtitle = "Linux fijo con bash, Python, Git y curl sin Shizuku ni ADB. " +
                        "La consola manual conserva el Modo Pro opcional.",
                    checked = terminalOn,
                    colors = colors,
                ) { on ->
                    terminalOn = on
                    com.blackclaw.android.terminal.TerminalConfig.enabled = on
                }
                if (terminalOn) {
                    Divider(colors)
                    NavRow(
                        icon = Icons.Outlined.Code,
                        title = "Abrir terminal",
                        subtitle = "Linux local; sesión separada de la IA",
                        colors = colors,
                        onClick = onOpenTerminal,
                    )
                }
                Divider(colors)
                var securityOn by remember {
                    mutableStateOf(com.blackclaw.android.security.SecurityConfig.enabled)
                }
                SwitchRow(
                    icon = Icons.Outlined.Bolt,
                    title = "Seguridad (antimalware)",
                    subtitle = "Detecta apps riesgosas y bloquea las que te llenan de anuncios " +
                        "(revoca superposición, fuerza detención o desinstala).",
                    checked = securityOn,
                    colors = colors,
                ) { on ->
                    securityOn = on
                    com.blackclaw.android.security.SecurityConfig.enabled = on
                }
                if (securityOn) {
                    Divider(colors)
                    NavRow(
                        icon = Icons.Outlined.Bolt,
                        title = "Abrir seguridad",
                        subtitle = "Escanea apps y bloquea anuncios",
                        colors = colors,
                        onClick = onOpenSecurity,
                    )
                }
                Divider(colors)
                var fastPath by remember {
                    mutableStateOf(KVUtils.getBoolean("cfg_fast_path", true))
                }
                SwitchRow(
                    icon = Icons.Outlined.Bolt,
                    title = "Atajos rápidos (sin IA)",
                    subtitle = "Ejecuta comandos simples (abrir app, poner música, navegar) al " +
                        "instante sin usar el modelo. Apágalo para que todo pase por la IA.",
                    checked = fastPath,
                    colors = colors,
                ) { on ->
                    fastPath = on
                    KVUtils.putBoolean("cfg_fast_path", on); KVUtils.sync()
                }
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
                Divider(colors)
                SwitchRow(
                    icon = Icons.Outlined.Security,
                    title = "Compartir memoria local con canales remotos",
                    subtitle = "Apagado por seguridad · $sharedTurnCount turnos locales. Al activarlo, " +
                        "cada contacto remoto sigue aislado, pero puede usar contexto local reciente.",
                    checked = remoteMemoryBridge,
                    colors = colors,
                ) { enabled ->
                    remoteMemoryBridge = enabled
                    com.blackclaw.android.conversation.ConversationRepository.remoteBridgeEnabled = enabled
                }
            }

            // ── Acerca de ──────────────────────────────────────────────────────
            SettingsSection(title = "Acerca de", colors = colors) {
                NavRow(
                    icon = Icons.Outlined.Info,
                    title = "BlackClaw",
                    subtitle = "v${BuildConfig.VERSION_NAME} · Beta · toca para buscar actualizaciones",
                    trailing = "v${BuildConfig.VERSION_NAME}",
                    colors = colors,
                    onClick = {
                        (ctx as? android.app.Activity)?.let {
                            com.blackclaw.android.utils.AppUpdater.checkForUpdate(it, force = true)
                        }
                    },
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
        ClawGlassCard(
            colors = colors,
            modifier = Modifier.fillMaxWidth(),
            radius = 20.dp,
            elevated = false,
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
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    colors: BlackClawColors,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
            if (subtitle != null) {
                Text(subtitle, fontSize = 11.sp, color = colors.textTertiary, lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = colors.accent),
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
