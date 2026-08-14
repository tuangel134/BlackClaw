package com.blackclaw.android.agent.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Resolves the runtime model for the user's Automatic preference.
 *
 * This is deliberately separate from the persisted provider value: AUTO is a
 * preference, while the effective model can change as connectivity changes.
 * Keeping the policy pure makes the fallback order easy to test without an
 * Android device.
 */
object AutomaticModelResolver {

    fun effectiveMode(
        selectedMode: ActiveModelMode,
        internetValidated: Boolean,
        hasLocalModel: Boolean,
        hasCloudModel: Boolean,
    ): ActiveModelMode {
        if (selectedMode != ActiveModelMode.AUTOMATIC) return selectedMode

        // Prefer cloud whenever it is reachable and configured. If the network
        // disappears, or the cloud was never configured, keep the conversation
        // usable with the downloaded on-device model.
        if (internetValidated && hasCloudModel) return ActiveModelMode.CLOUD
        if (hasLocalModel) return ActiveModelMode.LOCAL
        // There is no local fallback. Keep CLOUD as the truthful state so the
        // UI can explain that the only configured model needs internet.
        return ActiveModelMode.CLOUD
    }

    fun isInternetValidated(context: Context): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
