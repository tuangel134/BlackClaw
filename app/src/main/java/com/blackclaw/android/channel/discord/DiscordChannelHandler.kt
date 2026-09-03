package com.blackclaw.android.channel.discord

import com.blackclaw.android.channel.Channel
import com.blackclaw.android.channel.ChannelHandler
import com.blackclaw.android.channel.ChannelManager
import com.blackclaw.android.channel.auth.ChannelAuthorization
import com.blackclaw.android.utils.KVUtils
import com.blackclaw.android.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class DiscordChannelHandler(
    private val scope: CoroutineScope,
    private var botToken: String,
) : ChannelHandler {

    override val channel = Channel.DISCORD

    @Volatile
    private var lastChannelId: String? = null

    private val callback = object : DiscordCallback<String> {
        override fun onSuccess(result: String) { XLog.i(TAG, "Discord reply succeeded: resultChars=${result.length}") }
        override fun onFailure(error: String) { XLog.e(TAG, "Discord reply failed: errorChars=${error.length}") }
    }

    override fun isConnected(): Boolean = DiscordGatewayClient.getInstance().isConnected()

    override fun init() {
        if (botToken.isEmpty()) {
            XLog.w(TAG, "Discord Bot Token not configured, Discord channel will be unavailable")
            return
        }

        DiscordApiClient.getInstance().init(botToken)
        DiscordGatewayClient.getInstance().setOnDiscordMessageListener(
            object : DiscordGatewayClient.OnDiscordMessageListener {
                override fun onDiscordMessage(channelId: String, messageId: String, content: String) {
                    // Authorize BEFORE adopting this channel as the reply target, so a
                    // rejected sender cannot hijack where the agent's next answer goes.
                    // The bound identity is the Discord channel id, so ownership means
                    // "this DM / private channel", which is the intended boundary.
                    val auth = ChannelAuthorization.evaluate(channel, channelId, content)
                    if (!auth.allowed) {
                        auth.reply?.let { sendMessageToUser(channelId, it) }
                        return
                    }

                    lastChannelId = channelId
                    auth.reply?.let { sendMessageToUser(channelId, it) }
                    if (auth.justPaired) return

                    XLog.i(TAG, "[${channel.displayName}] Message received, channelId=$channelId")
                    ChannelManager.dispatchMessage(channel, content, messageId)
                }
            }
        )
        scope.launch {
            try {
                DiscordGatewayClient.getInstance().start(botToken)
                XLog.i(TAG, "Discord Gateway started")
            } catch (e: Exception) {
                XLog.e(TAG, "Discord Gateway failed to start", e)
            }
        }
    }

    override fun disconnect() {
        try {
            DiscordGatewayClient.getInstance().setOnDiscordMessageListener(null)
            DiscordGatewayClient.getInstance().stop()
            lastChannelId = null
            XLog.i(TAG, "Discord Gateway disconnected")
        } catch (e: Exception) {
            XLog.w(TAG, "Exception on Discord disconnect", e)
        }
    }

    override fun reinitFromStorage() {
        disconnect()
        botToken = KVUtils.getDiscordBotToken()
        init()
    }

    override fun sendMessage(content: String, messageID: String) {
        val channelId = lastChannelId
        if (channelId.isNullOrEmpty()) {
            XLog.w(TAG, "Discord reply failed: no available channelId")
            return
        }
        if (content.isBlank()) {
            XLog.w(TAG, "Discord skipping empty message")
            return
        }
        scope.launch {
            try {
                DiscordApiClient.getInstance().sendMessage(channelId, content, callback)
            } catch (e: Exception) {
                XLog.e(TAG, "Discord reply failed", e)
            }
        }
    }

    override fun sendImage(imageBytes: ByteArray, messageID: String) {
        val channelId = lastChannelId ?: return
        scope.launch {
            try {
                DiscordApiClient.getInstance().sendImage(channelId, imageBytes, callback = callback)
            } catch (e: Exception) {
                XLog.e(TAG, "Discord image send failed", e)
            }
        }
    }

    override fun sendFile(file: java.io.File, messageID: String) {
        val channelId = lastChannelId ?: return
        scope.launch {
            try {
                DiscordApiClient.getInstance().sendFile(
                    channelId, file.readBytes(), file.name,
                    callback = callback
                )
            } catch (e: Exception) {
                XLog.e(TAG, "Discord file send failed", e)
            }
        }
    }

    override fun getLastSenderId(): String? = lastChannelId

    override fun restoreRoutingContext(targetUserId: String) {
        if (targetUserId.isNotEmpty()) lastChannelId = targetUserId
    }

    override fun sendMessageToUser(userId: String, content: String) {
        if (userId.isEmpty() || content.isBlank()) return
        scope.launch {
            try {
                DiscordApiClient.getInstance().sendMessage(userId, content, callback)
            } catch (e: Exception) {
                XLog.e(TAG, "Discord sendMessageToUser failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "DiscordHandler"
    }
}
