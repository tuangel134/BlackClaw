package com.blackclaw.android.automation

import android.content.Context
import android.location.Location
import android.os.PowerManager
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.TaskEvent
import com.blackclaw.android.agent.OfflineTaskQueue
import com.blackclaw.android.assistant.AssistantItemType
import com.blackclaw.android.assistant.AssistantScheduler
import com.blackclaw.android.assistant.AssistantStore
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog

/** Matches event rules and wakes the agent to execute their multi-step action text. */
object AutomationEngine {
    private const val TAG = "AutomationEngine"
    private const val LOCATION_STATE_PREFIX = "automation_location_inside_"

    fun onNotification(context: Context, packageName: String, title: String, text: String) {
        AutomationRuleStore.list().asSequence()
            .filter { it.enabled && it.trigger == AutomationRuleStore.Trigger.NOTIFICATION }
            .filter { notificationMatches(it, packageName, title, text) }
            .filter { cooldownReady(it) }
            .forEach { fire(context, it, "Notificación de $title") }
    }

    fun onLocation(context: Context, location: Location) {
        AutomationRuleStore.list().asSequence()
            .filter { it.enabled && it.trigger != AutomationRuleStore.Trigger.NOTIFICATION }
            .forEach { rule ->
                val distance = FloatArray(1)
                Location.distanceBetween(location.latitude, location.longitude, rule.latitude, rule.longitude, distance)
                val inside = distance[0] <= rule.radiusM
                val stateKey = LOCATION_STATE_PREFIX + rule.id
                val hadState = KVUtils.getString(stateKey, "")
                if (hadState.isBlank()) {
                    KVUtils.putString(stateKey, inside.toString()); KVUtils.sync()
                    return@forEach
                }
                val wasInside = hadState.toBoolean()
                KVUtils.putString(stateKey, inside.toString()); KVUtils.sync()
                val crossed = when (rule.trigger) {
                    AutomationRuleStore.Trigger.LOCATION_ENTER -> !wasInside && inside
                    AutomationRuleStore.Trigger.LOCATION_EXIT -> wasInside && !inside
                    else -> false
                }
                if (crossed && cooldownReady(rule)) fire(context, rule, "Ubicación detectada")
            }
    }

    fun fire(context: Context, rule: AutomationRuleStore.Rule, reason: String = "Ejecución manual") {
        AutomationRuleStore.markRun(rule.id)
        if (isWakeAction(rule.actionText)) {
            val item = AssistantStore.create(
                type = AssistantItemType.ALARM,
                title = rule.name.ifBlank { "Alerta de automatización" },
                body = reason,
                triggerAtMs = System.currentTimeMillis() + 1_500L,
                challenge = "none", source = "automation",
            )
            AssistantScheduler.arm(context, item)
            XLog.i(TAG, "Immediate wake alarm armed for ${rule.id}")
            return
        }
        executeTask(context, rule.actionText, "automation:${rule.id}")
    }

    fun executeTask(context: Context, text: String, source: String = "scheduled") {
        val vm = ClawApplication.appViewModelInstance
        if (vm.isTaskRunning()) {
            OfflineTaskQueue.enqueue(text, source = "scheduled", expiryMs = 24 * 60 * 60_000L)
            XLog.i(TAG, "Agent busy; queued $source")
            return
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlackClaw::Automation").apply {
            acquire(10 * 60_000L)
        }
        runCatching {
            vm.startTask(text, "auto_${System.currentTimeMillis()}", autoReturnToChat = false,
                surface = com.blackclaw.android.conversation.ConversationRepository.Surface.AUTOMATION) { event ->
                if (event is TaskEvent.Completed || event is TaskEvent.Failed || event is TaskEvent.Cancelled) {
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }
        }.onFailure {
            if (wakeLock.isHeld) wakeLock.release()
            XLog.e(TAG, "Could not start automation", it)
        }
    }

    private fun cooldownReady(rule: AutomationRuleStore.Rule): Boolean =
        System.currentTimeMillis() - rule.lastRunAtMs >= rule.cooldownMs

    private fun isWakeAction(text: String): Boolean {
        val s = text.lowercase()
        return listOf("despiértame", "despiertame", "wake me", "haz sonar una alarma", "activa una alarma")
            .any { it in s }
    }

    internal fun notificationMatches(rule: AutomationRuleStore.Rule, packageName: String,
                                     title: String, text: String): Boolean {
        if (rule.trigger != AutomationRuleStore.Trigger.NOTIFICATION || !rule.enabled) return false
        if (rule.packageName.isNotBlank() && rule.packageName != packageName) return false
        return rule.match.isBlank() || "$title\n$text".contains(rule.match, ignoreCase = true)
    }
}
