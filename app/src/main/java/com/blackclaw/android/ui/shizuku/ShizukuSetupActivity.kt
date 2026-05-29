package com.blackclaw.android.ui.shizuku

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Warning
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
import com.blackclaw.android.shizuku.ShizukuManager
import com.blackclaw.android.ui.chat.BlackClawColors
import com.blackclaw.android.ui.chat.ThemeManager
import com.blackclaw.android.ui.chat.ThemeManager.toComposeColors
import kotlinx.coroutines.delay

class ShizukuSetupActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        val colors = with(ThemeManager) { tc.toComposeColors() }
        setContent {
            ShizukuSetupScreen(
                colors = colors,
                onBack = { finish() },
                onOpenPlay = { openMarket("moe.shizuku.privileged.api") },
                onOpenShizuku = { openShizukuApp() },
                onRequestPermission = { ShizukuManager.requestPermission() },
            )
        }
    }

    private fun openMarket(pkg: String) {
        val tryGoogle = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val webFallback = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(tryGoogle) } catch (_: Exception) {
            try { startActivity(webFallback) } catch (_: Exception) {}
        }
    }

    private fun openShizukuApp() {
        val pkg = "moe.shizuku.privileged.api"
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) startActivity(launch)
        else openMarket(pkg)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShizukuSetupScreen(
    colors: BlackClawColors,
    onBack: () -> Unit,
    onOpenPlay: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    val state = remember(refreshTick) { ShizukuManager.state(ctx) }

    // Auto-refresh every 1.5 s while the screen is visible so the state
    // reflects what the user just did in the Shizuku app.
    LaunchedEffect(Unit) {
        while (true) { delay(1500); refreshTick++ }
    }

    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Shizuku", fontSize = 18.sp,
                            fontWeight = FontWeight.Bold, color = colors.textPrimary)
                        Text("Acciones rápidas para BlackClaw",
                            fontSize = 11.sp, color = colors.textSecondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Atrás", tint = colors.textPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Default.Refresh, "Refrescar", tint = colors.textSecondary)
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
            StatusCard(state, colors, onOpenPlay, onOpenShizuku, onRequestPermission)

            // Why Shizuku card
            Surface(
                color = colors.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(14.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bolt, null, tint = colors.accent,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("¿Para qué sirve?",
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Con Shizuku activo BlackClaw puede:\n" +
                        "• Tap y swipe ~10× más rápidos\n" +
                        "• Funcionar dentro de juegos y apps con Surface\n" +
                        "• Forzar la detención de apps de verdad\n" +
                        "• Ejecutar comandos shell útiles (am, dumpsys, settings…)\n\n" +
                        "Si no lo instalas no pasa nada: BlackClaw sigue funcionando con accesibilidad.",
                        fontSize = 12.sp, color = colors.textSecondary, lineHeight = 18.sp,
                    )
                }
            }

            // How-to setup tabs
            if (state == ShizukuManager.State.NOT_INSTALLED || state == ShizukuManager.State.INSTALLED_OFF) {
                Text(
                    "CÓMO ACTIVARLO",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = colors.textTertiary, letterSpacing = 0.8.sp,
                    modifier = Modifier.padding(start = 18.dp, top = 4.dp, bottom = 6.dp),
                )
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = colors.surface,
                    contentColor = colors.accent,
                    modifier = Modifier.padding(horizontal = 14.dp).clip(RoundedCornerShape(12.dp)),
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Sin PC", fontSize = 13.sp) },
                        icon = { Icon(Icons.Default.Smartphone, null, modifier = Modifier.size(16.dp)) },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Con PC", fontSize = 13.sp) },
                        icon = { Icon(Icons.Default.Computer, null, modifier = Modifier.size(16.dp)) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (selectedTab == 0) WirelessAdbInstructions(colors) else PcInstructions(colors)
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun StatusCard(
    state: ShizukuManager.State,
    colors: BlackClawColors,
    onOpenPlay: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val (icon, title, subtitle, action, actionColor) = when (state) {
        ShizukuManager.State.NOT_INSTALLED -> Quintuple(
            Icons.Default.Warning,
            "No instalado",
            "Instala la app Shizuku para empezar.",
            "Instalar Shizuku", colors.accent,
        )
        ShizukuManager.State.INSTALLED_OFF -> Quintuple(
            Icons.Default.Warning,
            "Instalado · servicio apagado",
            "Abre Shizuku y arranca el servicio (instrucciones abajo).",
            "Abrir Shizuku", colors.accent,
        )
        ShizukuManager.State.RUNNING_NO_PERM -> Quintuple(
            Icons.Default.Warning,
            "Servicio activo · falta permiso",
            "Concede a BlackClaw acceso a Shizuku.",
            "Conceder permiso", colors.accent,
        )
        ShizukuManager.State.READY -> Quintuple(
            Icons.Default.Check,
            "Todo listo",
            "BlackClaw puede usar acciones rápidas vía Shizuku.",
            null, Color(0xFF22C55E),
        )
    }

    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, actionColor.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(actionColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = actionColor, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary)
                    Text(subtitle, fontSize = 12.sp, color = colors.textSecondary)
                }
            }
            if (action != null) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        when (state) {
                            ShizukuManager.State.NOT_INSTALLED -> onOpenPlay()
                            ShizukuManager.State.INSTALLED_OFF -> onOpenShizuku()
                            ShizukuManager.State.RUNNING_NO_PERM -> onRequestPermission()
                            else -> Unit
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.background,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(action, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun WirelessAdbInstructions(colors: BlackClawColors) {
    Column(modifier = Modifier.padding(horizontal = 14.dp)) {
        StepCard(1, "Activa Opciones de desarrollador",
            "Ajustes → Acerca del teléfono → toca 7 veces \"Número de compilación\".",
            colors)
        StepCard(2, "Activa la depuración inalámbrica",
            "Ajustes → Sistema → Opciones de desarrollador → " +
            "activa \"Depuración inalámbrica\". Acepta el aviso. " +
            "Esta opción aparece desde Android 11.",
            colors)
        StepCard(3, "Empareja una vez",
            "Toca \"Depuración inalámbrica\" para entrar. Pulsa " +
            "\"Vincular dispositivo con código de emparejamiento\". " +
            "Anota el puerto y código que aparecen.",
            colors)
        StepCard(4, "Inicia Shizuku",
            "Abre la app Shizuku → pestaña \"Use wireless debugging\" → " +
            "introduce el código + puerto que viste antes. Si pide auto-pairing, " +
            "úsalo. Verás \"Service running\" en verde.",
            colors)
        StepCard(5, "Concede el permiso a BlackClaw",
            "Vuelve aquí. El estado de arriba debería decir \"Servicio activo · " +
            "falta permiso\". Pulsa \"Conceder permiso\". Acepta en el popup.",
            colors)
        Surface(
            color = colors.aiBubble, shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Text(
                "⚠️ Tendrás que repetir los pasos 3-4 después de cada reinicio del " +
                "teléfono (es una limitación de Android, no de BlackClaw). " +
                "El permiso a BlackClaw se mantiene.",
                fontSize = 11.sp, color = colors.textSecondary, lineHeight = 16.sp,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun PcInstructions(colors: BlackClawColors) {
    Column(modifier = Modifier.padding(horizontal = 14.dp)) {
        StepCard(1, "Conecta el teléfono al PC",
            "USB con depuración USB activada (Ajustes → Sistema → Opciones de " +
            "desarrollador → \"Depuración USB\").",
            colors)
        StepCard(2, "Instala adb en tu PC",
            "Si no lo tienes: descarga Platform Tools de Google " +
            "(developer.android.com/tools/releases/platform-tools).",
            colors)
        StepCard(3, "Comprueba que adb ve el teléfono",
            "Terminal/CMD: `adb devices`. Debe listar tu dispositivo en \"device\".",
            colors)
        StepCard(4, "Inicia Shizuku desde el PC",
            "Ejecuta:\n" +
            "    adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh\n\n" +
            "(Algunas builds usan /storage/emulated/0/... — la ruta correcta sale en " +
            "la propia app Shizuku, pestaña \"Start via ADB\".)",
            colors)
        StepCard(5, "Permiso a BlackClaw",
            "Vuelve a esta pantalla y pulsa \"Conceder permiso\".",
            colors)
        Surface(
            color = colors.aiBubble, shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Text(
                "✅ Esta vía es más estable: el servicio sigue activo aunque " +
                "reinicies el teléfono (mientras no lo apagues).",
                fontSize = 11.sp, color = colors.textSecondary, lineHeight = 16.sp,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun StepCard(num: Int, title: String, body: String, colors: BlackClawColors) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$num", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = colors.accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary)
            Text(body, fontSize = 12.sp, color = colors.textSecondary,
                lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private data class Quintuple<A, B, C, D, E>(
    val first: A, val second: B, val third: C, val fourth: D, val fifth: E,
)
