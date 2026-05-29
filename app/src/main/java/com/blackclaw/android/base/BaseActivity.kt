package com.blackclaw.android.base

import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.blankj.utilcode.util.AdaptScreenUtils
import com.blankj.utilcode.util.BarUtils
import com.blackclaw.android.R

/**
 *
 * Screen adaptation uses pt; using dp on some devices causes toast line breaks at incorrect positions
 */
open class BaseActivity : AppCompatActivity() {

    override fun getResources(): Resources {
        val resources = super.getResources()
        return AdaptScreenUtils.adaptWidth(resources, getDesignWidth())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyStatusBarMode()

        // Handle status bar height uniformly - applied after layout is loaded
        applyStatusBarPadding()
    }

    /**
     * Auto-callback on theme switch to update status bar text color
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStatusBarMode()
    }

    /**
     * Set status bar text color based on current theme mode
     * Light theme → dark text (light mode)
     * Dark theme → light text (dark mode)
     */
    private fun applyStatusBarMode() {
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        BarUtils.setStatusBarLightMode(this, !isNightMode)
    }

    /**
     * Add top padding equal to the status bar height to the root view
     * Subclasses can disable this by overriding isApplyStatusBarPadding()
     */
    private fun applyStatusBarPadding() {
        window.decorView.post {
            val rootView = findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
            rootView?.let { applyPaddingToRootView(it) }
        }
    }

    /**
     * Apply status bar height padding to the root view
     * If the layout already handles insets, override this method to customize behavior
     */
    protected open fun applyPaddingToRootView(rootView: View) {
        if (!isApplyStatusBarPadding()) return

        val statusBarHeight = BarUtils.getStatusBarHeight()
        val existingPaddingTop = rootView.paddingTop

        // Only add padding if there is none or it is smaller than the status bar height
        if (existingPaddingTop < statusBarHeight) {
            rootView.updatePadding(top = statusBarHeight)
        }
    }

    /**
     * Subclasses can override this method to disable automatic status bar height padding
     */
    protected open fun isApplyStatusBarPadding(): Boolean = true

    /**
     * Screen adaptation - design spec size
     */
    open fun getDesignWidth(): Int {
        return 402
    }
}