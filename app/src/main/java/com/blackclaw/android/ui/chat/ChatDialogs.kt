package com.blackclaw.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ======================== SKILL DIALOGS ========================

@Composable
internal fun MonitorDialog(
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
internal fun SendMessageDialog(
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
