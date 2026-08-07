package com.blackclaw.android.game

import com.blackclaw.android.ClawApplication
import com.blackclaw.android.adb.PrivilegedShell
import com.blackclaw.android.service.ClawAccessibilityService
import com.blackclaw.android.service.ForegroundService
import com.blackclaw.android.utils.XLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

/** Executes saved game macros/autoclicker jobs on a bounded, stoppable worker. */
object GameAutomationRuntime {
    private const val TAG = "GameAutomation"
    private const val MAX_RUNTIME_MS = 30 * 60_000L

    data class Status(
        val running: Boolean,
        val kind: String = "",
        val label: String = "",
        val completedActions: Int = 0,
        val startedAtMs: Long = 0L,
        val message: String = "",
    )

    private val stopRequested = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val restartRequested = AtomicBoolean(false)
    @Volatile private var state = Status(false)
    @Volatile private var worker: Thread? = null

    fun status(): Status = state

    @Synchronized fun playMacro(
        macro: GameMacro,
        repeat: Int,
        speed: Double,
        loop: Boolean = false,
        maxDurationMs: Long = 10 * 60_000L,
    ): Result<Unit> {
        if (state.running) return Result.failure(IllegalStateException("Ya hay una automatización activa."))
        val safeRepeat = repeat.coerceIn(1, 100)
        val safeSpeed = speed.coerceIn(0.25, 3.0)
        val safeMaxDuration = maxDurationMs.coerceIn(10_000L, MAX_RUNTIME_MS)
        val estimate = (macro.estimatedDurationMs() * safeRepeat / safeSpeed).roundToLong()
        if (!loop && estimate > MAX_RUNTIME_MS) {
            return Result.failure(IllegalArgumentException("La macro duraría más de 30 minutos; reduce repeticiones."))
        }
        stopRequested.set(false)
        paused.set(false)
        restartRequested.set(false)
        state = Status(true, "macro", macro.name, startedAtMs = System.currentTimeMillis(), message = "Preparando")
        worker = Thread({
            runAutomation("Macro ${macro.name}", macro.packageName) {
                var count = 0
                var completedLoops = 0
                val deadline = System.currentTimeMillis() + safeMaxDuration
                macroLoop@ while (!stopRequested.get() && (loop || completedLoops < safeRepeat)) {
                    for (gesture in macro.gestures) {
                        if (restartRequested.getAndSet(false)) {
                            count = 0
                            completedLoops = 0
                            state = state.copy(completedActions = 0, message = "Reiniciando")
                            continue@macroLoop
                        }
                        if (System.currentTimeMillis() >= deadline) {
                            state = state.copy(message = "Completada: alcanzó el tiempo configurado")
                            return@runAutomation
                        }
                        if (!waitOrStop((gesture.delayBeforeMs / safeSpeed).roundToLong())) return@runAutomation
                        if (!foregroundStillMatches(macro.packageName)) {
                            state = state.copy(message = "Detenida: el juego dejó de estar al frente")
                            return@runAutomation
                        }
                        val ok = when (gesture) {
                            is GameGesture.Tap -> dispatchTap(gesture.x, gesture.y)
                            is GameGesture.Swipe -> dispatchSwipe(
                                gesture.startX, gesture.startY, gesture.endX, gesture.endY,
                                (gesture.durationMs / safeSpeed).roundToLong().coerceIn(30, 5_000),
                            )
                        }
                        if (!ok) {
                            state = state.copy(message = "Detenida: falló el gesto ${count + 1}")
                            return@runAutomation
                        }
                        count++
                        state = state.copy(completedActions = count, message = "Ejecutando")
                        if (count % 10 == 0) notify("${macro.name}: $count acciones")
                    }
                    completedLoops++
                    if (loop) state = state.copy(message = "Bucle ${completedLoops + 1}")
                }
                state = state.copy(message = "Completada")
            }
        }, "game-macro-${macro.id.take(8)}").also { it.start() }
        return Result.success(Unit)
    }

    @Synchronized fun startAutoclicker(
        packageName: String,
        normalizedX: Int,
        normalizedY: Int,
        intervalMs: Long,
        durationMs: Long,
    ): Result<Unit> {
        if (state.running) return Result.failure(IllegalStateException("Ya hay una automatización activa."))
        val interval = intervalMs.coerceIn(50L, 5_000L)
        val duration = durationMs.coerceIn(1_000L, MAX_RUNTIME_MS)
        val clicks = (duration / interval).coerceIn(1, 20_000).toInt()
        stopRequested.set(false)
        paused.set(false)
        restartRequested.set(false)
        val label = "Autoclicker [$normalizedX,$normalizedY]"
        state = Status(true, "autoclicker", label, startedAtMs = System.currentTimeMillis(), message = "Preparando")
        worker = Thread({
            runAutomation(label, packageName) {
                var index = 0
                while (index < clicks) {
                    if (!awaitReady()) return@runAutomation
                    if (restartRequested.getAndSet(false)) {
                        index = 0
                        state = state.copy(completedActions = 0, message = "Reiniciando")
                    }
                    if (!foregroundStillMatches(packageName)) {
                        state = state.copy(message = "Detenido: el juego dejó de estar al frente")
                        return@runAutomation
                    }
                    if (!dispatchTap(normalizedX, normalizedY)) {
                        state = state.copy(message = "Detenido: falló el tap")
                        return@runAutomation
                    }
                    index++
                    state = state.copy(completedActions = index, message = "Ejecutando")
                    if (!waitOrStop(interval)) return@runAutomation
                }
                state = state.copy(message = "Completado")
            }
        }, "game-autoclicker").also { it.start() }
        return Result.success(Unit)
    }

    @Synchronized fun stop(): Boolean {
        if (!state.running) return false
        stopRequested.set(true)
        worker?.interrupt()
        state = state.copy(message = "Deteniendo")
        return true
    }

    @Synchronized fun pause(): Boolean {
        if (!state.running || paused.get()) return false
        paused.set(true)
        state = state.copy(message = "Pausada")
        notify("${state.label} · pausada")
        return true
    }

    @Synchronized fun resume(): Boolean {
        if (!state.running || !paused.get()) return false
        paused.set(false)
        state = state.copy(message = "Reanudando")
        return true
    }

    @Synchronized fun restart(): Boolean {
        if (!state.running) return false
        restartRequested.set(true)
        paused.set(false)
        state = state.copy(message = "Reiniciando")
        notify("${state.label} · reiniciando")
        return true
    }

    private inline fun runAutomation(label: String, packageName: String, block: () -> Unit) {
        val ctx = ClawApplication.instance
        notify("$label · toca Detener desde BlackClaw")
        XLog.i(TAG, "Started $label on $packageName")
        try {
            Thread.sleep(650L) // let the assistant surface finish closing
            block()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Throwable) {
            XLog.e(TAG, "$label failed", e)
            state = state.copy(message = "Error: ${e.message}")
        } finally {
            val finalMessage = if (stopRequested.get()) "Detenida por el usuario" else state.message
            val finalCount = state.completedActions
            state = Status(false, state.kind, state.label, finalCount, state.startedAtMs, finalMessage)
            worker = null
            ForegroundService.resetToIdle(ctx)
            XLog.i(TAG, "Finished $label: $finalMessage ($finalCount actions)")
        }
    }

    private fun notify(text: String) {
        ForegroundService.updateTaskStatus(ClawApplication.instance, text)
    }

    private fun waitOrStop(ms: Long): Boolean {
        var remaining = ms.coerceAtLeast(0L)
        while (!stopRequested.get() && remaining > 0L) {
            if (!awaitReady()) return false
            val slice = minOf(40L, remaining)
            val before = System.currentTimeMillis()
            try { Thread.sleep(slice) } catch (_: InterruptedException) {
                if (stopRequested.get()) return false
            }
            if (!paused.get()) remaining -= (System.currentTimeMillis() - before).coerceAtLeast(1L)
        }
        return !stopRequested.get()
    }

    private fun awaitReady(): Boolean {
        while (paused.get() && !stopRequested.get()) {
            if (System.currentTimeMillis() - state.startedAtMs > MAX_RUNTIME_MS) {
                state = state.copy(message = "Detenida: límite total de 30 minutos")
                stopRequested.set(true)
                return false
            }
            try { Thread.sleep(50L) } catch (_: InterruptedException) {
                if (stopRequested.get()) return false
            }
        }
        return !stopRequested.get()
    }

    private fun foregroundStillMatches(expected: String): Boolean {
        val service = ClawAccessibilityService.getConnectedInstance(500L) ?: return false
        val current = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (current == expected) return true
        // Our accessibility overlay may become the active window while the game remains behind it.
        if (current == ClawApplication.instance.packageName || current == "com.android.systemui") {
            return ClawAccessibilityService.getLastExternalPackage() == expected
        }
        return false
    }

    private fun dispatchTap(nx: Int, ny: Int): Boolean {
        val (width, height) = screenSize()
        val x = GameControlPolicy.toPixel(nx, width)
        val y = GameControlPolicy.toPixel(ny, height)
        if (PrivilegedShell.isAvailable() && PrivilegedShell.execFast("input tap $x $y")) return true
        return ClawAccessibilityService.getConnectedInstance(1_000L)?.performTap(x, y) == true
    }

    private fun dispatchSwipe(nx1: Int, ny1: Int, nx2: Int, ny2: Int, durationMs: Long): Boolean {
        val (width, height) = screenSize()
        val x1 = GameControlPolicy.toPixel(nx1, width); val y1 = GameControlPolicy.toPixel(ny1, height)
        val x2 = GameControlPolicy.toPixel(nx2, width); val y2 = GameControlPolicy.toPixel(ny2, height)
        if (PrivilegedShell.isAvailable() &&
            PrivilegedShell.execFast("input swipe $x1 $y1 $x2 $y2 $durationMs")) return true
        return ClawAccessibilityService.getConnectedInstance(1_000L)
            ?.performSwipe(x1, y1, x2, y2, durationMs) == true
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val metrics = android.util.DisplayMetrics()
        val wm = ClawApplication.instance.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }
}
