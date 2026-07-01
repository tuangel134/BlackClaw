package com.blackclaw.android.ui.security

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.security.AppRiskScanner
import com.blackclaw.android.security.SecurityActions
import com.blackclaw.android.ui.chat.BlackClawColors
import com.blackclaw.android.ui.chat.ThemeManager
import com.blackclaw.android.ui.chat.ThemeManager.toComposeColors
import java.util.concurrent.Executors

/**
 * Antimalware / app-security screen. Lists installed apps ranked by risk with
 * one-tap actions (neutralize ad spam, uninstall, open settings), plus a
 * prominent "an app is spamming ads" shortcut.
 */
class SecurityActivity : BaseActivity() {

    private val exec = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        val colors = with(ThemeManager) { tc.toComposeColors() }
        setContent { SecurityScreen(colors, exec, ::toast) { finish() } }
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { exec.shutdownNow() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecurityScreen(
    colors: BlackClawColors,
    exec: java.util.concurrent.ExecutorService,
    toast: (String) -> Unit,
    onBack: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var apps by remember { mutableStateOf<List<AppRiskScanner.AppRisk>>(emptyList()) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AppRiskScanner.scan().filter { it.level != AppRiskScanner.Level.LOW }
        }
        apps = result
        loading = false
    }

    fun act(pkg: String, action: String) {
        exec.execute {
            val msg = when (action) {
                "neutralize" -> SecurityActions.neutralize(pkg)
                "uninstall" -> SecurityActions.uninstall(pkg)
                "settings" -> { SecurityActions.openAppSettings(pkg); "Abrí los ajustes de la app." }
                else -> "Acción desconocida"
            }
            toast(msg)
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Seguridad", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                        Text("Apps riesgosas y bloqueo de anuncios", fontSize = 11.sp, color = colors.textSecondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = colors.textPrimary)
                    }
                },
                actions = {
                    TextButton(onClick = { reloadKey++ }) { Text("Reescanear", color = colors.accent) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface, titleContentColor = colors.textPrimary),
            )
        },
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = colors.accent)
                    Spacer(Modifier.height(12.dp))
                    Text("Escaneando apps…", color = colors.textSecondary, fontSize = 13.sp)
                }
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
        ) {
            item {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = colors.surface, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.3f)),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("¿Una app te molesta con anuncios?", fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                        Text("Pídeselo a BlackClaw en el chat: \"una app me llena de anuncios, encuéntrala y " +
                            "bloquéala\". O revisa abajo las apps que pueden dibujar sobre otras.",
                            fontSize = 12.sp, color = colors.textSecondary, lineHeight = 16.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("APPS A REVISAR (${apps.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = colors.textTertiary)
                Spacer(Modifier.height(6.dp))
            }
            if (apps.isEmpty()) {
                item {
                    Text("No encontré apps con señales de riesgo. 👍",
                        fontSize = 13.sp, color = colors.textSecondary,
                        modifier = Modifier.padding(vertical = 20.dp))
                }
            }
            items(apps) { app ->
                RiskCard(app, colors, onNeutralize = { act(app.pkg, "neutralize") },
                    onUninstall = { act(app.pkg, "uninstall") },
                    onSettings = { act(app.pkg, "settings") })
                Spacer(Modifier.height(10.dp))
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

@Composable
private fun RiskCard(
    app: AppRiskScanner.AppRisk,
    colors: BlackClawColors,
    onNeutralize: () -> Unit,
    onUninstall: () -> Unit,
    onSettings: () -> Unit,
) {
    val levelColor = when (app.level) {
        AppRiskScanner.Level.HIGH -> Color(0xFFE05252)
        AppRiskScanner.Level.MEDIUM -> Color(0xFFE0A02E)
        AppRiskScanner.Level.LOW -> colors.textSecondary
    }
    Surface(color = colors.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(app.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    Text(app.pkg, fontSize = 11.sp, color = colors.textSecondary)
                }
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(levelColor.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text("${app.level} · ${app.score}", fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, color = levelColor)
                }
            }
            Spacer(Modifier.height(6.dp))
            app.reasons.take(4).forEach {
                Text("• $it", fontSize = 12.sp, color = colors.textSecondary, lineHeight = 16.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (app.requestsOverlay) {
                    Button(
                        onClick = onNeutralize,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent, contentColor = colors.background),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text("Bloquear anuncios", fontSize = 12.sp) }
                }
                OutlinedButton(
                    onClick = onUninstall, shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) { Text("Desinstalar", fontSize = 12.sp, color = colors.accent) }
                OutlinedButton(
                    onClick = onSettings, shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) { Text("Info", fontSize = 12.sp, color = colors.textSecondary) }
            }
        }
    }
}
