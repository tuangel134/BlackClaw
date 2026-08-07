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
        /** Kept as a separate logcat tag so `adb logcat -s BLACKCLAW_INIT` still works. */
        private const val INIT_TAG = "BLACKCLAW_INIT"
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
        runCatching { com.blackclaw.android.proactive.BriefingScheduler.syncAll(this) }
            .onFailure {
                XLog.w(TAG, "Briefing scheduler sync failed, proactive daily briefings " +
                    "will not fire until next app start: ${it.message}")
            }
        runCatching { com.blackclaw.android.agent.OpenCodeZenModels.refreshIfStale() }
            .onFailure {
                XLog.w(TAG, "OpenCode Zen model refresh failed, model picker keeps the " +
                    "last cached list: ${it.message}")
            }
        // Background self-check: which deep-link catalog schemes actually resolve
        // on THIS device (helps prune/verify). Log only, non-blocking.
        runCatching {
            Thread({
                runCatching {
                    val cat = com.blackclaw.android.perception.AppActionScanner.verifiedCatalog()
                    XLog.i(TAG, "Deep-link catalog: ${cat.count { it.second }} scheme-verified / " +
                        "${cat.size} installed of ${com.blackclaw.android.tool.impl.AppDeepLinks.CATALOG.size}")
                }.onFailure {
                    XLog.w(TAG, "Deep-link catalog scan failed, app-open shortcuts fall back " +
                        "to unverified schemes: ${it.message}")
                }
            }, "deeplink-scan").start()
        }.onFailure { XLog.w(TAG, "Could not start deeplink-scan thread: ${it.message}") }
        // Unpack the bundled offline voice model in the background so the
        // hands-free wake word works out of the box (no download needed).
        runCatching { com.blackclaw.android.assistant.VoskModelManager.prepareIfNeeded() }
            .onFailure {
                XLog.w(TAG, "Vosk model unpack failed, hands-free wake word will not " +
                    "work (offline speech recognition unavailable): ${it.message}")
            }
        runCatching {
            if (com.blackclaw.android.proactive.ProactiveConfig.enabled ||
                com.blackclaw.android.assistant.GeofenceChecker.hasActiveGeofences()) {
                com.blackclaw.android.service.KeepAliveJobService.schedule(this)
            }
        }.onFailure {
            XLog.w(TAG, "KeepAlive job scheduling failed, proactive checks and geofence " +
                "reminders may stop when the app is killed: ${it.message}")
        }
        LocalBackendHealth.recoverPendingGpuCrashIfNeeded()
        ToolRegistry.getInstance().registerAllTools(ToolRegistry.DeviceType.MOBILE)
        // Trim stale assistant-hub items (old alerts / long-done reminders) so the
        // store doesn't grow unbounded on long-lived installs.
        runCatching { com.blackclaw.android.assistant.AssistantStore.pruneOldItems() }
            .onFailure {
                XLog.w(TAG, "Assistant store prune failed, stale reminders/alerts will " +
                    "keep accumulating: ${it.message}")
            }
        com.blackclaw.android.agent.skill.SkillRegistry.loadBuiltInSkills()
        com.blackclaw.android.agent.PlaybookManager.loadAll(this)
        XLog.i(TAG, "ClawApplication initialized, tools registered: ${ToolRegistry.getInstance().getAllTools().size}")

        // Write network logs to file (set to true when debugging)
        DefaultAgentService.FILE_LOGGING_ENABLED = BuildConfig.DEBUG
        DefaultAgentService.FILE_LOGGING_CACHE_DIR = cacheDir

        // Lightweight initialization (main thread)
        appViewModelInstance.initCommon()
        val initThread = Thread({
            try {
                XLog.i(INIT_TAG, "app-async-init thread STARTED")
                val hasConfig = KVUtils.hasLlmConfig()
                XLog.i(INIT_TAG, "app-async-init: hasLlmConfig=$hasConfig, canDrawOverlays=${android.provider.Settings.canDrawOverlays(instance)}")
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
                }.onFailure {
                    XLog.w(INIT_TAG, "Silent self-ADB reconnect failed, privileged shell " +
                        "actions fall back to accessibility gestures: ${it.message}")
                }
                XLog.i(INIT_TAG, "app-async-init thread DONE")
            } catch (e: Exception) {
                // Genuine failure — stays at error level.
                XLog.e(INIT_TAG, "app-async-init CRASHED: ${e.message}", e)
            }
        }, "app-async-init")
        initThread.isDaemon = true
        initThread.start()

        // Watchdog: if init takes more than 30 s, log a warning.
        // This catches hung model loads or deadlocks without killing the process.
        Thread({
            initThread.join(30_000L)
            if (initThread.isAlive) {
                // Genuine failure signal — stays at error level so it is visible
                // in release logcat, not just in the in-app log store.
                XLog.e(INIT_TAG, "app-async-init TIMEOUT after 30s — init may be hung")
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
                    runCatching { com.blackclaw.android.agent.OpenCodeZenModels.refreshOnNetwork() }
                        .onFailure {
                            XLog.w(TAG, "OpenCode Zen model refresh on network recovery " +
                                "failed, model list stays stale: ${it.message}")
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
