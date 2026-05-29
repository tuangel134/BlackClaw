package com.blackclaw.android.ui.guide

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.blackclaw.android.R
import com.blackclaw.android.AppCapabilityCoordinator
import com.blackclaw.android.AppRequirement
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.utils.KVUtils

class GuideActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)

        bindSection(
            findViewById(R.id.guideAccessibility),
            R.drawable.ic_accessibility,
            R.string.guide_title_accessibility,
            R.string.guide_desc_accessibility
        ) {
            AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.ACCESSIBILITY)
            Toast.makeText(this, R.string.home_enable_accessibility, Toast.LENGTH_LONG).show()
        }
        bindSection(
            findViewById(R.id.guideNotification),
            R.drawable.ic_notification,
            R.string.guide_title_notification,
            R.string.guide_desc_notification
        ) {
            if (!AppCapabilityCoordinator.isNotificationPermissionGranted(this)) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
                }
            } else {
                Toast.makeText(this, R.string.home_notification_enabled, Toast.LENGTH_SHORT).show()
            }
        }
        bindSection(
            findViewById(R.id.guideOverlay),
            R.drawable.ic_window,
            R.string.guide_title_overlay,
            R.string.guide_desc_overlay
        ) {
            if (!AppCapabilityCoordinator.snapshot(this).overlayGranted) {
                AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.OVERLAY)
            }
        }
        bindSection(
            findViewById(R.id.guideBattery),
            R.drawable.ic_battery,
            R.string.guide_title_battery,
            R.string.guide_desc_battery
        ) {
            AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.BATTERY_OPTIMIZATION)
        }
        bindSection(
            findViewById(R.id.guideStorage),
            R.drawable.ic_storage,
            R.string.guide_title_storage,
            R.string.guide_desc_storage
        ) {
            AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.STORAGE)
        }

        findViewById<View>(R.id.btnStart).setOnClickListener { finishGuide() }
        findViewById<View>(R.id.tvSkip).setOnClickListener { finishGuide() }
    }

    private fun bindSection(view: View, iconRes: Int, titleRes: Int, descRes: Int, onClick: () -> Unit) {
        view.findViewById<ImageView>(R.id.ivIcon).setImageResource(iconRes)
        view.findViewById<TextView>(R.id.tvTitle).setText(titleRes)
        view.findViewById<TextView>(R.id.tvDescription).setText(descRes)
        view.setOnClickListener { onClick() }
    }

    private fun finishGuide() {
        KVUtils.setGuideShown(true)
        finish()
    }
}
