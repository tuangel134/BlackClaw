package com.blackclaw.android.ui.skills

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.graphicsLayer
import com.blackclaw.android.agent.skill.UserSkill
import com.blackclaw.android.agent.skill.UserSkillStore
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.ui.chat.BlackClawColors
import com.blackclaw.android.ui.chat.ThemeManager
import com.blackclaw.android.ui.chat.ThemeManager.toComposeColors
import com.blackclaw.android.ui.chat.ComposeChatActivity
import android.content.Intent
import java.util.UUID

class SkillsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        val colors = with(ThemeManager) { tc.toComposeColors() }
        setContent {
            SkillsScreen(
                colors = colors,
                onBack = { finish() },
                onRunSkill = { skill ->
                    val intent = Intent(this, ComposeChatActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("task", skill.prompt)
                    }
                    startActivity(intent)
                    finish()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillsScreen(
    colors: BlackClawColors,
    onBack: () -> Unit,
    onRunSkill: (UserSkill) -> Unit,
) {
    var skills by remember { mutableStateOf(UserSkillStore.all()) }
    var editingSkill by remember { mutableStateOf<UserSkill?>(null) }
    var creatingNew by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Mis skills",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás", tint = colors.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface,
                    titleContentColor = colors.textPrimary,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { creatingNew = true },
                containerColor = colors.accent,
                contentColor = colors.background,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Crear skill")
            }
        },
    ) { padding ->
        if (skills.isEmpty()) {
            EmptyState(colors = colors, modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(skills, key = { it.id }) { skill ->
                    SkillCard(
                        skill = skill,
                        colors = colors,
                        onRun = { onRunSkill(skill) },
                        onEdit = { editingSkill = skill },
                        onDelete = {
                            UserSkillStore.delete(skill.id)
                            skills = UserSkillStore.all()
                        },
                    )
                }
            }
        }
    }

    if (creatingNew) {
        SkillEditorDialog(
            initial = null,
            colors = colors,
            onSave = { newSkill ->
                UserSkillStore.upsert(newSkill)
                skills = UserSkillStore.all()
                creatingNew = false
            },
            onDismiss = { creatingNew = false },
        )
    }
    editingSkill?.let { skill ->
        SkillEditorDialog(
            initial = skill,
            colors = colors,
            onSave = { updated ->
                UserSkillStore.upsert(updated)
                skills = UserSkillStore.all()
                editingSkill = null
            },
            onDismiss = { editingSkill = null },
        )
    }
}

@Composable
private fun EmptyState(colors: BlackClawColors, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("✨", fontSize = 36.sp)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Aún no tienes skills",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Crea automatizaciones reutilizables\ny ejecútalas con un solo toque.",
                fontSize = 13.sp,
                color = colors.textSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Pulsa + para crear la primera",
                fontSize = 12.sp,
                color = colors.accent,
            )
        }
    }
}

@Composable
private fun SkillCard(
    skill: UserSkill,
    colors: BlackClawColors,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "scale")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.aiBubbleBorder),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(skill.emoji, fontSize = 22.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        skill.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    if (skill.description.isNotBlank()) {
                        Text(
                            skill.description,
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                            maxLines = 2,
                        )
                    }
                }
            }
            if (skill.trigger.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = colors.aiBubble,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        "🎯  \"${skill.trigger}\"",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row {
                Button(
                    onClick = onRun,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.background,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Ejecutar", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Editar", tint = colors.textSecondary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = colors.textTertiary)
                }
            }
        }
    }
}

@Composable
private fun SkillEditorDialog(
    initial: UserSkill?,
    colors: BlackClawColors,
    onSave: (UserSkill) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var trigger by remember { mutableStateOf(initial?.trigger ?: "") }
    var prompt by remember { mutableStateOf(initial?.prompt ?: "") }
    var emoji by remember { mutableStateOf(initial?.emoji ?: "✨") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(20.dp),
            color = colors.surface,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    if (initial == null) "Nueva skill" else "Editar skill",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(14.dp))

                FieldLabel("Emoji", colors)
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it.take(2) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(0.4f),
                    colors = textFieldColors(colors),
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.height(10.dp))

                FieldLabel("Nombre", colors)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors(colors),
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(10.dp))

                FieldLabel("Descripción", colors)
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors(colors),
                    shape = RoundedCornerShape(10.dp),
                    maxLines = 2,
                )
                Spacer(Modifier.height(10.dp))

                FieldLabel("Disparador (opcional)", colors)
                OutlinedTextField(
                    value = trigger,
                    onValueChange = { trigger = it },
                    singleLine = true,
                    placeholder = { Text("ej. rutina mañana", color = colors.textTertiary) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors(colors),
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.height(10.dp))

                FieldLabel("Prompt", colors)
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    placeholder = {
                        Text(
                            "Lo que la IA debe hacer cuando se ejecute esta skill…",
                            color = colors.textTertiary,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    colors = textFieldColors(colors),
                    shape = RoundedCornerShape(10.dp),
                    maxLines = 6,
                )

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar", color = colors.textSecondary)
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = {
                            if (name.isNotBlank() && prompt.isNotBlank()) {
                                onSave(
                                    UserSkill(
                                        id = initial?.id ?: UUID.randomUUID().toString().take(8),
                                        name = name.trim(),
                                        description = description.trim(),
                                        trigger = trigger.trim(),
                                        prompt = prompt.trim(),
                                        emoji = emoji.ifBlank { "✨" },
                                        createdAtMs = initial?.createdAtMs ?: System.currentTimeMillis(),
                                        updatedAtMs = System.currentTimeMillis(),
                                    )
                                )
                            }
                        },
                        enabled = name.isNotBlank() && prompt.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            contentColor = colors.background,
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Guardar", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String, colors: BlackClawColors) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = colors.textSecondary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun textFieldColors(c: BlackClawColors) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = c.textPrimary,
    unfocusedTextColor = c.textPrimary,
    focusedBorderColor = c.accent,
    unfocusedBorderColor = c.inputBorder,
    cursorColor = c.accent,
    focusedContainerColor = c.background,
    unfocusedContainerColor = c.background,
)
