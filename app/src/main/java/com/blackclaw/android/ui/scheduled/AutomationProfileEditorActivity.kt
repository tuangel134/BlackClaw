package com.blackclaw.android.ui.scheduled

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackclaw.android.automation.AutomationProfileScheduler
import com.blackclaw.android.automation.AutomationProfileStore
import com.blackclaw.android.automation.AutomationProfileValidator
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.ui.chat.BlackClawColors
import com.blackclaw.android.ui.chat.ThemeManager
import com.blackclaw.android.ui.chat.ThemeManager.toComposeColors
import com.blackclaw.android.ui.design.ClawGlassBackdrop
import com.blackclaw.android.ui.design.ClawGlassCard
import com.blackclaw.android.ui.design.ClawGlassPill
import com.blackclaw.android.ui.design.ClawReveal
import org.json.JSONObject

/** Visual editor for deterministic profiles. JSON remains an import/export detail for the AI. */
class AutomationProfileEditorActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val existing = intent.getStringExtra(EXTRA_PROFILE_ID)
            ?.let { AutomationProfileStore.find(it) }
        val theme = ThemeManager.getColors()
        window.statusBarColor = theme.toolbarBg
        val colors = with(ThemeManager) { theme.toComposeColors() }
        setContent {
            ClawGlassBackdrop(colors = colors) {
                AutomationProfileEditorScreen(
                    colors = colors,
                    initial = existing,
                    onBack = { finish() },
                    onSaved = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "automation_profile_id"

        fun editIntent(context: android.content.Context, id: String? = null): Intent =
            Intent(context, AutomationProfileEditorActivity::class.java).apply {
                id?.let { putExtra(EXTRA_PROFILE_ID, it) }
            }
    }
}

private data class TriggerDraft(
    val type: AutomationProfileStore.TriggerType,
    val params: Map<String, String> = emptyMap(),
)

private data class ConditionDraft(
    val type: AutomationProfileStore.ConditionType,
    val params: Map<String, String> = emptyMap(),
    val negate: Boolean = false,
)

private data class ActionDraft(
    val type: AutomationProfileStore.ActionType,
    val primary: String = "",
    val secondary: String = "",
    val paramsText: String = "",
    val rawParams: Map<String, Any> = emptyMap(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomationProfileEditorScreen(
    colors: BlackClawColors,
    initial: AutomationProfileStore.Profile?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var cooldown by remember { mutableStateOf((initial?.cooldownMs ?: 60_000L).toString()) }
    var maxRuns by remember { mutableStateOf((initial?.maxRunsPerDay ?: 0).toString()) }
    var maxRuntime by remember { mutableStateOf((initial?.maxRuntimeMs ?: 600_000L).toString()) }
    var concurrency by remember {
        mutableStateOf(initial?.concurrency ?: AutomationProfileStore.Concurrency.SKIP_IF_RUNNING)
    }
    val triggers = remember { mutableStateListOf<TriggerDraft>().apply { addAll(initial.toTriggerDrafts()) } }
    val conditions = remember { mutableStateListOf<ConditionDraft>().apply { addAll(initial.toConditionDrafts()) } }
    val actions = remember { mutableStateListOf<ActionDraft>().apply { addAll(initial.toActionDrafts()) } }
    var showTriggerPicker by remember { mutableStateOf(false) }
    var showConditionPicker by remember { mutableStateOf(false) }
    var showActionPicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    fun save() {
        val edited = AutomationProfileStore.Profile(
            id = initial?.id.orEmpty(),
            name = name.trim(),
            description = description.trim(),
            enabled = enabled,
            triggers = triggers.map { AutomationProfileStore.Trigger(it.type, it.params.toAnyMap()) },
            conditions = conditions.map {
                AutomationProfileStore.Condition(it.type, it.params.toAnyMap(), it.negate)
            },
            actions = actions.map { it.toAction() },
            cooldownMs = cooldown.toLongOrNull() ?: -1L,
            maxRunsPerDay = maxRuns.toIntOrNull() ?: -1,
            maxRuntimeMs = maxRuntime.toLongOrNull() ?: -1L,
            concurrency = concurrency,
            approvedAtMs = initial?.approvedAtMs ?: 0L,
        )
        // Preserve execution history when the user edits an existing flow. Previously
        // saving from the visual editor reset runCount/lastStatus/createdAt metadata.
        val profile = initial?.copy(
            name = edited.name,
            description = edited.description,
            enabled = edited.enabled,
            triggers = edited.triggers,
            conditions = edited.conditions,
            actions = edited.actions,
            cooldownMs = edited.cooldownMs,
            maxRunsPerDay = edited.maxRunsPerDay,
            maxRuntimeMs = edited.maxRuntimeMs,
            concurrency = edited.concurrency,
        ) ?: edited
        val errors = AutomationProfileValidator.validate(profile)
        if (errors.isNotEmpty()) {
            error = errors.joinToString(" ")
            return
        }
        val stored = AutomationProfileStore.create(profile, enable = enabled)
        if (stored.isFailure) {
            error = stored.exceptionOrNull()?.message ?: "No se pudo guardar el perfil."
            return
        }
        AutomationProfileScheduler.sync(context)
        onSaved()
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "Nuevo flujo" else "Editar flujo", color = colors.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Atrás", tint = colors.textPrimary) }
                },
                actions = {
                    IconButton(onClick = ::save) { Icon(Icons.Default.Save, "Guardar", tint = colors.accent) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ClawReveal {
                    FlowBuilderOverview(
                        colors = colors,
                        triggerCount = triggers.size,
                        conditionCount = conditions.size,
                        actionCount = actions.size,
                        enabled = enabled,
                    )
                }
            }
            item {
                ClawReveal(index = 1) {
                SectionCard(colors, "Identidad") {
                    OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(description, { description = it }, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Activo al guardar", color = colors.textPrimary, modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                }
                }
            }
            item {
                SectionTitle(colors, "CUANDO", "El flujo comienza si coincide cualquiera de estos disparadores")
                if (triggers.isEmpty()) EmptyEditorHint(colors, "Añade al menos un disparador")
            }
            itemsIndexed(triggers, key = { index, item -> "trigger-$index-${item.type}" }) { index, draft ->
                TriggerEditorCard(colors, draft,
                    onChange = { triggers[index] = it },
                    onDelete = { triggers.removeAt(index) },
                    onMoveUp = { if (index > 0) triggers.add(index - 1, triggers.removeAt(index)) },
                    onMoveDown = { if (index < triggers.lastIndex) triggers.add(index + 1, triggers.removeAt(index)) })
            }
            item { AddBlockButton(colors, "Añadir disparador") { showTriggerPicker = true } }

            item {
                SectionTitle(colors, "SI", "Opcional: todas estas condiciones deben cumplirse")
                if (conditions.isEmpty()) EmptyEditorHint(colors, "Opcional: limita por horario, batería, app o variables")
            }
            itemsIndexed(conditions, key = { index, item -> "condition-$index-${item.type}" }) { index, draft ->
                ConditionEditorCard(colors, draft,
                    onChange = { conditions[index] = it },
                    onDelete = { conditions.removeAt(index) },
                    onMoveUp = { if (index > 0) conditions.add(index - 1, conditions.removeAt(index)) },
                    onMoveDown = { if (index < conditions.lastIndex) conditions.add(index + 1, conditions.removeAt(index)) })
            }
            item { AddBlockButton(colors, "Añadir condición") { showConditionPicker = true } }

            item {
                SectionTitle(colors, "HAZ", "BlackClaw ejecuta estas acciones en orden")
                if (actions.isEmpty()) EmptyEditorHint(colors, "Añade al menos una acción")
            }
            itemsIndexed(actions, key = { index, item -> "action-$index-${item.type}" }) { index, draft ->
                ActionEditorCard(colors, draft,
                    onChange = { actions[index] = it },
                    onDelete = { actions.removeAt(index) },
                    onMoveUp = { if (index > 0) actions.add(index - 1, actions.removeAt(index)) },
                    onMoveDown = { if (index < actions.lastIndex) actions.add(index + 1, actions.removeAt(index)) })
            }
            item { AddBlockButton(colors, "Añadir acción") { showActionPicker = true } }

            item {
                SectionCard(colors, "Límites y ejecución") {
                    NumberField(cooldown, { cooldown = it }, "Cooldown (ms)")
                    NumberField(maxRuns, { maxRuns = it }, "Máximo de ejecuciones por día (0 = sin límite)")
                    NumberField(maxRuntime, { maxRuntime = it }, "Tiempo máximo de una ejecución (ms)")
                    Picker(colors, "Concurrencia", concurrency, AutomationProfileStore.Concurrency.values().toList()) {
                        concurrency = it
                    }
                }
            }
            error?.let { message -> item { Text(message, color = colors.textTertiary, fontSize = 13.sp) } }
            item {
                Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Save, null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (enabled) "Guardar y activar" else "Guardar borrador")
                }
            }
        }
    }

    if (showTriggerPicker) EnumPickerDialog("Disparador", AutomationProfileStore.TriggerType.values().toList(), {
        triggers += TriggerDraft(it)
        showTriggerPicker = false
    }, { showTriggerPicker = false })
    if (showConditionPicker) EnumPickerDialog("Condición", AutomationProfileStore.ConditionType.values().toList(), {
        conditions += ConditionDraft(it)
        showConditionPicker = false
    }, { showConditionPicker = false })
    if (showActionPicker) EnumPickerDialog("Acción", AutomationProfileStore.ActionType.values().toList(), {
        actions += ActionDraft(it)
        showActionPicker = false
    }, { showActionPicker = false })
}

@Composable
private fun FlowBuilderOverview(
    colors: BlackClawColors,
    triggerCount: Int,
    conditionCount: Int,
    actionCount: Int,
    enabled: Boolean,
) {
    ClawGlassCard(colors = colors, modifier = Modifier.fillMaxWidth(), radius = 26.dp) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Constructor de flujo", color = colors.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("Diseña la lógica como una frase: CUANDO ocurra algo, SI se cumplen los filtros, HAZ una o varias acciones.",
                color = colors.textSecondary, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(
                    Triple("CUANDO", triggerCount, triggerCount > 0),
                    Triple("SI", conditionCount, true),
                    Triple("HAZ", actionCount, actionCount > 0),
                ).forEach { (label, count, ready) ->
                    ClawGlassPill(
                        colors = colors,
                        selected = ready,
                        modifier = Modifier.weight(1f),
                        onClick = {},
                    ) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, color = if (ready) colors.accent else colors.textSecondary,
                                fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("$count", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Text(
                if (enabled) "Se guardará ACTIVO" else "Se guardará como BORRADOR y no se ejecutará",
                color = if (enabled) colors.accent else colors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SectionCard(colors: BlackClawColors, title: String, content: @Composable () -> Unit) {
    ClawGlassCard(colors = colors, modifier = Modifier.fillMaxWidth(), radius = 20.dp, elevated = false) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SectionTitle(colors: BlackClawColors, title: String, subtitle: String) {
    Column {
        Text(title, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = colors.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun EmptyEditorHint(colors: BlackClawColors, text: String) {
    Text(text, color = colors.textTertiary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun AddBlockButton(colors: BlackClawColors, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, null, tint = colors.accent)
        Spacer(Modifier.width(3.dp))
        Text(label, color = colors.accent)
    }
}

@Composable
private fun TriggerEditorCard(colors: BlackClawColors, draft: TriggerDraft,
                              onChange: (TriggerDraft) -> Unit, onDelete: () -> Unit,
                              onMoveUp: () -> Unit, onMoveDown: () -> Unit) {
    SectionCard(colors, "Cuando · ${draft.type.label()}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Picker(colors, "Tipo", draft.type, AutomationProfileStore.TriggerType.values().toList(), modifier = Modifier.weight(1f)) {
                onChange(TriggerDraft(it, emptyMap()))
            }
            IconButton(onClick = onMoveUp) { Icon(Icons.Default.ArrowUpward, "Subir", tint = colors.textTertiary) }
            IconButton(onClick = onMoveDown) { Icon(Icons.Default.ArrowDownward, "Bajar", tint = colors.textTertiary) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Eliminar", tint = colors.textTertiary) }
        }
        TriggerParams(colors, draft, onChange)
    }
}

@Composable
private fun TriggerParams(colors: BlackClawColors, draft: TriggerDraft, onChange: (TriggerDraft) -> Unit) {
    @Composable
    fun field(key: String, label: String) {
        val value = draft.params[key].orEmpty()
        OutlinedTextField(value, { onChange(draft.withParam(key, it)) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
    }
    when (draft.type) {
        AutomationProfileStore.TriggerType.TIME -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft.params["hour"].orEmpty(), { onChange(draft.withParam("hour", it)) }, label = { Text("Hora") }, modifier = Modifier.weight(1f))
                OutlinedTextField(draft.params["minute"].orEmpty(), { onChange(draft.withParam("minute", it)) }, label = { Text("Minuto") }, modifier = Modifier.weight(1f))
            }
            field("days", "Días: daily, weekdays o mon,wed")
        }
        AutomationProfileStore.TriggerType.NOTIFICATION -> { field("package", "Paquete opcional"); field("match", "Texto a buscar") }
        AutomationProfileStore.TriggerType.LOCATION_ENTER, AutomationProfileStore.TriggerType.LOCATION_EXIT -> {
            field("latitude", "Latitud"); field("longitude", "Longitud"); field("radius_m", "Radio en metros")
        }
        AutomationProfileStore.TriggerType.APP_FOREGROUND, AutomationProfileStore.TriggerType.APP_CLOSED -> field("package", "Paquete")
        AutomationProfileStore.TriggerType.CONNECTIVITY -> { field("state", "online/offline"); field("transport", "wifi/cellular/none") }
        AutomationProfileStore.TriggerType.BATTERY -> { field("min", "Mínimo %"); field("max", "Máximo %") }
        AutomationProfileStore.TriggerType.CHARGING -> field("value", "true/false")
        AutomationProfileStore.TriggerType.SCREEN -> field("state", "on/off/unlocked")
        AutomationProfileStore.TriggerType.HEADSET, AutomationProfileStore.TriggerType.BLUETOOTH -> { field("connected", "true/false"); field("name", "Nombre opcional") }
        AutomationProfileStore.TriggerType.WIFI -> { field("connected", "true/false"); field("ssid", "SSID opcional") }
        AutomationProfileStore.TriggerType.CALL_STATE -> { field("state", "ringing/offhook/idle"); field("number", "Número opcional") }
        AutomationProfileStore.TriggerType.SMS_RECEIVED -> { field("sender", "Remitente opcional"); field("match", "Texto a buscar") }
        AutomationProfileStore.TriggerType.WEBHOOK -> field("token", "Token del Intent de Tasker")
        AutomationProfileStore.TriggerType.MANUAL, AutomationProfileStore.TriggerType.BOOT -> Unit
    }
}

@Composable
private fun ConditionEditorCard(colors: BlackClawColors, draft: ConditionDraft,
                                onChange: (ConditionDraft) -> Unit, onDelete: () -> Unit,
                                onMoveUp: () -> Unit, onMoveDown: () -> Unit) {
    SectionCard(colors, "Si · ${draft.type.label()}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Picker(colors, "Tipo", draft.type, AutomationProfileStore.ConditionType.values().toList(), modifier = Modifier.weight(1f)) { onChange(draft.copy(type = it, params = emptyMap())) }
            IconButton(onClick = onMoveUp) { Icon(Icons.Default.ArrowUpward, "Subir", tint = colors.textTertiary) }
            IconButton(onClick = onMoveDown) { Icon(Icons.Default.ArrowDownward, "Bajar", tint = colors.textTertiary) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Eliminar", tint = colors.textTertiary) }
        }
        val params = draft.params.toEditorText()
        OutlinedTextField(params, { onChange(draft.copy(params = parseEditorParams(it))) },
            label = { Text("Parámetros (clave=valor, separados por coma)") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Invertir condición", color = colors.textPrimary, modifier = Modifier.weight(1f))
            Switch(draft.negate, { onChange(draft.copy(negate = it)) })
        }
    }
}

@Composable
private fun ActionEditorCard(colors: BlackClawColors, draft: ActionDraft,
                             onChange: (ActionDraft) -> Unit, onDelete: () -> Unit,
                             onMoveUp: () -> Unit, onMoveDown: () -> Unit) {
    SectionCard(colors, "Entonces · ${draft.type.label()}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Picker(colors, "Tipo", draft.type, AutomationProfileStore.ActionType.values().toList(), modifier = Modifier.weight(1f)) { onChange(ActionDraft(it)) }
            IconButton(onClick = onMoveUp) { Icon(Icons.Default.ArrowUpward, "Subir", tint = colors.textTertiary) }
            IconButton(onClick = onMoveDown) { Icon(Icons.Default.ArrowDownward, "Bajar", tint = colors.textTertiary) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Eliminar", tint = colors.textTertiary) }
        }
        when (draft.type) {
            AutomationProfileStore.ActionType.TOOL -> {
                OutlinedTextField(draft.primary, { onChange(draft.copy(primary = it)) }, label = { Text("Nombre de herramienta") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.paramsText, { onChange(draft.copy(paramsText = it)) }, label = { Text("Parámetros (clave=valor)") }, modifier = Modifier.fillMaxWidth())
            }
            AutomationProfileStore.ActionType.AGENT_TASK -> OutlinedTextField(draft.primary, { onChange(draft.copy(primary = it)) }, label = { Text("Tarea para BlackClaw") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            AutomationProfileStore.ActionType.RUN_ROUTINE -> OutlinedTextField(draft.primary, { onChange(draft.copy(primary = it)) }, label = { Text("Nombre de rutina") }, modifier = Modifier.fillMaxWidth())
            AutomationProfileStore.ActionType.NOTIFY -> {
                OutlinedTextField(draft.primary, { onChange(draft.copy(primary = it)) }, label = { Text("Título") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.secondary, { onChange(draft.copy(secondary = it)) }, label = { Text("Texto") }, modifier = Modifier.fillMaxWidth())
            }
            AutomationProfileStore.ActionType.SET_VARIABLE -> {
                OutlinedTextField(draft.primary, { onChange(draft.copy(primary = it)) }, label = { Text("Variable") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.secondary, { onChange(draft.copy(secondary = it)) }, label = { Text("Valor") }, modifier = Modifier.fillMaxWidth())
            }
            AutomationProfileStore.ActionType.WAIT -> OutlinedTextField(draft.primary, { onChange(draft.copy(primary = it)) }, label = { Text("Milisegundos (máximo 60000)") }, modifier = Modifier.fillMaxWidth())
            AutomationProfileStore.ActionType.IF, AutomationProfileStore.ActionType.LOOP -> {
                Text("Bloque avanzado: usa JSON para definir la condición y las acciones anidadas.",
                    color = colors.textSecondary, fontSize = 12.sp)
                OutlinedTextField(
                    draft.paramsText,
                    { onChange(draft.copy(paramsText = it)) },
                    label = { Text(if (draft.type == AutomationProfileStore.ActionType.IF)
                        "JSON: condition_type, condition_params, then, else"
                    else "JSON: count, actions") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T : Enum<T>> Picker(
    colors: BlackClawColors,
    label: String,
    selected: T,
    values: List<T>,
    modifier: Modifier = Modifier,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(selected.label(), {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().then(modifier).fillMaxWidth())
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value -> DropdownMenuItem(text = { Text(value.label()) }, onClick = { onSelected(value); expanded = false }) }
        }
    }
}

@Composable
private fun <T : Enum<T>> EnumPickerDialog(title: String, values: List<T>, onSelected: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column { values.forEach { value -> TextButton(onClick = { onSelected(value) }, modifier = Modifier.fillMaxWidth()) { Text(value.label()) } } }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } })
}

@Composable
private fun NumberField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
}

private fun Enum<*>.label(): String = name.lowercase().replace('_', ' ')

private fun TriggerDraft.withParam(key: String, value: String) = copy(params = params + (key to value))

private fun Map<String, String>.toAnyMap(): Map<String, Any> = entries.associate { it.key to it.value }

private fun Map<String, String>.toEditorText(): String = entries.joinToString(", ") { "${it.key}=${it.value}" }

private fun parseEditorParams(raw: String): Map<String, String> = raw.split(',').mapNotNull { token ->
    val parts = token.split('=', limit = 2)
    if (parts.size != 2 || parts[0].trim().isBlank()) null else parts[0].trim() to parts[1].trim()
}.toMap()

private fun ActionDraft.toAction(): AutomationProfileStore.Action {
    val params: Map<String, Any> = when (type) {
        AutomationProfileStore.ActionType.TOOL -> mapOf("tool" to primary, "params" to parseEditorParams(paramsText).toAnyMap())
        AutomationProfileStore.ActionType.AGENT_TASK -> mapOf("text" to primary)
        AutomationProfileStore.ActionType.RUN_ROUTINE -> mapOf("name" to primary)
        AutomationProfileStore.ActionType.NOTIFY -> mapOf("title" to primary, "text" to secondary)
        AutomationProfileStore.ActionType.SET_VARIABLE -> mapOf("name" to primary, "value" to secondary)
        AutomationProfileStore.ActionType.WAIT -> mapOf("ms" to primary)
        AutomationProfileStore.ActionType.IF, AutomationProfileStore.ActionType.LOOP ->
            parseJsonParams(paramsText, rawParams)
    }
    return AutomationProfileStore.Action(type, params)
}

private fun AutomationProfileStore.Profile?.toTriggerDrafts(): List<TriggerDraft> =
    this?.triggers?.map { TriggerDraft(it.type, it.params.mapValues { value -> value.value.editorText() }) }.orEmpty()

private fun AutomationProfileStore.Profile?.toConditionDrafts(): List<ConditionDraft> =
    this?.conditions?.map { ConditionDraft(it.type, it.params.mapValues { value -> value.value.editorText() }, it.negate) }.orEmpty()

private fun AutomationProfileStore.Profile?.toActionDrafts(): List<ActionDraft> = this?.actions?.map { action ->
    when (action.type) {
        AutomationProfileStore.ActionType.TOOL -> ActionDraft(action.type, action.params["tool"].editorText(), paramsText = (action.params["params"] as? Map<*, *>)?.entries?.joinToString(", ") { "${it.key}=${it.value.editorText()}" }.orEmpty(), rawParams = action.params)
        AutomationProfileStore.ActionType.AGENT_TASK -> ActionDraft(action.type, action.params["text"].editorText(), rawParams = action.params)
        AutomationProfileStore.ActionType.RUN_ROUTINE -> ActionDraft(action.type, action.params["name"].editorText(), rawParams = action.params)
        AutomationProfileStore.ActionType.NOTIFY -> ActionDraft(action.type, action.params["title"].editorText(), action.params["text"].editorText(), rawParams = action.params)
        AutomationProfileStore.ActionType.SET_VARIABLE -> ActionDraft(action.type, action.params["name"].editorText(), action.params["value"].editorText(), rawParams = action.params)
        AutomationProfileStore.ActionType.WAIT -> ActionDraft(action.type, action.params["ms"].editorText(), rawParams = action.params)
        AutomationProfileStore.ActionType.IF, AutomationProfileStore.ActionType.LOOP ->
            ActionDraft(action.type, paramsText = action.params.toJsonText(), rawParams = action.params)
    }
}.orEmpty()

private fun parseJsonParams(raw: String, fallback: Map<String, Any>): Map<String, Any> {
    if (raw.isBlank()) return fallback
    return runCatching {
        jsonObjectToMap(JSONObject(raw))
    }.getOrDefault(fallback)
}

private fun Map<String, Any>.toJsonText(): String = JSONObject(this).toString()

private fun jsonObjectToMap(json: JSONObject): Map<String, Any> {
    val result = LinkedHashMap<String, Any>()
    json.keys().forEach { key ->
        val value = json.opt(key)
        if (value != null && value != JSONObject.NULL) result[key] = jsonValueToEditorAny(value)
    }
    return result
}

private fun jsonValueToEditorAny(value: Any): Any = when (value) {
    is JSONObject -> jsonObjectToMap(value)
    is org.json.JSONArray -> (0 until value.length()).mapNotNull { index ->
        value.opt(index)?.takeIf { it != JSONObject.NULL }?.let(::jsonValueToEditorAny)
    }
    else -> value
}

private fun Any?.editorText(): String = when (this) {
    null -> ""
    is Number -> toDouble().let { value ->
        if (value.isFinite() && value == value.toLong().toDouble()) value.toLong().toString() else toString()
    }
    else -> toString()
}
