package com.blackclaw.android.ui.assist

import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.core.view.WindowCompat
import com.blackclaw.android.TaskEvent
import com.blackclaw.android.appViewModel
import com.blackclaw.android.assistant.JarvisVoice
import com.blackclaw.android.assistant.Speaker
import com.blackclaw.android.assistant.VoiceInputManager
import com.blackclaw.android.ui.design.ClawAnimation
import com.blackclaw.android.utils.XLog
import java.util.UUID
import kotlin.math.cos
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

        /**
         * Cards shown with one answer.
         *
         * Four is enough to compare a handful of search results and few enough that the
         * spoken reply is still the thing the panel leads with — this is a voice surface
         * first, and a screenful of cards turns it into a browser.
         */
        const val MAX_TURN_CARDS = 4
    }

    enum class Phase { LISTENING, THINKING, SPEAKING, IDLE, NEED_MIC }

    /**
     * @param cards structured results produced by the tools this turn ran. They are
     *   attached to the answer rather than shown as they arrive, so the cards and the
     *   sentence explaining them appear together instead of the panel rearranging itself
     *   mid-reply.
     */
    data class Turn(
        val fromUser: Boolean,
        val text: String,
        val cards: List<com.blackclaw.android.cards.AssistCard> = emptyList(),
        val id: String = UUID.randomUUID().toString(),
    )

    private val turns = mutableStateListOf<Turn>()
    private val status = mutableStateOf("Le escucho, jefe…")
    private val partial = mutableStateOf("")
    private val phase = mutableStateOf(Phase.LISTENING)
    private val rms = mutableFloatStateOf(0f)
    private val progress = mutableStateOf<QuickAssistProgress?>(null)
    private val recovery = mutableStateOf<QuickAssistRecovery?>(null)
    private var started = false
    private var silentCount = 0
    private var busy by mutableStateOf(false)
    private var lastCommand = ""

    // Streaming state for the current answer.
    private val streamBuf = StringBuilder()
    private var spokenLen = 0
    private var didStreamSpeak = false
    private var toolUsed = false
    private var leftApp = false   // an app-launching tool ran → hand off & close

    /** Cards collected from tools during the current command, attached when it answers. */
    private val pendingCards = mutableListOf<com.blackclaw.android.cards.AssistCard>()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening()
        else {
            phase.value = Phase.NEED_MIC
            status.value = "Necesito permiso de micrófono para escucharte."
            recovery.value = QuickAssistRecovery.MICROPHONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The background wake-word service and this panel must never listen or
        // speak together. Without this hand-off it can hear the same command,
        // execute a second task and voice an unrelated failure over QuickAssist.
        com.blackclaw.android.service.VoiceWakeService.pauseForQuickAssist()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
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
                progress = progress.value,
                recovery = recovery.value,
                onMic = { if (!busy) { hapticTap(); switchingToVoice(); ensureMicThenListen() } },
                onOrbTap = { hapticTap(); bargeIn() },
                onSuggestion = { runSuggestion(it) },
                onTyped = { onTypedCommand(it) },
                onStartTyping = { stopListeningForTyping() },
                onCancel = { cancelCurrentTask() },
                onRecovery = { runRecoveryAction() },
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
            if (cmd.isNotBlank()) window.decorView.postDelayed({ onCommand(cmd) }, 100L)
            else window.decorView.postDelayed({ ensureMicThenListen() }, 300L)
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
            recovery.value = QuickAssistRecovery.MICROPHONE
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
        recovery.value = null
        status.value = if (turns.isEmpty()) "Le escucho, jefe…" else "Dígame…"
        updateLiveNotif("ESCUCHANDO", status.value)
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
        if (startEmergencyProtection(command)) return
        silentCount = 0
        // Reset streaming state for this answer.
        streamBuf.setLength(0); spokenLen = 0; didStreamSpeak = false; toolUsed = false; leftApp = false
        pendingCards.clear()
        progress.value = null
        recovery.value = null
        // End-of-conversation phrases close the panel politely.
        if (isFarewell(command)) {
            turns.add(Turn(true, command))
            phase.value = Phase.SPEAKING
            status.value = "Hasta luego, jefe."
            Speaker.speak("A sus órdenes, jefe.")
            window.decorView.postDelayed({ if (!isFinishing) finish() }, 1400)
            return
        }
        if (isScreenQuery(command)) {
            handleScreenQuery(command)
            return
        }
        busy = true
        lastCommand = command
        turns.add(Turn(true, command))
        phase.value = Phase.THINKING
        partial.value = ""
        status.value = "Pensando…"
        progress.value = QuickAssistProgress("Preparando la respuesta", "Entendiendo tu solicitud")
        updateLiveNotif("PENSANDO", command)
        Speaker.speak(JarvisVoice.commandAck())

        val override = buildContextPrompt(command)
        val taskId = "assist-" + UUID.randomUUID().toString().take(8)
        runCatching {
            appViewModel.startTask(command, taskId, agentPromptOverride = override, autoReturnToChat = false,
                surface = com.blackclaw.android.conversation.ConversationRepository.Surface.QUICK_ASSIST) { event ->
                runOnUiThread {
                    when (event) {
                        is TaskEvent.ToolAction -> {
                            toolUsed = true
                            if (isAppLaunchTool(event.toolName)) leftApp = true
                            if (busy) {
                                phase.value = Phase.THINKING
                                status.value = QuickAssistTaskReducer.toolLabel(event.toolName) + "…"
                                progress.value = QuickAssistProgress(
                                    QuickAssistTaskReducer.toolLabel(event.toolName),
                                    "Ejecutando una acción segura",
                                )
                            }
                        }
                        is TaskEvent.LoopStart -> if (busy) {
                            if (status.value == "Pensando…") status.value = "Trabajando…"
                            progress.value = QuickAssistProgress("Trabajando", "Ronda ${event.round}", event.round)
                        }
                        is TaskEvent.Progress -> if (busy) {
                            status.value = event.description.take(60)
                            progress.value = QuickAssistProgress("En progreso", event.description.take(100), event.step)
                        }
                        is TaskEvent.Thinking -> onStreamToken(event.content)
                        is TaskEvent.Response -> { if (busy && event.text.isNotBlank() && streamBuf.isEmpty()) status.value = event.text.take(300) }
                        is TaskEvent.ToolCards -> {
                            val decoded = com.blackclaw.android.cards.AssistCardCodec
                                .decode(event.payload)
                            // Later results replace earlier ones of the same shape only
                            // by accumulation order; the cap keeps a chatty search from
                            // burying the answer under a wall of cards.
                            decoded.forEach {
                                if (pendingCards.size < MAX_TURN_CARDS) pendingCards.add(it)
                            }
                        }
                        is TaskEvent.Completed -> answerStreamed(event.answer)
                        is TaskEvent.Failed -> {
                            recovery.value = recoveryFor(event.error)
                            answerStreamed(friendlyError(event.error))
                        }
                        is TaskEvent.Cancelled, is TaskEvent.Blocked -> {
                            busy = false
                            progress.value = null
                            phase.value = Phase.IDLE
                        }
                        else -> Unit
                    }
                }
            }
        }.onFailure {
            XLog.w(TAG, "startTask failed: ${it.message}")
            answerStreamed("Hubo un problema al ejecutar la tarea.")
        }
    }

    /**
     * Safety fast-path for both protection modes. It runs before the agent so a
     * locked-screen invocation never waits for network/model latency before
     * starting the foreground service. Keeping this Activity visible briefly is
     * intentional: Android permits the camera/mic foreground service because it
     * was started from an activity the user can see, even on the keyguard.
     */
    private fun startEmergencyProtection(command: String): Boolean {
        val options = com.blackclaw.android.emergency.EmergencyCommandParser.parse(command) ?: return false
        runCatching { VoiceInputManager.cancelListenOnce() }
        if (options.silent) runCatching { Speaker.stop() }
        val started = com.blackclaw.android.emergency.EmergencyService.start(this, options)
        if (started) {
            busy = false
            progress.value = null
            phase.value = Phase.IDLE
            if (options.silent) {
                // No spoken/textual acknowledgement for discreet mode. The short
                // hand-off lets the camera foreground service acquire its sensor
                // before this overlay disappears from the lock screen.
                window.decorView.postDelayed({ if (!isFinishing) finish() }, 900L)
            } else {
                turns.add(Turn(true, command))
                turns.add(Turn(false, "Modo emergencia iniciándose. Puedes cancelarlo desde la notificación."))
                status.value = "Activando emergencia…"
                // The normal mode has a five-second cancellation window. Keep the
                // lock-screen activity visible through it so camera startup remains
                // eligible for Android's while-in-use foreground-service policy.
                window.decorView.postDelayed({ if (!isFinishing) finish() }, 6_200L)
            }
        } else {
            turns.add(Turn(true, command))
            phase.value = Phase.IDLE
            status.value = "Configura el contacto y los permisos del modo de protección en Ajustes."
        }
        return true
    }

    private fun buildContextPrompt(command: String): String? {
        val sharedLines = com.blackclaw.android.conversation.ConversationRepository
            .recentLocalLines(maxTurns = 8, maxChars = 1_200)
        val sessionTurns = turns.takeLast(4)
        if (sharedLines.isEmpty() && sessionTurns.none { !it.fromUser }) return null
        return buildString {
            if (sharedLines.isNotEmpty()) {
                append("Contexto reciente del asistente (chat, voz, tareas):\n")
                sharedLines.forEach { append(it).append('\n') }
                append('\n')
            }
            if (sessionTurns.any { !it.fromUser }) {
                append("En ESTA sesión de voz:\n")
                sessionTurns.forEach { t ->
                    val role = if (t.fromUser) "Usuario" else "Tú"
                    append(role).append(": ").append(t.text.take(200)).append('\n')
                }
                append('\n')
            }
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
        return c.split(' ').size <= 3 && phrases.any { c == it || c.startsWith(it) }
    }

    private fun isGreeting(cmd: String): Boolean {
        val c = cmd.lowercase().trim().replace(Regex("[¿?!,.]"), "").trim()
        val greetings = listOf(
            "hola", "hey", "buenas", "buenos dias", "buenos días", "buenas tardes",
            "buenas noches", "que tal", "qué tal", "hello", "hi", "hey blackclaw",
            "hola blackclaw", "oye", "oye blackclaw", "garra",
        )
        return c.split(' ').size <= 3 && greetings.any { c == it || c.startsWith(it) }
    }

    private fun isScreenQuery(cmd: String): Boolean {
        val c = cmd.lowercase().trim()
        return c.contains("que hay en mi pantalla") || c.contains("qué hay en mi pantalla") ||
            c.contains("que ves en pantalla") || c.contains("qué ves en pantalla") ||
            c.contains("que estoy viendo") || c.contains("qué estoy viendo") ||
            c.contains("lee mi pantalla") || c.contains("leeme la pantalla") ||
            c.contains("que dice la pantalla") || c.contains("qué dice la pantalla") ||
            c.contains("what's on my screen") || c.contains("read my screen") ||
            c.contains("que app estoy usando") || c.contains("dónde estoy") ||
            c.contains("donde estoy")
    }

    private fun handleScreenQuery(command: String) {
        turns.add(Turn(true, command))
        phase.value = Phase.THINKING
        status.value = "Analizando la pantalla…"
        updateLiveNotif("PENSANDO", "Analizando pantalla")
        Thread({
            val service = com.blackclaw.android.service.ClawAccessibilityService.getConnectedInstance(1_000L)
            // A real VoiceInteractionSession receives this data before this Activity
            // overlays the foreground app. It is the most accurate source because it
            // does not depend on an accessibility event winning a timing race.
            val nativeAssistContext = AssistInvocationContext.recent(30_000L)
            // Accessibility/OCR caches remain a useful fallback for direct launches
            // and devices that decline AssistStructure or screenshot delivery.
            val cachedTree = if (service != null) {
                com.blackclaw.android.service.ClawAccessibilityService.getRecentExternalScreenTree(20_000L)
            } else null
            val cachedPackage = com.blackclaw.android.service.ClawAccessibilityService.getLastExternalPackage()
            val cachedOcr = com.blackclaw.android.perception.ExternalScreenOcrCache.recentLines(20_000L)
            val preAssistTree = nativeAssistContext?.accessibilityTree ?: cachedTree
            val preAssistPackage = nativeAssistContext?.packageName ?: cachedPackage
            val preAssistOcr = nativeAssistContext?.ocrLines?.ifEmpty { cachedOcr } ?: cachedOcr
            val hasPreAssistContext = nativeAssistContext != null ||
                preAssistTree != null || preAssistOcr.isNotEmpty()
            val registry = com.blackclaw.android.tool.ToolRegistry.getInstance()
            val liveTree = if (!hasPreAssistContext) {
                runCatching { registry.getTool("get_screen_info")?.execute(emptyMap())?.data }.getOrNull()
            } else null
            val app = if (hasPreAssistContext && !preAssistPackage.isNullOrBlank()) {
                preAssistPackage
            } else {
                runCatching { registry.getTool("get_foreground_app")?.execute(emptyMap())?.data }.getOrNull()
            }
            val ownPanelIsFocused = !hasPreAssistContext &&
                (app.isNullOrBlank() || app.contains(packageName))

            // OCR is supplemental, never a fallback: accessibility can see controls
            // but not text rendered by Canvas, maps, video, games or many WebViews.
            // It is skipped for a pre-assist snapshot because the live pixels now hold
            // this assistant panel, not the application the user asked about.
            val ocrLines = if (hasPreAssistContext) {
                preAssistOcr
            } else if (!ownPanelIsFocused &&
                com.blackclaw.android.perception.ScreenCaptureService.isRunning()) {
                com.blackclaw.android.perception.ScreenCaptureService.captureBitmap()?.let {
                    com.blackclaw.android.perception.ScreenOcr.readingOrder(
                        com.blackclaw.android.perception.ScreenOcr.recognizeScreen(it), limit = 60)
                }.orEmpty()
            } else emptyList()
            // Don't ask for MediaProjection after our full-screen panel has taken
            // focus: it would capture this UI instead of the app behind it. Pixel
            // context for an assistant invocation comes from the pre-assist cache;
            // regular agent OCR keeps requesting capture when it is actually
            // operating over the target app.
            val requestedCapture = !ownPanelIsFocused && !hasPreAssistContext &&
                !com.blackclaw.android.perception.ScreenCaptureService.isRunning()
            if (requestedCapture) {
                runOnUiThread {
                    com.blackclaw.android.perception.ScreenCapturePermissionActivity.start(applicationContext)
                }
            }
            val scene = com.blackclaw.android.perception.ScreenScene.compose(
                foregroundApp = app,
                accessibilityTree = preAssistTree ?: liveTree,
                ocrLines = ocrLines,
            )
            val answer = when {
                scene.visibleText.isNotEmpty() -> buildString {
                    if (nativeAssistContext != null) {
                        append("Lectura recibida al invocarme:\n")
                    } else if (hasPreAssistContext) {
                        append("Lectura tomada justo antes de abrirme:\n")
                    }
                    append(scene.describeForQuickAssist())
                    if (requestedCapture) append("\n\nPara leer también texto dibujado o vídeo, acepta el permiso de captura.")
                }
                ownPanelIsFocused -> "No alcancé a guardar el contexto de la app anterior antes de abrirme. Vuelve a invocarme sobre esa app; con accesibilidad activa guardaré su texto justo antes de mostrar este panel."
                requestedCapture -> "Puedo leer los controles accesibles, pero para ver todo lo dibujado en pantalla necesito que aceptes el permiso de captura que acabo de abrir."
                else -> "No encontré texto legible en pantalla. Puede ser una imagen, vídeo o una app que oculta su contenido."
            }
            runOnUiThread {
                turns.add(Turn(false, answer.take(500)))
                phase.value = Phase.SPEAKING
                status.value = ""
                speakBySentences(answer.take(400))
                updateLiveNotif("BLACKCLAW", answer.take(60))
                relistenWhenDoneSpeaking()
            }
        }, "screen-query").start()
    }

    /** Friendly Spanish status for a tool the agent is running. */
    private fun friendlyTool(tool: String): String = QuickAssistTaskReducer.toolLabel(tool) + "…"

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

    private fun recoveryFor(raw: String): QuickAssistRecovery? {
        val error = raw.lowercase()
        return when {
            error.contains("permiso") || error.contains("permission") ||
                error.contains("accesibilidad") || error.contains("accessibility") -> QuickAssistRecovery.ACCESSIBILITY
            error.contains("network") || error.contains("timeout") || error.contains("conexión") ||
                error.contains("conexion") -> QuickAssistRecovery.CONNECTION
            error.isNotBlank() -> QuickAssistRecovery.RETRY
            else -> null
        }
    }

    private fun runRecoveryAction() {
        when (recovery.value) {
            QuickAssistRecovery.MICROPHONE -> ensureMicThenListen()
            QuickAssistRecovery.ACCESSIBILITY -> runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            QuickAssistRecovery.CONNECTION -> runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
            }
            QuickAssistRecovery.RETRY -> lastCommand.takeIf { it.isNotBlank() }?.let(::onCommand)
            null -> Unit
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
        progress.value = null
        val display = stripReasoning(raw)
            .replace(Regex("[*_#`>]+"), " ")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .ifBlank { "Listo, jefe." }
        turns.add(Turn(false, display, pendingCards.toList()))
        pendingCards.clear()
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
                cancelCurrentTask()
            }
            phase.value == QuickAssistActivity.Phase.SPEAKING -> {
                Speaker.stop()
                if (!busy) ensureMicThenListen()
            }
        }
    }

    private fun cancelCurrentTask() {
        if (!busy) return
        runCatching { appViewModel.stopTask() }
        busy = false
        runCatching { Speaker.stop() }
        status.value = "Cancelado, jefe."
        phase.value = Phase.IDLE
        progress.value = null
        scheduleIdleAutoClose()
    }

    private fun hapticTap() {
        runCatching {
            window.decorView.performHapticFeedback(
                android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    private fun stripReasoning(text: String): String {
        val reasoningPatterns = listOf(
            Regex("""(?i)^(respond[ií]|contest[eé]|ofrec[ií]|ayud[oé]|salud[oé]|pregunt[oé])\s+(al|a la|el|la)\s+usuario.*"""),
            Regex("""(?i)^(debo|voy a|necesito|tengo que|puedo|quiero)\s+(responder|contestar|ayudar|saludar|preguntar|decir).*"""),
            Regex("""(?i)^el usuario (quiere|pide|dice|necesita|solicita).*"""),
            Regex("""(?i)^(i should|i will|i need to|i can|the user wants|the user is).*"""),
            Regex("""(?i)^(let me|first,? i|now i|then i|ok,? so).*"""),
        )
        val lines = text.lines()
        val cleaned = lines.filter { line ->
            val trimmed = line.trim()
            trimmed.isEmpty() || !reasoningPatterns.any { it.containsMatchIn(trimmed) }
        }
        val result = cleaned.joinToString("\n").trim()
        return result.ifBlank { text }
    }

    private fun updateLiveNotif(phaseLabel: String, detail: String) {
        runCatching { AssistLiveNotification.show(this, phaseLabel, detail) }
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
        runCatching { AssistLiveNotification.dismiss(this) }
        saveSessionMemory()
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

    private fun saveSessionMemory() {
        runCatching {
            val userTurns = turns.filter { it.fromUser }
            if (userTurns.isEmpty()) return
            val summary = com.blackclaw.android.memory.ConversationMemory.extractSummary(
                turns.map { (if (it.fromUser) "USER" else "ASSISTANT") to it.text }
            )
            if (summary.isNotBlank()) {
                com.blackclaw.android.memory.ConversationMemory.record(
                    conversationId = "voice-${System.currentTimeMillis()}",
                    summary = summary,
                    topics = com.blackclaw.android.memory.ConversationMemory.extractTopics(
                        turns.map { (if (it.fromUser) "USER" else "ASSISTANT") to it.text }
                    ),
                )
            }
        }
    }
}

/** The system-assistant surface has its own restrained obsidian-and-gold identity. */
private object ObsidianGold {
    val Void = Color(0xFF050504)
    val Obsidian = Color(0xFF0C0B09)
    val Surface = Color(0xFF15130F)
    val Raised = Color(0xFF201C14)
    val Outline = Color(0xFF403623)
    val Ink = Color(0xFFFFF8E7)
    val Muted = Color(0xFFC0B59D)
    val Quiet = Color(0xFF827864)
    val Gold = Color(0xFFD7AC4A)
    val GoldLight = Color(0xFFFFE09A)
    val GoldDeep = Color(0xFF76521B)
    val Ember = Color(0xFFB97832)
    val Alert = Color(0xFFE49B5A)

    fun active(phase: QuickAssistActivity.Phase): Color = when (phase) {
        QuickAssistActivity.Phase.LISTENING -> Gold
        QuickAssistActivity.Phase.THINKING -> GoldLight
        QuickAssistActivity.Phase.SPEAKING -> Ember
        QuickAssistActivity.Phase.NEED_MIC -> Alert
        QuickAssistActivity.Phase.IDLE -> GoldDeep
    }

    val actionBrush: Brush
        get() = Brush.linearGradient(listOf(GoldLight, Gold, GoldDeep))
}

@Composable
private fun AssistScreen(
    turns: List<QuickAssistActivity.Turn>,
    status: String,
    partial: String,
    phase: QuickAssistActivity.Phase,
    rms: Float,
    busy: Boolean,
    progress: QuickAssistProgress?,
    recovery: QuickAssistRecovery?,
    onMic: () -> Unit,
    onOrbTap: () -> Unit,
    onSuggestion: (String) -> Unit,
    onTyped: (String) -> Unit,
    onStartTyping: () -> Unit,
    onCancel: () -> Unit,
    onRecovery: () -> Unit,
    onClose: () -> Unit,
) {
    var textMode by remember { mutableStateOf(false) }
    var typedText by remember { mutableStateOf("") }
    val level by animateFloatAsState(rms, tween(120, easing = LinearEasing), label = "level")
    val bgTop by animateColorAsState(
        when (phase) {
            QuickAssistActivity.Phase.LISTENING -> Color(0xFF17130C)
            QuickAssistActivity.Phase.THINKING -> Color(0xFF1A160D)
            QuickAssistActivity.Phase.SPEAKING -> Color(0xFF1B110A)
            else -> ObsidianGold.Obsidian
        }, tween(600), label = "bgTop")
    // Subtle, continuous vertical drift of the gradient for a "living" feel.
    val bgAnim = rememberInfiniteTransition(label = "bg")
    val drift by bgAnim.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(5000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "drift")
    val compactOrb = turns.isNotEmpty()
    val orbSize by animateDpAsState(
        targetValue = if (compactOrb) 82.dp else 150.dp,
        animationSpec = tween(360, easing = FastOutSlowInEasing),
        label = "orbSize",
    )

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to bgTop,
                (0.45f + drift * 0.15f) to ObsidianGold.Obsidian,
                1f to ObsidianGold.Void,
            )
        ),
    ) {
        PremiumAuroraBackdrop(phase = phase, level = level)
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            PremiumAssistHeader(phase, onClose)
            Spacer(Modifier.height(if (compactOrb) 8.dp else 14.dp))
            Surface(
                color = ObsidianGold.Raised.copy(alpha = 0.68f),
                shape = RoundedCornerShape(38.dp),
                modifier = Modifier.padding(4.dp)
                    .border(1.dp, ObsidianGold.GoldDeep.copy(alpha = 0.55f), RoundedCornerShape(38.dp)),
            ) {
                ReactiveOrb(orbSize = orbSize, level = level, phase = phase, onTap = onOrbTap)
            }
            Spacer(Modifier.height(if (compactOrb) 8.dp else 14.dp))
            androidx.compose.animation.AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    androidx.compose.animation.fadeIn(tween(400)) togetherWith
                        androidx.compose.animation.fadeOut(tween(300))
                },
                label = "phaseLabel",
            ) { p ->
                Text(
                    when (p) {
                        QuickAssistActivity.Phase.LISTENING -> "ESCUCHANDO"
                        QuickAssistActivity.Phase.THINKING -> "PENSANDO"
                        QuickAssistActivity.Phase.SPEAKING -> "BLACKCLAW"
                        QuickAssistActivity.Phase.NEED_MIC -> "PERMISO"
                        QuickAssistActivity.Phase.IDLE -> "EN PAUSA"
                    },
                    color = ObsidianGold.GoldLight, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 3.5.sp,
                )
            }

            // Live partial transcript while listening.
            if (phase == QuickAssistActivity.Phase.LISTENING && partial.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(partial, color = ObsidianGold.Ink, fontSize = 18.sp, textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium)
            }

            // The live task state is compact and always offers a way back out.
            if (phase == QuickAssistActivity.Phase.THINKING && progress != null) {
                Spacer(Modifier.height(10.dp))
                TaskProgressCard(progress, onCancel)
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
                items(turns, key = { it.id }) { t -> TurnBubble(t) }
                if (turns.isEmpty()) {
                    item {
                        WelcomePanel(status, phase)
                    }
                }
            }

            if (recovery != null) {
                Spacer(Modifier.height(8.dp))
                RecoveryCard(recovery, onRecovery)
            }

            // Status line for speaking/idle phases.
            if ((phase == QuickAssistActivity.Phase.SPEAKING || phase == QuickAssistActivity.Phase.IDLE) && turns.isNotEmpty()) {
                Text(status, color = ObsidianGold.Muted, fontSize = 13.sp,
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
                        placeholder = { Text("Escribe un mensaje…", color = ObsidianGold.Quiet) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = ObsidianGold.Ink, fontSize = 15.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (typedText.isNotBlank() && !busy) { onTyped(typedText); typedText = ""; textMode = false }
                        }),
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ObsidianGold.Gold,
                            unfocusedBorderColor = ObsidianGold.Outline,
                            cursorColor = ObsidianGold.Gold,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .background(ObsidianGold.actionBrush)
                            .clickable(enabled = !busy) {
                                if (typedText.isNotBlank()) { onTyped(typedText); typedText = ""; textMode = false }
                            },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar", tint = ObsidianGold.Void, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = { textMode = false; onMic() }) {
                        Icon(Icons.Default.Mic, "Hablar", tint = ObsidianGold.GoldLight)
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
                                .background(ObsidianGold.actionBrush)
                                .clickable { onMic() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Mic, "Hablar", tint = ObsidianGold.Void, modifier = Modifier.size(30.dp)) }
                        Spacer(Modifier.width(16.dp))
                    }
                    IconButton(
                        onClick = { onStartTyping(); textMode = true },
                        modifier = Modifier.size(44.dp).clip(CircleShape)
                            .background(ObsidianGold.Raised)
                            .border(1.dp, ObsidianGold.Outline, CircleShape),
                    ) { Icon(Icons.Default.Keyboard, "Escribir", tint = ObsidianGold.GoldLight, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun WelcomePanel(status: String, phase: QuickAssistActivity.Phase) {
    val title = when (phase) {
        QuickAssistActivity.Phase.NEED_MIC -> "Elige cómo continuar"
        QuickAssistActivity.Phase.IDLE -> "¿Qué necesitas?"
        else -> "Estoy aquí"
    }
    Surface(
        color = ObsidianGold.Surface.copy(alpha = 0.92f),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            .border(1.dp, ObsidianGold.Outline, RoundedCornerShape(22.dp)),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = ObsidianGold.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text(status, color = ObsidianGold.Muted, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistCapabilityChip("Voz o texto")
                AssistCapabilityChip("Resultados claros")
            }
        }
    }
}

@Composable
private fun AssistCapabilityChip(label: String) {
    Surface(color = ObsidianGold.Raised, shape = RoundedCornerShape(10.dp)) {
        Text(label, color = ObsidianGold.GoldLight, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
    }
}

@Composable
private fun PremiumAssistHeader(phase: QuickAssistActivity.Phase, onClose: () -> Unit) {
    val active = phase == QuickAssistActivity.Phase.LISTENING || phase == QuickAssistActivity.Phase.THINKING
    Surface(
        color = ObsidianGold.Surface.copy(alpha = 0.88f),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, ObsidianGold.Outline, RoundedCornerShape(22.dp)),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(10.dp))
                    .background(ObsidianGold.actionBrush),
                contentAlignment = Alignment.Center,
            ) { Text("B", color = ObsidianGold.Void, fontWeight = FontWeight.Black, fontSize = 16.sp) }
            Spacer(Modifier.width(11.dp))
            Column {
                Text("BLACKCLAW", color = ObsidianGold.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("ASISTENTE DEL SISTEMA", color = ObsidianGold.Quiet, fontSize = 9.sp, letterSpacing = 1.1.sp)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(8.dp).clip(CircleShape)
                .background(if (active) ObsidianGold.Gold else ObsidianGold.Quiet))
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, "Cerrar", tint = ObsidianGold.Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun PremiumAuroraBackdrop(phase: QuickAssistActivity.Phase, level: Float) {
    val reduceMotion = ClawAnimation.reduceMotion()
    val infinite = rememberInfiniteTransition(label = "aurora")
    val drift by infinite.animateFloat(
        0f, if (reduceMotion) 0f else 1f,
        infiniteRepeatable(tween(if (reduceMotion) 1 else 6500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "auroraDrift",
    )
    val phaseColor by animateColorAsState(
        ObsidianGold.active(phase), tween(800), label = "auroraColor"
    )
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            brush = Brush.radialGradient(listOf(phaseColor.copy(alpha = 0.16f + level * 0.09f), Color.Transparent)),
            radius = size.width * 0.75f,
            center = Offset(size.width * (0.18f + drift * 0.18f), size.height * 0.20f),
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(ObsidianGold.GoldDeep.copy(alpha = 0.14f), Color.Transparent)),
            radius = size.width * 0.62f,
            center = Offset(size.width * (0.88f - drift * 0.16f), size.height * 0.62f),
        )
    }
}

@Composable
private fun TaskProgressCard(progress: QuickAssistProgress, onCancel: () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "dots")
    val dot1 by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "d1")
    val dot2 by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(600, delayMillis = 200), RepeatMode.Reverse), label = "d2")
    val dot3 by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(600, delayMillis = 400), RepeatMode.Reverse), label = "d3")

    Surface(
        color = ObsidianGold.Surface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, ObsidianGold.Outline, RoundedCornerShape(18.dp)),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(dot1, dot2, dot3).forEach { alpha ->
                    Box(Modifier.size(7.dp).clip(CircleShape)
                        .background(ObsidianGold.Gold.copy(alpha = 0.3f + alpha * 0.7f)))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(progress.label, color = ObsidianGold.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (progress.detail.isNotBlank()) {
                    Text(progress.detail, color = ObsidianGold.Muted, fontSize = 11.sp, maxLines = 1)
                }
            }
            Surface(
                color = ObsidianGold.Raised,
                shape = RoundedCornerShape(13.dp),
                modifier = Modifier.heightIn(min = 48.dp).clickable(onClick = onCancel),
            ) {
                Box(Modifier.padding(horizontal = 13.dp), contentAlignment = Alignment.Center) {
                    Text("Cancelar", color = ObsidianGold.GoldLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun RecoveryCard(recovery: QuickAssistRecovery, onAction: () -> Unit) {
    val (title, detail, action) = when (recovery) {
        QuickAssistRecovery.MICROPHONE -> Triple(
            "Activa el micrófono", "Puedes seguir escribiendo mientras das el permiso.", "Dar permiso")
        QuickAssistRecovery.ACCESSIBILITY -> Triple(
            "Falta un permiso", "Activa Accesibilidad para que BlackClaw pueda completar esa acción.", "Abrir ajustes")
        QuickAssistRecovery.CONNECTION -> Triple(
            "Revisa tu conexión", "Conéctate a una red y vuelve a intentarlo.", "Abrir red")
        QuickAssistRecovery.RETRY -> Triple(
            "No se completó la acción", "Puedes volver a intentarlo sin repetir tu solicitud.", "Reintentar")
    }
    Surface(
        color = Color(0xFF26170C).copy(alpha = 0.96f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, ObsidianGold.Alert.copy(alpha = 0.55f), RoundedCornerShape(18.dp)),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = ObsidianGold.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = ObsidianGold.Muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Spacer(Modifier.width(10.dp))
            Surface(
                color = ObsidianGold.Alert,
                shape = RoundedCornerShape(13.dp),
                modifier = Modifier.heightIn(min = 48.dp).clickable(onClick = onAction),
            ) {
                Box(Modifier.padding(horizontal = 13.dp), contentAlignment = Alignment.Center) {
                    Text(action, color = ObsidianGold.Void, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SuggestionChips(onSuggestion: (String) -> Unit) {
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val chips = remember(hour) {
        val base = when {
            hour in 5..11 -> listOf("¿Qué tengo hoy?", "Pon una alarma a las 7", "¿Cómo está el clima?",
                "Resume mis notificaciones", "¿Cuánta batería?", "Pon música para trabajar")
            hour in 12..17 -> listOf("¿Qué tengo hoy?", "Pídeme un Uber", "Pon música",
                "Lee mis notificaciones", "¿Cuánta batería?", "Recuérdame llamar al dentista a las 5")
            else -> listOf("¿Qué tengo mañana?", "Pon una alarma a las 7", "Pon música relajante",
                "¿Alguna app me muestra anuncios?", "Resume mis mensajes", "¿Cuánta batería?")
        }
        base.shuffled().take(6)
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { c ->
            Surface(
                color = ObsidianGold.Raised,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.border(1.dp, ObsidianGold.Outline, RoundedCornerShape(18.dp))
                    .clickable { onSuggestion(c) },
            ) {
                Text(c, color = ObsidianGold.Muted, fontSize = 13.sp,
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

    val visible = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    LaunchedEffect(Unit) { visible.targetState = true }

    androidx.compose.animation.AnimatedVisibility(
        visibleState = visible,
        enter = androidx.compose.animation.fadeIn(tween(300)) +
            androidx.compose.animation.slideInVertically(tween(300)) { it / 3 },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
            // Real cards are built from values returned by tools; never guess a card from prose.
            if (t.cards.isNotEmpty()) {
                com.blackclaw.android.ui.cards.AssistCardList(
                    cards = t.cards,
                    modifier = Modifier.widthIn(max = 360.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            val bg = if (t.fromUser) ObsidianGold.Raised else ObsidianGold.Surface
            val fg = ObsidianGold.Ink
            Surface(
                color = bg,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.border(
                    1.dp,
                    if (t.fromUser) ObsidianGold.GoldDeep.copy(alpha = 0.58f) else ObsidianGold.Outline,
                    RoundedCornerShape(16.dp),
                ),
            ) {
                Column(Modifier.widthIn(max = 340.dp).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(t.text, color = fg, fontSize = 15.sp, lineHeight = 21.sp)
                }
            }
            if (!t.fromUser) {
                val urls = remember(t.text) { URL_REGEX.findAll(t.text).map { it.value }.distinct().toList() }
                val links = urls.filter { !IMG_EXT.containsMatchIn(it) }
                if (links.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    links.take(2).forEach { url ->
                        Surface(
                            color = ObsidianGold.Raised, shape = RoundedCornerShape(12.dp),
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
                                Text(prettyUrl(url), color = ObsidianGold.GoldLight, fontSize = 13.sp, maxLines = 1)
                            }
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
private fun ReactiveOrb(orbSize: androidx.compose.ui.unit.Dp, level: Float, phase: QuickAssistActivity.Phase, onTap: () -> Unit = {}) {
    val reduceMotion = ClawAnimation.reduceMotion()
    val infinite = rememberInfiniteTransition(label = "orb")
    val rot by infinite.animateFloat(
        0f, if (reduceMotion) 0f else 360f,
        infiniteRepeatable(tween(if (reduceMotion) 1 else 6000, easing = LinearEasing)), label = "rot")
    val breathe by infinite.animateFloat(
        0.9f, if (reduceMotion) 0.9f else 1.06f,
        infiniteRepeatable(tween(if (reduceMotion) 1 else 1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe")
    val sweep by infinite.animateFloat(
        0f, if (reduceMotion) 0f else 360f,
        infiniteRepeatable(tween(if (reduceMotion) 1 else 1100, easing = LinearEasing)), label = "sweep")

    val thinking = phase == QuickAssistActivity.Phase.THINKING
    val speaking = phase == QuickAssistActivity.Phase.SPEAKING
    val energy = when {
        phase == QuickAssistActivity.Phase.LISTENING -> level
        speaking -> (breathe - 0.9f) * 1.9f
        thinking -> 0.18f
        else -> 0.06f
    }
    val coreScale = if (phase == QuickAssistActivity.Phase.LISTENING) 0.92f + level * 0.5f else breathe

    Box(Modifier.size(orbSize).clickable { onTap() },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val baseR = size.minDimension / 2f
            val ringColors = listOf(ObsidianGold.GoldLight, ObsidianGold.Gold, ObsidianGold.GoldDeep)
            for (i in 0 until 3) {
                val wobble = (sin((rot / 57.3f) + i * 0.6f) * 0.04f).toFloat()
                val r = baseR * (0.55f + i * 0.16f) * (1f + energy * 0.35f + wobble)
                drawCircle(
                    color = ringColors[i].copy(alpha = 0.24f - i * 0.05f),
                    radius = r, center = c,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f + level * 6f),
                )
            }
            // Slow orbiting flecks make the core feel alive without turning it into
            // a noisy rainbow spinner. Their speed and glow follow the current state.
            for (i in 0..2) {
                val angle = ((rot + i * 120f) / 57.2958f).toDouble()
                val orbit = baseR * (0.70f + i * 0.08f)
                val point = Offset(
                    c.x + cos(angle).toFloat() * orbit,
                    c.y + sin(angle).toFloat() * orbit,
                )
                drawCircle(
                    color = ObsidianGold.GoldLight.copy(alpha = 0.45f + energy * 0.45f),
                    radius = baseR * (0.018f + energy * 0.016f),
                    center = point,
                )
            }
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(ObsidianGold.Gold.copy(alpha = 0.30f), Color.Transparent),
                    center = c, radius = baseR * (0.7f + level * 0.4f)),
                radius = baseR * (0.7f + level * 0.4f), center = c,
            )
            val coreR = baseR * 0.42f * coreScale
            rotate(rot, c) {
                drawCircle(
                    brush = Brush.linearGradient(
                        listOf(ObsidianGold.GoldDeep, ObsidianGold.Gold, ObsidianGold.GoldLight),
                        start = Offset(c.x - coreR, c.y - coreR),
                        end = Offset(c.x + coreR, c.y + coreR)),
                    radius = coreR, center = c,
                )
            }
            if (thinking) {
                drawArc(
                    color = ObsidianGold.GoldLight.copy(alpha = 0.92f),
                    startAngle = sweep, sweepAngle = 80f, useCenter = false,
                    topLeft = Offset(c.x - baseR * 0.62f, c.y - baseR * 0.62f),
                    size = androidx.compose.ui.geometry.Size(baseR * 1.24f, baseR * 1.24f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f),
                )
            }
            if (speaking) {
                for (i in 0..1) {
                    drawArc(
                        color = ObsidianGold.Gold.copy(alpha = 0.30f + i * 0.16f),
                        startAngle = -60f + sweep + i * 180f,
                        sweepAngle = 58f,
                        useCenter = false,
                        topLeft = Offset(c.x - baseR * (0.72f + i * 0.10f), c.y - baseR * (0.72f + i * 0.10f)),
                        size = androidx.compose.ui.geometry.Size(baseR * (1.44f + i * 0.20f), baseR * (1.44f + i * 0.20f)),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f + energy * 3f),
                    )
                }
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
                        color = ObsidianGold.Void.copy(alpha = 0.96f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                }
            }
        }
    }
}
