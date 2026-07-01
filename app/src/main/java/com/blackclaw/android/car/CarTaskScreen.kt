package com.blackclaw.android.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.blackclaw.android.R
import com.blackclaw.android.TaskEvent
import com.blackclaw.android.appViewModel
import com.blackclaw.android.assistant.Speaker
import java.util.UUID

/**
 * Runs a single BlackClaw task from the car and shows its result, reading the
 * answer aloud (driving-safe). Reuses the exact same pipeline as the phone
 * (fast-path deep links, tools, agent loop) via [appViewModel].
 */
class CarTaskScreen(
    carContext: CarContext,
    private val command: String,
) : Screen(carContext) {

    private enum class State { RUNNING, DONE, ERROR }

    @Volatile private var state: State = State.RUNNING
    @Volatile private var message: String = "Un momento…"
    private var started = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = start()
            override fun onDestroy(owner: LifecycleOwner) {
                if (state == State.RUNNING) runCatching { appViewModel.stopTask() }
            }
        })
    }

    private fun start() {
        if (started) return
        started = true
        val taskId = "car-" + UUID.randomUUID().toString().take(8)
        runCatching {
            appViewModel.startTask(command, taskId) { event ->
                when (event) {
                    is TaskEvent.Completed -> finishWith(State.DONE, event.answer.ifBlank { "Listo." })
                    is TaskEvent.Failed -> finishWith(State.ERROR, "No pude completarlo: ${event.error}")
                    is TaskEvent.Blocked -> finishWith(State.ERROR, "Se bloqueó por un diálogo del sistema.")
                    is TaskEvent.Cancelled -> finishWith(State.ERROR, "Cancelado.")
                    is TaskEvent.ToolAction -> update("${event.toolName}…")
                    else -> { /* progress noise — ignore on the car */ }
                }
            }
        }.onFailure { finishWith(State.ERROR, "No pude iniciar la tarea.") }
    }

    private fun update(text: String) {
        message = text
        postInvalidate()
    }

    private fun finishWith(newState: State, text: String) {
        state = newState
        message = text
        if (newState == State.DONE) runCatching { Speaker.speak(text) }
        postInvalidate()
    }

    private fun postInvalidate() {
        ContextCompat.getMainExecutor(carContext).execute { runCatching { invalidate() } }
    }

    override fun onGetTemplate(): Template {
        if (state == State.RUNNING) {
            return MessageTemplate.Builder(message)
                .setTitle("BlackClaw")
                .setHeaderAction(Action.BACK)
                .setLoading(true)
                .build()
        }

        val appIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_launcher_monochrome)
        ).build()

        val repeat = Action.Builder()
            .setTitle("Repetir")
            .setOnClickListener { runCatching { Speaker.speak(message) } }
            .build()
        val home = Action.Builder()
            .setTitle("Inicio")
            .setBackgroundColor(CarColor.BLUE)
            .setOnClickListener { screenManager.popToRoot() }
            .build()

        return MessageTemplate.Builder(message)
            .setTitle("BlackClaw")
            .setIcon(appIcon)
            .setHeaderAction(Action.BACK)
            .addAction(repeat)
            .addAction(home)
            .build()
    }
}
