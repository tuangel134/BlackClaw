package com.blackclaw.android.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackclaw.android.channel.Channel
import com.blackclaw.android.channel.auth.ChannelAuthPolicy
import com.blackclaw.android.channel.auth.ChannelAuthorization
import com.blackclaw.android.ui.design.*

/**
 * Bot token plus the pairing state for one remote channel.
 *
 * ## Why pairing lives here and is not optional
 *
 * A configured bot token alone used to be enough for anyone who found the bot to
 * drive the phone. [ChannelAuthorization] now refuses every inbound message until an
 * owner is bound, and the only way to bind one is to send the code shown on this
 * screen. So this screen is not a nicety: without it the channel simply never works,
 * which is why the pairing card sits above the token field rather than buried below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelConfigScreen(
    channel: Channel,
    title: String,
    tokenHint: String,
    helpText: String,
    initialToken: String,
    lanAddress: String?,
    lanAccessCode: String,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
) {
    var token by remember { mutableStateOf(initialToken) }
    var tokenVisible by remember { mutableStateOf(false) }
    var pairingRefresh by remember { mutableIntStateOf(0) }
    var saved by remember { mutableStateOf(false) }

    val paired = remember(pairingRefresh) { ChannelAuthorization.isPaired(channel) }
    val maskedOwner = remember(pairingRefresh) { ChannelAuthorization.maskedOwner(channel) }
    // Reading the code generates one on demand, so the card is never empty when the
    // user needs it. Only computed while unpaired: once bound, showing a live code
    // would imply another account could still claim the channel.
    val pairingCode = remember(pairingRefresh, paired) {
        if (paired) "" else ChannelAuthorization.pairingCodeForDisplay(channel)
    }

    Scaffold(
        containerColor = ClawPalette.Elevation.Level0,
        topBar = {
            TopAppBar(
                title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = ClawTextColors.Primary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás",
                            tint = ClawTextColors.Primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ClawPalette.Elevation.Level0),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            ClawReveal(index = 0) {
                if (paired) PairedCard(maskedOwner) {
                    ChannelAuthorization.unpair(channel)
                    pairingRefresh++
                } else {
                    ClawSecretCard(
                        title = "Vinculación pendiente",
                        explanation = "Envía este código como mensaje al bot desde la cuenta " +
                            "que quieras autorizar. Hasta entonces se ignora cualquier mensaje: " +
                            "sin esto, cualquier desconocido que encuentre el bot podría " +
                            "controlar el teléfono.",
                        code = pairingCode,
                        accent = ClawPalette.Guard,
                        onRegenerate = {
                            ChannelAuthorization.regeneratePairingCode(channel)
                            pairingRefresh++
                        },
                    )
                }
            }

            ClawReveal(index = 1) {
                ClawCard(accent = ClawPalette.Signature) {
                    Column(Modifier.padding(16.dp)) {
                        Text("BOT TOKEN", fontSize = 11.sp, letterSpacing = 1.3.sp,
                            fontWeight = FontWeight.Bold, color = ClawPalette.Signature.base)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it; saved = false },
                            placeholder = { Text(tokenHint, color = ClawTextColors.Tertiary,
                                fontSize = 13.sp) },
                            singleLine = true,
                            visualTransformation = if (tokenVisible) VisualTransformation.None
                                else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                    Icon(
                                        if (tokenVisible) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                        if (tokenVisible) "Ocultar token" else "Mostrar token",
                                        tint = ClawTextColors.Secondary,
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = ClawTextColors.Primary,
                                unfocusedTextColor = ClawTextColors.Primary,
                                focusedBorderColor = ClawPalette.Signature.base,
                                unfocusedBorderColor = ClawPalette.Elevation.Outline,
                                cursorColor = ClawPalette.Signature.base,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(9.dp))
                        Text(helpText, fontSize = 11.5.sp, lineHeight = 16.sp,
                            color = ClawTextColors.Tertiary)
                    }
                }
            }

            ClawReveal(index = 2) {
                ClawPrimaryButton(
                    text = if (saved) "Guardado" else "Guardar y reconectar",
                    icon = Icons.Default.Save,
                    accent = ClawPalette.Signature,
                    onClick = { onSave(token.trim()); saved = true },
                )
            }

            // The LAN page cannot be unlocked without this code, so it is surfaced
            // wherever the address is surfaced.
            ClawReveal(index = 3) {
                LanConfigCard(lanAddress, lanAccessCode)
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun PairedCard(maskedOwner: String, onUnpair: () -> Unit) {
    ClawCard(accent = ClawPalette.Finance, elevated = true) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(ClawPalette.Finance.base),
                )
                Spacer(Modifier.width(9.dp))
                Text("VINCULADO", fontSize = 11.sp, letterSpacing = 1.3.sp,
                    fontWeight = FontWeight.Bold, color = ClawPalette.Finance.base)
            }
            Spacer(Modifier.height(9.dp))
            Text(
                "Solo la cuenta $maskedOwner puede dar órdenes por este canal. " +
                    "Los mensajes de cualquier otra se descartan en silencio.",
                fontSize = 12.5.sp, lineHeight = 18.sp, color = ClawTextColors.Secondary,
            )
            Spacer(Modifier.height(14.dp))
            ClawSecondaryButton(
                text = "Desvincular",
                icon = Icons.Default.LinkOff,
                accent = ClawPalette.Danger,
                onClick = onUnpair,
            )
        }
    }
}

@Composable
private fun LanConfigCard(address: String?, accessCode: String) {
    val running = address != null && accessCode.isNotBlank()
    ClawCard(accent = ClawPalette.Note) {
        Column(Modifier.padding(16.dp)) {
            Text("CONFIGURAR DESDE EL NAVEGADOR", fontSize = 11.sp, letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold, color = ClawPalette.Note.base)
            Spacer(Modifier.height(9.dp))
            if (!running) {
                Text(
                    "Activa el servidor de configuración en Ajustes para editar estos " +
                        "valores desde un navegador.",
                    fontSize = 12.5.sp, lineHeight = 18.sp, color = ClawTextColors.Secondary,
                )
            } else {
                Text(
                    "Abre http://$address en el navegador y escribe el código de acceso. " +
                        "El código se pide porque el servidor escucha en el propio " +
                        "teléfono, donde cualquier app instalada podría alcanzarlo.",
                    fontSize = 12.5.sp, lineHeight = 18.sp, color = ClawTextColors.Secondary,
                )
                Spacer(Modifier.height(12.dp))
                ClawSecretCard(
                    title = "Código de acceso",
                    explanation = "Cámbialo cuando quieras: se renueva al reiniciar el servidor.",
                    code = accessCode,
                    accent = ClawPalette.Note,
                    copyLabel = "Código de acceso copiado",
                )
            }
        }
    }
}
