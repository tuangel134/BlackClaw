package com.blackclaw.android.ui.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.blackclaw.android.R
import com.blackclaw.android.agent.skill.Skill
import com.blackclaw.android.agent.skill.SkillCategory
import com.blackclaw.android.agent.skill.SkillRegistry
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.blackclaw.android.utils.XLog
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    // Token count color: grey → blue → amber → red
    val tokenColor = when {
        sessionTokens < 5000 -> colors.textTertiary
        sessionTokens < 15000 -> Color(0xFF60A5FA) // blue
        sessionTokens < 25000 -> Color(0xFFFBBF24) // amber
        else -> Color(0xFFF87171) // soft red
    }

    Column {
        var showModelMenu by remember { mutableStateOf(false) }

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
                    Text(
                        "Chats",
                        fontSize = 12.sp,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                // Local/Cloud toggle — two plain buttons, no container
                Surface(
                    onClick = { onTabChange("local") },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedTab == "local") colors.aiBubble else Color.Transparent,
                    border = if (selectedTab == "local") androidx.compose.foundation.BorderStroke(1.dp, colors.aiBubbleBorder) else null,
                ) {
                    Text(
                        "Local",
                        fontSize = 12.sp,
                        color = if (selectedTab == "local") colors.accent else colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Surface(
                    onClick = { onTabChange("cloud") },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedTab == "cloud") colors.aiBubble else Color.Transparent,
                    border = if (selectedTab == "cloud") androidx.compose.foundation.BorderStroke(1.dp, colors.aiBubbleBorder) else null,
                ) {
                    Text(
                        "Cloud",
                        fontSize = 12.sp,
                        color = if (selectedTab == "cloud") colors.accent else colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
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

        // Model status + dropdown — filtered by selected tab
        Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .clickable { showModelMenu = true }
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = modelStatus,
                fontSize = 11.sp,
                color = colors.textTertiary,
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
                val tokenSuffix = if (!isLocalModel && sessionCost > 0) {
                    " · $formattedTokens tokens · $costText"
                } else {
                    " · $formattedTokens tokens"
                }
                Text(
                    text = tokenSuffix,
                    fontSize = 11.sp,
                    color = tokenColor,
                )
            }
        }
            // Model switcher dropdown — only show configured/downloaded models
            DropdownMenu(
                expanded = showModelMenu,
                onDismissRequest = { showModelMenu = false },
            ) {
                val kvUtils = com.blackclaw.android.utils.KVUtils
                val apiKey = kvUtils.getLlmApiKey()
                val baseUrl = kvUtils.getLlmBaseUrl()
                val currentModel = kvUtils.getLlmModelName()

                if (selectedTab == "cloud") {
                    // Cloud models: from configured provider
                    if (apiKey.isNotEmpty()) {
                        val activeProvider = com.blackclaw.android.agent.CloudProvider.entries.find {
                            it.defaultBaseUrl == baseUrl
                        }
                        val modelsToShow = activeProvider?.models
                            ?: com.blackclaw.android.agent.CloudProvider.OPENAI.models
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

// ======================== PERMISSION BANNER ========================

@Composable
private fun PermissionBanner(onClick: () -> Unit, colors: BlackClawColors) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.accent.copy(alpha = 0.12f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Shield, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Permisos necesarios. Pulsa para configurar.",
                color = colors.accent,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
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
    val scope = rememberCoroutineScope()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(messages.size - 1) }
        }
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
        items(messages.size) { index ->
            val message = messages[index]
            // Slide-in + fade animation for the most recent bubble.
            // Older messages render instantly so scrolling feels snappy.
            val isLast = index == messages.size - 1
            AnimatedMessageWrapper(animate = isLast) {
                when (message.role) {
                    ChatMessage.Role.USER -> UserBubble(message.content, message.timestamp, colors)
                    ChatMessage.Role.ASSISTANT -> AssistantBubble(message.content, message.timestamp, colors, message.modelName)
                    ChatMessage.Role.SYSTEM -> SystemMessage(message.content, colors)
                    ChatMessage.Role.TOOL_GROUP -> ToolGroup(message, colors)
                }
            }
        }
    }
}

@Composable
private fun AnimatedMessageWrapper(animate: Boolean, content: @Composable () -> Unit) {
    if (!animate) {
        content()
        return
    }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "msg-alpha",
    )
    val translateY by animateFloatAsState(
        targetValue = if (visible) 0f else 16f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "msg-translate",
    )
    Box(
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha
            this.translationY = translateY
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
            Surface(
                color = colors.userBubble,
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
            ) {
                Text(
                    text = text,
                    color = colors.userText,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
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

@Composable
private fun AssistantBubble(text: String, timestamp: Long, colors: BlackClawColors, modelName: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 64.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            // Avatar — vectorial BC initials with accent color, no PNG dependency
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.avatar),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "BC",
                    color = colors.accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                )
            }
            Spacer(Modifier.width(8.dp))

            // Bubble
            if (text == "...") {
                Surface(
                    color = colors.aiBubble,
                    shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.aiBubbleBorder),
                ) {
                    TypingIndicator(
                        color = colors.accent,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    )
                }
            } else {
                Surface(
                    color = colors.aiBubble,
                    shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.aiBubbleBorder),
                ) {
                    Text(
                        text = text,
                        color = colors.aiText,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
        if (text != "...") {
            val footer = listOfNotNull(
                modelName?.takeIf { it.isNotBlank() },
                formatBubbleTimestamp(timestamp)
            ).joinToString(" · ")
            Text(
                text = footer,
                fontSize = 9.sp,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = 40.dp, top = 1.dp, bottom = 2.dp),
            )
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

@Composable
private fun ToolGroup(message: ChatMessage, colors: BlackClawColors) {
    Column(
        modifier = Modifier.padding(start = 54.dp, end = 64.dp, top = 2.dp, bottom = 2.dp),
    ) {
        message.toolSteps?.forEach { step ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 1.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (step.success) colors.accent else colors.textTertiary),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${step.toolName} → ${step.summary}",
                    fontSize = 12.sp,
                    color = colors.textTertiary,
                )
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
            if (isLocalModel) onTaskModeChange(prefillIsTask)
            onPrefillConsumed()
        }
    }

    val taskBg = Color(0xFF1A1410)
    val taskBorder = colors.accent.copy(alpha = 0.25f)

    Column(
        modifier = Modifier
            .background(if (isTaskMode && isLocalModel) taskBg else colors.surface)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(
            color = if (isTaskMode && isLocalModel) taskBorder else colors.divider,
            thickness = 1.dp,
        )

        // Segmented Chat/Task toggle — Local LLM only
        if (isLocalModel) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Chat button
                Surface(
                    onClick = { onTaskModeChange(false) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (!isTaskMode) colors.aiBubble else Color.Transparent,
                    border = if (!isTaskMode) androidx.compose.foundation.BorderStroke(1.dp, colors.aiBubbleBorder) else null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        "💬 Chat",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (!isTaskMode) colors.textPrimary else colors.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 9.dp),
                    )
                }
                // Task button
                Surface(
                    onClick = { onTaskModeChange(true) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isTaskMode) colors.accent else Color.Transparent,
                    border = if (isTaskMode) androidx.compose.foundation.BorderStroke(1.dp, colors.accent) else null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        "🤖 Task",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isTaskMode) Color.White else colors.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 9.dp),
                    )
                }
            }
        }

        // Input bar — always visible, style changes in Task mode
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = {
                    Text(
                        when {
                            isLocalModel && isTaskMode -> "Describe una tarea…"
                            !isLocalModel -> "Chatea o pide una tarea…"
                            else -> "Habla con la IA local…"
                        },
                        color = if (isTaskMode && isLocalModel) colors.accent.copy(alpha = 0.5f) else colors.textTertiary,
                        fontSize = 14.sp,
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp, max = 100.dp),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = if (isTaskMode && isLocalModel) colors.accent else colors.accent.copy(alpha = 0.4f),
                    unfocusedBorderColor = if (isTaskMode && isLocalModel) colors.accent.copy(alpha = 0.6f) else colors.inputBorder,
                    cursorColor = if (isTaskMode && isLocalModel) colors.accent else colors.accent,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedContainerColor = if (isTaskMode && isLocalModel) taskBg else Color.Transparent,
                    unfocusedContainerColor = if (isTaskMode && isLocalModel) taskBg else Color.Transparent,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                maxLines = 4,
            )

            Spacer(Modifier.width(6.dp))

            // Voice input mic button (Issue #44) — launches Android system speech dialog.
            // Available whenever input is enabled (including while a task runs, so user
            // can queue next prompt with voice without waiting).
            val micEnabled = inputEnabled
            FloatingActionButton(
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
                        Toast.makeText(
                            context,
                            R.string.voice_input_unavailable,
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        XLog.e("VoiceInput", "voice launch failed unexpectedly", e)
                        Toast.makeText(
                            context,
                            R.string.voice_input_error,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier
                    .size(34.dp)
                    .alpha(if (micEnabled) 1f else 0.35f),
                containerColor = colors.background,
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = stringResource(R.string.voice_input_button_cd),
                    tint = colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }

            Spacer(Modifier.width(6.dp))

            FloatingActionButton(
                onClick = {
                    if (isTaskRunning) {
                        onStopAll()
                    } else if (!isAwaitingReply && inputEnabled && text.isNotBlank()) {
                        if (!isLocalModel || isTaskMode) {
                            onSendTask(text.trim())
                            text = ""
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        } else {
                            onSendChat(text.trim())
                            text = ""
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    }
                },
                modifier = Modifier
                    .size(34.dp)
                    .alpha(if ((text.isBlank() || !inputEnabled || isAwaitingReply) && !isTaskRunning) 0.35f else 1f),
                containerColor = when {
                    isTaskRunning -> Color(0xFFF44336)
                    isAwaitingReply -> colors.background
                    text.isBlank() -> colors.background
                    isTaskMode && isLocalModel -> colors.accent
                    else -> colors.userBubble
                },
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
            ) {
                Icon(
                    when {
                        isTaskRunning -> Icons.Default.Close
                        isAwaitingReply -> Icons.Default.MoreHoriz
                        else -> Icons.Default.ArrowUpward
                    },
                    contentDescription = when {
                        isTaskRunning -> "Detener"
                        isAwaitingReply -> "Esperando respuesta"
                        else -> "Enviar"
                    },
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ======================== SKILL SHORTCUT BAR ========================

@Composable
private fun SkillShortcutBar(
    skills: List<Skill>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSkillTap: (Skill) -> Unit,
    colors: BlackClawColors,
) {
    val categoryIcons = mapOf(
        SkillCategory.INPUT to Icons.Outlined.Keyboard,
        SkillCategory.DISMISS to Icons.Outlined.Close,
        SkillCategory.NAVIGATION to Icons.Outlined.Navigation,
        SkillCategory.MESSAGING to Icons.Outlined.Chat,
        SkillCategory.MEDIA to Icons.Outlined.CameraAlt,
        SkillCategory.GENERAL to Icons.Outlined.AutoAwesome,
    )

    Column {
        // Toggle row
        Surface(
            onClick = onToggle,
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Skills",
                    fontSize = 12.sp,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Plegar" else "Desplegar",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // Expanded skill chips
        if (expanded) {
            // Two rows of chips using FlowRow-style layout
            val rows = skills.chunked((skills.size + 1) / 2)
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (row in rows) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (skill in row) {
                            val icon = categoryIcons[skill.category] ?: Icons.Outlined.AutoAwesome
                            Surface(
                                onClick = { onSkillTap(skill) },
                                shape = RoundedCornerShape(20.dp),
                                color = colors.accent.copy(alpha = 0.1f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        tint = colors.accent,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        skill.name,
                                        fontSize = 11.sp,
                                        color = colors.accent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
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
            Prompt("💬 Manda hola a Mamá por WhatsApp", true),
        )
    } else {
        listOf(
            Prompt("Cuéntame un chiste", false),
            Prompt("¿Qué puedes hacer?", false),
            Prompt("Ayúdame a redactar un email", false),
        )
    }

    val headerText = if (!isLocalModel) "IA en la nube" else "IA local"

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        BCAvatar(
            size = 48.dp,
            cornerRadius = 12.dp,
            colors = colors,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "BlackClaw",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            headerText,
            fontSize = 12.sp,
            color = colors.accent,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        // Hint text — Local has styled bold parts, Cloud is plain
        if (isLocalModel) {
            Text(
                buildAnnotatedString {
                    append("Habla en modo ")
                    withStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.Bold)) {
                        append("💬 Chat")
                    }
                    append(", o cambia a ")
                    withStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.Bold)) {
                        append("🤖 Tarea")
                    }
                    append(" para controlar tu teléfono")
                },
                fontSize = 11.sp,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.widthIn(max = 260.dp),
            )
        } else {
            Text(
                "Chat y tareas en uno \u2014 escribe lo que quieras",
                fontSize = 11.sp,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.widthIn(max = 260.dp),
            )
        }
        Spacer(Modifier.height(12.dp))

        // Suggested prompt chips — same style as Quick Tasks items
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            prompts.forEach { prompt ->
                val barAlpha = if (prompt.isTask) 1f else 0.5f
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = colors.background,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.inputBorder),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectPrompt(prompt.text, prompt.isTask) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(38.dp)
                                .background(
                                    colors.accent.copy(alpha = barAlpha),
                                    RoundedCornerShape(topStart = 9.dp, bottomStart = 9.dp),
                                ),
                        )
                        Text(
                            prompt.text,
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }
    }
}

// ======================== QUICK TASKS PANEL (v9) ========================

@Composable
private fun QuickTasksPanel(
    isLocalModel: Boolean,
    onFillTask: (String) -> Unit,
    onMonitorClick: () -> Unit,
    monitorActive: Boolean,
    colors: BlackClawColors,
) {
    // Default to collapsed — user opens it explicitly with the button.
    var expanded by remember { mutableStateOf(false) }

    // Cloud-only tasks (multi-step, Siri can't do)
    val cloudOnlyTasks = listOf(
        "🦞 Abre Reddit y busca blackclaw",
        "🎬 Busca en YouTube vídeos divertidos de gatos",
        "📦 Instala Telegram desde Play Store",
        "🐦 Mira qué es tendencia en Twitter y dímelo",
        "💬 Mira mi último chat de WhatsApp y resúmelo",
        "📋 Copia el último email y búscalo en Google",
        "📧 Escribe un email diciendo que llegaré tarde",
    )
    val reasoningTasks = listOf(
        "📵 Mira mis notificaciones — ¿algo importante?",
        "📋 Lee mi portapapeles y explícame qué dice",
        "🧹 Mira mi almacenamiento — ¿qué puedo borrar?",
        "🔔 Lee mis notificaciones y resúmelas",
        "🔋 Mira mi batería y dime si debo cargarla",
    )
    val deterministicTasks = listOf(
        "💬 Manda hola a Mamá por WhatsApp",
        "📱 ¿Qué apps tengo instaladas?",
        "🌡️ ¿Cuánto está mi teléfono de caliente?",
        "🔵 ¿Está el Bluetooth encendido?",
        "🔋 ¿Cuánta batería me queda?",
        "📞 Llama a Mamá",
        "💾 ¿Cuánto almacenamiento tengo?",
        "📲 ¿Qué versión de Android tengo?",
    )
    val quickTasks = if (isLocalModel) {
        reasoningTasks + deterministicTasks
    } else {
        cloudOnlyTasks + reasoningTasks + deterministicTasks
    }

    Column(
        modifier = Modifier.background(colors.surface),
    ) {
        HorizontalDivider(color = colors.divider, thickness = 1.dp)

        // Handle / button — ▲ Plantillas Rápidas ▲
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = "Alternar",
                tint = colors.accent,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Plantillas rápidas",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = colors.accent,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = "Alternar",
                tint = colors.accent,
                modifier = Modifier.size(12.dp),
            )
        }

        // Collapsible content
        if (expanded) {
            // Quick task items — scrollable
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                quickTasks.forEach { task ->
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = colors.background,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.inputBorder),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onFillTask(task.substringAfter(" ")) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(38.dp)
                                    .background(colors.accent, RoundedCornerShape(topStart = 9.dp, bottomStart = 9.dp)),
                            )
                            Text(
                                task,
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            }

            // Background section — always visible, NOT inside scroll
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(
                    "BACKGROUND",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textTertiary,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )

                // Monitor card
                val monitorBorderColor = if (monitorActive) colors.accent else colors.inputBorder
                Surface(
                    onClick = {
                        if (!monitorActive) onMonitorClick()
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = colors.background,
                    border = androidx.compose.foundation.BorderStroke(
                        if (monitorActive) 1.dp else 0.5.dp,
                        monitorBorderColor,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(
                                    colors.accent.copy(alpha = 0.12f),
                                    RoundedCornerShape(9.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("👁️", fontSize = 15.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (monitorActive) "Activo" else "Monitor & auto-respuesta",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            )
                            Text(
                                if (monitorActive) "Monitorización activa — usa la barra superior para detener" else "Lee mensajes y responde automáticamente",
                                fontSize = 9.sp,
                                color = colors.textTertiary,
                            )
                        }
                        if (!monitorActive) {
                            Text("›", color = colors.textTertiary, fontSize = 14.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            } // end Background Column
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

// ======================== TASK SKILLS PANEL ========================

@Composable
private fun TaskSkillsPanel(
    isLocalModel: Boolean,
    taskMessages: List<ChatMessage>,
    onMonitorClick: () -> Unit,
    onSendClick: () -> Unit,
    onSkillTap: (String) -> Unit,
    activatingSkill: String?,
    monitorActive: Boolean,
    colors: BlackClawColors,
    modifier: Modifier = Modifier,
) {
    val builtInSkills = remember { SkillRegistry.getUserFacing() }
    val categoryIcons = mapOf(
        SkillCategory.INPUT to Icons.Outlined.Keyboard,
        SkillCategory.DISMISS to Icons.Outlined.Close,
        SkillCategory.NAVIGATION to Icons.Outlined.Navigation,
        SkillCategory.MESSAGING to Icons.Outlined.Chat,
        SkillCategory.GENERAL to Icons.Outlined.AutoAwesome,
    )

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Flujos",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            Text(
                "Tareas en segundo plano impulsadas por IA — cosas que un prompt no puede hacer.",
                fontSize = 12.sp,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = colors.accent.copy(alpha = 0.12f),
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            ) {
                Text(
                    "Experimental — más flujos próximamente",
                    fontSize = 11.sp,
                    color = colors.accent,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        // Monitor Messages — always shown (background workflow, both modes need it)
        item {
            SkillCard(
                icon = Icons.Outlined.Visibility,
                title = "Monitorizar mensajes",
                description = "Responde mensajes en segundo plano",
                onClick = onMonitorClick,
                isActivating = activatingSkill == "monitor",
                isActive = monitorActive,
                colors = colors,
            )
        }

        // Send Message — available on both (workflow card shortcut)
        item {
            SkillCard(
                icon = Icons.Outlined.Send,
                title = "Enviar mensaje",
                description = "Envía un mensaje desde cualquier app",
                onClick = onSendClick,
                colors = colors,
            )
        }

        // Built-in user-facing skills from SkillRegistry
        if (builtInSkills.isNotEmpty()) {
            items(builtInSkills.size) { index ->
                val skill = builtInSkills[index]
                val example = skill.triggerPatterns.firstOrNull()
                    ?.replace(Regex("\\{\\w+\\}"), "...")
                    ?.replace(".+", "...")
                    ?: skill.name
                SkillCard(
                    icon = categoryIcons[skill.category] ?: Icons.Outlined.AutoAwesome,
                    title = skill.name,
                    description = skill.description,
                    onClick = { onSkillTap(example) },
                    colors = colors,
                )
            }
        }

        // Task progress messages (if any)
        if (taskMessages.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Recientes",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textTertiary,
                )
            }
            items(taskMessages.size) { index ->
                val msg = taskMessages[index]
                if (msg.role == ChatMessage.Role.USER) {
                    UserBubble(msg.content, msg.timestamp, colors)
                } else {
                    SystemMessage(msg.content, colors)
                }
            }
        }
    }
}

@Composable
private fun SkillCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    isActivating: Boolean = false,
    isActive: Boolean = false,
    colors: BlackClawColors,
) {
    val activeOrange = Color(0xFFE8751A)
    val borderColor = when {
        isActive -> activeOrange
        isActivating -> colors.accent
        else -> colors.inputBorder
    }
    val cardBg = when {
        isActive -> activeOrange.copy(alpha = 0.08f)
        else -> colors.surface
    }
    val iconBg = when {
        isActive -> activeOrange.copy(alpha = 0.15f)
        else -> colors.accent.copy(alpha = 0.12f)
    }
    val iconTint = if (isActive) activeOrange else colors.accent

    // Progress animation
    val progress by animateFloatAsState(
        targetValue = if (isActivating) 1f else 0f,
        animationSpec = if (isActivating) tween(2000, easing = LinearEasing) else snap(),
        label = "skillProgress",
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        border = androidx.compose.foundation.BorderStroke(if (isActive) 1.dp else 0.5.dp, borderColor),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(iconBg, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isActive) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = activeOrange, modifier = Modifier.size(22.dp))
                    } else {
                        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (isActive) activeOrange else colors.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (isActive) "Ejecutándose en segundo plano" else description,
                        fontSize = 12.sp,
                        color = if (isActive) activeOrange.copy(alpha = 0.7f) else colors.textTertiary,
                        lineHeight = 16.sp,
                    )
                }
                if (!isActive && !isActivating) {
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
                }
            }

            // Progress bar during activation
            if (isActivating) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = activeOrange,
                    trackColor = colors.inputBorder,
                )
            }
        }
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
