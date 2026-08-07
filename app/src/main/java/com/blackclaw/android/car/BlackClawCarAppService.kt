package com.blackclaw.android.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.blackclaw.android.BuildConfig

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
 * ## Why the host validator matters
 *
 * This service is `exported="true"` in the manifest — it has to be, that is how the
 * Android Auto host binds to it. The validator is the ONLY thing that decides which
 * app is allowed to complete that bind. `ALLOW_ALL_HOSTS_VALIDATOR` is documented by
 * Google as development-only: it skips signature verification entirely, so **any**
 * installed app can bind to `androidx.car.app.CarAppService` and drive the whole
 * assistant — start tasks, read results back, exfiltrate everything the session
 * surfaces — with no user-visible prompt and no permission of its own. That is a
 * full remote-control channel handed out to any app holding zero permissions.
 *
 * So: allow-all only in debug builds (needed for the Desktop Head Unit, whose
 * signature is not in any published allow-list), and in release pin to the
 * signatures Google ships in the library — `hosts_allowlist_sample` covers
 * `com.google.android.projection.gearhead` (Android Auto) and
 * `com.google.android.apps.automotive.templates.host` (Automotive OS).
 */
class BlackClawCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) {
            // Debug only: the Desktop Head Unit / emulator hosts are not in the
            // published allow-list, so pinning would make local testing impossible.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = BlackClawSession()
}
