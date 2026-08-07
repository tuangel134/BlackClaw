package com.blackclaw.android.game

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.blackclaw.android.service.ClawAccessibilityService
import kotlin.math.hypot

/**
 * Accessibility-only floating macro editor. It deliberately captures touches in an explicit
 * recording layer instead of reading /dev/input, so it works without ADB or Shizuku.
 */
object GameAutoclickerOverlay {
    private val main = Handler(Looper.getMainLooper())

    // @Volatile because open()/close()/isOpen() are called from tool-execution
    // threads while every mutation happens on the main thread. Without it a tool
    // thread can read a stale null/non-null and either double-add the overlay or
    // skip a teardown.
    @Volatile private var windowManager: WindowManager? = null
    @Volatile private var panel: View? = null
    @Volatile private var capture: View? = null
    @Volatile private var gamePackage: String = ""

    /**
     * Main-thread confined. It used to be cleared from open() on the caller's
     * (tool) thread while the capture layer appended to it on the main thread —
     * an unsynchronized ArrayList mutated from two threads, which can corrupt its
     * internal size/array. All mutations now happen on the main thread.
     */
    private val draft = mutableListOf<GameGesture>()
    private var nameInput: EditText? = null

    fun isOpen(): Boolean = panel != null || capture != null

    fun open(packageName: String): Result<Unit> {
        val service = ClawAccessibilityService.getConnectedInstance(1_500L)
            ?: return Result.failure(IllegalStateException("Activa el servicio de Accesibilidad de BlackClaw."))
        main.post {
            if (gamePackage != packageName) draft.clear()
            gamePackage = packageName
            showPanel(service)
        }
        return Result.success(Unit)
    }

    fun close() = main.post {
        capture?.let { runCatching { windowManager?.removeView(it) } }
        panel?.let { runCatching { windowManager?.removeView(it) } }
        capture = null
        panel = null
        nameInput = null
        // CONTEXT LEAK FIX: windowManager is a WindowManagerImpl obtained from the
        // AccessibilityService, so it holds that service's Context. This object is
        // a singleton that outlives the service, so leaving the reference here kept
        // a destroyed service (and everything it references) alive until process
        // death, and a later removeView on a dead window token would throw.
        // showPanel() re-resolves it from the live service on every open.
        windowManager = null
    }

    private fun showPanel(service: AccessibilityService) {
        closeViewsOnly()
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val density = service.resources.displayMetrics.density
        val box = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12, density), dp(10, density), dp(12, density), dp(10, density))
            background = rounded(0xeE11151c.toInt(), dp(18, density).toFloat())
            elevation = dp(10, density).toFloat()
        }
        val title = TextView(service).apply {
            text = "BLACKCLAW · AUTOCLICKER"
            setTextColor(Color.WHITE); textSize = 12f
            setPadding(0, dp(4, density), 0, dp(8, density))
        }
        box.addView(title)
        nameInput = EditText(service).apply {
            hint = "Nombre: farmeo, ataque…"
            setHintTextColor(0xff9aa4b2.toInt()); setTextColor(Color.WHITE); textSize = 14f
            setSingleLine(true)
        }.also { box.addView(it, LinearLayout.LayoutParams(dp(230, density), dp(44, density))) }
        box.addView(row(service, density,
            button(service, "● Grabar") { beginCapture(service) },
            button(service, "Guardar") { saveDraft(service) },
            button(service, "×") { close() },
        ))
        box.addView(row(service, density,
            button(service, "▶") { playNamed(service) },
            button(service, "Ⅱ") { GameAutomationRuntime.pause() },
            button(service, "↺") { GameAutomationRuntime.restart() },
            button(service, "■") { GameAutomationRuntime.stop() },
        ))
        box.addView(TextView(service).apply {
            text = "${draft.size} acciones · arrastra desde el título"
            setTextColor(0xff9aa4b2.toInt()); textSize = 11f
        })
        panel = box
        val panelParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END, focusable = true).apply {
            x = dp(12, density); y = dp(140, density)
        }
        var dragX = 0f; var dragY = 0f; var originX = 0; var originY = 0
        title.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragX = event.rawX; dragY = event.rawY; originX = panelParams.x; originY = panelParams.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    // END gravity means increasing x moves the window left.
                    panelParams.x = (originX - (event.rawX - dragX)).toInt().coerceAtLeast(0)
                    panelParams.y = (originY + (event.rawY - dragY)).toInt().coerceAtLeast(0)
                    runCatching { wm.updateViewLayout(box, panelParams) }; true
                }
                else -> false
            }
        }
        wm.addView(box, panelParams)
    }

    private fun beginCapture(service: AccessibilityService) {
        panel?.let { runCatching { windowManager?.removeView(it) } }; panel = null
        val layer = CaptureView(service) { gestures ->
            draft.clear(); draft.addAll(gestures)
            capture?.let { runCatching { windowManager?.removeView(it) } }; capture = null
            showPanel(service)
        }
        capture = layer
        windowManager?.addView(layer, overlayParams(WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.START))
    }

    private fun saveDraft(context: Context) {
        val name = nameInput?.text?.toString()?.trim().orEmpty()
        when {
            name.isBlank() -> toast(context, "Escribe un nombre para la macro")
            draft.isEmpty() -> toast(context, "Pulsa Grabar y coloca al menos un toque")
            else -> {
                runCatching { GameMacroStore.save(name, gamePackage, draft.toList()) }.fold(
                    onSuccess = { toast(context, "Macro '$name' guardada (${draft.size} acciones)") },
                    onFailure = { toast(context, it.message ?: "No pude guardar la macro") },
                )
            }
        }
    }

    private fun playNamed(context: Context) {
        val name = nameInput?.text?.toString()?.trim().orEmpty()
        nameInput?.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(nameInput?.windowToken, 0)
        val macro = GameMacroStore.find(name)
        if (macro == null) toast(context, "No encontré la macro '$name'")
        else GameAutomationRuntime.playMacro(macro, repeat = 1, speed = 1.0, loop = false,
            maxDurationMs = 10 * 60_000L).onFailure { toast(context, it.message ?: "No pude iniciarla") }
    }

    private fun closeViewsOnly() {
        capture?.let { runCatching { windowManager?.removeView(it) } }
        panel?.let { runCatching { windowManager?.removeView(it) } }
        capture = null; panel = null
    }

    private fun overlayParams(width: Int, height: Int, gravityValue: Int, focusable: Boolean = false) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        (if (focusable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = gravityValue }

    private fun row(context: Context, density: Float, vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, dp(42, density), 1f).apply {
            setMargins(dp(2, density), dp(3, density), dp(2, density), dp(3, density))
        }) }
    }

    private fun button(context: Context, label: String, action: () -> Unit) = Button(context).apply {
        text = label; textSize = 11f; setTextColor(Color.WHITE); isAllCaps = false
        background = rounded(0xff293241.toInt(), 14f); setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }

    private fun toast(context: Context, message: String) =
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int, density: Float) = (value * density).toInt()

    private class CaptureView(
        context: Context,
        private val done: (List<GameGesture>) -> Unit,
    ) : View(context) {
        private val gestures = mutableListOf<GameGesture>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var downX = 0f; private var downY = 0f; private var downAt = 0L
        private var previousAt = 0L
        private val density = resources.displayMetrics.density

        init { setBackgroundColor(0x22000000) }

        override fun onDraw(canvas: Canvas) {
            paint.color = 0xdd11151c.toInt(); canvas.drawRoundRect(16f, 20f, width - 16f, 86f * density, 20f, 20f, paint)
            paint.color = Color.WHITE; paint.textSize = 16f * density
            canvas.drawText("Toca o desliza para añadir acciones", 30f, 52f * density, paint)
            paint.textSize = 13f * density
            canvas.drawText("LISTO (${gestures.size})", width - 130f * density, 52f * density, paint)
            gestures.forEachIndexed { index, g ->
                paint.color = 0xff6ee7ff.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
                val x = when (g) { is GameGesture.Tap -> g.x; is GameGesture.Swipe -> g.startX }
                val y = when (g) { is GameGesture.Tap -> g.y; is GameGesture.Swipe -> g.startY }
                val px = x * width / 1000f; val py = y * height / 1000f
                canvas.drawCircle(px, py, 16f * density, paint)
                paint.style = Paint.Style.FILL; paint.textSize = 11f * density
                canvas.drawText("${index + 1}", px - 5f * density, py + 4f * density, paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN && event.y < 95f * density && event.x > width - 170f * density) {
                done(gestures.toList()); return true
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; downAt = event.eventTime; return true }
                MotionEvent.ACTION_UP -> {
                    if (gestures.size >= 300) {
                        Toast.makeText(context, "Límite de 300 acciones", Toast.LENGTH_SHORT).show()
                        return true
                    }
                    val now = event.eventTime
                    val delay = if (previousAt == 0L) 650L else (downAt - previousAt).coerceIn(50L, 30_000L)
                    val nx1 = (downX * 1000 / width).toInt().coerceIn(0, 1000)
                    val ny1 = (downY * 1000 / height).toInt().coerceIn(0, 1000)
                    val nx2 = (event.x * 1000 / width).toInt().coerceIn(0, 1000)
                    val ny2 = (event.y * 1000 / height).toInt().coerceIn(0, 1000)
                    if (hypot(event.x - downX, event.y - downY) > 24f * density) {
                        gestures += GameGesture.Swipe(nx1, ny1, nx2, ny2,
                            (now - downAt).coerceIn(60L, 5_000L), delay)
                    } else gestures += GameGesture.Tap(nx1, ny1, delay)
                    previousAt = now; invalidate(); return true
                }
            }
            return true
        }
    }
}
