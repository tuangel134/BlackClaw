package com.blackclaw.android.ui.adb

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.blackclaw.android.adb.AdbController
import com.blackclaw.android.adb.AdbKeyStore
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.ui.chat.BlackClawColors
import com.blackclaw.android.ui.chat.ThemeManager
import com.blackclaw.android.ui.chat.ThemeManager.toComposeColors
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * "Modo Pro" — guided self-ADB pairing. Lets BlackClaw pair with its own adbd
 * over loopback using Wireless Debugging, granting shell-level power (fast taps,
 * force-stop, dumpsys…) with NO PC and NO Shizuku.
 */
class AdbProActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        val colors = with(ThemeManager) { tc.toComposeColors() }
        val store = AdbKeyStore(this)
        store.keyPair()
        AdbController.init(this)
        setContent {
            AdbProScreen(
                colors = colors,
                fingerprint = store.fingerprint(),
                onBack = { finish() },
                onOpenDevOptions = { openWirelessDebug() },
            )
        }
    }

    private fun openWirelessDebug() {
        // Best path: jump straight to Wireless debugging. Fall back to dev
        // options, then to general settings.
        val candidates = listOf(
            Intent("android.settings.WIRELESS_ADB_DEBUGGING_SETTINGS"),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(intent); return } catch (_: Exception) {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdbProScreen(
    colors: BlackClawColors,
    fingerprint: String,
    onBack: () -> Unit,
    onOpenDevOptions: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(AdbController.state) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(false) }

    fun refresh() { state = AdbController.state }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Modo Pro", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            color = colors.textPrimary)
                        Text("ADB integrado · sin Shizuku · sin PC",
                            fontSize = 11.sp, color = colors.textSecondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Atrás", tint = colors.textPrimary)
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // ── Connection status ─────────────────────────────────
            ConnectionStatusCard(state, colors)

            Spacer(Modifier.height(16.dp))

            // ── Pairing flow (only when not yet paired) ───────────
            if (state == AdbController.State.NOT_PAIRED || state == AdbController.State.ERROR) {
                Text("CÓMO EMPAREJAR (1 sola vez)",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = colors.textTertiary, letterSpacing = 0.8.sp,
                    modifier = Modifier.padding(bottom = 8.dp))

                StepCard(1, "Activa Opciones de desarrollador",
                    "Ajustes → Acerca del teléfono → toca 7 veces \"Número de compilación\".",
                    colors)
                StepCard(2, "Activa Depuración inalámbrica",
                    "Te llevo ahí con el botón de abajo. Activa \"Depuración inalámbrica\".",
                    colors)
                StepCard(3, "Abre \"Vincular con código\"",
                    "Dentro de Depuración inalámbrica, toca \"Vincular dispositivo con código " +
                    "de emparejamiento\". Verás un código de 6 dígitos.",
                    colors)
                StepCard(4, "Deja BlackClaw leerlo solo",
                    "Con ese diálogo ABIERTO, pulsa \"Emparejar automático\": BlackClaw lee " +
                    "el código y el puerto de la pantalla por accesibilidad. No tienes que " +
                    "copiar nada.",
                    colors)

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onOpenDevOptions,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.5f)),
                ) {
                    Icon(Icons.Default.Settings, null, tint = colors.accent,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Abrir Depuración inalámbrica", color = colors.accent,
                        fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(10.dp))

                // ── Primary: fully automatic pairing via accessibility ──
                Button(
                    onClick = {
                        busy = true
                        statusMsg = "Abriendo el diálogo de emparejamiento… entra en " +
                            "\"Vincular dispositivo con código\" y deja la pantalla abierta."
                        statusOk = false
                        scope.launch {
                            // Start the background poller, then send the user to
                            // the system dialog. The reader picks up the code once
                            // that dialog is foreground; pairing runs over loopback.
                            val job = async { AdbController.autoPair(ctx) }
                            kotlinx.coroutines.delay(300)
                            onOpenDevOptions()
                            val res = job.await()
                            busy = false
                            refresh()
                            statusOk = res.isSuccess
                            statusMsg = if (res.isSuccess)
                                "Emparejado automáticamente. BlackClaw ya tiene acceso ADB."
                            else AdbController.lastError ?: "Falló el emparejamiento automático."
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.background,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                            color = colors.background)
                        Spacer(Modifier.width(10.dp))
                        Text("Leyendo código…", fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(Icons.Default.Bolt, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Emparejar automático", fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("¿No funcionó la lectura automática? Escribe el código a mano:",
                    fontSize = 11.sp, color = colors.textTertiary)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = code,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) code = it },
                    label = { Text("Código de 6 dígitos") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.aiBubbleBorder,
                        focusedLabelColor = colors.accent,
                        unfocusedLabelColor = colors.textSecondary,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        cursorColor = colors.accent,
                    ),
                )

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        busy = true
                        statusMsg = null
                        scope.launch {
                            val res = AdbController.pair(ctx, code)
                            busy = false
                            refresh()
                            statusOk = res.isSuccess
                            statusMsg = if (res.isSuccess)
                                "Emparejado correctamente. BlackClaw ya tiene acceso ADB."
                            else AdbController.lastError ?: "Falló el emparejamiento."
                        }
                    },
                    enabled = code.length == 6 && !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.background,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                            color = colors.background)
                        Spacer(Modifier.width(10.dp))
                        Text("Emparejando…", fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(Icons.Default.Link, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Emparejar a mano", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // ── Already paired: connect / disconnect controls ─────
            if (state == AdbController.State.PAIRED_DISCONNECTED ||
                state == AdbController.State.CONNECTED ||
                state == AdbController.State.CONNECTING) {
                Button(
                    onClick = {
                        busy = true
                        statusMsg = null
                        scope.launch {
                            val res = AdbController.connect(ctx)
                            busy = false
                            refresh()
                            statusOk = res.isSuccess
                            statusMsg = if (res.isSuccess) "Conectado."
                            else AdbController.lastError ?: "No se pudo conectar."
                        }
                    },
                    enabled = !busy && state != AdbController.State.CONNECTED,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.background,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                            color = colors.background)
                        Spacer(Modifier.width(10.dp))
                        Text("Conectando…", fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(Icons.Default.Link, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state == AdbController.State.CONNECTED) "Conectado" else "Conectar",
                            fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        AdbController.forgetPairing()
                        refresh()
                        statusMsg = "Emparejamiento olvidado."
                        statusOk = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.textTertiary.copy(alpha = 0.4f)),
                ) {
                    Icon(Icons.Default.LinkOff, null, tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Olvidar emparejamiento", color = colors.textSecondary)
                }
            }

            // ── status toast line ─────────────────────────────────
            statusMsg?.let { msg ->
                Spacer(Modifier.height(14.dp))
                Surface(
                    color = if (statusOk) Color(0xFF22C55E).copy(alpha = 0.12f)
                            else colors.accent.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(msg, fontSize = 12.sp,
                        color = if (statusOk) Color(0xFF22C55E) else colors.textSecondary,
                        modifier = Modifier.padding(12.dp), lineHeight = 17.sp)
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── identity fingerprint ──────────────────────────────
            Surface(
                color = colors.surface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(0.5.dp, colors.aiBubbleBorder),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Identidad ADB de BlackClaw",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text("🔐 SHA-256: $fingerprint", fontSize = 10.sp,
                        color = colors.textTertiary)
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "El emparejamiento es de una sola vez. Tras reiniciar el teléfono, " +
                "vuelve a activar Depuración inalámbrica y pulsa Conectar (no hace falta " +
                "re-emparejar).",
                fontSize = 11.sp, color = colors.textTertiary,
                textAlign = TextAlign.Center, lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun ConnectionStatusCard(state: AdbController.State, colors: BlackClawColors) {
    val (icon, title, subtitle, tint) = when (state) {
        AdbController.State.NOT_PAIRED -> Quad(
            Icons.Default.Warning, "Sin emparejar",
            "Empareja una vez para activar el control rápido por ADB.", colors.accent)
        AdbController.State.PAIRED_DISCONNECTED -> Quad(
            Icons.Default.LinkOff, "Emparejado · desconectado",
            "Pulsa Conectar para reactivar el acceso.", colors.accent)
        AdbController.State.CONNECTING -> Quad(
            Icons.Default.Bolt, "Conectando…", "Estableciendo túnel TLS con adbd.", colors.accent)
        AdbController.State.CONNECTED -> Quad(
            Icons.Default.Check, "Conectado",
            "Acciones rápidas activas: fast_tap, force_stop_app, shell…", Color(0xFF22C55E))
        AdbController.State.ERROR -> Quad(
            Icons.Default.Warning, "Error",
            AdbController.lastError ?: "Algo falló.", Color(0xFFEF4444))
    }

    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.4f)),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(42.dp).clip(CircleShape)
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary)
                Text(subtitle, fontSize = 12.sp, color = colors.textSecondary, lineHeight = 16.sp)
            }
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
            modifier = Modifier.size(28.dp).clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$num", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Text(body, fontSize = 12.sp, color = colors.textSecondary,
                lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
