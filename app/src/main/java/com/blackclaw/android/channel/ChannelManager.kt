package com.blackclaw.android.channel

import com.blackclaw.android.channel.discord.DiscordChannelHandler
import com.blackclaw.android.channel.telegram.TelegramChannelHandler
import com.blackclaw.android.channel.wechat.WeChatChannelHandler
import com.blackclaw.android.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

enum class Channel(val displayName: String) {
    DISCORD("Discord"),
    TELEGRAM("Telegram"),
    WECHAT("WeChat"),
    LOCAL("Local"),
}

object ChannelManager {

    private const val TAG = "ChannelManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient()

    private val handlers = mutableMapOf<Channel, ChannelHandler>()
    private var messageListener: OnMessageReceivedListener? = null

    /**
     * Callback interface for received messages
     */
    interface OnMessageReceivedListener {
        fun onMessageReceived(channel: Channel, message: String, messageID: String)
    }

    @JvmStatic
    fun setOnMessageReceivedListener(listener: OnMessageReceivedListener?) {
        this.messageListener = listener
    }

    /**
     * Initialize all channels. Call this in Application.onCreate().
     */
    @JvmStatic
    @JvmOverloads
    fun init(
        discordBotToken: String? = null,
        telegramBotToken: String? = null,
        wechatBotToken: String? = null,
        wechatApiBaseUrl: String? = null
    ) {
        handlers[Channel.DISCORD] = DiscordChannelHandler(
            scope,
            discordBotToken?.takeIf { it.isNotEmpty() } ?: "",
        )
        handlers[Channel.TELEGRAM] = TelegramChannelHandler(
            scope, httpClient,
            telegramBotToken?.takeIf { it.isNotEmpty() } ?: "",
        )
        handlers[Channel.WECHAT] = WeChatChannelHandler(
            scope,
            wechatBotToken?.takeIf { it.isNotEmpty() } ?: "",
            wechatApiBaseUrl?.takeIf { it.isNotEmpty() } ?: "",
        )

        handlers.values.forEach { it.init() }
        XLog.i(TAG, "ChannelManager initialized")
    }

    /**
     * Re-read config from MMKV and re-initialize all channels (call after user saves config)
     */
    @JvmStatic
    fun reinitFromStorage() {
        handlers.values.forEach { it.reinitFromStorage() }
    }

    /**
     * Reconnect only channels that are not connected (call on network recovery; does not affect channels already running)
     */
    @JvmStatic
    fun reconnectIfNeeded() {
        handlers.forEach { (channel, handler) ->
            if (!handler.isConnected()) {
                XLog.i(TAG, "Reconnecting ${channel.displayName} channel")
                handler.reinitFromStorage()
            }
        }
    }

    @JvmStatic
    fun reinitDiscordFromStorage() {
        handlers[Channel.DISCORD]?.reinitFromStorage()
    }

    @JvmStatic
    fun reinitTelegramFromStorage() {
        handlers[Channel.TELEGRAM]?.reinitFromStorage()
    }

    @JvmStatic
    fun reinitWeChatFromStorage() {
        handlers[Channel.WECHAT]?.reinitFromStorage()
    }

    @JvmStatic
    fun disconnectAll() {
        handlers.forEach { (channel, handler) ->
            if (handler.isConnected()) {
                XLog.i(TAG, "Disconnecting ${channel.displayName} channel")
                handler.disconnect()
            }
        }
    }

    @JvmStatic
    fun sendMessage(channel: Channel, content: String, messageID: String) {
        val trimmedContent = content.trim('\n', '\r')
        if (trimmedContent.isBlank()) {
            XLog.w(TAG, "sendMessage skipping empty message [${channel.displayName}]")
            return
        }
        XLog.d(TAG, "sendMessage [${channel.displayName}]: ${trimmedContent.take(120)}")
        if (channel != Channel.LOCAL) {
            val identity = "${channel.name}:${getLastSenderId(channel) ?: messageID}"
            com.blackclaw.android.conversation.ConversationRepository.appendRemote(
                identity, com.blackclaw.android.conversation.ConversationRepository.Role.ASSISTANT,
                trimmedContent)
        }
        handlers[channel]?.sendMessage(trimmedContent, messageID)
    }

    @JvmStatic
    fun sendImage(channel: Channel, imageBytes: ByteArray, messageID: String) {
        handlers[channel]?.sendImage(imageBytes, messageID)
    }

    @JvmStatic
    fun sendFile(channel: Channel, file: java.io.File, messageID: String) {
        XLog.i(TAG, "sendFile: ${file.name} via ${channel.displayName}")
        handlers[channel]?.sendFile(file, messageID)
    }

    /**
     * Immediately flush pending messages in the specified channel's buffer. Call when a task ends.
     */
    @JvmStatic
    fun flushMessages(channel: Channel) {
        handlers[channel]?.flushMessages()
    }

    /**
     * Restore the routing context for the specified channel. Call before a scheduled task executes.
     */
    @JvmStatic
    fun restoreRoutingContext(channel: Channel, targetUserId: String) {
        handlers[channel]?.restoreRoutingContext(targetUserId)
    }

    /**
     * Get the identifier of the most recent message sender for the specified channel. Used for scheduled task persistence.
     */
    @JvmStatic
    fun getLastSenderId(channel: Channel): String? {
        return handlers[channel]?.getLastSenderId()
    }

    /**
     * Proactively send a message by user identifier (without relying on messageID context). Used for scheduled task triggers.
     */
    @JvmStatic
    fun sendMessageToUser(channel: Channel, userId: String, content: String) {
        val trimmedContent = content.trim('\n', '\r')
        if (trimmedContent.isBlank()) return
        XLog.d(TAG, "sendMessageToUser [${channel.displayName}] userId=${userId.take(20)}: ${trimmedContent.take(120)}")
        handlers[channel]?.sendMessageToUser(userId, trimmedContent)
    }

    /**
     * Called internally by ChannelHandlers to dispatch received messages to registered listeners.
     */
    @JvmStatic
    fun dispatchMessage(channel: Channel, message: String, messageID: String) {
        messageListener?.onMessageReceived(channel, message, messageID)
    }
}
