package com.blackclaw.android.emergency

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EmergencyEventLog {
    private val lock = Any()

    fun append(context: Context, event: String) = synchronized(lock) {
        val dir = File(context.filesDir, "emergency").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
        File(dir, "events.log").appendText("$timestamp\t${event.replace('\n', ' ')}\n")
    }

}
