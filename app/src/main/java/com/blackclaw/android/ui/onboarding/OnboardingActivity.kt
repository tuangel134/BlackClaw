package com.blackclaw.android.ui.onboarding

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.blackclaw.android.AppCapabilityCoordinator
import com.blackclaw.android.AppRequirement
import com.blackclaw.android.ServiceBindingState
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.ui.chat.BlackClawColors
import com.blackclaw.android.ui.chat.ThemeManager
import com.blackclaw.android.ui.chat.ThemeManager.toComposeColors
import com.blackclaw.android.utils.KVUtils

/**
 * Guided permission onboarding. BlackClaw needs several non-obvious permissions
 * (Accessibility, Notification access, Overlay, exact alarms, battery whitelist)
 * spread across different system screens. New users get lost finding them, so
 * this screen walks through each one with live status and a one-tap "Activar"
 * that opens the right settings page. State refreshes automatically on resume.
 */
class OnboardingActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        val colors = with(ThemeManager) { tc.toComposeColors() }
        setContent {
            OnboardingScreen(colors = colors, onDone = {
                KVUtils.putBoolean(KEY_ONBOARDING_DONE, true)
                KVUtils.sync()
                finish()
            })
        }
    }

    companion object {
        const val KEY_ONBOARDING_DONE = "onboarding_completed_v1"

        /** Whether the essential capabilities for the core experience are present. */
        fun hasEssentials(context: Context): Boolean {
            val snap = AppCapabilityCoordinator.snapshot(context)
            return snap.accessibilityState == ServiceBindingState.READY &&
                snap.notificationPermissionGranted
        }

        fun wasCompleted(): Boolean = KVUtils.getBoolean(KEY_ONBOARDING_DONE, false)
    }
}

private data class PermStep(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val rationale: String,
    val essential: Boolean,
    val isGranted: (AppCapabilitySnapshotLite) -> Boolean,
    val onActivate: (Context) -> Unit,
)

/** Lightweight view of the capability snapshot the UI cares about. */
private data class AppCapabilitySnapshotLite(
    val accessibility: Boolean,
    val notificationPermission: Boolean,
    val notificationAccess: Boolean,
    val overlay: Boolean,
    val battery: Boolean,
    val exactAlarms: Boolean,
)

private fun liteSnapshot(ctx: Context): AppCapabilitySnapshotLite {
    val s = AppCapabilityCoordinator.snapshot(ctx)
    val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    } else true
    return AppCapabilitySnapshotLite(
        accessibility = s.accessibilityState == ServiceBindingState.READY,
        notificationPermission = s.notificationPermissionGranted,
        notificationAccess = s.notificationAccessState == ServiceBindingState.READY,
        overlay = s.overlayGranted,
        battery = s.batteryOptimizationIgnored,
        exactAlarms = exact,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingScreen(colors: BlackClawColors, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var snap by remember { mutableStateOf(liteSnapshot(ctx)) }

    // Refresh status whenever we come back from a system settings screen.
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) snap = liteSnapshot(ctx)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val steps = remember {
        listOf(
            PermStep(
                "accessibility", Icons.Default.Accessibility, "Accesibilidad",
                "El corazón de BlackClaw: le permite ver la pantalla y tocar por ti. Sin esto el agente no puede actuar.",
                essential = true, isGranted = { it.accessibility },
                onActivate = { AppCapabilityCoordinator.openSystemSettings(it, AppRequirement.ACCESSIBILITY) },
            ),
            PermStep(
                "notifPerm", Icons.Default.Notifications, "Mostrar notificaciones",
                "Para enviarte recordatorios, alarmas y avisos del asistente.",
                essential = true, isGranted = { it.notificationPermission },
                onActivate = { openAppNotificationSettings(it) },
            ),
            PermStep(
                "notifAccess", Icons.Default.NotificationsActive, "Leer notificaciones",
                "Deja que el asistente proactivo reaccione a tus mensajes (poner una alarma, registrar un cobro). Opcional pero recomendado.",
                essential = false, isGranted = { it.notificationAccess },
                onActivate = { AppCapabilityCoordinator.openSystemSettings(it, AppRequirement.NOTIFICATION_ACCESS) },
            ),
            PermStep(
                "overlay", Icons.Default.Layers, "Superponer en pantalla",
                "Para la burbuja flotante y las alarmas a pantalla completa sobre otras apps.",
                essential = false, isGranted = { it.overlay },
                onActivate = { AppCapabilityCoordinator.openSystemSettings(it, AppRequirement.OVERLAY) },
            ),
            PermStep(
                "exactAlarm", Icons.Default.Alarm, "Alarmas exactas",
                "Para que las alarmas y recordatorios suenen justo a la hora, no minutos después.",
                essential = false, isGranted = { it.exactAlarms },
                onActivate = { openExactAlarmSettings(it) },
            ),
            PermStep(
                "battery", Icons.Default.BatteryChargingFull, "Sin optimización de batería",
                "Evita que el sistema mate al asistente en segundo plano (clave en Honor/Xiaomi/Samsung).",
                essential = false, isGranted = { it.battery },
                onActivate = { AppCapabilityCoordinator.openSystemSettings(it, AppRequirement.BATTERY_OPTIMIZATION) },
            ),
        )
    }

    val essentialsReady = steps.filter { it.essential }.all { it.isGranted(snap) }
    val grantedCount = steps.count { it.isGranted(snap) }
    val progress by animateFloatAsState(
        targetValue = grantedCount.toFloat() / steps.size,
        animationSpec = tween(600), label = "progress")

    Scaffold(
        containerColor = colors.background,
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize()
                .background(colors.background)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Gradient hero ──
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(
                        colors.accent.copy(alpha = 0.22f),
                        colors.accent.copy(alpha = 0.06f),
                        colors.background,
                    )))
                    .padding(horizontal = 20.dp)
                    .padding(top = 36.dp, bottom = 22.dp),
            ) {
                Column {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(listOf(
                                colors.accent, colors.accent.copy(alpha = 0.6f)))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.AutoAwesome, null, tint = colors.background,
                            modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Pon a punto BlackClaw", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                        color = colors.textPrimary)
                    Text("Unos permisos rápidos y tu asistente queda listo para trabajar por ti.",
                        fontSize = 14.sp, color = colors.textSecondary, lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 6.dp))

                    Spacer(Modifier.height(18.dp))
                    // Animated progress bar + count.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(50))
                                .background(colors.textTertiary.copy(alpha = 0.3f)),
                        ) {
                            Box(
                                Modifier.fillMaxWidth(progress).fillMaxHeight()
                                    .clip(RoundedCornerShape(50))
                                    .background(Brush.horizontalGradient(listOf(
                                        colors.accent, colors.accent.copy(alpha = 0.7f)))),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("$grantedCount/${steps.size}", fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, color = colors.accent)
                    }
                }
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(6.dp))
                steps.forEach { step ->
                    PermissionRow(step, step.isGranted(snap), colors)
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onDone,
                    enabled = essentialsReady,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent, contentColor = colors.background,
                        disabledContainerColor = colors.surface, disabledContentColor = colors.textTertiary),
                ) {
                    if (essentialsReady) {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (essentialsReady) "Empezar a usar BlackClaw" else "Activa los permisos esenciales",
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                if (!essentialsReady) {
                    Text("Los pasos marcados como esenciales (Accesibilidad y notificaciones) son necesarios para empezar.",
                        fontSize = 11.sp, color = colors.textTertiary,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
                }
                TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)) {
                    Text("Omitir por ahora", color = colors.textSecondary, fontSize = 13.sp)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun PermissionRow(step: PermStep, granted: Boolean, colors: BlackClawColors) {
    val ctx = LocalContext.current
    val green = Color(0xFF22C55E)
    Surface(
        color = if (granted) green.copy(alpha = 0.08f) else colors.surface,
        shape = RoundedCornerShape(16.dp),
        border = if (granted) BorderStroke(1.dp, green.copy(alpha = 0.35f))
            else BorderStroke(1.dp, colors.divider.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            // Rounded tinted icon tile.
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (granted) green.copy(alpha = 0.18f) else colors.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(step.icon, null, tint = if (granted) green else colors.accent,
                    modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(step.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    if (step.essential) {
                        Spacer(Modifier.width(6.dp))
                        Text("esencial", fontSize = 9.sp, color = colors.background,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(colors.accent, RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                }
                Text(step.rationale, fontSize = 11.5.sp, color = colors.textSecondary,
                    lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Spacer(Modifier.width(10.dp))
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, "Activado", tint = green,
                        modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Listo", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = green)
                }
            } else {
                Button(
                    onClick = { step.onActivate(ctx) },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent, contentColor = colors.background),
                ) {
                    Text("Activar", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun openAppNotificationSettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(intent) }.onFailure {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${ctx.packageName}")).apply {
                if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}

private fun openExactAlarmSettings(ctx: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
        Uri.parse("package:${ctx.packageName}")).apply {
        if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(intent) }.onFailure {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${ctx.packageName}")).apply {
                if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
