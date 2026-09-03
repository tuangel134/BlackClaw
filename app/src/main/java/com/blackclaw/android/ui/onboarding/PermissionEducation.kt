package com.blackclaw.android.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackclaw.android.ui.chat.BlackClawColors

/**
 * User-facing permission education shared by onboarding and Settings.
 *
 * Android's system dialogs tell users WHAT is being requested but not WHY.
 * Keeping the rationale in one catalog prevents different screens from giving
 * contradictory explanations for the same capability.
 */
enum class PermissionTopic {
    ACCESSIBILITY,
    NOTIFICATIONS,
    NOTIFICATION_ACCESS,
    OVERLAY,
    EXACT_ALARMS,
    BATTERY,
    FILES,
    MICROPHONE,
    CAMERA,
    LOCATION,
    BACKGROUND_LOCATION,
    CONTACTS,
    CALENDAR,
    SMS,
    CALL_LOG,
    PHONE_STATE,
    BLUETOOTH,
    INSTALL_PACKAGES,
    MODIFY_SYSTEM_SETTINGS,
    SCREEN_CAPTURE,
    APP_DISCOVERY,
    INTERNET,
    BOOT_AND_BACKGROUND,
}

data class PermissionExplanation(
    val topic: PermissionTopic,
    val title: String,
    val shortReason: String,
    val whyNeeded: String,
    val withoutIt: String,
    val privacy: String,
    val optional: Boolean,
    val systemManaged: Boolean = false,
)

object PermissionEducationCatalog {
    private val entries = listOf(
        PermissionExplanation(
            PermissionTopic.ACCESSIBILITY,
            "Accesibilidad",
            "Ver la interfaz y tocar, escribir o desplazarse por ti.",
            "Es el control principal del agente. BlackClaw usa el árbol de accesibilidad para entender botones, texto y campos de la app que tienes abierta y ejecutar las acciones que le pediste.",
            "BlackClaw todavía puede conversar, usar algunas herramientas directas y funciones que no controlan la pantalla, pero no podrá operar otras apps de forma general.",
            "El servicio se ejecuta en el teléfono. Si eliges un modelo cloud para una tarea, el contexto de pantalla necesario para esa tarea puede formar parte del prompt enviado únicamente al proveedor que configuraste.",
            optional = false,
        ),
        PermissionExplanation(
            PermissionTopic.NOTIFICATIONS,
            "Mostrar notificaciones",
            "Avisarte de tareas, recordatorios, alarmas y procesos en segundo plano.",
            "Android 13+ exige permiso para que BlackClaw pueda mostrar avisos normales. También permite que las tareas largas y servicios en primer plano tengan una notificación visible y entendible.",
            "Las tareas pueden seguir funcionando cuando Android lo permita, pero perderás recordatorios, progreso visible y varios avisos importantes.",
            "BlackClaw solo publica notificaciones generadas por sus propias funciones. Puedes revocar este permiso cuando quieras.",
            optional = false,
        ),
        PermissionExplanation(
            PermissionTopic.NOTIFICATION_ACCESS,
            "Leer notificaciones",
            "Detectar mensajes y eventos para auto-respuestas y asistente proactivo.",
            "Permite que BlackClaw reciba el contenido que Android entrega al Notification Listener: app origen, título, texto y acciones disponibles. Se usa solo para funciones que dependen de lo que acaba de llegar.",
            "No funcionarán la lectura global de notificaciones, auto-respuestas basadas en ellas ni reglas/proactividad que reaccionen a una notificación entrante.",
            "El contenido se procesa localmente primero. Si una función usa un modelo cloud, el fragmento necesario puede enviarse al proveedor configurado para decidir o redactar la acción.",
            optional = true,
        ),
        PermissionExplanation(
            PermissionTopic.OVERLAY,
            "Ventana flotante",
            "Mostrar controles de BlackClaw encima de otras apps.",
            "Se usa para superficies flotantes y controles que deben seguir visibles mientras trabajas en otra app.",
            "El chat principal y la automatización siguen disponibles, pero la burbuja/panel flotante no podrá aparecer sobre otras aplicaciones.",
            "No permite leer otras apps por sí solo; únicamente autoriza dibujar una ventana encima de ellas.",
            optional = true,
        ),
        PermissionExplanation(
            PermissionTopic.EXACT_ALARMS,
            "Alarmas exactas",
            "Disparar alarmas y automatizaciones a la hora solicitada.",
            "Android puede retrasar trabajo en segundo plano para ahorrar batería. Este acceso permite programar eventos que realmente necesitan ocurrir a una hora exacta.",
            "Recordatorios y automatizaciones de tiempo pueden ejecutarse con retraso dependiendo de las políticas de batería del sistema.",
            "No da acceso a datos personales. Solo cambia cómo Android agenda los eventos creados por BlackClaw.",
            optional = true,
        ),
        PermissionExplanation(
            PermissionTopic.BATTERY,
            "Sin restricción de batería",
            "Reducir cierres del asistente y retrasos en segundo plano.",
            "Algunos fabricantes detienen agresivamente servicios, voz, recordatorios y automatizaciones. Excluir BlackClaw de optimización mejora su continuidad.",
            "El uso normal en primer plano seguirá funcionando, pero wake word, recordatorios, geocercas y automatizaciones pueden llegar tarde o detenerse.",
            "Puede aumentar ligeramente el consumo si mantienes funciones permanentes activas. BlackClaw no lo necesita para tareas puntuales.",
            optional = true,
        ),
        PermissionExplanation(
            PermissionTopic.FILES,
            "Acceso a archivos",
            "Abrir modelos, ZIM, importaciones, exportaciones y archivos que tú elijas.",
            "Las funciones de terminal, biblioteca offline, importación de modelos y herramientas de lectura/escritura necesitan acceder a almacenamiento compartido cuando trabajan fuera del espacio privado de BlackClaw.",
            "El chat y las funciones que usan almacenamiento interno seguirán funcionando, pero no podrás operar libremente con archivos compartidos que requieran este acceso.",
            "Los archivos no se suben automáticamente. Una tarea cloud solo recibe contenido de archivo cuando la función solicitada necesita incluirlo en el contexto del modelo.",
            optional = true,
        ),
        PermissionExplanation(
            PermissionTopic.MICROPHONE,
            "Micrófono",
            "Escucharte en Quick Assist, modo voz y palabra de activación.",
            "BlackClaw necesita audio para reconocer tus órdenes habladas. El motor Vosk incluido puede procesar voz completamente offline; también puede usarse el reconocedor del sistema cuando corresponda.",
            "Podrás escribir y usar el resto de BlackClaw, pero no podrá escucharte ni mantener el wake word.",
            "Con Vosk, el reconocimiento se hace en el dispositivo. Si se usa el reconocedor de voz del sistema, su tratamiento de audio depende del servicio de reconocimiento configurado en Android.",
            optional = true,
        ),
        PermissionExplanation(
            PermissionTopic.CAMERA,
            "Cámara",
            "Capturar evidencia cuando activas funciones de protección que usan cámara.",
            "La cámara se usa en el modo de emergencia/evidencia cuando el usuario lo ha configurado para grabar video o tomar evidencia visual.",
            "Las funciones normales de asistente siguen disponibles; solo se desactiva la evidencia que requiere cámara.",
            "BlackClaw no necesita tener la cámara activa para el uso normal. Android muestra indicadores de privacidad cuando una app usa la cámara.",
            optional = true,
        ),
        PermissionExplanation(
            PermissionTopic.LOCATION,
            "Ubicación precisa",
            "Guardar 'este lugar' y evaluar automatizaciones de llegada/salida.",
            "Se usa cuando guardas un lugar semántico, pides tu ubicación o creas una regla que depende de estar dentro o fuera de una zona.",
            "No podrás guardar la ubicación actual ni usar correctamente automatizaciones de lugar.",
            "Los lugares guardados se almacenan cifrados mediante Android Keystore. Los perfiles reutilizan un ID estable y no necesitan exponer las coordenadas guardadas al modelo para resolver el nombre del lugar.",
            optional = true,
        ),
        PermissionExplanation(
            PermissionTopic.BACKGROUND_LOCATION,
            "Ubicación en segundo plano",
            "Detectar geocercas aunque BlackClaw no esté abierto.",
            "Solo es necesaria para automatizaciones de ubicación que deban dispararse mientras la app está en segundo plano, por ejemplo 'cuando llegue a casa de mi novia'.",
            "Las ubicaciones todavía pueden evaluarse cuando BlackClaw está activo y existe un fallback best-effort, pero las entradas/salidas en segundo plano serán menos fiables.",
            "No implica seguimiento continuo. BlackClaw usa geofences de bajo consumo y última ubicación conocida; los lugares permanecen cifrados localmente.",
            optional = true,
        ),
        PermissionExplanation(
            PermissionTopic.CONTACTS,
            "Contactos",
            "Encontrar personas y crear contactos cuando se lo pides.",
            "Permite resolver nombres a números para llamadas/mensajes y verificar contactos creados por herramientas nativas.",
            "BlackClaw no podrá buscar personas en tu agenda ni crear/verificar contactos directamente.",
            "La agenda se consulta solo para la tarea que la necesita. Si utilizas un modelo cloud, BlackClaw intenta enviar únicamente el contexto necesario para resolver esa tarea.",
            optional = true,
        ),
        PermissionExplanation(
            PermissionTopic.CALENDAR,
            "Calendario",
            "Leer o crear eventos del calendario del sistema.",
            "Se usa cuando pides consultar tu agenda externa o crear una cita directamente en el calendario de Android/Google disponible en el dispositivo.",
            "El calendario interno del Assistant Hub sigue disponible, pero no podrá leer/escribir el calendario del sistema.",
            "Los eventos solo se consultan cuando una función los necesita; BlackClaw no sincroniza por su cuenta una copia completa a servidores propios.",
            optional = true,
        ),
        PermissionExplanation(
            PermissionTopic.SMS,
            "SMS",
            "Leer o enviar SMS y reaccionar a mensajes SMS si tú configuras una automatización.",
            "Habilita herramientas directas de lectura/envío y triggers SMS del motor de automatización.",
            "Esas herramientas y reglas de SMS no funcionarán. El resto de canales y apps de mensajería siguen disponibles.",
            "El contenido de SMS es sensible. Solo se usa para la acción solicitada o una automatización que el usuario haya creado; un modelo cloud puede recibir el fragmento necesario cuando esa acción requiera razonamiento.",
            optional = true,
        ),
        PermissionExplanation(
            PermissionTopic.CALL_LOG,
            "Registro de llamadas",
            "Consultar llamadas recientes cuando se lo pides.",
            "Permite a la herramienta de llamadas responder preguntas como quién llamó recientemente o mostrar entradas del historial.",
            "Las llamadas normales y otras herramientas continúan; solo deja de estar disponible la consulta del historial.",
            "BlackClaw no necesita copiar permanentemente el registro. Se consulta bajo demanda para la tarea solicitada.",
            optional = true,
        ),
        PermissionExplanation(
            PermissionTopic.PHONE_STATE,
            "Estado del teléfono",
            "Detectar estados de llamada para no pelear por el micrófono y para automatizaciones.",
            "Ayuda al modo voz y a las reglas basadas en llamada a saber si el teléfono está sonando, ocupado o libre.",
            "Las funciones relacionadas con estado de llamada pueden ser incompletas y el asistente tendrá menos contexto para ceder el micrófono.",
            "No se usa para identificarte ni para publicidad. Su finalidad es coordinar funciones del dispositivo.",
            optional = true,
        ),
        PermissionExplanation(
            PermissionTopic.BLUETOOTH,
            "Dispositivos Bluetooth cercanos",
            "Detectar conexiones Bluetooth usadas por automatizaciones.",
            "Permite identificar de forma segura el dispositivo conectado en Android moderno cuando una regla depende de audífonos, auto u otro accesorio.",
            "La automatización puede saber menos sobre qué dispositivo se conectó, aunque otras funciones sigan operando.",
            "BlackClaw no realiza un escaneo continuo para publicidad. El nombre del dispositivo solo se usa como contexto de la automatización configurada.",
            optional = true,
        ),
        PermissionExplanation(
            PermissionTopic.INSTALL_PACKAGES,
            "Instalar actualizaciones",
            "Abrir el instalador de Android para actualizar BlackClaw desde un APK.",
            "El actualizador integrado descarga una release firmada y entrega el APK al instalador del sistema. Android sigue mostrando su propia confirmación de instalación.",
            "Podrás actualizar manualmente descargando el APK desde GitHub.",
            "BlackClaw no puede instalar silenciosamente una app de terceros con este acceso; la instalación sigue pasando por la interfaz de Android.",
            optional = true,
        ),
        PermissionExplanation(
            PermissionTopic.MODIFY_SYSTEM_SETTINGS,
            "Modificar ajustes del sistema",
            "Cambiar ciertos ajustes cuando una herramienta directa lo requiere.",
            "Algunas acciones de brillo/configuración pueden usar la API de ajustes de Android en lugar de navegar visualmente por menús.",
            "BlackClaw puede intentar rutas alternativas, pero algunos cambios directos no estarán disponibles.",
            "Solo se usa al ejecutar una acción que cambia ese ajuste. Las herramientas de mayor riesgo siguen pasando por las políticas de seguridad de BlackClaw.",
            optional = true,
        ),
        PermissionExplanation(
            PermissionTopic.SCREEN_CAPTURE,
            "Captura de pantalla",
            "Leer interfaces que Accesibilidad no puede ver, como juegos o SurfaceView.",
            "Android pide consentimiento MediaProjection antes de que BlackClaw pueda capturar la pantalla para OCR, screenshot o percepción visual.",
            "La automatización seguirá usando Accesibilidad, pero OCR visual y control de superficies no accesibles pueden dejar de funcionar.",
            "La sesión de captura depende del consentimiento visible de Android. BlackClaw usa la imagen para la función solicitada y no necesita conservar capturas permanentemente.",
            optional = true,
        ),
        PermissionExplanation(
            PermissionTopic.APP_DISCOVERY,
            "Ver apps instaladas",
            "Encontrar qué apps puede abrir/controlar y detectar riesgos locales.",
            "BlackClaw consulta paquetes instalados para resolver 'abre X', descubrir deep links, elegir reproductores y ejecutar el escáner de seguridad.",
            "La detección genérica de apps y varias funciones de seguridad/control serían mucho menos precisas.",
            "Es un permiso técnico declarado en el manifest; Android no muestra un diálogo runtime para concederlo. La lista se usa localmente para funciones de BlackClaw.",
            optional = false,
            systemManaged = true,
        ),
        PermissionExplanation(
            PermissionTopic.INTERNET,
            "Internet",
            "Conectar modelos cloud, búsquedas web, canales, descargas y actualizaciones.",
            "Las funciones online necesitan conectividad de red. Los modelos locales, ZIM y varias herramientas siguen funcionando sin internet.",
            "No habrá proveedores cloud, búsquedas web, Telegram/Discord/WeChat ni descargas mientras estés sin conexión.",
            "Android concede este permiso al instalar; no tiene un diálogo runtime. BlackClaw solo conecta con servicios requeridos por la función que uses/configures.",
            optional = false,
            systemManaged = true,
        ),
        PermissionExplanation(
            PermissionTopic.BOOT_AND_BACKGROUND,
            "Arranque y servicios en segundo plano",
            "Rearmar alarmas, automatizaciones y servicios configurados después de reiniciar.",
            "Android borra alarmas/geofences de memoria en ciertos reinicios. BlackClaw escucha el arranque para reconstruir únicamente lo que el usuario dejó configurado.",
            "Después de reiniciar, recordatorios o automatizaciones podrían no volver a quedar activos hasta abrir BlackClaw.",
            "Son permisos técnicos del sistema. No otorgan acceso adicional a datos personales y no muestran un diálogo runtime.",
            optional = false,
            systemManaged = true,
        ),
    )

    val all: List<PermissionExplanation> get() = entries

    fun get(topic: PermissionTopic): PermissionExplanation =
        entries.first { it.topic == topic }
}

@Composable
fun PermissionExplanationDialog(
    topic: PermissionTopic,
    colors: BlackClawColors,
    onDismiss: () -> Unit,
    onContinue: (() -> Unit)? = null,
) {
    val item = remember(topic) { PermissionEducationCatalog.get(topic) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item.title, color = colors.textPrimary, fontWeight = FontWeight.Bold)
                PermissionBadge(item = item, colors = colors)
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                item {
                    Text(item.shortReason, color = colors.textSecondary, fontSize = 13.sp)
                }
                item { ExplanationSection("¿Para qué lo usa?", item.whyNeeded, colors) }
                item { ExplanationSection("Si lo rechazas", item.withoutIt, colors) }
                item { ExplanationSection("Privacidad", item.privacy, colors) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Ahora no", color = colors.textSecondary)
            }
        },
        confirmButton = {
            if (onContinue != null && !item.systemManaged) {
                Button(
                    onClick = onContinue,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.background,
                    ),
                ) { Text("Continuar", fontWeight = FontWeight.Bold) }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Entendido", color = colors.accent, fontWeight = FontWeight.Bold)
                }
            }
        },
    )
}

@Composable
fun PermissionOverviewDialog(
    colors: BlackClawColors,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf<PermissionTopic?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("¿Por qué BlackClaw pide permisos?", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                Text("Tú decides qué capacidades activar", color = colors.textSecondary, fontSize = 12.sp)
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "BlackClaw no necesita que concedas todo de golpe. Los permisos opcionales se usan únicamente cuando activas la función asociada.",
                        color = colors.textSecondary,
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                items(PermissionEducationCatalog.all, key = { it.topic.name }) { item ->
                    val open = expanded == item.topic
                    Surface(
                        color = colors.background.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, colors.divider.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth().clickable {
                            expanded = if (open) null else item.topic
                        },
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text(item.shortReason, color = colors.textSecondary, fontSize = 11.5.sp)
                                }
                                Spacer(Modifier.width(8.dp))
                                PermissionBadge(item, colors)
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (open) "Contraer" else "Ver detalle",
                                    tint = colors.textTertiary,
                                )
                            }
                            if (open) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    color = colors.divider.copy(alpha = 0.65f),
                                )
                                ExplanationSection("¿Para qué lo usa?", item.whyNeeded, colors)
                                Spacer(Modifier.height(7.dp))
                                ExplanationSection("Si lo rechazas", item.withoutIt, colors)
                                Spacer(Modifier.height(7.dp))
                                ExplanationSection("Privacidad", item.privacy, colors)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar", color = colors.accent, fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
fun PermissionBundleExplanationDialog(
    topics: List<PermissionTopic>,
    colors: BlackClawColors,
    title: String = "Permisos para esta función",
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    val items = remember(topics) {
        topics.distinct().map(PermissionEducationCatalog::get)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = colors.textPrimary, fontWeight = FontWeight.Bold)
                Text(
                    "BlackClaw solo solicitará los que todavía falten.",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(items, key = { it.topic.name }) { item ->
                    Surface(
                        color = colors.background.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, colors.divider.copy(alpha = 0.55f)),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    item.title,
                                    modifier = Modifier.weight(1f),
                                    color = colors.textPrimary,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                PermissionBadge(item, colors)
                            }
                            Spacer(Modifier.height(5.dp))
                            Text(item.whyNeeded, color = colors.textSecondary, fontSize = 11.5.sp, lineHeight = 16.sp)
                            Spacer(Modifier.height(5.dp))
                            Text("Sin este permiso: ${item.withoutIt}", color = colors.textTertiary, fontSize = 10.5.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Ahora no", color = colors.textSecondary)
            }
        },
        confirmButton = {
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.background,
                ),
            ) { Text("Continuar", fontWeight = FontWeight.Bold) }
        },
    )
}

@Composable
private fun PermissionBadge(item: PermissionExplanation, colors: BlackClawColors) {
    val text = when {
        item.systemManaged -> "técnico"
        item.optional -> "opcional"
        else -> "esencial"
    }
    val tint = when {
        item.systemManaged -> colors.textTertiary
        item.optional -> colors.textSecondary
        else -> colors.accent
    }
    Surface(color = tint.copy(alpha = 0.14f), shape = RoundedCornerShape(20.dp)) {
        Text(
            text,
            color = tint,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ExplanationSection(title: String, body: String, colors: BlackClawColors) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = colors.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
        Text(body, color = colors.textSecondary, fontSize = 11.5.sp, lineHeight = 16.sp)
    }
}
