package com.blackclaw.android.ui.adb

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackclaw.android.automation.AutomationToken
import com.blackclaw.android.tool.guard.PrivilegedToolConsent
import com.blackclaw.android.ui.design.*
import kotlinx.coroutines.delay

/**
 * Consent switch for the arbitrary-command tools, plus the automation token.
 *
 * ## Why this screen has to exist
 *
 * `ToolRiskPolicy` refuses `shell_exec`, `terminal`, `remote_shell`,
 * `remote_connect` and `add_smart_device` unless the user armed them here, and its
 * refusal message names "Ajustes → Modo Pro" literally. Without this card those tools
 * are simply unreachable and the error is a dead end.
 *
 * The switch is deliberately not a plain preference. Granting Shizuku or pairing ADB
 * once is not the same as consenting to the model opening a shell at any later
 * moment — the agent reads screen text, notifications and web pages, so a
 * prompt-injected model could reach a shell during an ordinary session. Arming
 * expires on its own so a user who forgets to switch it off is not left exposed, and
 * the countdown is shown so the expiry is not a surprise.
 */
@Composable
fun PrivilegedToolsCard(modifier: Modifier = Modifier) {
    var armed by remember { mutableStateOf(PrivilegedToolConsent.isArmed()) }
    var minutesLeft by remember { mutableIntStateOf(PrivilegedToolConsent.remainingMinutes()) }

    // Poll the countdown while armed. The window can lapse while this screen is open,
    // and a switch still reading "on" after the tools stopped working would be a lie.
    LaunchedEffect(armed) {
        while (armed) {
            delay(20_000)
            val stillArmed = PrivilegedToolConsent.isArmed()
            minutesLeft = PrivilegedToolConsent.remainingMinutes()
            if (!stillArmed) armed = false
        }
    }

    ClawCard(modifier = modifier.fillMaxWidth(), accent = ClawPalette.Guard, elevated = armed) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Terminal, null,
                    tint = ClawPalette.Guard.base,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Permitir herramientas de shell",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ClawTextColors.Primary,
                    )
                    Text(
                        if (armed) "Activo · quedan $minutesLeft min"
                        else "Desactivado",
                        fontSize = 12.sp,
                        color = if (armed) ClawPalette.Guard.base else ClawTextColors.Tertiary,
                        fontWeight = if (armed) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                Switch(
                    checked = armed,
                    onCheckedChange = { want ->
                        if (want) PrivilegedToolConsent.arm() else PrivilegedToolConsent.disarm()
                        armed = PrivilegedToolConsent.isArmed()
                        minutesLeft = PrivilegedToolConsent.remainingMinutes()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ClawPalette.Guard.onAccent,
                        checkedTrackColor = ClawPalette.Guard.base,
                        uncheckedTrackColor = ClawPalette.Elevation.Level3,
                    ),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Habilita shell_exec, terminal y shell remoto durante " +
                    "${PrivilegedToolConsent.WINDOW_MS / 60_000} minutos y luego se apaga solo. " +
                    "Se pide aparte de Shizuku/ADB porque la IA lee pantalla, notificaciones y " +
                    "páginas web: si algo de eso la manipula, no debería poder abrir una shell.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = ClawTextColors.Secondary,
            )
            AnimatedVisibility(visible = armed) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Estas herramientas siguen bloqueadas para peticiones que llegan por " +
                            "Telegram, Discord o WeChat, incluso con esto activo.",
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = ClawPalette.Guard.base,
                    )
                }
            }
        }
    }
}

/**
 * Token a third-party automation app must present.
 *
 * Separate from the switch above because it authorises a different thing: not what
 * the agent may run, but who may ask it to run anything at all.
 */
@Composable
fun AutomationTokenCard(modifier: Modifier = Modifier) {
    var refresh by remember { mutableIntStateOf(0) }
    val code = remember(refresh) { AutomationToken.tokenForDisplay() }

    ClawSecretCard(
        title = "Token de automatización",
        explanation = "Pásalo como extra \"${AutomationToken.EXTRA_TOKEN}\" en el intent " +
            "com.blackclaw.android.RUN_TASK desde Tasker o similar. Sin él se rechaza la " +
            "petición: Android no dice qué app envió un intent, así que este secreto es la " +
            "única prueba de que lo autorizaste tú.",
        code = code,
        modifier = modifier,
        accent = ClawPalette.Signature,
        copyLabel = "Token copiado",
        onRegenerate = {
            AutomationToken.regenerate()
            refresh++
        },
    )
}
