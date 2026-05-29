package com.blackclaw.android.ui.splash

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import com.blackclaw.android.R
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.ui.chat.ComposeChatActivity

/**
 * Animated splash:
 *  - icon scales in + rotates slightly + glow ring pulses
 *  - app name + slogan fade in with staggered delay
 *  - hand-off to chat after a short choreography (~700 ms)
 *
 * If the user launched us via a debug intent, the task extra is forwarded.
 */
class SplashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* disabled */ }
        })

        val logo = findViewById<View>(R.id.ivLogo)
        val glow = findViewById<View>(R.id.glowRing)
        val name = findViewById<TextView>(R.id.tvAppName)
        val slogan = findViewById<TextView>(R.id.tvSlogan)

        // Initial state — invisible / shrunk
        logo?.apply {
            scaleX = 0.4f
            scaleY = 0.4f
            alpha = 0f
        }
        glow?.apply {
            scaleX = 0.6f
            scaleY = 0.6f
            alpha = 0f
        }
        name?.apply {
            alpha = 0f
            translationY = 20f
        }
        slogan?.apply {
            alpha = 0f
            translationY = 20f
        }

        // Choreography
        val logoIn = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logo, "scaleX", 0.4f, 1.08f, 1f),
                ObjectAnimator.ofFloat(logo, "scaleY", 0.4f, 1.08f, 1f),
                ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f),
                ObjectAnimator.ofFloat(logo, "rotation", -10f, 0f),
            )
            duration = 520
            interpolator = DecelerateInterpolator()
        }
        val glowPulse = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(glow, "alpha", 0f, 1f, 0.6f),
                ObjectAnimator.ofFloat(glow, "scaleX", 0.6f, 1.15f, 1f),
                ObjectAnimator.ofFloat(glow, "scaleY", 0.6f, 1.15f, 1f),
            )
            duration = 700
            interpolator = AccelerateDecelerateInterpolator()
        }
        val nameIn = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(name, "alpha", 0f, 1f),
                ObjectAnimator.ofFloat(name, "translationY", 20f, 0f),
            )
            duration = 380
            startDelay = 240
            interpolator = DecelerateInterpolator()
        }
        val sloganIn = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(slogan, "alpha", 0f, 0.9f),
                ObjectAnimator.ofFloat(slogan, "translationY", 20f, 0f),
            )
            duration = 380
            startDelay = 380
            interpolator = DecelerateInterpolator()
        }

        AnimatorSet().apply {
            playTogether(logoIn, glowPulse, nameIn, sloganIn)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    handOffToChat()
                }
            })
            start()
        }
    }

    private fun handOffToChat() {
        val next = Intent(this, ComposeChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            intent?.getStringExtra("task")?.let { putExtra("task", it) }
            intent?.getStringExtra("chat")?.let { putExtra("chat", it) }
        }
        startActivity(next)
        // Subtle cross-fade
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
