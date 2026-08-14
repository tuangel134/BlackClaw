package com.blackclaw.android.ui.guide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * A scrollable "what BlackClaw can do" guide with concrete example phrases for
 * each capability — so users actually discover voice, smart home, remote PC,
 * routines, habits, free models, etc.
 */
class FeaturesGuideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { FeaturesGuideScreen(onBack = { finish() }) }
    }
}

private data class Feature(
    val emoji: String,
    val title: String,
    val desc: String,
    val examples: List<String>,
)

private val FEATURES = listOf(
    Feature("📲", "Controla tus apps",
        "Pídele acciones en apps populares y las hace al instante saltando directo a la pantalla útil (deep links) y completando el resto. Soporta +60 apps: Uber, DiDi, Cabify, Uber Eats, Rappi, DoorDash, Glovo, Spotify, YouTube, Maps, Waze, WhatsApp, Telegram, Instagram, X, Amazon, Mercado Libre, AliExpress, eBay, Netflix, Disney+, Booking, Airbnb, Gmail y más. Además, por accesibilidad puede operar CUALQUIER app en pantalla.",
        listOf("\"pídeme un Uber al aeropuerto\"", "\"pide sushi en Uber Eats\"", "\"busca un teclado en Amazon\"", "\"navega a casa\"")),
    Feature("🐾", "Asistente del teléfono",
        "Ponlo como asistente por defecto (Ajustes → Modo voz → Asistente del teléfono) e invócalo con el gesto/botón de inicio, incluso sobre la pantalla bloqueada. Aparece una pantalla flotante con animación y puedes conversar de ida y vuelta sin repetir la palabra de activación. Toca el orbe para interrumpirlo.",
        listOf("Mantén el botón de inicio → habla", "\"¿qué hora es?\" → \"¿y mañana llueve?\"", "Di \"gracias\" para cerrar")),
    Feature("📅", "Calendario y agenda",
        "Dile una cita y la pone en el calendario, suena como alarma a su hora y la ves en la agenda — ahora o dentro de semanas. Mira todo en la vista de Calendario (Mes/Agenda) y reprograma tocando.",
        listOf("\"tengo una reunión a las 7\"", "\"en 3 semanas tengo médico a las 5 de la tarde\"", "\"¿qué tengo hoy?\"")),
    Feature("🗓️", "Tareas programadas y automatizaciones",
        "Crea una tarea desde Automatizaciones con un flujo guiado: escribe lo que quieres, elige si BlackClaw actuará o solo te avisará, selecciona una hora o fecha, y decide si se repite. También puedes crear perfiles estilo Tasker con disparadores de Wi‑Fi, notificaciones, ubicación y horario.",
        listOf("\"recuérdame revisar el clima en 30 minutos\"", "\"cada lunes a las 9, revisa mi agenda\"", "\"cuando me conecte al Wi‑Fi de casa, avísame\"")),
    Feature("🎵", "Música en tu reproductor",
        "Reproduce en el reproductor que tú uses (Musicolet, Spotify, YouTube Music, Poweramp…), no solo en uno. Funciona offline con tu música local.",
        listOf("\"pon Bad Bunny\"", "\"reproduce lofi en YouTube Music\"", "\"usa Musicolet para la música\"")),
    Feature("🎤", "Modo voz manos libres",
        "Activa el modo voz en Ajustes. Di la palabra de activación y tu orden. Funciona en segundo plano y sin internet (modelo offline incluido).",
        listOf("\"garra, pon una alarma a las 7\"", "\"garra, ¿cuánta batería tengo?\"", "\"garra, manda un mensaje a mamá\"")),
    Feature("⏰", "Asistente nativo",
        "Alarmas, recordatorios, notas, eventos y finanzas dentro de la app — sin salir a otras apps.",
        listOf("\"recuérdame llamar al dentista mañana a las 5\"", "\"pon una alarma con reto para despertar\"", "\"anota que tengo reunión el lunes\"")),
    Feature("🤖", "Proactivo",
        "Vigila tus notificaciones y actúa solo: pone alarmas de citas, registra cobros, te avisa de cosas importantes. Configúralo en Ajustes → Proactivo.",
        listOf("Detecta \"reunión mañana 9am\" → pone alarma 8:30", "Detecta un cobro → lo registra en finanzas")),
    Feature("🌐", "Búsqueda en internet",
        "Pregunta cualquier cosa actual y busca la respuesta de verdad.",
        listOf("\"¿qué película se estrena esta semana?\"", "\"precio del bitcoin\"", "\"noticias de hoy\"")),
    Feature("🏠", "Casa inteligente",
        "Controla luces, enchufes y dispositivos vía webhooks (Home Assistant, IFTTT). Configúralos primero.",
        listOf("\"enciende la luz del salón\"", "\"apaga la cafetera\"")),
    Feature("📶", "Modo automático local / nube",
        "En Ajustes → Modelo → Automático, BlackClaw usa tu modelo en la nube cuando hay internet y cambia al modelo local descargado cuando no hay conexión. Puedes ver qué modelo está activo en el selector del chat.",
        listOf("Activa \"Automático\" en el selector de modelos", "Sin internet → responde con tu modelo local", "Con internet → usa tu modelo cloud configurado")),
    Feature("👁️", "Ve y entiende la pantalla",
        "Lee texto con OCR incluso en juegos y superficies personalizadas. Cuando el modelo tiene visión, recibe la imagen original; si no, usa el texto OCR como respaldo. También puede combinar la pantalla con la accesibilidad para tocar el elemento correcto.",
        listOf("\"lee lo que hay en pantalla\"", "\"busca el botón Continuar y tócalo\"", "Adjunta una imagen y pregunta qué contiene")),
    Feature("💻", "Controla tu PC",
        "Conecta tu computadora por SSH y diagnostica/arregla problemas desde el teléfono.",
        listOf("\"conecta mi PC 192.168.1.10 usuario angel\"", "\"el wifi de mi PC no funciona, arréglalo\"", "\"vigila el log y avísame si hay un error\"")),
    Feature("⌨️", "Terminal tipo Termux",
        "Incluye una terminal local persistente para el usuario y para BlackClaw. Funciona sin root ni Shizuku para comandos del entorno de la app; de forma opcional añade ADB propio, Shizuku o ADB por Wi‑Fi para acciones privilegiadas.",
        listOf("\"abre la terminal\"", "\"lista los archivos de mi espacio de trabajo\"", "\"conecta por ADB al dispositivo remoto\"")),
    Feature("📚", "Biblioteca offline",
        "Consulta archivos ZIM compatibles sin internet. BlackClaw detecta bibliotecas locales, busca el índice y devuelve solo los pasajes relevantes en vez de inventar una respuesta.",
        listOf("\"consulta mi Wikipedia offline: ¿qué es TCP?\"", "\"busca esto en la biblioteca ZIM\"")),
    Feature("⚡", "Rutinas",
        "Crea secuencias multi-paso que se ejecutan a una hora o cuando lo pidas. Puedes guardarlas, probarlas, editarlas y limitar cuántas veces se ejecutan.",
        listOf("\"crea una rutina de mañana: alarma 7, clima, calendario\"", "\"ejecuta mi rutina de mañana\"", "\"guarda estos pasos como rutina X\"")),
    Feature("📊", "Hábitos y bienestar",
        "Trackea hábitos con rachas, y registra ánimo/sueño.",
        listOf("\"bebí 2 vasos de agua\"", "\"hice ejercicio 30 minutos\"", "\"dormí 7 horas, dormí bien\"")),
    Feature("🧠", "Te conoce",
        "Aprende tus preferencias y rutinas con el tiempo, con personalidad estilo JARVIS.",
        listOf("\"recuerda que mi jefe se llama Carlos\"", "\"¿qué reuniones tengo esta semana?\"")),
    Feature("💸", "Modelos gratis",
        "En Ajustes → Modelo → BlackClaw Free tienes modelos cloud gratis, sin cuenta ni API key. El catálogo se puede actualizar desde el selector del chat. También puedes importar modelos locales compatibles detectados en Downloads, Documents o carpetas de IA.",
        listOf("Elige BlackClaw Free → Actualizar modelos", "Descarga o importa un modelo local compatible", "Combínalo con el modo Automático")),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeaturesGuideScreen(onBack: () -> Unit) {
    val bg = Color(0xFF0A0A0F)
    val surface = Color(0xFF141420)
    val accent = Color(0xFF00D4FF)
    val textPrimary = Color(0xFFC8D0E8)
    val textSecondary = Color(0xFF7A80A0)

    Scaffold(
        containerColor = bg,
        topBar = {
            TopAppBar(
                title = { Text("¿Qué puede hacer?", color = textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = surface),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "BlackClaw es tu asistente personal. Escribe o habla — esto es lo que puede hacer:",
                color = textSecondary, fontSize = 14.sp,
            )
            FEATURES.forEach { f ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(surface, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.emoji, fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(f.title, color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Text(f.desc, color = textSecondary, fontSize = 13.sp)
                    f.examples.forEach { ex ->
                        Text("• $ex", color = accent, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
