package com.blackclaw.android.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import com.blackclaw.android.base.BaseActivity
import java.io.File

/**
 * Internal plaintext player. Its cache copy is deleted when playback closes.
 *
 * ## Do not give the [VideoView] a background
 *
 * This screen used to call `setBackgroundColor(Color.BLACK)` on the player, and the
 * result was that every recording played as a black rectangle with working audio and no
 * error of any kind.
 *
 * [VideoView] is a `SurfaceView`. Its video is composited in a separate layer *behind*
 * the window, and the view's job is to leave a transparent hole for it to show through.
 * Setting any opaque background makes the view paint that colour into the window layer
 * instead — directly over the hole. The video is still decoding and playing perfectly,
 * just hidden. Nothing reports an error because nothing failed.
 *
 * The dark backdrop the background was there for comes from the parent container, which
 * is what should provide it.
 *
 * ## Audio segments reach this screen too
 *
 * Emergency recording produces audio segments alongside the camera ones, and the
 * evidence list offers the same action for both. An audio file has no picture, so
 * without saying so the screen looks like the same failure as the bug above. Audio-only
 * playback gets an explicit label rather than an unexplained empty stage.
 */
class EmergencyEvidencePlayerActivity : BaseActivity() {
    private var preview: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keeps the evidence out of screenshots, screen recorders and mirrored displays.
        // Consequence worth knowing: the video area is black in any capture of this
        // screen, so a screenshot cannot be used to check whether playback works.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val candidate = intent.getStringExtra(EXTRA_PATH)?.let(::File)
        val previewRoot = File(cacheDir, "emergency_evidence").canonicalFile
        preview = candidate?.takeIf {
            runCatching {
                val canonical = it.canonicalFile
                canonical.isFile && canonical.path.startsWith(previewRoot.path + File.separator)
            }.getOrDefault(false)
        }
        val file = preview
        if (file == null) {
            finish()
            return
        }

        // The vault names decrypted copies by media type, so the extension is the same
        // signal the list used to pick this screen in the first place.
        val audioOnly = file.extension.equals("m4a", ignoreCase = true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 36, 24, 24)
            setBackgroundColor(BACKDROP)
        }
        val title = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE) ?: "Evidencia"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(12, 12, 12, 20)
        }

        // The stage owns the dark backdrop so the player itself can stay transparent.
        val stage = FrameLayout(this).apply {
            setBackgroundColor(STAGE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        val player = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
        }
        stage.addView(player)
        if (audioOnly) {
            // Only ever drawn over the surface when there is no picture to hide.
            stage.addView(
                TextView(this).apply {
                    text = "Solo audio\nEste segmento no tiene imagen."
                    gravity = Gravity.CENTER
                    textSize = 15f
                    setTextColor(Color.rgb(168, 157, 185))
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER,
                    )
                }
            )
        }

        val close = Button(this).apply {
            text = "CERRAR"
            setOnClickListener { finish() }
        }
        root.addView(
            title,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(stage)
        root.addView(
            close,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        setContentView(root)

        val controls = MediaController(this).apply { setAnchorView(stage) }
        player.setMediaController(controls)
        player.setVideoPath(file.absolutePath)
        player.setOnPreparedListener {
            player.start()
            controls.show(3_000)
        }
        player.setOnErrorListener { _, what, extra ->
            // Report the codes: a truncated segment and an unreadable one fail very
            // differently, and without them the toast says nothing actionable.
            ToastHelper.show(this, "No se pudo reproducir este segmento ($what/$extra)")
            true
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) preview?.delete()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "evidence_path"
        const val EXTRA_TITLE = "evidence_title"

        private val BACKDROP = Color.rgb(7, 5, 12)
        private val STAGE = Color.rgb(3, 2, 6)
    }
}

private object ToastHelper {
    fun show(context: android.content.Context, message: String) =
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
}
