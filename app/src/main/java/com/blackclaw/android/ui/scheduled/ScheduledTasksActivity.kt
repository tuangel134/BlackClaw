package com.blackclaw.android.ui.scheduled

import android.os.Bundle
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.scheduler.ScheduledTaskManager
import com.blackclaw.android.automation.AutomationEngine
import com.blackclaw.android.automation.AutomationProfileEngine
import com.blackclaw.android.automation.AutomationProfileScheduler
import com.blackclaw.android.automation.AutomationProfileStore
import com.blackclaw.android.automation.AutomationProfileValidator
import com.blackclaw.android.automation.AutomationRuleStore
import com.blackclaw.android.automation.ExternalAutomationEntrypoint
import com.blackclaw.android.ui.chat.BlackClawColors
import com.blackclaw.android.ui.chat.ComposeChatActivity
import com.blackclaw.android.ui.chat.ThemeManager
import com.blackclaw.android.ui.chat.ThemeManager.toComposeColors
import com.blackclaw.android.ui.design.ClawGlassBackdrop
import com.blackclaw.android.ui.design.ClawGlassCard
import com.blackclaw.android.ui.design.ClawGlassPill
import com.blackclaw.android.ui.design.ClawReveal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ScheduledTasksActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        val colors = with(ThemeManager) { tc.toComposeColors() }
        setContent {
            ClawGlassBackdrop(colors = colors) {
                ScheduledTasksScreen(colors = colors, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduledTasksScreen(colors: BlackClawColors, onBack: () -> Unit) {
    var tasks by remember { mutableStateOf(ScheduledTaskManager.listAll()) }
    var rules by remember { mutableStateOf(AutomationRuleStore.list()) }
    var profiles by remember { mutableStateOf(AutomationProfileStore.list()) }
    // Advanced flows are the canonical Tasker-like surface. Legacy rules remain
    // available for installed users, but should not be the first mental model.
    var selected by remember { mutableStateOf(2) }
    var showAddRule by remember { mutableStateOf(false) }
    var showAddTask by remember { mutableStateOf(false) }
    var showAgentFlowBuilder by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                tasks = ScheduledTaskManager.listAll()
                rules = AutomationRuleStore.list()
                profiles = AutomationProfileStore.list()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text("Automatizaciones",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = colors.textPrimary)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás", tint = colors.textPrimary)
                    }
                },
                actions = {
                    if (selected == 0) {
                        IconButton(onClick = { showAddTask = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Nueva tarea", tint = colors.accent)
                        }
                        if (tasks.isNotEmpty()) {
                            IconButton(onClick = {
                                tasks.forEach { ScheduledTaskManager.cancel(ctx, it.id) }
                                tasks = ScheduledTaskManager.listAll()
                            }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "Eliminar todas", tint = colors.textTertiary)
                            }
                        }
                    } else if (selected == 1) {
                        IconButton(onClick = { showAddRule = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Nueva regla", tint = colors.accent)
                        }
                    } else if (selected == 2) {
                        IconButton(onClick = { showAgentFlowBuilder = true }) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Crear con BlackClaw", tint = colors.accent)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    titleContentColor = colors.textPrimary,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ClawGlassCard(
                colors = colors,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                elevated = false,
                radius = 24.dp,
            ) {
                Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(
                        Triple("Agenda", tasks.size, "⏱"),
                        Triple("Reglas", rules.size, "⚡"),
                        Triple("Flujos", profiles.size, "✦"),
                    ).forEachIndexed { index, item ->
                        ClawGlassPill(
                            colors = colors,
                            selected = selected == index,
                            modifier = Modifier.weight(1f),
                            onClick = { selected = index },
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 9.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("${item.third} ${item.first}",
                                    color = if (selected == index) colors.accent else colors.textPrimary,
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("${item.second}", color = colors.textSecondary, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
            if (selected == 0) {
                if (tasks.isEmpty()) EmptyState(colors, Modifier.weight(1f), onCreate = { showAddTask = true }) else LazyColumn(
                    modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(tasks, key = { it.id }) { task -> TaskCard(task, colors) {
                        ScheduledTaskManager.cancel(ctx, task.id); tasks = ScheduledTaskManager.listAll()
                    } }
                }
            } else if (selected == 1) {
                if (rules.isEmpty()) RuleEmptyState(colors, Modifier.weight(1f)) else LazyColumn(
                    modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(rules, key = { it.id }) { rule -> RuleCard(rule, colors,
                        onToggle = { AutomationRuleStore.setEnabled(rule.id, it); rules = AutomationRuleStore.list() },
                        onRun = { AutomationEngine.fire(ctx, rule) },
                        onDelete = { AutomationRuleStore.delete(rule.id); rules = AutomationRuleStore.list() }) }
                }
            } else {
                if (profiles.isEmpty()) {
                    ProfileEmptyState(
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        onCreateWithAgent = { showAgentFlowBuilder = true },
                        onCreateManual = { ctx.startActivity(AutomationProfileEditorActivity.editIntent(ctx)) },
                    )
                } else LazyColumn(
                    modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "flow-hero") {
                        FlowHeroCard(
                            profiles = profiles,
                            colors = colors,
                            onCreateWithAgent = { showAgentFlowBuilder = true },
                            onCreateManual = { ctx.startActivity(AutomationProfileEditorActivity.editIntent(ctx)) },
                        )
                    }
                    itemsIndexed(profiles, key = { _, item -> item.id }) { index, profile ->
                        ClawReveal(index = index.coerceAtMost(8)) {
                            ProfileCard(profile, colors,
                                onToggle = {
                                    AutomationProfileStore.setEnabled(profile.id, it)
                                    AutomationProfileScheduler.sync(ctx)
                                    profiles = AutomationProfileStore.list()
                                },
                                onRun = { AutomationProfileEngine.runNow(ctx, profile) },
                                onEdit = { ctx.startActivity(AutomationProfileEditorActivity.editIntent(ctx, profile.id)) },
                                onDelete = {
                                    AutomationProfileStore.delete(profile.id)
                                    AutomationProfileScheduler.sync(ctx)
                                    profiles = AutomationProfileStore.list()
                                })
                        }
                    }
                }
            }
        }
    }
    if (showAddRule) AddRuleDialog(colors, onDismiss = { showAddRule = false }) {
        rules = AutomationRuleStore.list(); showAddRule = false
    }
    if (showAddTask) CreateTaskSheet(
        colors = colors,
        onDismiss = { showAddTask = false },
        onSaved = {
            tasks = ScheduledTaskManager.listAll()
            showAddTask = false
        },
    )
    if (showAgentFlowBuilder) AgentFlowBuilderDialog(
        colors = colors,
        onDismiss = { showAgentFlowBuilder = false },
        onCreate = { request ->
            showAgentFlowBuilder = false
            val task = buildString {
                append("Crea una automatización BlackClaw basada exactamente en esta solicitud del usuario: ")
                append(request.trim())
                append("\n\nUsa la herramienta automation_profile. Consulta capabilities si necesitas conocer disparadores o acciones disponibles, valida el flujo antes de guardarlo y guárdalo como draft para que el usuario pueda revisarlo en Automatizaciones > Flujos. No lo actives ni ejecutes sin confirmación local explícita. Conserva la intención del usuario y prefiere acciones determinísticas TOOL cuando exista una herramienta adecuada.")
            }
            ctx.startActivity(Intent(ctx, ComposeChatActivity::class.java).apply {
                putExtra(ExternalAutomationEntrypoint.EXTRA_TASK, task)
            })
        },
    )
}

@Composable
private fun RuleEmptyState(colors: BlackClawColors, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⚡", fontSize = 42.sp); Spacer(Modifier.height(12.dp))
            Text("Sin reglas todavía", color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
            Text("Crea reglas por notificación o ubicación con +\no pídele a BlackClaw: “si pasa X, haz Y”",
                color = colors.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AgentFlowBuilderDialog(
    colors: BlackClawColors,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var request by remember { mutableStateOf("") }
    val examples = remember {
        listOf(
            "Cuando llegue a casa, activa Wi‑Fi y avísame",
            "Si recibo una notificación del trabajo, sube el volumen",
            "Cada mañana revisa mi batería y avísame si está baja",
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface.copy(alpha = .97f),
        shape = RoundedCornerShape(28.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                        .background(colors.accent.copy(alpha = .15f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = colors.accent) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Crear con BlackClaw", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                    Text("Descríbelo con tus palabras", color = colors.textSecondary, fontSize = 11.sp)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "BlackClaw revisará sus capacidades, construirá el flujo y lo guardará como borrador. Nada se activará sin que tú lo confirmes.",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
                OutlinedTextField(
                    value = request,
                    onValueChange = { request = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(18.dp),
                    placeholder = { Text("Ej.: cuando conecte mis audífonos, abre Spotify y pon el volumen al 40%") },
                    label = { Text("¿Qué quieres automatizar?") },
                )
                Text("Ideas rápidas", color = colors.textTertiary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                examples.forEach { example ->
                    Surface(
                        onClick = { request = example },
                        color = colors.accent.copy(alpha = .08f),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(.6.dp, colors.accent.copy(alpha = .22f)),
                    ) {
                        Text(example, color = colors.textSecondary, fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = request.isNotBlank(),
                onClick = { onCreate(request) },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.background),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Crear borrador", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = colors.textSecondary) }
        },
    )
}

@Composable
private fun FlowHeroCard(
    profiles: List<AutomationProfileStore.Profile>,
    colors: BlackClawColors,
    onCreateWithAgent: () -> Unit,
    onCreateManual: () -> Unit,
) {
    val active = profiles.count { it.enabled }
    val runs = profiles.sumOf { it.runCount }
    ClawGlassCard(colors = colors, modifier = Modifier.fillMaxWidth(), radius = 26.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                        .background(colors.accent.copy(alpha = .16f)),
                    contentAlignment = Alignment.Center,
                ) { Text("✦", color = colors.accent, fontSize = 26.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Flujos inteligentes", color = colors.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Dile a BlackClaw qué debe pasar y qué hacer; él arma el flujo y tú lo revisas.",
                        color = colors.textSecondary, fontSize = 12.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowMetric("$active", "activos", colors, Modifier.weight(1f))
                FlowMetric("${profiles.size - active}", "borradores", colors, Modifier.weight(1f))
                FlowMetric("$runs", "ejecuciones", colors, Modifier.weight(1f))
            }
            Button(
                onClick = onCreateWithAgent,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.background),
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("Crear con BlackClaw", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onCreateManual, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Default.Tune, contentDescription = null, tint = colors.textSecondary)
                Spacer(Modifier.width(6.dp))
                Text("Editor manual", color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun FlowMetric(value: String, label: String, colors: BlackClawColors, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(colors.accent.copy(alpha = .08f))
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Column {
            Text(value, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(label, color = colors.textSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ProfileEmptyState(
    colors: BlackClawColors,
    modifier: Modifier = Modifier,
    onCreateWithAgent: () -> Unit,
    onCreateManual: () -> Unit,
) {
    Box(modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        ClawGlassCard(colors = colors, modifier = Modifier.fillMaxWidth(), radius = 28.dp) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("✦", color = colors.accent, fontSize = 44.sp)
                Spacer(Modifier.height(12.dp))
                Text("Tu primera automatización", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text("Prueba: “cuando llegue a casa, activa Wi‑Fi y avísame”.\nBlackClaw puede crearla como borrador para que la revises antes de activarla.",
                    color = colors.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(18.dp))
                Button(onClick = onCreateWithAgent, shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.background)) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Crear con BlackClaw", fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onCreateManual) {
                    Text("Abrir editor manual", color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: AutomationProfileStore.Profile, colors: BlackClawColors,
                        onToggle: (Boolean) -> Unit, onRun: () -> Unit, onEdit: () -> Unit,
                        onDelete: () -> Unit) {
    val validationErrors = remember(profile) { AutomationProfileValidator.validate(profile) }
    ClawGlassCard(colors = colors, modifier = Modifier.fillMaxWidth(), radius = 22.dp, elevated = false) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🧩", fontSize = 24.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.name, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text("${if (profile.enabled) "ACTIVO" else "BORRADOR"} · ${profile.triggers.size} disparador(es) · ${profile.actions.size} acción(es)",
                        color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                Switch(checked = profile.enabled, onCheckedChange = onToggle)
            }
            if (profile.description.isNotBlank()) {
                Text(profile.description, color = colors.textSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 5.dp))
            }
            if (validationErrors.isNotEmpty()) {
                Text("Necesita revisión: ${validationErrors.first()}", color = colors.textTertiary,
                    fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
            }
            Text("Límite: ${if (profile.maxRunsPerDay == 0) "sin límite diario" else "${profile.maxRunsPerDay}/día"} · cooldown ${profile.cooldownMs / 1000}s · máximo ${profile.maxRuntimeMs / 1000}s",
                color = colors.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
            if (profile.lastStatus != "never") {
                Text("Último resultado: ${profile.lastStatus}${profile.lastError.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}",
                    color = if (profile.lastStatus == "success") colors.accent else colors.textTertiary,
                    fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = validationErrors.isEmpty(), onClick = onRun) {
                    Text("Probar", color = if (validationErrors.isEmpty()) colors.accent else colors.textTertiary)
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, "Editar", tint = colors.accent)
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Eliminar", tint = colors.textTertiary) }
            }
        }
    }
}

@Composable
private fun RuleCard(rule: AutomationRuleStore.Rule, colors: BlackClawColors,
                     onToggle: (Boolean) -> Unit, onRun: () -> Unit, onDelete: () -> Unit) {
    ClawGlassCard(colors = colors, modifier = Modifier.fillMaxWidth(), radius = 20.dp, elevated = false) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (rule.trigger == AutomationRuleStore.Trigger.NOTIFICATION) "🔔" else "📍", fontSize = 24.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(rule.name, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text(rule.trigger.name.lowercase().replace('_', ' '), color = colors.accent, fontSize = 11.sp)
                }
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            if (rule.match.isNotBlank()) Text("SI contiene: ${rule.match}", color = colors.textSecondary, fontSize = 12.sp)
            Text("ENTONCES: ${rule.actionText}", color = colors.textPrimary, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRun) { Text("Probar", color = colors.accent) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Eliminar", tint = colors.textTertiary) }
            }
        }
    }
}

@Composable
private fun AddRuleDialog(colors: BlackClawColors, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var name by remember { mutableStateOf("") }; var match by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("") }; var pkg by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }; var lon by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf(AutomationRuleStore.Trigger.NOTIFICATION) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = colors.surface,
        title = { Text("Nueva automatización", color = colors.textPrimary) },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AutomationRuleStore.Trigger.entries.forEach { t -> FilterChip(selected = trigger == t,
                    onClick = { trigger = t }, label = { Text(when(t) {
                        AutomationRuleStore.Trigger.NOTIFICATION -> "Notificación"
                        AutomationRuleStore.Trigger.LOCATION_ENTER -> "Llegar"
                        AutomationRuleStore.Trigger.LOCATION_EXIT -> "Salir"
                    }, fontSize = 11.sp) }) }
            }
            if (trigger == AutomationRuleStore.Trigger.NOTIFICATION) {
                OutlinedTextField(match, { match = it }, label = { Text("Contacto o texto") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pkg, { pkg = it }, label = { Text("Paquete opcional (com.whatsapp)") }, modifier = Modifier.fillMaxWidth())
            } else {
                OutlinedTextField(lat, { lat = it }, label = { Text("Latitud") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lon, { lon = it }, label = { Text("Longitud") }, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(action, { action = it }, label = { Text("Acciones en orden") }, minLines = 3,
                placeholder = { Text("Apaga datos móviles, enciende Wi-Fi y verifica") }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank() && action.isNotBlank() &&
            (trigger == AutomationRuleStore.Trigger.NOTIFICATION || (lat.toDoubleOrNull() != null && lon.toDoubleOrNull() != null)),
            onClick = {
                AutomationRuleStore.create(name, trigger, match, pkg, action,
                    lat.toDoubleOrNull() ?: 0.0, lon.toDoubleOrNull() ?: 0.0)
                onSaved()
            }) { Text("Guardar", color = colors.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun EmptyState(colors: BlackClawColors, modifier: Modifier = Modifier, onCreate: () -> Unit = {}) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(72.dp).clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Text("⏰", fontSize = 36.sp) }
            Spacer(Modifier.height(20.dp))
            Text("Sin tareas programadas", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text("Pídele a la IA \"recuérdame X mañana a las 9\"\ny aparecerán aquí.",
                fontSize = 13.sp, color = colors.textSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onCreate, shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Crear tarea", color = colors.background, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private enum class QuickTaskWhen { IN_30_MIN, TODAY_18, TOMORROW_09, CUSTOM }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateTaskSheet(
    colors: BlackClawColors,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val now = remember { System.currentTimeMillis() }
    var text by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ScheduledTaskManager.Mode.TASK) }
    var selectedWhen by remember { mutableStateOf(QuickTaskWhen.IN_30_MIN) }
    var customTime by remember { mutableStateOf<Long?>(null) }
    var recurrence by remember { mutableStateOf(ScheduledTaskManager.Recurrence.ONCE) }
    var error by remember { mutableStateOf<String?>(null) }

    fun clock(hour: Int, minute: Int, dayOffset: Int = 0): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (dayOffset == 0 && calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    fun openCustomPicker() {
        val seed = Calendar.getInstance()
        val picker = DatePickerDialog(context, { _, year, month, day ->
            val date = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, day)
            }
            TimePickerDialog(context, { _, hour, minute ->
                date.set(Calendar.HOUR_OF_DAY, hour)
                date.set(Calendar.MINUTE, minute)
                date.set(Calendar.SECOND, 0)
                date.set(Calendar.MILLISECOND, 0)
                customTime = date.timeInMillis
                selectedWhen = QuickTaskWhen.CUSTOM
            }, seed.get(Calendar.HOUR_OF_DAY), seed.get(Calendar.MINUTE), true).show()
        }, seed.get(Calendar.YEAR), seed.get(Calendar.MONTH), seed.get(Calendar.DAY_OF_MONTH))
        picker.datePicker.minDate = System.currentTimeMillis()
        picker.show()
    }

    val triggerAtMs = when (selectedWhen) {
        QuickTaskWhen.IN_30_MIN -> now + 30 * 60_000L
        QuickTaskWhen.TODAY_18 -> clock(18, 0)
        QuickTaskWhen.TOMORROW_09 -> clock(9, 0, 1)
        QuickTaskWhen.CUSTOM -> customTime ?: (now + 30 * 60_000L)
    }
    val dateFormat = remember { SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()) }
    val triggerLabel = dateFormat.format(Date(triggerAtMs))
    val eveningTrigger = clock(18, 0)
    val eveningLabel = if (eveningTrigger - now < 12 * 60 * 60_000L) "Hoy 18:00" else "Mañana 18:00"
    val canSave = text.trim().isNotEmpty() && triggerAtMs > System.currentTimeMillis()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.textTertiary) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(15.dp))
                        .background(colors.accent.copy(alpha = .16f)),
                    contentAlignment = Alignment.Center,
                ) { Text("✦", color = colors.accent, fontSize = 25.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Nueva tarea", color = colors.textPrimary, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("Se ejecutará aunque cierres BlackClaw", color = colors.textSecondary, fontSize = 12.sp)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = colors.textTertiary)
                }
            }

            Text("¿Qué quieres que haga?", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 5,
                placeholder = { Text("Ej.: revisa el clima y avísame si lloverá") },
                supportingText = { Text("Puedes escribirlo como se lo dirías a BlackClaw", color = colors.textTertiary) },
                shape = RoundedCornerShape(16.dp),
            )

            Text("Tipo de ejecución", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTaskChoice(
                    colors = colors,
                    selected = mode == ScheduledTaskManager.Mode.TASK,
                    icon = Icons.Default.PlayArrow,
                    title = "Ejecutar",
                    subtitle = "BlackClaw actuará",
                    modifier = Modifier.weight(1f),
                    onClick = { mode = ScheduledTaskManager.Mode.TASK },
                )
                QuickTaskChoice(
                    colors = colors,
                    selected = mode == ScheduledTaskManager.Mode.CHAT,
                    icon = Icons.Default.NotificationsActive,
                    title = "Avisarme",
                    subtitle = "Solo notificación",
                    modifier = Modifier.weight(1f),
                    onClick = { mode = ScheduledTaskManager.Mode.CHAT },
                )
            }

            Text("¿Cuándo?", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickTaskChoice(
                    colors = colors, selected = selectedWhen == QuickTaskWhen.IN_30_MIN,
                    icon = Icons.Default.Timer, title = "30 min", subtitle = "Desde ahora",
                    modifier = Modifier.weight(1f), onClick = { selectedWhen = QuickTaskWhen.IN_30_MIN },
                )
                QuickTaskChoice(
                    colors = colors, selected = selectedWhen == QuickTaskWhen.TODAY_18,
                    icon = Icons.Default.WbSunny, title = eveningLabel, subtitle = "Siguiente turno",
                    modifier = Modifier.weight(1f), onClick = { selectedWhen = QuickTaskWhen.TODAY_18 },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickTaskChoice(
                    colors = colors, selected = selectedWhen == QuickTaskWhen.TOMORROW_09,
                    icon = Icons.Default.Event, title = "Mañana 09:00", subtitle = "Al comenzar el día",
                    modifier = Modifier.weight(1f), onClick = { selectedWhen = QuickTaskWhen.TOMORROW_09 },
                )
                QuickTaskChoice(
                    colors = colors, selected = selectedWhen == QuickTaskWhen.CUSTOM,
                    icon = Icons.Default.Schedule, title = "Elegir fecha", subtitle = "Personalizado",
                    modifier = Modifier.weight(1f), onClick = ::openCustomPicker,
                )
            }

            Text("Repetición", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ScheduledTaskManager.Recurrence.ONCE to "Una vez",
                    ScheduledTaskManager.Recurrence.DAILY to "Cada día",
                    ScheduledTaskManager.Recurrence.WEEKLY to "Cada semana",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = recurrence == value,
                        onClick = { recurrence = value },
                        label = { Text(label, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Surface(
                color = colors.surface,
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.accent.copy(alpha = .35f)),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Vista previa", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${if (mode == ScheduledTaskManager.Mode.TASK) "🤖 BlackClaw ejecutará" else "🔔 Te avisaré"} · $triggerLabel",
                        color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text.ifBlank { "Escribe una tarea para verla aquí" },
                        color = if (text.isBlank()) colors.textTertiary else colors.textSecondary,
                        fontSize = 13.sp,
                    )
                    if (recurrence != ScheduledTaskManager.Recurrence.ONCE) {
                        Text(
                            if (recurrence == ScheduledTaskManager.Recurrence.DAILY) "Se repetirá cada día" else "Se repetirá cada semana",
                            color = colors.accent, fontSize = 11.sp,
                        )
                    }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            Button(
                onClick = {
                    if (text.isBlank()) {
                        error = "Escribe qué quieres que haga BlackClaw."
                    } else if (triggerAtMs <= System.currentTimeMillis()) {
                        error = "Elige una hora futura."
                    } else {
                        ScheduledTaskManager.schedule(
                            context = context,
                            mode = mode,
                            text = text.trim(),
                            triggerAtMs = triggerAtMs,
                            recurrence = recurrence,
                        )
                        onSaved()
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(17.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.background,
                    disabledContainerColor = colors.surface,
                    disabledContentColor = colors.textTertiary,
                ),
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Programar tarea", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun QuickTaskChoice(
    colors: BlackClawColors,
    selected: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(
        if (selected) colors.accent.copy(alpha = .16f) else colors.surface,
        label = "task-choice-container",
    )
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.2.dp else .5.dp,
            if (selected) colors.accent else colors.aiBubbleBorder,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) colors.accent else colors.textSecondary,
                modifier = Modifier.size(22.dp),
            )
            Text(title, color = if (selected) colors.accent else colors.textPrimary,
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(subtitle, color = colors.textSecondary, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun TaskCard(
    task: ScheduledTaskManager.ScheduledTask,
    colors: BlackClawColors,
    onCancel: () -> Unit,
) {
    val df = SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault())
    val recurStr = when (task.recurrence) {
        ScheduledTaskManager.Recurrence.ONCE -> "una vez"
        ScheduledTaskManager.Recurrence.HOURLY -> "cada hora"
        ScheduledTaskManager.Recurrence.DAILY -> "cada día"
        ScheduledTaskManager.Recurrence.WEEKLY -> "cada semana"
        ScheduledTaskManager.Recurrence.INTERVAL -> "cada ${task.intervalMs / 60_000}m"
    }
    val modeIcon = if (task.mode == ScheduledTaskManager.Mode.TASK) "🤖" else "💬"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.aiBubbleBorder),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                        .background(colors.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { Text(modeIcon, fontSize = 22.sp) }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(df.format(Date(task.triggerAtMs)),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    Text(recurStr, fontSize = 11.sp, color = colors.accent)
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancelar", tint = colors.textTertiary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(color = colors.aiBubble, shape = RoundedCornerShape(8.dp)) {
                Text("\"${task.text}\"", fontSize = 12.sp, color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}
