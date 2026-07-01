package com.blackclaw.android.ui.terminal

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.terminal.TerminalEngine
import com.blackclaw.android.ui.chat.BlackClawColors
import com.blackclaw.android.ui.chat.ThemeManager
import com.blackclaw.android.ui.chat.ThemeManager.toComposeColors
import java.util.concurrent.Executors

/**
 * BlackClaw's internal terminal UI. Shares the exact same [TerminalEngine]
 * session as the AI's `terminal` tool, so what the user runs here and what the
 * assistant runs are one continuous session (working dir, backend, adb).
 */
class TerminalActivity : BaseActivity() {

    private val exec = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        val colors = with(ThemeManager) { tc.toComposeColors() }
        setContent { TerminalScreen(colors, ::runCommand) { finish() } }
    }

    private fun runCommand(cmd: String, onResult: (String) -> Unit) {
        exec.execute {
            val out = runCatching { TerminalEngine.run(applicationContext, cmd) }
                .getOrElse { "error: ${it.message}" }
            runOnUiThread { onResult(out) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { exec.shutdownNow() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalScreen(
    colors: BlackClawColors,
    onRun: (String, (String) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val lines = remember { mutableStateListOf("BlackClaw terminal — escribe 'help' para empezar.") }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var backendLabel by remember { mutableStateOf(TerminalEngine.effectiveBackend().name.lowercase()) }
    val listState = rememberLazyListState()

    fun submit() {
        val cmd = input.trim()
        if (cmd.isEmpty() || busy) return
        lines.add("${TerminalEngine.prompt()}$cmd")
        input = ""
        busy = true
        onRun(cmd) { out ->
            if (out.contains('\u000C')) {
                lines.clear()
            } else if (out.isNotBlank()) {
                out.split("\n").forEach { lines.add(it) }
            }
            backendLabel = TerminalEngine.effectiveBackend().name.lowercase()
            busy = false
        }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Terminal", fontSize = 18.sp, color = colors.textPrimary)
                        Text("backend: $backendLabel", fontSize = 11.sp, color = colors.textSecondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = colors.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface, titleContentColor = colors.textPrimary),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .background(colors.background).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                items(lines) { line ->
                    Text(
                        line, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = colors.textPrimary, softWrap = true, overflow = TextOverflow.Clip,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().background(colors.surface)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = colors.textPrimary),
                    placeholder = { Text("comando…", fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.aiBubbleBorder,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        cursorColor = colors.accent,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { submit() }, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Ejecutar",
                        tint = if (busy) colors.textSecondary else colors.accent)
                }
            }
        }
    }
}
