package com.blackclaw.android.ui.assist

import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.blackclaw.android.TaskEvent
import com.blackclaw.android.appViewModel
import com.blackclaw.android.assistant.JarvisVoice
import com.blackclaw.android.assistant.Speaker
import com.blackclaw.android.assistant.VoiceInputManager
import com.blackclaw.android.utils.XLog
import java.util.UUID
import kotlin.math.sin

/**
 * Phone-wide assistant entry point (ACTION_ASSIST). A full-screen, Gemini-style
 * conversational panel that shows EVERYTHING inline — it never bounces you into
 * the main app. Listens, runs the command, shows + speaks the answer, then keeps
 * the conversation going (auto re-listen) so you can talk back and forth fluidly.
 * Works over the lock screen.
 */
class QuickAssistActivity : ComponentActivity() {

    companion object {
        private const val TAG = "QuickAssist"
        /** Optional: run this command immediately instead of listening first. */
        const val EXTRA_COMMAND = "assist_command"
    }

    enum class Phase { LISTENING, THINKING, SPEAKING, IDLE, NEED_MIC }

    data class Turn(val fromUser: Boolean, val text: String)

    private val turns = mutableStateListOf<Turn>()
    private val status = mutableStateOf("Le escucho, jefe…")
    private val partial = mutableStateOf("")
    private val phase = mutableStateOf(Phase.LISTENING)
    private val rms = mutableFloatStateOf(0f)
    private var started = false
    private var silentCount = 0
    private var busy = false   // a task is running

    // Streaming state for the current answer.
    private val streamBuf = StringBuilder()
    private var spokenLen = 0
    private var didStreamSpeak = false
    private var toolUsed = false
    private var leftApp = false   // an app-launching tool ran → hand off & close

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening()
        else { phase.value = Phase.NEED_MIC; status.value = "Necesito permiso de micrófono para escucharte." }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        setContent {
            AssistScreen(
                turns = turns,
                status = status.value,
                partial = partial.value,
                phase = phase.value,
                rms = rms.floatValue,
                busy = busy,
                onMic = { if (!busy) { switchingToVoice(); ensureMicThenListen() } },
                onOrbTap = { bargeIn() },
                onSuggestion = { runSuggestion(it) },
                onTyped = { onTypedCommand(it) },
                onStartTyping = { stopListeningForTyping() },
                onClose = { runCatching { if (busy) appViewModel.stopTask() }; finish() },
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !started) {
            started = true
            // If invoked with a ready command (e.g. from the wake word), run it
            // directly; otherwise open the mic. Consume the extra so a relaunch/
            // recreation doesn't re-run the stale command (singleTask keeps it).
            val cmd = intent?.getStringExtra(EXTRA_COMMAND)?.trim().orEmpty()
            intent?.removeExtra(EXTRA_COMMAND)
            if (cmd.isNotBlank()) window.decorView.postDelayed({ onCommand(cmd) }, 200L)
            else window.decorView.postDelayed({ ensureMicThenListen() }, 600L)
        }
    }

    /** Re-invoked while alive (singleTask): run the NEW command, ignore the old one. */
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        val cmd = newIntent.getStringExtra(EXTRA_COMMAND)?.trim().orEmpty()
        newIntent.removeExtra(EXTRA_COMMAND)
        runCatching { Speaker.stop() }
        // Reinvoking the panel while a task is running (user pressing the power
        // button again) must let them regain control — cancel the current
        // (possibly stuck/looping) task instead of silently ignoring the press.
        if (busy) {
            runCatching { appViewModel.stopTask() }
            busy = false
            status.value = "Cancelado, jefe."
            phase.value = Phase.IDLE
        }
        if (cmd.isNotBlank()) onCommand(cmd) else ensureMicThenListen()
    }

    fun runSuggestion(cmd: String) { if (!busy) onCommand(cmd) }

    /** User tapped the keyboard icon: stop listening, let them type instead. */
    private fun stopListeningForTyping() {
        runCatching { VoiceInputManager.cancelListenOnce() }
        if (!busy) { phase.value = Phase.IDLE; status.value = "Escribe tu mensaje…" }
    }

    /** Re-entering voice mode after typing: nothing to cancel, just a hook for parity. */
    private fun switchingToVoice() { partial.value = "" }

    /** Submit a typed command — same pipeline as a spoken one. */
    private fun onTypedCommand(text: String) {
        val cmd = text.trim()
        if (cmd.isEmpty() || busy) return
        runCatching { VoiceInputManager.cancelListenOnce() }
        onCommand(cmd)
    }

    private fun ensureMicThenListen() {
        if (!VoiceInputManager.isAvailable()) {
            phase.value = Phase.NEED_MIC
            status.value = "El reconocimiento de voz no está disponible en este dispositivo."
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) startListening()
        else permLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        phase.value = Phase.LISTENING
        partial.value = ""
        status.value = if (turns.isEmpty()) "Le escucho, jefe…" else "Dígame…"
        VoiceInputManager.listenOnce(
            onResult = { onCommand(it) },
            onError = { onListenError(it) },
            onRms = { db -> rms.floatValue = ((db + 2f) / 12f).coerceIn(0f, 1f) },
            onPartial = { partial.value = it },
        )
    }

    private fun onListenError(err: String) {
        // Silence/no-match: keep the panel, offer to tap, and after two quiet
        // rounds gracefully step back (don't nag, don't dismiss abruptly).
        silentCount++
        partial.value = ""
        if (silentCount >= 2) {
            phase.value = Phase.IDLE
            status.value = "Aquí estoy si me necesita. Toca el micrófono para hablar."
            // Auto-close after a while of inactivity to save battery (mic + screen).
            scheduleIdleAutoClose()
        } else {
            phase.value = Phase.IDLE
            status.value = "No le escuché. Toca el micrófono para hablar."
        }
    }

    /** Close the panel if it stays idle (~25s) — avoids lingering mic/screen drain. */
    private fun scheduleIdleAutoClose() {
        val dv = window.decorView
        dv.postDelayed({
            if (!isFinishing && !busy && phase.value == Phase.IDLE) finish()
        }, 25_000L)
    }

    private fun onCommand(command: String) {
        if (command.isBlank()) { onListenError(""); return }
        silentCount = 0
        // Reset streaming state for this answer.
        streamBuf.setLength(0); spokenLen = 0; didStreamSpeak = false; toolUsed = false; leftApp = false
        // End-of-conversation phrases close the panel politely.
        if (isFarewell(command)) {
            turns.add(Turn(true, command))
            phase.value = Phase.SPEAKING
            status.value = "Hasta luego, jefe."
            Speaker.speak("A sus órdenes, jefe.")
            window.decorView.postDelayed({ if (!isFinishing) finish() }, 1400)
            return
        }
        busy = true
        turns.add(Turn(true, command))
        phase.value = Phase.THINKING
        partial.value = ""
        status.value = "Pensando…"
        Speaker.speak(JarvisVoice.commandAck())

        // Conversational context: when this is a follow-up, give the agent the
        // recent exchange so "¿y mañana?" / "y eso?" make sense.
        val override = buildContextPrompt(command)
        val taskId = "assist-" + UUID.randomUUID().toString().take(8)
        runCatching {
            appViewModel.startTask(command, taskId, agentPromptOverride = override, autoReturnToChat = false) { event ->
                runOnUiThread {
                    when (event) {
                        is TaskEvent.ToolAction -> {
                            toolUsed = true
                            if (isAppLaunchTool(event.toolName)) leftApp = true
                            if (busy) status.value = friendlyTool(event.toolName)
                        }
                        is TaskEvent.LoopStart -> { if (busy && status.value.isBlank()) status.value = "Trabajando…" }
                        is TaskEvent.Progress -> { if (busy) status.value = event.description.take(60) }
                        is TaskEvent.Thinking -> onStreamToken(event.content)
                        is TaskEvent.Response -> { if (busy && event.text.isNotBlank() && streamBuf.isEmpty()) status.value = event.text.take(300) }
                        is TaskEvent.Completed -> answerStreamed(event.answer)
                        is TaskEvent.Failed -> answerStreamed(friendlyError(event.error))
                        is TaskEvent.Cancelled, is TaskEvent.Blocked -> { busy = false; phase.value = Phase.IDLE }
                        else -> Unit
                    }
                }
            }
        }.onFailure {
            XLog.w(TAG, "startTask failed: ${it.message}")
            answerStreamed("Hubo un problema al ejecutar la tarea.")
        }
    }

    /** Build a context-aware prompt for follow-ups (null = run command as-is). */
    private fun buildContextPrompt(command: String): String? {
        // Only inject context if there's a prior assistant answer in this session.
        val priorAnswer = turns.lastOrNull { !it.fromUser }?.text ?: return null
        val priorUser = turns.lastOrNull { it.fromUser }?.text.orEmpty()
        return buildString {
            append("Conversación reciente (para contexto):\n")
            if (priorUser.isNotBlank()) append("Usuario: ").append(priorUser.take(200)).append('\n')
            append("Tú: ").append(priorAnswer.take(300)).append("\n\n")
            append("El usuario ahora dice: \"").append(command).append("\". ")
            append("Si es continuación de lo anterior, tenlo en cuenta. Actúa o responde.")
        }
    }

    /** True for phrases that should end the conversation. */
    private fun isFarewell(cmd: String): Boolean {
        val c = cmd.lowercase().trim()
        val phrases = listOf(
            "gracias", "muchas gracias", "adiós", "adios", "hasta luego", "nos vemos",
            "eso es todo", "nada más", "nada mas", "ya está", "ya esta", "listo gracias",
            "chao", "bye", "thanks", "thank you", "that's all", "cállate", "callate", "ya no",
        )
        // Match short utterances only, so "gracias por X, ahora haz Y" still runs.
        return c.split(' ').size <= 3 && phrases.any { c == it || c.startsWith(it) }
    }

    /** Friendly Spanish status for a tool the agent is running. */    /** Friendly Spanish status for a tool the agent is running. */
    private fun friendlyTool(tool: String): String = when {
        tool.contains("open_app_action") || tool == "open_app" -> "Abriendo la app…"
        tool.contains("play_music") -> "Poniendo música…"
        tool.contains("send_message") || tool.contains("send_sms") -> "Enviando el mensaje…"
        tool.contains("make_call") -> "Llamando…"
        tool.contains("appointment") || tool.contains("alarm") || tool.contains("reminder") ||
            tool.contains("event") -> "Agendando…"
        tool.contains("web") || tool.contains("http") -> "Buscando en internet…"
        tool.contains("screen") || tool.contains("ocr") -> "Mirando la pantalla…"
        tool.contains("tap") || tool.contains("input") || tool.contains("swipe") ||
            tool.contains("scroll") -> "Tocando la pantalla…"
        tool.contains("notification") -> "Revisando notificaciones…"
        tool.contains("device_info") || tool.contains("battery") -> "Consultando el dispositivo…"
        else -> "Trabajando…"
    }

    /** Turn a raw failure into a helpful spoken reason instead of a generic error. */
    private fun friendlyError(raw: String): String {
        val e = raw.lowercase()
        return when {
            e.contains("no está instalada") || e.contains("not installed") || e.contains("no instalada") ->
                "Esa app no está instalada, jefe. ¿La instalo desde Play Store?"
            e.contains("permiso") || e.contains("permission") || e.contains("accesibilidad") ||
                e.contains("accessibility") -> "Me falta un permiso para eso. Revísalo en Ajustes."
            e.contains("not_found") || e.contains("no encontr") || e.contains("not found") ->
                "No encontré el elemento en pantalla. ¿Intento de otra forma?"
            e.contains("network") || e.contains("timeout") || e.contains("conexión") || e.contains("conexion") ->
                "Hubo un problema de conexión, jefe."
            e.contains("rate") || e.contains("límite") || e.contains("limite") || e.contains("429") ->
                "El modelo está saturado ahora mismo. Prueba en unos segundos."
            e.contains("401") || e.contains("403") || e.contains("unauthor") ->
                "Ese modelo dejó de estar disponible; cambié a otro gratis, reintenta."
            e.isBlank() -> "No pude completarlo, jefe."
            else -> "No pude completarlo: ${raw.take(120)}"
        }
    }

    /** A streamed token from the model. Show it live and speak completed sentences. */
    private fun onStreamToken(token: String) {
        if (token.isBlank()) return
        streamBuf.append(token)
        val full = streamBuf.toString()
        phase.value = Phase.SPEAKING
        status.value = full.replace(Regex("\\s+"), " ").trim().take(400)
        if (toolUsed) return   // don't speak tool-planning aloud; that's a task, not a Q&A
        val boundary = sentenceBoundary(full, spokenLen)
        if (boundary > spokenLen) {
            val speech = full.substring(spokenLen, boundary)
                .replace(URL_REGEX, " ").replace(Regex("[*_#`>]+"), " ")
                .replace(Regex("\\s+"), " ").trim()
            if (speech.isNotBlank()) { Speaker.speak(speech, flush = false); didStreamSpeak = true }
            spokenLen = boundary
        }
    }

    /** Index just past the last sentence-ending punctuation after [from] (min chunk 12 chars). */
    private fun sentenceBoundary(text: String, from: Int): Int {
        var last = -1
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '\n' || c == '。') last = i + 1
            i++
        }
        return if (last - from >= 12) last else from
    }

    /** Finalize the answer: add the rich bubble, speak any remainder, then re-listen. */
    private fun answerStreamed(raw: String) {
        busy = false
        val display = raw
            .replace(Regex("[*_#`>]+"), " ")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .ifBlank { "Listo, jefe." }
        turns.add(Turn(false, display))
        status.value = ""
        phase.value = Phase.SPEAKING
        if (leftApp) {
            // The task handed off to another app (Maps/Uber/…). Speak the result
            // and close the panel — don't listen in the background over that app.
            val speech = display.replace(URL_REGEX, " ").replace(Regex("\\s+"), " ").trim()
            if (didStreamSpeak) {
                val full = streamBuf.toString()
                if (spokenLen < full.length) {
                    val tail = full.substring(spokenLen).replace(URL_REGEX, " ")
                        .replace(Regex("[*_#`>]+"), " ").replace(Regex("\\s+"), " ").trim()
                    if (tail.isNotBlank()) Speaker.speak(tail, flush = false)
                }
            } else {
                Speaker.speak(speech.take(400), flush = true)
            }
            finishWhenDoneSpeaking()
            return
        }
        if (didStreamSpeak && !toolUsed) {
            // Speak the tail we streamed but haven't voiced yet.
            val full = streamBuf.toString()
            if (spokenLen < full.length) {
                val tail = full.substring(spokenLen)
                    .replace(URL_REGEX, " ").replace(Regex("[*_#`>]+"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                if (tail.isNotBlank()) Speaker.speak(tail, flush = false)
            }
        } else {
            val speech = display.replace(URL_REGEX, " ").replace(Regex("\\s+"), " ").trim()
            speakBySentences(speech.take(600))
        }
        relistenWhenDoneSpeaking()
    }

    /** Speak text sentence by sentence (queued) so it sounds natural, not one blob. */
    private fun speakBySentences(text: String) {
        val parts = text.split(Regex("(?<=[.!?。])\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) { Speaker.speak(text, flush = true); return }
        parts.forEachIndexed { i, s -> Speaker.speak(s.trim(), flush = i == 0) }
    }

    /** Poll until TTS finishes (any queued sentences), then re-open the mic. */
    private fun relistenWhenDoneSpeaking() {
        val dv = window.decorView
        val poll = object : Runnable {
            override fun run() {
                if (isFinishing || busy) return
                if (Speaker.isSpeaking()) dv.postDelayed(this, 300)
                else ensureMicThenListen()
            }
        }
        dv.postDelayed(poll, 700)
    }

    /** Poll until TTS finishes, then close the panel (used after app hand-off). */
    private fun finishWhenDoneSpeaking() {
        val dv = window.decorView
        val poll = object : Runnable {
            override fun run() {
                if (isFinishing) return
                if (Speaker.isSpeaking()) dv.postDelayed(this, 300)
                else finish()
            }
        }
        dv.postDelayed(poll, 900)
    }

    /** Tools that launch/hand off to another app → the panel should close after. */
    private fun isAppLaunchTool(tool: String): Boolean {
        val t = tool.lowercase()
        return t.contains("open_app") || t.contains("open_url") || t.contains("play_music") ||
            t.contains("make_call") || t.contains("send_message") || t.contains("send_sms") ||
            t.contains("open_app_action")
    }

    /** Tap the orb: cancel a running task, or interrupt TTS to talk. */
    private fun bargeIn() {
        when {
            busy -> {
                runCatching { appViewModel.stopTask() }
                busy = false
                runCatching { Speaker.stop() }
                status.value = "Cancelado, jefe."
                phase.value = Phase.IDLE
                scheduleIdleAutoClose()
            }
            phase.value == QuickAssistActivity.Phase.SPEAKING -> {
                Speaker.stop()
                if (!busy) ensureMicThenListen()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // DON'T stop listening on pause — this activity shows over the lock screen
        // and Android may briefly pause it during transitions (display wake, biometric
        // prompt, etc.). Killing the mic on every onPause causes "doesn't listen" bugs.
        // We only release the mic in onDestroy (user dismissed) or when a task runs.
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { Speaker.stop() }
        runCatching { VoiceInputManager.stopWakeLoop() }
        // Hand the mic back to the background wake service so "garra" keeps working.
        if (VoiceInputManager.wakeEnabled) {
            runCatching {
                val i = android.content.Intent(this,
                    com.blackclaw.android.service.VoiceWakeService::class.java)
                    .setAction(com.blackclaw.android.service.VoiceWakeService.ACTION_RESUME_WAKE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
                else startService(i)
            }
        }
    }
}

@Composable
private fun AssistScreen(
    turns: List<QuickAssistActivity.Turn>,
    status: String,
    partial: String,
    phase: QuickAssistActivity.Phase,
    rms: Float,
    busy: Boolean,
    onMic: () -> Unit,
    onOrbTap: () -> Unit,
    onSuggestion: (String) -> Unit,
    onTyped: (String) -> Unit,
    onStartTyping: () -> Unit,
    onClose: () -> Unit,
) {
    var textMode by remember { mutableStateOf(false) }
    var typedText by remember { mutableStateOf("") }
    val level by animateFloatAsState(rms, tween(120, easing = LinearEasing), label = "level")
    val bgTop by animateColorAsState(
        when (phase) {
            QuickAssistActivity.Phase.LISTENING -> Color(0xFF1A0E3A)
            QuickAssistActivity.Phase.THINKING -> Color(0xFF0E1A3A)
            QuickAssistActivity.Phase.SPEAKING -> Color(0xFF2A0E2E)
            else -> Color(0xFF120A1E)
        }, tween(600), label = "bgTop")
    // Subtle, continuous vertical drift of the gradient for a "living" feel.
    val bgAnim = rememberInfiniteTransition(label = "bg")
    val drift by bgAnim.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(5000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "drift")

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to bgTop,
                (0.45f + drift * 0.15f) to Color(0xFF08060F),
                1f to Color(0xFF050309),
            )
        ),
    ) {
        // Close button
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            Icon(Icons.Default.Close, "Cerrar", tint = Color(0xFF9A8BC0))
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            ReactiveOrb(level = level, phase = phase, onTap = onOrbTap)
            Spacer(Modifier.height(20.dp))
            Text(
                when (phase) {
                    QuickAssistActivity.Phase.LISTENING -> "ESCUCHANDO"
                    QuickAssistActivity.Phase.THINKING -> "PENSANDO"
                    QuickAssistActivity.Phase.SPEAKING -> "BLACKCLAW"
                    QuickAssistActivity.Phase.NEED_MIC -> "PERMISO"
                    QuickAssistActivity.Phase.IDLE -> "EN PAUSA"
                },
                color = Color(0xFFB9A7E0), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
            )

            // Live partial transcript while listening.
            if (phase == QuickAssistActivity.Phase.LISTENING && partial.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(partial, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(16.dp))

            // Conversation transcript (everything stays here, no app redirect).
            val listState = rememberLazyListState()
            LaunchedEffect(turns.size) {
                if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(turns) { t -> TurnBubble(t) }
                if (turns.isEmpty()) {
                    item {
                        Text(
                            status, color = Color.White.copy(alpha = 0.92f), fontSize = 20.sp,
                            textAlign = TextAlign.Center, lineHeight = 27.sp,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }

            // Status line for non-listening phases (thinking/idle/need mic).
            if (phase != QuickAssistActivity.Phase.LISTENING && turns.isNotEmpty()) {
                Text(status, color = Color(0xFF9A8BC0), fontSize = 13.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(8.dp))
            }

            // Suggestion chips on first open / when idle, to hint what to say.
            // Hidden in text mode so they don't crowd the keyboard input row.
            val showChips = !textMode && (phase == QuickAssistActivity.Phase.IDLE ||
                (turns.isEmpty() && phase == QuickAssistActivity.Phase.LISTENING))
            if (showChips) {
                SuggestionChips(onSuggestion)
                Spacer(Modifier.height(10.dp))
            }

            // Input dock: type a message, or tap-to-talk (idle / need mic).
            if (textMode) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = typedText,
                        onValueChange = { typedText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Escribe un mensaje…", color = Color(0xFF7A6FA0)) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (typedText.isNotBlank() && !busy) { onTyped(typedText); typedText = ""; textMode = false }
                        }),
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF9D5CFF),
                            unfocusedBorderColor = Color(0xFF3A2E5C),
                            cursorColor = Color(0xFF9D5CFF),
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .background(Brush.radialGradient(listOf(Color(0xFF7C3AED), Color(0xFF3A1E6E))))
                            .clickable(enabled = !busy) {
                                if (typedText.isNotBlank()) { onTyped(typedText); typedText = ""; textMode = false }
                            },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar", tint = Color.White, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = { textMode = false; onMic() }) {
                        Icon(Icons.Default.Mic, "Hablar", tint = Color(0xFFB9A7E0))
                    }
                }
            } else {
                // Keyboard toggle is always available (even while listening/
                // thinking/speaking) so the user can switch to typing whenever
                // voice isn't cooperating. The big tap-to-talk mic only shows
                // when idle, same as before.
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    if (phase == QuickAssistActivity.Phase.IDLE || phase == QuickAssistActivity.Phase.NEED_MIC) {
                        Box(
                            Modifier.size(64.dp).clip(CircleShape)
                                .background(Brush.radialGradient(listOf(Color(0xFF7C3AED), Color(0xFF3A1E6E))))
                                .clickable { onMic() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Mic, "Hablar", tint = Color.White, modifier = Modifier.size(30.dp)) }
                        Spacer(Modifier.width(16.dp))
                    }
                    IconButton(
                        onClick = { onStartTyping(); textMode = true },
                        modifier = Modifier.size(44.dp).clip(CircleShape)
                            .background(Color(0xFF221A38)),
                    ) { Icon(Icons.Default.Keyboard, "Escribir", tint = Color(0xFFB9A7E0), modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SuggestionChips(onSuggestion: (String) -> Unit) {
    // A varied mix so the panel signals it can do more than "open app X":
    // agenda/reminders, device control, security, and general Q&A.
    val chips = remember {
        listOf(
            "¿Qué tengo hoy?", "Pon música", "¿Cuánta batería?",
            "Pídeme un Uber", "Lee mis notificaciones", "¿Qué hora es?",
            "Recuérdame llamar al dentista mañana a las 5",
            "¿Alguna app me está mostrando anuncios?",
            "Resume mis mensajes sin leer",
            "Pon una alarma a las 7",
        ).shuffled().take(6)
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { c ->
            Surface(
                color = Color(0xFF221A38),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.clickable { onSuggestion(c) },
            ) {
                Text(c, color = Color(0xFFCFC2EE), fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }
    }
}

private val URL_REGEX = Regex("""https?://[^\s)\]]+""")
private val IMG_EXT = Regex("""\.(png|jpe?g|webp|gif)(\?.*)?$""", RegexOption.IGNORE_CASE)

@Composable
private fun TurnBubble(t: QuickAssistActivity.Turn) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val align = if (t.fromUser) Alignment.End else Alignment.Start
    val bg = if (t.fromUser) Color(0xFF2A2140) else Color(0xFF17223A)
    val fg = if (t.fromUser) Color(0xFFE8E0FF) else Color.White

    val visible = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    LaunchedEffect(Unit) { visible.targetState = true }

    val urls = remember(t.text) { URL_REGEX.findAll(t.text).map { it.value }.distinct().toList() }
    val images = urls.filter { IMG_EXT.containsMatchIn(it) }
    val links = urls.filter { it !in images }

    androidx.compose.animation.AnimatedVisibility(
        visibleState = visible,
        enter = androidx.compose.animation.fadeIn(tween(300)) +
            androidx.compose.animation.slideInVertically(tween(300)) { it / 3 },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
            Surface(color = bg, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.widthIn(max = 340.dp).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(t.text, color = fg, fontSize = 15.sp, lineHeight = 21.sp)
                    images.take(2).forEach { url ->
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.ui.viewinterop.AndroidView(
                            factory = { c ->
                                android.widget.ImageView(c).apply {
                                    adjustViewBounds = true
                                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                    runCatching {
                                        com.bumptech.glide.Glide.with(c).load(url).into(this)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                    }
                }
            }
            if (links.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                links.take(3).forEach { url ->
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = Color(0xFF20304F), shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.widthIn(max = 340.dp).clickable {
                            runCatching {
                                ctx.startActivity(android.content.Intent(
                                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        },
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("🔗", fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(prettyUrl(url), color = Color(0xFF8FB4FF), fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Short host+path for a link chip. */
private fun prettyUrl(url: String): String = runCatching {
    val u = android.net.Uri.parse(url)
    (u.host?.removePrefix("www.") ?: url) + (u.path?.take(20) ?: "")
}.getOrDefault(url.take(40))
@Composable
private fun ReactiveOrb(level: Float, phase: QuickAssistActivity.Phase, onTap: () -> Unit = {}) {
    val infinite = rememberInfiniteTransition(label = "orb")
    val rot by infinite.animateFloat(
        0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "rot")
    val breathe by infinite.animateFloat(
        0.9f, 1.06f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe")
    val sweep by infinite.animateFloat(
        0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "sweep")

    val thinking = phase == QuickAssistActivity.Phase.THINKING
    val speaking = phase == QuickAssistActivity.Phase.SPEAKING
    val coreScale = if (phase == QuickAssistActivity.Phase.LISTENING) 0.92f + level * 0.5f else breathe

    Box(Modifier.size(150.dp).clickable { onTap() },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val baseR = size.minDimension / 2f
            val ringColors = listOf(Color(0xFF7C3AED), Color(0xFF9D5CFF), Color(0xFF00D4FF))
            for (i in 0 until 3) {
                val wobble = (sin((rot / 57.3f) + i * 0.6f) * 0.04f).toFloat()
                val r = baseR * (0.55f + i * 0.16f) * (1f + level * 0.35f + wobble)
                drawCircle(
                    color = ringColors[i].copy(alpha = 0.18f - i * 0.04f),
                    radius = r, center = c,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f + level * 6f),
                )
            }
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0x559D5CFF), Color(0x00000000)),
                    center = c, radius = baseR * (0.7f + level * 0.4f)),
                radius = baseR * (0.7f + level * 0.4f), center = c,
            )
            val coreR = baseR * 0.42f * coreScale
            rotate(rot, c) {
                drawCircle(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFF7C3AED), Color(0xFF00D4FF), Color(0xFFEC4899)),
                        start = Offset(c.x - coreR, c.y - coreR),
                        end = Offset(c.x + coreR, c.y + coreR)),
                    radius = coreR, center = c,
                )
            }
            if (thinking) {
                drawArc(
                    color = Color.White.copy(alpha = 0.85f),
                    startAngle = sweep, sweepAngle = 80f, useCenter = false,
                    topLeft = Offset(c.x - baseR * 0.62f, c.y - baseR * 0.62f),
                    size = androidx.compose.ui.geometry.Size(baseR * 1.24f, baseR * 1.24f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f),
                )
            }

            // ── BlackClaw mark: three tapered claw slashes, centered in the core.
            // Pulses with mic level (listening) / breathes otherwise. On-brand,
            // no emoji — a stylized claw that feels like the app's identity.
            val clawTilt = if (thinking) sweep * 0.05f else -18f
            rotate(clawTilt, c) {
                val reach = baseR * (0.34f + level * 0.14f)   // slash length reacts to voice
                val gap = baseR * 0.16f
                val stroke = (baseR * 0.055f) * (0.85f + level * 0.6f)
                for (k in -1..1) {
                    val dx = k * gap
                    // Curved slash: starts thin at top, sweeps down — a claw rake.
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(c.x + dx - reach * 0.28f, c.y - reach)
                        quadraticBezierTo(
                            c.x + dx + reach * 0.10f, c.y,
                            c.x + dx + reach * 0.30f, c.y + reach)
                    }
                    drawPath(
                        path,
                        color = Color.White.copy(alpha = 0.92f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                }
            }
        }
    }
}
