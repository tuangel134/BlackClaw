package com.blackclaw.android.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * A single Android Auto connection. Hands back the home grid screen; deeper
 * screens (ask / task result) are pushed onto the ScreenManager stack.
 */
class BlackClawSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = BlackClawHomeScreen(carContext)
}
