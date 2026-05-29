package com.blackclaw.android

import com.blackclaw.android.agent.DefaultAgentService
import com.blackclaw.android.agent.llm.LocalBackendHealth
import com.blackclaw.android.base.BaseApp
import com.blackclaw.android.channel.ChannelManager
import com.blackclaw.android.tool.ToolRegistry
import com.blackclaw.android.utils.AppLogStore
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import com.blankj.utilcode.util.NetworkUtils

/**
 * Application entry point
 */

val appViewModel: AppViewModel by lazy { ClawApplication.appViewModelInstance }
class ClawApplication : BaseApp() {

    companion object {
        private const val TAG = "ClawApplication"
        lateinit var instance: ClawApplication
            private set
        lateinit var appViewModelInstance: AppViewModel
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLogStore.init(this)
        XLog.setDEBUG(BuildConfig.DEBUG)
        // Install standalone Conscrypt as a security provider so libadb-android's
        // TLS 1.3 pairing uses org.conscrypt.* (stable exportKeyingMaterial
        // signature) instead of the OEM platform conscrypt, which throws
        // NoSuchMethodException on some ROMs (e.g. MagicOS 10 / Android 15).
        runCatching {
            java.security.Security.insertProviderAt(
                org.conscrypt.Conscrypt.newProvider(), 1)
            XLog.i(TAG, "Conscrypt provider installed")
        }.onFailure { XLog.w(TAG, "Conscrypt install failed: ${it.message}") }
        registerNetworkCallback()
        com.blackclaw.android.shizuku.ShizukuManager.init()
        appViewModelInstance = getAppViewModelProvider()[AppViewModel::class.java]
        KVUtils.init(this)
        com.blackclaw.android.adb.AdbController.init(this)
        LocalBackendHealth.recoverPendingGpuCrashIfNeeded()
        ToolRegistry.getInstance().registerAllTools(ToolRegistry.DeviceType.MOBILE)
        com.blackclaw.android.agent.skill.SkillRegistry.loadBuiltInSkills()
        com.blackclaw.android.agent.PlaybookManager.loadAll(this)
        XLog.e(TAG, "ClawApplication initialized, tools registered: ${ToolRegistry.getInstance().getAllTools().size}")

        // Write network logs to file (set to true when debugging)
        DefaultAgentService.FILE_LOGGING_ENABLED = BuildConfig.DEBUG
        DefaultAgentService.FILE_LOGGING_CACHE_DIR = cacheDir

        // Lightweight initialization (main thread)
        appViewModelInstance.initCommon()
        val initThread = Thread({
            try {
                android.util.Log.e("BLACKCLAW_INIT", "app-async-init thread STARTED")
                val hasConfig = KVUtils.hasLlmConfig()
                android.util.Log.e("BLACKCLAW_INIT", "app-async-init: hasLlmConfig=$hasConfig, canDrawOverlays=${android.provider.Settings.canDrawOverlays(instance)}")
                if (hasConfig) {
                    appViewModelInstance.initAgent()
                    appViewModelInstance.afterInit()
                }
                // Best-effort silent self-ADB reconnect if the user paired before.
                // Wireless debugging must be re-enabled by the user after a reboot;
                // if it's off this just fails quietly and we use accessibility.
                runCatching {
                    if (com.blackclaw.android.adb.AdbController.isPaired() &&
                        !com.blackclaw.android.adb.AdbController.isConnected()) {
                        kotlinx.coroutines.runBlocking {
                            com.blackclaw.android.adb.AdbController.connect(instance)
                        }
                    }
                }
                android.util.Log.e("BLACKCLAW_INIT", "app-async-init thread DONE")
            } catch (e: Exception) {
                android.util.Log.e("BLACKCLAW_INIT", "app-async-init CRASHED: ${e.message}", e)
            }
        }, "app-async-init")
        initThread.isDaemon = true
        initThread.start()

        // Watchdog: if init takes more than 30 s, log a warning.
        // This catches hung model loads or deadlocks without killing the process.
        Thread({
            initThread.join(30_000L)
            if (initThread.isAlive) {
                android.util.Log.e("BLACKCLAW_INIT", "app-async-init TIMEOUT after 30s — init may be hung")
            }
        }, "app-async-init-watchdog").also { it.isDaemon = true }.start()
    }

    private var networkListener: NetworkUtils.OnNetworkStatusChangedListener? = null

    /**
     * Listen for network recovery and automatically re-initialize channels.
     * Fixes channel initialization failures when booting with no network, and reconnects channels after network outages.
     */
    private fun registerNetworkCallback() {
        networkListener = object : NetworkUtils.OnNetworkStatusChangedListener {
            override fun onConnected(networkType: NetworkUtils.NetworkType?) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (KVUtils.hasLlmConfig()) {
                        XLog.i(TAG, "Network recovered (${networkType?.name}), checking and reconnecting dropped channels")
                        ChannelManager.reconnectIfNeeded()
                    }
                }, 2000)
            }

            override fun onDisconnected() {
                XLog.w(TAG, "Network disconnected")
            }
        }
        NetworkUtils.registerNetworkStatusChangedListener(networkListener)
    }

}
