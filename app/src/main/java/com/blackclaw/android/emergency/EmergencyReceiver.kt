package com.blackclaw.android.emergency

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class EmergencyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            EmergencyService.ACTION_CANCEL, EmergencyService.ACTION_STOP ->
                context.startService(Intent(context, EmergencyService::class.java).setAction(intent.action))
        }
    }
}
