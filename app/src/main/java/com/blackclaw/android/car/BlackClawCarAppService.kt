package com.blackclaw.android.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point for BlackClaw on Android Auto (Car App Library).
 *
 * Exposes a voice-first, driving-safe surface on the car head unit (or phone
 * projection): a grid of one-tap actions (navigate, play music, call, read
 * notifications) plus a "Preguntar" search that uses the CAR's own speech-to-text
 * so the user never touches the phone while driving. Everything runs through the
 * same [com.blackclaw.android.AppViewModel.startTask] pipeline as the rest of the
 * app, and results are read aloud with [com.blackclaw.android.assistant.Speaker].
 *
 * NOTE on the host validator: this is a sideloaded, free app (not distributed via
 * Play), so we allow all hosts. If you ever ship through Play, replace this with a
 * pinned allow-list of the Android Auto + Desktop Head Unit signatures.
 */
class BlackClawCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = BlackClawSession()
}
