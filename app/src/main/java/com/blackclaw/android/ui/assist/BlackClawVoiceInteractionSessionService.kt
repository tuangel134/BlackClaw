package com.blackclaw.android.ui.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Creates a fresh native session for every system assistant invocation. */
class BlackClawVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        BlackClawVoiceInteractionSession(this)
}
