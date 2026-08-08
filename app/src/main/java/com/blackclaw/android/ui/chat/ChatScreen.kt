package com.blackclaw.android.ui.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackclaw.android.R
import com.blackclaw.android.cards.AssistCardCodec
import com.blackclaw.android.ui.cards.AssistCardList
import com.blackclaw.android.ui.cards.AssistCardSkin
import com.blackclaw.android.ui.design.ClawAnimation
import com.blackclaw.android.ui.design.ClawMotion
import com.blackclaw.android.ui.design.ClawPalette
import com.blackclaw.android.utils.XLog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BlackClaw Chat Screen — Jetpack Compose
 * Inspired by WhatsApp/Telegram/Slack dark theme
 */

// ======================== THEME COLORS ========================

data class BlackClawColors(
    val background: Color,
    val surface: Color,
    val userBubble: Color,
    val userText: Color,
    val aiBubble: Color,
    val aiBubbleBorder: Color,
    val aiText: Color,
    val avatar: Color,
    val accent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val inputBorder: Color,
)

val AbyssDark = BlackClawColors(
    background = Color(0xFF0A0A0F),
    surface = Color(0xFF0F0F1A),
    userBubble = Color(0xFF0066CC),
    userText = Color(0xFFF0F8FF),
    aiBubble = Color(0xFF141420),
    aiBubbleBorder = Color(0xFF1E1E30),
    aiText = Color(0xFFC8D0E8),
    avatar = Color(0xFF0055AA),
    accent = Color(0xFF00D4FF),
    textPrimary = Color(0xFFC8D0E8),
    textSecondary = Color(0xFF7A80A0),
    textTertiary = Color(0xFF3A3A55),
    divider = Color(0xFF0F0F1A),
    inputBorder = Color(0xFF1E1E30),
)

private fun Modifier.dismissKeyboardOnBackgroundTap(onDismissKeyboard: () -> Unit): Modifier =
    pointerInput(onDismissKeyboard) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val up = waitForUpOrCancellation()
            if (up != null) {
                onDismissKeyboard()
            }
        }
    }

// ======================== MAIN SCREEN ========================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    modelStatus: String,
    needsPermission: Boolean,
    isAwaitingReply: Boolean,
    isTaskRunning: Boolean,
    isDownloading: Boolean = false,
    downloadProgress: Int = 0,
    isLocalModel: Boolean = true,
    sessionTokens: Int = 0,
    sessionCost: Double = 0.0,
    onSendChat: (String) -> Unit,
    onSendTask: (String) -> Unit,
    onStartMonitor: (MonitorTargetSpec) -> Unit = {},
    onSendDirectMessage: (contact: String, app: String, message: String) -> Unit = { _, _, _ -> },
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenAutoReplies: () -> Unit = {},
    onOpenAssistant: () -> Unit = {},
    onFixPermissions: () -> Unit,
    onAttach: () -> Unit,
    conversations: List<ChatHistoryManager.ConversationSummary>,
    onSelectConversation: (ChatHistoryManager.ConversationSummary) -> Unit,
    onDeleteConversation: (ChatHistoryManager.ConversationSummary) -> Unit = {},
    onRenameConversation: (ChatHistoryManager.ConversationSummary, String) -> Unit = { _, _ -> },
    activeTasks: List<String> = emptyList(),
    onStopTask: (String) -> Unit = {},
    onStopAllTasks: () -> Unit = {},
    inputEnabled: Boolean = true,
    onModelSwitch: (modelId: String, displayName: String) -> Unit = { _, _ -> },
    colors: BlackClawColors = AbyssDark,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissKeyboard = {
        keyboardController?.hide()
        focusManager.clearFocus()
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Shared state for prompt chip → input bar prefill
    var prefillText by remember { mutableStateOf("") }
    var prefillIsTask by remember { mutableStateOf(false) }
    // Task mode state — lifted here so content area can react
    var isTaskMode by remember { mutableStateOf(false) }
    // Local/Cloud tab — controls UI presentation AND triggers model switch.
    // Keep the tab aligned with the actual active model so returning from
    // Settings/model changes cannot leave the toolbar UI out of sync.
    var selectedTab by remember { mutableStateOf(if (isLocalModel) "local" else "cloud") }
    val isLocalUI = selectedTab == "local"
    // Skill dialog and activation states
    var showMonitorSheet by remember { mutableStateOf(false) }
    var showSendSheet by remember { mutableStateOf(false) }
    var showChatsSheet by remember { mutableStateOf(false) }
    var activatingSkill by remember { mutableStateOf<String?>(null) }

    // Chat mode is always the default — user can switch to Task manually

    // When activating finishes (2s animation), clear state
    LaunchedEffect(activatingSkill) {
        if (activatingSkill != null) {
            kotlinx.coroutines.delay(2000)
            activatingSkill = null
        }
    }

    LaunchedEffect(isLocalModel) {
        selectedTab = if (isLocalModel) "local" else "cloud"
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = colors.surface,
            ) {
                AssistantPanel(
                    onOpenHub = {
                        scope.launch { drawerState.close() }
                        onOpenAssistant()
                    },
                    onSettings = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    onModels = {
                        scope.launch { drawerState.close() }
                        onOpenModels()
                    },
                    onAutoReplies = {
                        scope.launch { drawerState.close() }
                        onOpenAutoReplies()
                    },
                    colors = colors,
                )
            }
        }
    ) {
        Scaffold(
            containerColor = colors.background,
            topBar = {
                Column(
                    modifier = Modifier.dismissKeyboardOnBackgroundTap(dismissKeyboard)
                ) {
                    ChatTopBar(
                        modelStatus = modelStatus,
                        sessionTokens = sessionTokens,
                        sessionCost = sessionCost,
                        isLocalModel = isLocalModel,
                        selectedTab = selectedTab,
                        onTabChange = { tab ->
                            selectedTab = tab
                            val kvUtils = com.blackclaw.android.utils.KVUtils
                            if (tab == "cloud") {
                                // Check if cloud default model is configured
                                if (kvUtils.hasDefaultCloudModel()) {
                                    val modelId = kvUtils.getDefaultCloudModel()
                                    val provider = com.blackclaw.android.agent.CloudProvider.fromName(
                                        kvUtils.getDefaultCloudProvider().ifBlank { kvUtils.getLlmProvider() }
                                    )
                                    val displayName = provider.models.find { it.id == modelId }?.displayName ?: modelId
                                    onModelSwitch(modelId, displayName)
                                } else {
                                    // No cloud model configured — signal "no model" state
                                    com.blackclaw.android.utils.XLog.i("ChatScreen", "Cloud tab: no default cloud model configured")
                                    onModelSwitch("NONE", "")
                                }
                            } else {
                                // Check if local default model is configured
                                if (kvUtils.hasDefaultLocalModel()) {
                                    val localPath = kvUtils.getLocalModelPath()
                                    val name = java.io.File(localPath).nameWithoutExtension
                                        .replace("-", " ").replace("_", " ")
                                    onModelSwitch("LOCAL", name)
                                } else {
                                    // No local model configured — signal "no model" state
                                    com.blackclaw.android.utils.XLog.i("ChatScreen", "Local tab: no default local model configured")
                                    onModelSwitch("NONE", "")
                                }
                            }
                        },
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onSettings = onOpenSettings,
                        onModelSwitch = onModelSwitch,
                        onChats = { showChatsSheet = true },
                        colors = colors,
                    )
                    if (activeTasks.isNotEmpty()) {
                        ActiveTaskBar(
                            tasks = activeTasks,
                            onStopTask = onStopTask,
                            onStopAll = onStopAllTasks,
                            colors = colors,
                        )
                    }
                }
            },
            bottomBar = {
                if (!isDownloading) {
                    Column(
                        modifier = Modifier.imePadding()
                    ) {
                        ChatInputBar(
                            isAwaitingReply = isAwaitingReply,
                            isTaskRunning = isTaskRunning,
                            inputEnabled = inputEnabled,
                            isTaskMode = isTaskMode,
                            isLocalModel = isLocalUI,
                            onTaskModeChange = { isTaskMode = it },
                            onSendChat = onSendChat,
                            onSendTask = onSendTask,
                            onStopAll = onStopAllTasks,
                            onAttach = onAttach,
                            onOpenMonitor = { showMonitorSheet = true },
                            onOpenSendMessage = { showSendSheet = true },
                            colors = colors,
                            prefillText = prefillText,
                            prefillIsTask = prefillIsTask,
                            onPrefillConsumed = { prefillText = "" },
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .dismissKeyboardOnBackgroundTap(dismissKeyboard)
            ) {
                // Subtle ambient background — gradient from accent-tinted top
                // fading into surface, plus a soft radial glow behind the messages.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    colors.accent.copy(alpha = 0.04f),
                                    colors.background,
                                    colors.background,
                                )
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    colors.accent.copy(alpha = 0.08f),
                                    androidx.compose.ui.graphics.Color.Transparent,
                                ),
                                radius = 600f,
                            )
                        )
                )
                if (!isDownloading) {
                    // v9: always show messages or empty state regardless of mode
                    val userMessages = messages.filter { it.role != ChatMessage.Role.SYSTEM }
                    if (userMessages.isEmpty()) {
                        EmptyStateWithPrompts(
                            isLocalModel = isLocalUI,
                            onSelectPrompt = { text, isTask ->
                                prefillText = text
                                prefillIsTask = isTask
                                if (isTask && isLocalUI) isTaskMode = true
                            },
                            colors = colors,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        MessageList(
                            messages = messages,
                            colors = colors,
                            onBackgroundTap = dismissKeyboard,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // Download blocking overlay
                if (isDownloading) {
                    DownloadOverlay(progress = downloadProgress, colors = colors)
                }
            }
        }
    }

    // Monitor skill dialog
    if (showMonitorSheet) {
        MonitorDialog(
            onDismiss = { showMonitorSheet = false },
            onStart = { target ->
                showMonitorSheet = false
                activatingSkill = "monitor"
                onStartMonitor(target)
            },
            colors = colors,
        )
    }

    // Send Message skill dialog
    if (showSendSheet) {
        SendMessageDialog(
            onDismiss = { showSendSheet = false },
            onSend = { contact, app, message ->
                showSendSheet = false
                onSendDirectMessage(contact, app, message)
            },
            colors = colors,
        )
    }

    // Chats list sheet — conversation list moved here from the drawer.
    if (showChatsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showChatsSheet = false },
            containerColor = colors.surface,
        ) {
            SidebarContent(
                conversations = conversations,
                onNewChat = { showChatsSheet = false; onNewChat() },
                onSelectConversation = { showChatsSheet = false; onSelectConversation(it) },
                onDeleteConversation = onDeleteConversation,
                onRenameConversation = onRenameConversation,
                onSettings = { showChatsSheet = false; onOpenSettings() },
                onModels = { showChatsSheet = false; onOpenModels() },
                onAutoReplies = { showChatsSheet = false; onOpenAutoReplies() },
                onAssistant = { showChatsSheet = false; onOpenAssistant() },
                colors = colors,
            )
        }
    }
}

// ======================== TOP BAR ========================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    modelStatus: String,
    sessionTokens: Int = 0,
    sessionCost: Double = 0.0,
    isLocalModel: Boolean = true,
    selectedTab: String,
    onTabChange: (String) -> Unit,
    onMenuClick: () -> Unit,
    onSettings: () -> Unit,
    onModelSwitch: (modelId: String, displayName: String) -> Unit = { _, _ -> },
    onChats: () -> Unit = {},
    colors: BlackClawColors,
) {
    // Cost escalation: quiet → noted → warned → alarming.
    //
    // Taken from the app's semantic accents rather than four loose hex literals, so the
    // warning steps match every other warning in the product. Deliberately NOT themed:
    // this is telling the user their bill is growing, and that meaning must not change
    // because they picked the green theme.
    val tokenColor = when {
        sessionTokens < 5_000 -> colors.textTertiary
        sessionTokens < 15_000 -> ClawPalette.Note.base
        sessionTokens < 25_000 -> ClawPalette.Alarm.light
        else -> ClawPalette.Danger.light
    }

    Column {
        var showModelMenu by remember { mutableStateOf(false) }
        // CloudProvider contains only the offline seed for Zen. Keep an observable
        // copy of the live catalog so a refresh changes this picker immediately.
        var zenModels by remember { mutableStateOf(com.blackclaw.android.agent.OpenCodeZenModels.models()) }
        var refreshingZenModels by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Claw icon accent dot
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colors.accent),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = colors.textPrimary)) {
                                append("Black")
                            }
                            withStyle(SpanStyle(color = colors.accent)) {
                                append("Claw")
                            }
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onMenuClick) {
                    Icon(Icons.Default.Menu, contentDescription = "Menú")
                }
            },
            actions = {
                // Chats selector — opens the conversation list sheet.
                Surface(
                    onClick = onChats,
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.ChatBubbleOutline,
                            contentDescription = null,
                            tint = colors.textTertiary,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("Chats", fontSize = 12.sp, color = colors.textTertiary)
                    }
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Ajustes")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colors.surface,
                titleContentColor = colors.textPrimary,
                navigationIconContentColor = colors.textPrimary,
                actionIconContentColor = colors.textSecondary,
            ),
        )

        // Model status + scope switch + dropdown.
        //
        // The Local/Cloud switch lives on this row, not in the app bar above. In the bar
        // it had to share a single line with the wordmark, the drawer button, "Chats" and
        // the settings gear, and on a phone-width screen it simply did not fit — it
        // overlapped the drawer button and pushed the title and the settings gear off the
        // edge entirely. Here it also sits next to the thing it actually governs: it
        // decides which set of models the picker on the left offers.
        Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(start = 16.dp, end = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showModelMenu = true }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = modelStatus,
                    fontSize = 11.sp,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Shrinks before the token cost does: a truncated model name is
                    // recoverable from the picker, a truncated bill is not.
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.UnfoldMore,
                    contentDescription = "Cambiar modelo",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(12.dp),
                )
                if (sessionTokens > 0 && !isLocalModel) {
                    val formattedTokens = if (sessionTokens >= 1000) {
                        String.format("%.1fK", sessionTokens / 1000.0)
                    } else {
                        "$sessionTokens"
                    }
                    val costText = if (sessionCost < 0.01) "< $0.01" else "$${String.format("%.2f", sessionCost)}"
                    val tokenSuffix = if (sessionCost > 0) {
                        " · $formattedTokens tokens · $costText"
                    } else {
                        " · $formattedTokens tokens"
                    }
                    Text(
                        text = tokenSuffix,
                        fontSize = 11.sp,
                        color = tokenColor,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            ModelScopeSwitch(
                selectedTab = selectedTab,
                onTabChange = onTabChange,
                colors = colors,
            )
        }
            // Model switcher dropdown — only show configured/downloaded models
            DropdownMenu(
                expanded = showModelMenu,
                onDismissRequest = { showModelMenu = false },
            ) {
                val kvUtils = com.blackclaw.android.utils.KVUtils
                val currentModel = kvUtils.getLlmModelName()

                if (selectedTab == "cloud") {
                    // Resolve by persisted provider, not URL. Custom URLs and a
                    // trailing slash used to make Zen fall back to static OpenAI cards.
                    val activeCloud = com.blackclaw.android.agent.llm.ModelConfigRepository.snapshot().activeCloud
                    val activeProvider = activeCloud.provider
                    if (activeCloud.isConfigured) {
                        val modelsToShow = if (activeProvider == com.blackclaw.android.agent.CloudProvider.OPENCODE_ZEN) {
                            zenModels
                        } else {
                            activeProvider.models
                        }
                        if (activeProvider == com.blackclaw.android.agent.CloudProvider.OPENCODE_ZEN) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (refreshingZenModels) "Actualizando modelos gratis…" else "↻ Actualizar modelos gratis",
                                        fontSize = 13.sp,
                                        color = colors.accent,
                                    )
                                },
                                enabled = !refreshingZenModels,
                                onClick = {
                                    refreshingZenModels = true
                                    com.blackclaw.android.agent.OpenCodeZenModels.refreshNow {
                                        // Zen refreshes on its worker thread; Compose state
                                        // must be changed from the composition's main scope.
                                        scope.launch {
                                            zenModels = com.blackclaw.android.agent.OpenCodeZenModels.models()
                                            refreshingZenModels = false
                                        }
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                        modelsToShow.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            model.displayName,
                                            fontSize = 13.sp,
                                            fontWeight = if (model.id == currentModel && !isLocalModel) FontWeight.Bold else FontWeight.Normal,
                                        )
                                        if (model.id == currentModel && !isLocalModel) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("✓", fontSize = 12.sp, color = colors.accent)
                                        }
                                    }
                                },
                                onClick = {
                                    showModelMenu = false
                                    onModelSwitch(model.id, model.displayName)
                                },
                            )
                        }
                    } else {
                        // No API key configured
                        DropdownMenuItem(
                            text = { Text("API key no configurada", fontSize = 13.sp, color = colors.textTertiary) },
                            onClick = { showModelMenu = false; onSettings() },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Configurar API key…", fontSize = 13.sp, color = colors.accent) },
                        onClick = { showModelMenu = false; onSettings() },
                    )
                } else {
                    // Local models: downloaded models
                    val localPath = kvUtils.getLocalModelPath()
                    if (localPath.isNotEmpty() && java.io.File(localPath).exists()) {
                        val localName = java.io.File(localPath).nameWithoutExtension
                            .replace("-", " ").replace("_", " ")
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("$localName (On-device)", fontSize = 13.sp,
                                        fontWeight = if (isLocalModel) FontWeight.Bold else FontWeight.Normal)
                                    if (isLocalModel) {
                                        Spacer(Modifier.width(6.dp))
                                        Text("✓", fontSize = 12.sp, color = colors.accent)
                                    }
                                }
                            },
                            onClick = {
                                showModelMenu = false
                                onModelSwitch("LOCAL", localName)
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("No hay modelo local descargado", fontSize = 13.sp, color = colors.textTertiary) },
                            onClick = { showModelMenu = false; onSettings() },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Descargar modelos…", fontSize = 13.sp, color = colors.accent) },
                        onClick = { showModelMenu = false; onSettings() },
                    )
                }
            }
        }
        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
    }
}

/**
 * Local / Cloud switch, as one segmented control rather than two loose buttons.
 *
 * The pair used to be two independent `Surface`s that each toggled their own border, so
 * nothing said they were alternatives to each other — it read as two unrelated buttons
 * where exactly one happened to be outlined.
 *
 * The selected side is drawn by a single pill that animates between the two halves. That
 * movement is what communicates "these are the two options and you are on this one";
 * cross-fading two backgrounds cannot say that.
 */
@Composable
private fun ModelScopeSwitch(
    selectedTab: String,
    onTabChange: (String) -> Unit,
    colors: BlackClawColors,
) {
    val options = listOf("local" to "Local", "cloud" to "Cloud")
    val selectedIndex = options.indexOfFirst { it.first == selectedTab }.coerceAtLeast(0)

    // Fixed segment width, deliberately. The first version measured the available space
    // with BoxWithConstraints and used half of it — but a Row inside a top app bar hands
    // its children essentially unbounded width, so "half of the maximum" resolved to an
    // enormous number and the control swallowed the entire bar. A control this small
    // should state its own size rather than ask.
    val segment = 58.dp
    val segmentHeight = 30.dp
    val slide by animateDpAsState(
        targetValue = segment * selectedIndex,
        animationSpec = ClawMotion.gentleSpring(),
        label = "scopeSlide",
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.background.copy(alpha = 0.75f))
            .border(0.5.dp, colors.inputBorder, RoundedCornerShape(10.dp))
            .padding(2.dp),
    ) {
        // The pill is drawn first so the labels sit on top of it.
        Box(
            modifier = Modifier
                .offset(x = slide)
                .width(segment)
                .height(segmentHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.accent.copy(alpha = 0.16f))
                .border(1.dp, colors.accent.copy(alpha = 0.55f), RoundedCornerShape(8.dp)),
        )
        Row {
            options.forEachIndexed { index, (id, label) ->
                Box(
                    modifier = Modifier
                        .width(segment)
                        .height(segmentHeight)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onTabChange(id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        fontSize = 12.sp,
                        fontWeight = if (index == selectedIndex) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (index == selectedIndex) colors.accent else colors.textTertiary,
                    )
                }
            }
        }
    }
}

// ======================== MESSAGE LIST ========================

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    colors: BlackClawColors,
    onBackgroundTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Index of the 1 dp anchor emitted after the last bubble. Scrolling to a message
    // index puts that message's FIRST line at the top of the viewport, which during
    // streaming means watching the top of a reply while the part being written stays
    // off-screen. The anchor cannot be over-scrolled, so a request to reach it clamps
    // to "the end of the content is visible" — which is what following a reply means.
    val anchorIndex = messages.size

    // True while the newest bubble is on screen. Derived from layout rather than from a
    // flag we maintain, so it cannot drift out of sync with where the list actually is.
    val followingTail by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 2
        }
    }

    // A new bubble always wins the scroll: either the user just sent something or a
    // reply just started, and in both cases they want to see it.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(anchorIndex)
    }

    // Growth of the last bubble — the streaming case, which the old code missed
    // entirely because it only watched `messages.size`.
    //
    // Instant rather than animated: one animation per token queues up and stutters.
    // Suppressed unless the tail is already in view, so scrolling up to re-read
    // history is not undone by the next arriving token.
    val tailLength = messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(tailLength) {
        if (messages.isNotEmpty() && followingTail) listState.scrollToItem(anchorIndex)
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(onBackgroundTap) {
                detectTapGestures(onTap = { onBackgroundTap() })
            },
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        // No `key` on purpose. The transcript is append-only and the tail is mutated in
        // place, so position already is stable identity. A key derived from content
        // would be actively harmful: it would change on every streamed token and make
        // the list discard and rebuild the bubble being written.
        itemsIndexed(messages) { index, message ->
            AnimatedMessageWrapper(animate = index == messages.lastIndex) {
                when (message.role) {
                    ChatMessage.Role.USER -> UserBubble(message.content, message.timestamp, colors)
                    ChatMessage.Role.ASSISTANT -> AssistantBubble(message.content, message.timestamp, colors, message.modelName)
                    ChatMessage.Role.SYSTEM -> SystemMessage(message.content, colors)
                    ChatMessage.Role.TOOL_GROUP -> ToolGroup(message, colors)
                    ChatMessage.Role.CARDS -> CardRow(message.content, colors)
                }
            }
        }
        item { Spacer(Modifier.height(1.dp)) }
    }
}

/**
 * Structured tool results, drawn as cards in the transcript.
 *
 * Indented like an assistant bubble so it reads as part of the reply rather than as a
 * separate speaker. The payload is decoded here and an unreadable one yields nothing:
 * the reply text is already on screen, so a bad payload should cost the user a card, not
 * an error.
 */
@Composable
private fun CardRow(payload: String, colors: BlackClawColors) {
    val cards = remember(payload) { AssistCardCodec.decode(payload) }
    if (cards.isEmpty()) return
    // Built from the active theme rather than the card layer's own palette, so a card in
    // the chat belongs to whichever of the ten themes the user picked.
    val skin = remember(colors) {
        AssistCardSkin(
            surface = colors.aiBubble,
            surfaceRaised = colors.surface,
            outline = colors.aiBubbleBorder,
            textPrimary = colors.textPrimary,
            textSecondary = colors.textSecondary,
            textTertiary = colors.textTertiary,
            accent = colors.accent,
            onAccent = colors.userText,
            price = ClawPalette.Finance.base,
        )
    }
    AssistCardList(
        cards = cards,
        skin = skin,
        modifier = Modifier.padding(start = 54.dp, end = 20.dp, top = 3.dp, bottom = 3.dp),
    )
}

/**
 * Fade-and-rise for the newest bubble. Older ones render instantly so scrolling back
 * through a long conversation stays snappy.
 */
@Composable
private fun AnimatedMessageWrapper(animate: Boolean, content: @Composable () -> Unit) {
    if (!animate || ClawAnimation.reduceMotion()) {
        content()
        return
    }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    // One driver for both properties. Two independent animations of the same entrance
    // can desync, and the shared value also makes the timing obvious.
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = ClawMotion.enterTween(),
        label = "msgEnter",
    )
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress
            // dp, not raw pixels. The previous 16f was 16 *pixels*, which on this
            // phone's density is about 4 dp — an animation too small to perceive.
            translationY = (1f - progress) * 16.dp.toPx()
        }
    ) {
        content()
    }
}

// ======================== BC AVATAR ========================

/**
 * Reusable BlackClaw avatar composable.
 * Renders a rounded box with the "BC" initials in the accent color.
 * Replaces the old PNG blackclaw_avatar.png everywhere in the chat UI.
 */
@Composable
private fun BCAvatar(
    size: androidx.compose.ui.unit.Dp,
    cornerRadius: androidx.compose.ui.unit.Dp,
    colors: BlackClawColors,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(colors.avatar),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "BC",
            color = colors.accent,
            fontSize = (size.value * 0.28f).sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        )
    }
}

// ======================== BUBBLES ========================

@Composable
private fun UserBubble(text: String, timestamp: Long, colors: BlackClawColors) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 64.dp, end = 14.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            // Box rather than Surface because Surface takes a colour, not a Brush.
            // The gradient is derived by leaning the bubble slightly toward the theme
            // accent, so all ten themes get a matching sheen instead of one hardcoded
            // pair of blues that would only look right on the default.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                lerp(colors.userBubble, colors.accent, 0.20f),
                                colors.userBubble,
                            )
                        )
                    )
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        color = colors.userText,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
        Text(
            text = formatBubbleTimestamp(timestamp),
            fontSize = 9.sp,
            color = colors.textTertiary,
            modifier = Modifier
                .align(Alignment.End)
                .padding(end = 6.dp, top = 1.dp, bottom = 2.dp),
        )
    }
}

/**
 * The assistant's reply.
 *
 * The body goes through [ChatMarkdownText]: models format their answers, and until now
 * the bubble showed the source, so every `**bold**`, heading, list and fenced block
 * arrived as literal punctuation.
 *
 * `"..."` stays a sentinel for "still thinking" because that is what the controllers
 * emit; it is checked before parsing so the placeholder never reaches the renderer.
 */
@Composable
private fun AssistantBubble(text: String, timestamp: Long, colors: BlackClawColors, modelName: String? = null) {
    val isThinking = text == ChatMessage.PENDING
    val clipboard = LocalClipboardManager.current
    var copied by remember(timestamp) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1600)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 48.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.Start,
            // Top, not Bottom: a formatted reply can be many blocks tall, and an avatar
            // floating beside its last line reads as belonging to the wrong message.
            verticalAlignment = if (isThinking) Alignment.Bottom else Alignment.Top,
        ) {
            BCAvatar(size = 32.dp, cornerRadius = 16.dp, colors = colors)
            Spacer(Modifier.width(8.dp))

            Surface(
                color = colors.aiBubble,
                shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.aiBubbleBorder),
            ) {
                if (isThinking) {
                    TypingIndicator(
                        color = colors.accent,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    )
                } else {
                    // SelectionContainer rather than a long-press gesture: the message
                    // list already uses tap-to-dismiss-keyboard, and a competing
                    // long-press handler on every bubble would fight it. This also lets
                    // the user take part of a reply, not just all of it.
                    SelectionContainer {
                        ChatMarkdownText(
                            raw = text,
                            colors = colors,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
        if (!isThinking) {
            val footer = listOfNotNull(
                modelName?.takeIf { it.isNotBlank() },
                formatBubbleTimestamp(timestamp),
            ).joinToString(" · ")
            Row(
                modifier = Modifier.padding(start = 40.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = footer, fontSize = 9.sp, color = colors.textTertiary)
                Spacer(Modifier.width(8.dp))
                // A visible affordance beats a hidden gesture: before this there was no
                // way at all to get a reply out of the app.
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(text))
                            copied = true
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = if (copied) "Copiado" else "Copiar respuesta",
                        tint = if (copied) colors.accent else colors.textTertiary,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        if (copied) "Copiado" else "Copiar",
                        fontSize = 9.sp,
                        color = if (copied) colors.accent else colors.textTertiary,
                    )
                }
            }
        }
    }
}

private fun formatBubbleTimestamp(timestamp: Long): String {
    val pattern = if (DateUtils.isToday(timestamp)) "h:mm a" else "MMM d, h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}

@Composable
private fun TypingIndicator(color: Color, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val dots = listOf(0, 1, 2)

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        dots.forEach { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 200),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun SystemMessage(text: String, colors: BlackClawColors) {
    Text(
        text = text,
        color = colors.textTertiary,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 6.dp),
    )
}

/**
 * What the agent did, as a run of steps.
 *
 * Given a container and a rail rather than left as loose grey lines: these sit between
 * conversational bubbles, and without a boundary a run of six tool steps reads as the
 * assistant having sent six separate cryptic messages.
 *
 * The tool name is monospaced to mark it as a machine action rather than something the
 * model said. `success` is shown by the dot only, because it is the one thing this
 * screen actually knows about a step.
 */
@Composable
private fun ToolGroup(message: ChatMessage, colors: BlackClawColors) {
    val steps = message.toolSteps.orEmpty()
    if (steps.isEmpty()) return

    Column(
        modifier = Modifier.padding(start = 54.dp, end = 48.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(colors.aiBubble.copy(alpha = 0.6f))
                .padding(start = 2.dp),
        ) {
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                Box(
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(colors.accent.copy(alpha = 0.35f)),
                )
                Column(Modifier.padding(start = 9.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)) {
                    steps.forEach { step ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 1.5.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (step.success) colors.accent
                                        else colors.textTertiary
                                    ),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(
                                        SpanStyle(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = colors.textSecondary,
                                        )
                                    ) { append(step.toolName) }
                                    withStyle(SpanStyle(color = colors.textTertiary)) {
                                        append("  ${step.summary}")
                                    }
                                },
                                fontSize = 11.5.sp,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ======================== INPUT BAR ========================

@Composable
private fun ChatInputBar(
    isAwaitingReply: Boolean,
    isTaskRunning: Boolean,
    inputEnabled: Boolean = true,
    isTaskMode: Boolean,
    isLocalModel: Boolean,
    onTaskModeChange: (Boolean) -> Unit,
    onSendChat: (String) -> Unit,
    onSendTask: (String) -> Unit,
    onStopAll: () -> Unit = {},
    onAttach: () -> Unit,
    onOpenMonitor: () -> Unit = {},
    onOpenSendMessage: () -> Unit = {},
    colors: BlackClawColors,
    prefillText: String = "",
    prefillIsTask: Boolean = false,
    onPrefillConsumed: () -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    // Voice input — Android system RecognizerIntent, no RECORD_AUDIO needed (system dialog
    // handles its own permission). Appends transcript to current text instead of replacing,
    // so users can prefix with typed context.
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        XLog.d("VoiceInput", "voiceLauncher result: resultCode=${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
            if (spokenText != null) {
                XLog.i("VoiceInput", "transcript received: ${spokenText.length} chars, currentText.len=${text.length}")
                val prefix = when {
                    text.isBlank() -> ""
                    text.endsWith(" ") -> text
                    else -> "$text "
                }
                text = prefix + spokenText
            } else {
                XLog.w("VoiceInput", "transcript empty or missing from result data")
                Toast.makeText(context, R.string.voice_input_error, Toast.LENGTH_SHORT).show()
            }
        } else {
            XLog.d("VoiceInput", "voice input cancelled by user (resultCode != OK)")
        }
    }

    // Consume prefill from prompt chips
    LaunchedEffect(prefillText) {
        if (prefillText.isNotEmpty()) {
            text = prefillText
            onTaskModeChange(prefillIsTask)
            onPrefillConsumed()
        }
    }

    val taskMode = isTaskMode

    // Task mode tints the composer toward the theme accent. It used to use a hardcoded
    // warm brown (0xFF1A1410), which only made sense beside an amber accent and read as
    // a rendering fault on the other nine themes. Animated so the switch is legible as
    // a change of mode rather than an instant repaint.
    val barBackground by animateColorAsState(
        if (taskMode) lerp(colors.surface, colors.accent, 0.10f) else colors.surface,
        ClawMotion.standardTween(), label = "composerBg",
    )
    val barDivider by animateColorAsState(
        if (taskMode) colors.accent.copy(alpha = 0.35f) else colors.divider,
        ClawMotion.standardTween(), label = "composerDivider",
    )

    Column(
        modifier = Modifier
            .background(barBackground)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(color = barDivider, thickness = 1.dp)

        // Conversation and device work are distinct intents regardless of where the
        // model runs. Previously every cloud message was sent to the task agent, so a
        // simple "hola" could sit behind its tool/planning loop instead of chat.
        Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ModeChip(
                    label = "Chat",
                    icon = Icons.Outlined.ChatBubbleOutline,
                    selected = !isTaskMode,
                    fillWithAccent = false,
                    colors = colors,
                    onClick = { onTaskModeChange(false) },
                    modifier = Modifier.weight(1f),
                )
                ModeChip(
                    label = "Tarea",
                    icon = Icons.Outlined.AutoAwesome,
                    selected = isTaskMode,
                    fillWithAccent = true,
                    colors = colors,
                    onClick = { onTaskModeChange(true) },
                    modifier = Modifier.weight(1f),
                )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // One button opening a menu, rather than one button per action. Three
            // controls already share this row and the text field is narrow because of
            // it; a fourth and fifth would leave no room to type. The menu also gives
            // each action a label, which an icon alone cannot do for "monitor a chat".
            ComposerActionsButton(
                enabled = inputEnabled,
                colors = colors,
                onAttach = onAttach,
                onOpenMonitor = onOpenMonitor,
                onOpenSendMessage = onOpenSendMessage,
            )

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = {
                    // Short and single-line. Three action buttons share this row, so the
                    // field is narrow; the longer wording wrapped onto a second line and
                    // stretched the whole composer to twice its height while still empty.
                    Text(
                        when {
                            taskMode -> "Describe una tarea…"
                            !isLocalModel -> "Habla con la IA en la nube…"
                            else -> "Habla con la IA local…"
                        },
                        color = if (taskMode) colors.accent.copy(alpha = 0.55f) else colors.textTertiary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 120.dp),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = if (taskMode) colors.accent else colors.accent.copy(alpha = 0.4f),
                    unfocusedBorderColor = if (taskMode) colors.accent.copy(alpha = 0.6f) else colors.inputBorder,
                    cursorColor = colors.accent,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                maxLines = 4,
            )

            // Voice input — the Android system speech dialog, which handles its own
            // permission. Stays available while a task runs so the next prompt can be
            // dictated without waiting. The transcript is appended, not substituted.
            InputActionButton(
                icon = Icons.Default.Mic,
                contentDescription = stringResource(R.string.voice_input_button_cd),
                tint = colors.textTertiary,
                background = Color.Transparent,
                enabled = inputEnabled,
                onClick = {
                    XLog.i("VoiceInput", "mic tapped: text.len=${text.length}, isTaskMode=$isTaskMode, isLocalModel=$isLocalModel")
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                        putExtra(
                            RecognizerIntent.EXTRA_PROMPT,
                            context.getString(R.string.voice_input_prompt)
                        )
                    }
                    try {
                        voiceLauncher.launch(intent)
                    } catch (e: ActivityNotFoundException) {
                        XLog.e("VoiceInput", "no speech recognition service installed", e)
                        Toast.makeText(context, R.string.voice_input_unavailable, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        XLog.e("VoiceInput", "voice launch failed unexpectedly", e)
                        Toast.makeText(context, R.string.voice_input_error, Toast.LENGTH_SHORT).show()
                    }
                },
            )

            val canSend = text.isNotBlank() && inputEnabled && !isAwaitingReply
            val sendFill by animateColorAsState(
                when {
                    // Stop is deliberately NOT themed. Danger is semantic, and a stop
                    // control that turns green under the aurora theme would be actively
                    // misleading.
                    isTaskRunning -> ClawPalette.Danger.base
                    !canSend -> colors.aiBubble
                    taskMode -> colors.accent
                    else -> colors.userBubble
                },
                ClawMotion.quickTween(), label = "sendFill",
            )
            InputActionButton(
                icon = when {
                    isTaskRunning -> Icons.Default.Close
                    isAwaitingReply -> Icons.Default.MoreHoriz
                    else -> Icons.Default.ArrowUpward
                },
                contentDescription = when {
                    isTaskRunning -> "Detener"
                    isAwaitingReply -> "Esperando respuesta"
                    else -> "Enviar"
                },
                tint = when {
                    isTaskRunning -> ClawPalette.Danger.onAccent
                    !canSend -> colors.textTertiary
                    else -> colors.userText
                },
                background = sendFill,
                enabled = isTaskRunning || canSend,
                onClick = {
                    if (isTaskRunning) {
                        onStopAll()
                    } else if (canSend) {
                        if (taskMode) onSendTask(text.trim()) else onSendChat(text.trim())
                        text = ""
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                },
            )
        }
    }
}

/**
 * One of the two composer modes.
 *
 * The labels were `"💬 Chat"` and `"🤖 Task"` — an emoji baked into the string, and one
 * word left untranslated in an otherwise Spanish UI. Icon and text are separate now so
 * both can be styled and the label can be localised.
 *
 * The selected accent fill uses `colors.userText` for its content rather than a
 * hardcoded white: that slot exists precisely because it is the colour proven to sit on
 * the theme's saturated surface, and white fails contrast on the light themes.
 */
@Composable
private fun ModeChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    fillWithAccent: Boolean,
    colors: BlackClawColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fill by animateColorAsState(
        when {
            selected && fillWithAccent -> colors.accent
            selected -> colors.aiBubble
            else -> Color.Transparent
        },
        ClawMotion.quickTween(), label = "modeFill",
    )
    val content by animateColorAsState(
        when {
            selected && fillWithAccent -> colors.userText
            selected -> colors.textPrimary
            else -> colors.textTertiary
        },
        ClawMotion.quickTween(), label = "modeContent",
    )
    val outline by animateColorAsState(
        when {
            selected && fillWithAccent -> colors.accent
            selected -> colors.aiBubbleBorder
            else -> Color.Transparent
        },
        ClawMotion.quickTween(), label = "modeOutline",
    )
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(fill)
            .border(1.dp, outline, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = content)
    }
}

/**
 * Round action for the composer.
 *
 * Replaces the 34 dp [FloatingActionButton]s these used to be. 34 dp is well under the
 * platform's 48 dp minimum target and is genuinely awkward to hit one-handed; the touch
 * area here is 48 dp while the painted circle stays small, so nothing grows visually.
 */
@Composable
private fun InputActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    background: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The composer's "+" menu.
 *
 * ## Why this exists
 *
 * [MonitorDialog] and [SendMessageDialog] were fully implemented and unreachable: their
 * `show…` flags were never set to true from anywhere, so two finished features shipped
 * dark. This is the entry point they were missing.
 *
 * A menu rather than more buttons — the row's three existing controls already squeeze the
 * text field, and these two actions need words to be understandable. "Monitor a chat" is
 * not an icon.
 */
@Composable
private fun ComposerActionsButton(
    enabled: Boolean,
    colors: BlackClawColors,
    onAttach: () -> Unit,
    onOpenMonitor: () -> Unit,
    onOpenSendMessage: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        InputActionButton(
            icon = Icons.Outlined.Add,
            contentDescription = "Más acciones",
            tint = colors.textTertiary,
            background = Color.Transparent,
            enabled = enabled,
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surface,
        ) {
            ComposerMenuItem("Adjuntar imagen", Icons.Outlined.Image, colors) {
                expanded = false
                onAttach()
            }
            ComposerMenuItem("Vigilar un chat", Icons.Outlined.Visibility, colors) {
                expanded = false
                onOpenMonitor()
            }
            ComposerMenuItem("Enviar un mensaje", Icons.Outlined.Send, colors) {
                expanded = false
                onOpenSendMessage()
            }
        }
    }
}

@Composable
private fun ComposerMenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    colors: BlackClawColors,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 14.sp, color = colors.textPrimary) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp)) },
        onClick = onClick,
    )
}

// ======================== DOWNLOAD OVERLAY ========================

@Composable
private fun DownloadOverlay(progress: Int, colors: BlackClawColors) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BCAvatar(
                    size = 64.dp,
                    cornerRadius = 16.dp,
                    colors = colors,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Descargando tu cerebro de IA",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Solo se hace una vez",
                    fontSize = 13.sp,
                    color = colors.textTertiary,
                )
                Spacer(Modifier.height(24.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = colors.accent,
                    trackColor = colors.inputBorder,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "$progress%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.accent,
                )
            }
        }
    }
}

// ======================== EMPTY STATE ========================

@Composable
private fun EmptyStateWithPrompts(
    isLocalModel: Boolean,
    onSelectPrompt: (String, Boolean) -> Unit,
    colors: BlackClawColors,
    modifier: Modifier = Modifier,
) {
    data class Prompt(val text: String, val isTask: Boolean)

    // Cloud: show task examples (user can give tasks from chat)
    // Local: show chat examples (chat only, tasks go to Workflows tab)
    val prompts = if (!isLocalModel) {
        listOf(
            Prompt("¿Qué hora es en Tokio?", false),
            Prompt("Ayúdame a escribir un mensaje de cumpleaños", false),
            // No emoji in the text: this string is sent to the model verbatim, and the
            // rail beside the row is what marks it as a task now.
            Prompt("Manda hola a Mamá por WhatsApp", true),
        )
    } else {
        listOf(
            Prompt("Cuéntame un chiste", false),
            Prompt("¿Qué puedes hacer?", false),
            Prompt("Ayúdame a redactar un email", false),
        )
    }

    val headerText = if (!isLocalModel) "IA en la nube" else "IA local"
    val reduceMotion = ClawAnimation.reduceMotion()

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))

        // A slow halo behind the mark. This is the one purely decorative animation on
        // the screen, so it is the one that checks the reduced-motion setting, and it
        // breathes rather than pulses — a fast throb on the first thing you see reads as
        // an alert, not as welcome.
        Box(contentAlignment = Alignment.Center) {
            if (!reduceMotion) {
                val halo = rememberInfiniteTransition(label = "emptyHalo")
                val scale by halo.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.35f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2600, easing = ClawMotion.EaseInOut),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "haloScale",
                )
                val fade by halo.animateFloat(
                    initialValue = 0.18f,
                    targetValue = 0.04f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2600, easing = ClawMotion.EaseInOut),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "haloFade",
                )
                Box(
                    Modifier
                        .size(72.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            alpha = fade
                        }
                        .clip(CircleShape)
                        .background(colors.accent),
                )
            }
            BCAvatar(size = 52.dp, cornerRadius = 15.dp, colors = colors)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "BlackClaw",
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(5.dp))
        // Which brain is answering, as a chip — it changes what the assistant can do,
        // so it deserves more than a line of tinted text.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(colors.accent.copy(alpha = 0.12f))
                .border(0.5.dp, colors.accent.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(colors.accent),
            )
            Spacer(Modifier.width(6.dp))
            Text(headerText, fontSize = 11.sp, color = colors.accent, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = if (isLocalModel) {
                "Escribe para charlar, o cambia a Tarea para que controle tu teléfono"
            } else {
                "Chat y tareas en uno — escribe lo que quieras"
            },
            fontSize = 12.sp,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 17.sp,
            modifier = Modifier.widthIn(max = 280.dp),
        )

        Spacer(Modifier.height(20.dp))
        Text(
            "PRUEBA CON",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = colors.textTertiary,
        )
        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier.padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            prompts.forEachIndexed { index, prompt ->
                // Staggered so the suggestions arrive as a set instead of appearing
                // fully formed, which is what makes an empty screen feel inert.
                var shown by remember { mutableStateOf(reduceMotion) }
                LaunchedEffect(Unit) { shown = true }
                val appear by animateFloatAsState(
                    targetValue = if (shown) 1f else 0f,
                    animationSpec = ClawMotion.enterTween(ClawMotion.staggerDelay(index, stepMs = 70)),
                    label = "promptAppear",
                )
                Row(
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = appear
                            translationY = (1f - appear) * 12.dp.toPx()
                        }
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.aiBubble)
                        .border(0.5.dp, colors.inputBorder, RoundedCornerShape(12.dp))
                        .clickable { onSelectPrompt(prompt.text, prompt.isTask) }
                        .padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Task suggestions get a solid rail, chat ones a faint rail: the
                    // difference matters because one of them will operate the phone.
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(42.dp)
                            .background(colors.accent.copy(alpha = if (prompt.isTask) 1f else 0.4f)),
                    )
                    Spacer(Modifier.width(11.dp))
                    Text(
                        prompt.text,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        color = colors.textSecondary,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 10.dp),
                    )
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

// ======================== ASSISTANT DRAWER PANEL ========================

/**
 * Drawer content shown when swiping in from the left edge. Surfaces the
 * Assistant hub (the app's standout feature) instead of the chat list — chats
 * moved to a top-bar pill. Shows live per-category counts and quick nav.
 */
@Composable
private fun AssistantPanel(
    onOpenHub: () -> Unit,
    onSettings: () -> Unit,
    onModels: () -> Unit,
    onAutoReplies: () -> Unit,
    colors: BlackClawColors,
) {
    data class Cat(val type: com.blackclaw.android.assistant.AssistantItemType,
                   val label: String, val emoji: String, val tint: Color)
    val cats = listOf(
        Cat(com.blackclaw.android.assistant.AssistantItemType.REMINDER, "Recordatorios", "🔔", Color(0xFF8B5CF6)),
        Cat(com.blackclaw.android.assistant.AssistantItemType.ALARM, "Alarmas", "⏰", Color(0xFFF59E0B)),
        Cat(com.blackclaw.android.assistant.AssistantItemType.NOTE, "Notas", "📝", Color(0xFF38BDF8)),
        Cat(com.blackclaw.android.assistant.AssistantItemType.EVENT, "Calendario", "📅", Color(0xFFEC4899)),
        Cat(com.blackclaw.android.assistant.AssistantItemType.FINANCE, "Finanzas", "💰", Color(0xFF22C55E)),
    )
    Column(
        Modifier.fillMaxSize().padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = colors.accent, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text("Asistente", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        }
        Text("Tu centro de recordatorios, alarmas y más",
            fontSize = 12.sp, color = colors.textSecondary)
        Spacer(Modifier.height(18.dp))

        cats.forEach { c ->
            val count = remember { com.blackclaw.android.assistant.AssistantStore.countPending(c.type) }
            Surface(
                color = colors.aiBubble, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpenHub() },
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                            .background(c.tint.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) { Text(c.emoji, fontSize = 18.sp) }
                    Spacer(Modifier.width(12.dp))
                    Text(c.label, fontSize = 15.sp, color = colors.textPrimary,
                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (count > 0) {
                        Box(
                            Modifier.clip(CircleShape).background(c.tint.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) { Text("$count", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.tint) }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onOpenHub,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent, contentColor = colors.background),
        ) { Text("Abrir Asistente", fontWeight = FontWeight.SemiBold) }

        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = colors.divider)
        DrawerNavItem(Icons.Outlined.Forum, "Auto-respuestas", colors, onAutoReplies)
        DrawerNavItem(Icons.Outlined.SmartToy, "Modelos", colors, onModels)
        DrawerNavItem(Icons.Outlined.Settings, "Ajustes", colors, onSettings)
    }
}

@Composable
private fun DrawerNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String, colors: BlackClawColors, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = colors.textSecondary, fontSize = 14.sp)
    }
}

// ======================== SIDEBAR ========================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarContent(
    conversations: List<ChatHistoryManager.ConversationSummary>,
    onNewChat: () -> Unit,
    onSelectConversation: (ChatHistoryManager.ConversationSummary) -> Unit,
    onDeleteConversation: (ChatHistoryManager.ConversationSummary) -> Unit,
    onRenameConversation: (ChatHistoryManager.ConversationSummary, String) -> Unit,
    onSettings: () -> Unit,
    onModels: () -> Unit,
    onAutoReplies: () -> Unit,
    onAssistant: () -> Unit = {},
    colors: BlackClawColors,
) {
    var actionTarget by remember { mutableStateOf<ChatHistoryManager.ConversationSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatHistoryManager.ConversationSummary?>(null) }
    var renameTarget by remember { mutableStateOf<ChatHistoryManager.ConversationSummary?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Long-press action menu: Rename / Delete
    if (actionTarget != null) {
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(actionTarget!!.title, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            renameTarget = actionTarget
                            renameText = actionTarget!!.title
                            actionTarget = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Renombrar", color = colors.textPrimary)
                        }
                    }
                    TextButton(
                        onClick = {
                            deleteTarget = actionTarget
                            actionTarget = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFF87171), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Eliminar", color = Color(0xFFF87171))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { actionTarget = null }) { Text("Cancelar", color = colors.textSecondary) }
            },
            containerColor = colors.surface,
        )
    }

    // Delete confirmation
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("¿Eliminar conversación?", color = colors.textPrimary) },
            text = { Text(deleteTarget!!.title, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteConversation(deleteTarget!!)
                    deleteTarget = null
                }) { Text("Eliminar", color = Color(0xFFF87171)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancelar", color = colors.textSecondary) }
            },
            containerColor = colors.surface,
        )
    }

    // Rename dialog
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Renombrar conversación", color = colors.textPrimary) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.inputBorder,
                        cursorColor = colors.accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameText.trim()
                    if (newName.isNotEmpty() && renameTarget != null) {
                        onRenameConversation(renameTarget!!, newName)
                    }
                    renameTarget = null
                }) { Text("Guardar", color = colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancelar", color = colors.textSecondary) }
            },
            containerColor = colors.surface,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(top = 48.dp),
    ) {
        // Title with logo
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BCAvatar(
                size = 32.dp,
                cornerRadius = 8.dp,
                colors = colors,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.textPrimary)) { append("Black") }
                    withStyle(SpanStyle(color = colors.accent)) { append("Claw") }
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // New Chat button — pill, accent, with icon
        Button(
            onClick = onNewChat,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .height(46.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.background,
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Nuevo chat", fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(horizontal = 14.dp))
        Spacer(Modifier.height(10.dp))

        // Recent label
        Text(
            "Recientes",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textTertiary,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )

        // Conversations
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (conversations.isEmpty()) {
                item {
                    Text(
                        "Aún no hay conversaciones",
                        fontSize = 13.sp,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
            items(conversations.size) { index ->
                val conv = conversations[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onSelectConversation(conv) },
                            onLongClick = { actionTarget = conv },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = conv.title,
                        fontSize = 14.sp,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 20.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                    )
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Renombrar conversación",
                        tint = colors.textTertiary,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(18.dp)
                            .clickable {
                                renameText = conv.title
                                renameTarget = conv
                            },
                    )
                }
            }
        }

        HorizontalDivider(color = colors.divider)

        // Assistant hub — reminders, alarms, notes, calendar, finance (native)
        TextButton(
            onClick = onAssistant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Asistente", color = colors.textPrimary, fontWeight = FontWeight.Medium)
            }
        }

        // Bottom nav
        TextButton(
            onClick = onSettings,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Ajustes", color = colors.textSecondary)
            }
        }
        TextButton(
            onClick = onModels,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Modelos", color = colors.textSecondary)
            }
        }
        TextButton(
            onClick = onAutoReplies,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Forum, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Auto-respuestas", color = colors.textSecondary)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ======================== SKILL DIALOGS ========================

@Composable
private fun MonitorDialog(
    onDismiss: () -> Unit,
    onStart: (MonitorTargetSpec) -> Unit,
    colors: BlackClawColors,
) {
    var contact by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf("WhatsApp") }
    var appMenuExpanded by remember { mutableStateOf(false) }
    var selectedTone by remember { mutableStateOf("Casual") }
    val apps = MonitorTargetSpec.supportedApps
    val tones = listOf("Casual", "Formal", "Divertido")

    // Centered modal overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.44f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        // Centered card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { /* block clicks from dismissing */ },
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(3.dp)
                        .align(Alignment.CenterHorizontally)
                        .background(colors.textTertiary, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.height(14.dp))

                // Title
                Text(
                    "\uD83D\uDC41\uFE0F Monitor & Auto-Reply",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(12.dp))

                // Contact row: label + input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Destinatario",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.width(50.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = contact,
                        onValueChange = { contact = it },
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.sp,
                            color = colors.textPrimary,
                        ),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .background(colors.background, RoundedCornerShape(8.dp))
                                    .then(
                                        Modifier.border(1.dp, colors.inputBorder, RoundedCornerShape(8.dp))
                                    )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                if (contact.isEmpty()) {
                                    Text("e.g. Mom, +1 555 123 4567", fontSize = 12.sp, color = colors.textTertiary)
                                }
                                innerTextField()
                            }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))

                // App row: label + dropdown
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "App",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.width(50.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colors.background,
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.inputBorder),
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable { appMenuExpanded = true }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(selectedApp, fontSize = 12.sp, color = colors.textPrimary)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                            }
                        }
                        DropdownMenu(
                            expanded = appMenuExpanded,
                            onDismissRequest = { appMenuExpanded = false },
                        ) {
                            apps.forEach { app ->
                                DropdownMenuItem(
                                    text = { Text(app, fontSize = 12.sp) },
                                    onClick = { selectedApp = app; appMenuExpanded = false },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // Tone row: label + pill chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Tono",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.width(50.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tones.forEach { tone ->
                            val isOn = tone == selectedTone
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isOn) colors.userBubble.copy(alpha = 0.1f) else colors.background,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isOn) colors.userBubble else colors.inputBorder,
                                ),
                            ) {
                                Text(
                                    tone,
                                    fontSize = 11.sp,
                                    color = if (isOn) colors.accent else colors.textSecondary,
                                    modifier = Modifier
                                        .clickable { selectedTone = tone }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))

                // Start Monitoring button
                Surface(
                    onClick = {
                        val trimmed = contact.trim()
                        if (trimmed.isNotBlank()) {
                            onStart(
                                MonitorTargetSpec(
                                    label = trimmed,
                                    app = selectedApp,
                                    tone = selectedTone,
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = colors.userBubble,
                ) {
                    Text(
                        "Empezar monitoreo",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 11.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SendMessageDialog(
    onDismiss: () -> Unit,
    onSend: (contact: String, app: String, message: String) -> Unit,
    colors: BlackClawColors,
) {
    var contact by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf("WhatsApp") }
    var appMenuExpanded by remember { mutableStateOf(false) }
    val apps = listOf("WhatsApp", "Telegram", "Messages")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text("Enviar mensaje", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        },
        text = {
            Column {
                Text("Con una IA potente puedes escribir directamente:", fontSize = 11.sp, color = colors.textTertiary)
                Spacer(Modifier.height(2.dp))
                Text("\"send hi to Mom on WhatsApp\"", fontSize = 11.sp, color = colors.accent.copy(alpha = 0.7f))
                Spacer(Modifier.height(16.dp))

                // Fill-in-the-blank: "Send [___] to [___] on [WhatsApp ▾]"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Enviar ", fontSize = 15.sp, color = colors.textPrimary)
                    Text("\"", fontSize = 15.sp, color = colors.textTertiary)
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        placeholder = { Text("message", color = colors.textTertiary, fontSize = 14.sp) },
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.inputBorder,
                            cursorColor = colors.accent,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                        ),
                    )
                    Text("\"", fontSize = 15.sp, color = colors.textTertiary)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("to ", fontSize = 15.sp, color = colors.textPrimary)
                    OutlinedTextField(
                        value = contact,
                        onValueChange = { contact = it },
                        placeholder = { Text("name", color = colors.textTertiary, fontSize = 14.sp) },
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.inputBorder,
                            cursorColor = colors.accent,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                        ),
                    )
                    Text(" on ", fontSize = 15.sp, color = colors.textPrimary)
                    Box {
                        Surface(
                            onClick = { appMenuExpanded = true },
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.inputBorder),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(selectedApp, fontSize = 13.sp, color = colors.textPrimary)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
                            }
                        }
                        DropdownMenu(
                            expanded = appMenuExpanded,
                            onDismissRequest = { appMenuExpanded = false },
                        ) {
                            apps.forEach { app ->
                                DropdownMenuItem(
                                    text = { Text(app) },
                                    onClick = { selectedApp = app; appMenuExpanded = false },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (contact.isNotBlank() && message.isNotBlank()) onSend(contact.trim(), selectedApp, message.trim()) },
                enabled = contact.isNotBlank() && message.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Enviar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = colors.textSecondary)
            }
        },
    )
}

// ======================== ACTIVE TASK BAR ========================

@Composable
private fun ActiveTaskBar(
    tasks: List<String>,
    onStopTask: (String) -> Unit,
    onStopAll: () -> Unit,
    colors: BlackClawColors,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
    ) {
        // Monitor tasks bar
        if (tasks.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                            shape = androidx.compose.foundation.shape.CircleShape,
                        )
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (tasks.size == 1) "Monitoring: ${tasks[0]}" else "${tasks.size} monitoring",
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "▴" else "▾",
                    color = colors.textSecondary,
                    fontSize = 14.sp,
                )
            }
        }

        // Expanded — show each task with stop button
        if (expanded) {
            Divider(color = colors.textSecondary.copy(alpha = 0.2f), thickness = 0.5.dp)
            tasks.forEach { task ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                color = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                                shape = androidx.compose.foundation.shape.CircleShape,
                            )
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = task,
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "Detener",
                        color = androidx.compose.ui.graphics.Color(0xFFF44336),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { onStopTask(task) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            if (tasks.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = "Detener todo",
                        color = androidx.compose.ui.graphics.Color(0xFFF44336),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { onStopAll() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
