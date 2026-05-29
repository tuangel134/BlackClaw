package com.blackclaw.android.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blackclaw.android.ClawApplication
import com.blackclaw.android.R
import com.blackclaw.android.agent.llm.ActiveModelMode
import com.blackclaw.android.agent.llm.ModelConfigRepository
import com.blackclaw.android.channel.ChannelManager
import com.blackclaw.android.server.ConfigServerManager
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.widget.QRCodeDialog
import com.blackclaw.android.utils.XLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * ViewModel for SettingsActivity
 */
class SettingsViewModel : ViewModel() {

    // Settings data Flow (for dynamic updates)
    private val _settingItems = MutableStateFlow<Map<String, SettingValue>>(emptyMap())
    val settingItems: StateFlow<Map<String, SettingValue>> = _settingItems

    // Menu click event
    private val _menuClickEvent = MutableStateFlow<MenuAction?>(null)
    val menuClickEvent: StateFlow<MenuAction?> = _menuClickEvent

    init {
        refresh()
    }

    fun refresh() {
        val discordBotToken = KVUtils.getDiscordBotToken().isNotEmpty()
        val telegramBotToken = KVUtils.getTelegramBotToken().isNotEmpty()
        val wechatBotToken = KVUtils.getWechatBotToken().isNotEmpty()
        val map = mapOf(
            MenuAction.LLM_CONFIG.name to SettingValue.Text(getActiveModelDisplayName()),
            MenuAction.DISCORD.name to SettingValue.Text(ClawApplication.instance.getString(if (discordBotToken) R.string.common_bound else R.string.common_unbound)),
            MenuAction.TELEGRAM.name to SettingValue.Text(ClawApplication.instance.getString(if (telegramBotToken) R.string.common_bound else R.string.common_unbound)),
            MenuAction.WECHAT.name to SettingValue.Text(ClawApplication.instance.getString(if (wechatBotToken) R.string.common_bound else R.string.common_unbound)),
            MenuAction.LAN_CONFIG.name to SettingValue.Text(getLanConfigTrailingText())
        )
        _settingItems.value = map
    }

    /** Show the actual active model, not stale shared key. */
    private fun getActiveModelDisplayName(): String {
        val config = ModelConfigRepository.snapshot()
        val app = ClawApplication.instance
        return if (config.activeMode == ActiveModelMode.LOCAL) {
            val path = config.local.modelPath
            if (path.isNotEmpty() && java.io.File(path).exists()) {
                config.local.displayName + " · Local"
            } else {
                app.getString(R.string.common_unconfigured)
            }
        } else {
            val cloudModel = config.activeCloud.modelName
            if (cloudModel.isNotEmpty()) {
                "$cloudModel · Cloud"
            } else {
                app.getString(R.string.common_unconfigured)
            }
        }
    }

    /**
     * Update a setting value
     */
    fun updateSettingValue(key: String, value: SettingValue) {
        _settingItems.value = _settingItems.value.toMutableMap().apply {
            put(key, value)
        }
    }

    /**
     * Update trailing text
     */
    fun updateTrailingText(key: String, text: String) {
        updateSettingValue(key, SettingValue.Text(text))
    }

    /**
     * Handle menu item click
     */
    fun onMenuItemClick(action: MenuAction) {
        _menuClickEvent.value = action
    }

    /**
     * Clear menu click event
     */
    fun clearMenuClickEvent() {
        _menuClickEvent.value = null
    }

    /**
     * WeChat iLink QR code login flow
     */
    fun startWeChatQrLogin(context: Context) {
        viewModelScope.launch {
            val loadingDialog = com.blackclaw.android.widget.LoadingDialog.show(
                context = context,
                message = context.getString(R.string.channel_config_wechat_scanning)
            )
            try {
                val apiClient = com.blackclaw.android.channel.wechat.WeChatApiClient()
                val qrResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    apiClient.getQrCode()
                }
                loadingDialog.dismiss()
                if (qrResult == null) {
                    Toast.makeText(context, R.string.wechat_qr_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Use qrcode value to generate QR code Bitmap locally via ZXing
                val qrBitmap = generateQrBitmap(qrResult.qrcodeImgContent, 512)
                if (qrBitmap == null) {
                    Toast.makeText(context, R.string.wechat_qr_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                var pollingJob: Job? = null
                val dialog = QRCodeDialog.show(
                    context = context,
                    title = context.getString(R.string.channel_config_wechat_title),
                    subtitle = context.getString(R.string.channel_config_wechat_tip),
                    qrBitmap = qrBitmap,
                    onClose = { pollingJob?.cancel() }
                )
                pollingJob = startWeChatQrPolling(context, dialog, apiClient, qrResult.qrcode)
            } catch (e: Exception) {
                loadingDialog.dismiss()
                XLog.e("SettingsViewModel", "WeChat QR code login failed", e)
                Toast.makeText(context, R.string.wechat_qr_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startWeChatQrPolling(
        context: Context,
        dialog: QRCodeDialog,
        apiClient: com.blackclaw.android.channel.wechat.WeChatApiClient,
        qrcode: String
    ): Job {
        return viewModelScope.launch {
            while (isActive) {
                delay(2000)
                if (!dialog.isShowing) break
                try {
                    val authResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        apiClient.pollQrCodeStatus(qrcode)
                    }
                    if (authResult != null) {
                        // QR scan confirmed successfully, save token and baseurl
                        KVUtils.setWechatBotToken(authResult.botToken)
                        KVUtils.setWechatApiBaseUrl(authResult.baseUrl)
                        ChannelManager.reinitWeChatFromStorage()
                        dialog.showStatusOverlay(
                            ClawApplication.instance.getString(R.string.channel_config_wechat_confirmed)
                        )
                        refresh()
                        delay(1500)
                        dialog.dismiss()
                        break
                    }
                } catch (_: Exception) {
                    // Network error — silently retry
                }
            }
        }
    }


    /**
     * Toggle LAN config server on/off
     */
    fun toggleConfigServer(context: Context): String {
        return if (ConfigServerManager.isRunning()) {
            ConfigServerManager.stop()
            KVUtils.setConfigServerEnabled(false)
            val text = getLanConfigTrailingText()
            updateTrailingText(MenuAction.LAN_CONFIG.name, text)
            text
        } else {
            val started = ConfigServerManager.start(context)
            if (started) {
                KVUtils.setConfigServerEnabled(true)
                val text = getLanConfigTrailingText()
                updateTrailingText(MenuAction.LAN_CONFIG.name, text)
                text
            } else {
                ClawApplication.instance.getString(R.string.lan_config_no_wifi)
            }
        }
    }

    private fun getLanConfigTrailingText(): String {
        return if (ConfigServerManager.isRunning()) {
            ConfigServerManager.getAddress() ?: ClawApplication.instance.getString(R.string.lan_config_stopped)
        } else {
            ClawApplication.instance.getString(R.string.lan_config_stopped)
        }
    }

    fun isDiscordBound(): Boolean {
        return KVUtils.getDiscordBotToken().isNotEmpty()
    }

    fun isTelegramBound(): Boolean {
        return KVUtils.getTelegramBotToken().isNotEmpty()
    }

    fun isWechatBound(): Boolean {
        return KVUtils.getWechatBotToken().isNotEmpty()
    }

    fun unbindDiscord() {
        KVUtils.setDiscordBotToken("")
        ChannelManager.reinitDiscordFromStorage()
        refresh()
    }

    fun unbindTelegram() {
        KVUtils.setTelegramBotToken("")
        ChannelManager.reinitTelegramFromStorage()
        refresh()
    }

    fun unbindWeChat() {
        // Clear persisted contextToken (corresponds to 2.0.1 clearContextTokensForAccount)
        val accountId = KVUtils.getWechatBotToken().substringBefore(":").ifEmpty { "default" }
        com.blackclaw.android.channel.wechat.WeChatInbound.clearContextTokensForAccount(accountId)
        KVUtils.setWechatBotToken("")
        KVUtils.setWechatApiBaseUrl("")
        KVUtils.setWechatUpdatesCursor("")
        ChannelManager.reinitWeChatFromStorage()
        refresh()
    }

    /**
     * Sealed class for setting values
     */
    sealed class SettingValue {
        data class Text(val text: String) : SettingValue()
        data class Switch(val isOn: Boolean) : SettingValue()
    }

    /**
     * Encode text as a QR code Bitmap using ZXing
     */
    private fun generateQrBitmap(content: String, size: Int): android.graphics.Bitmap? {
        return try {
            val hints = mapOf(
                com.google.zxing.EncodeHintType.MARGIN to 1,
                com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val matrix = com.google.zxing.qrcode.QRCodeWriter()
                .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            XLog.e("SettingsViewModel", "Failed to generate QR code", e)
            null
        }
    }

    /**
     * Menu action enum
     */
    enum class MenuAction {
        DISCORD, TELEGRAM, WECHAT,
        LAN_CONFIG,
        LLM_CONFIG
    }
}
