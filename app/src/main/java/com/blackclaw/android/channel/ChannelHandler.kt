package com.blackclaw.android.channel

/**
 * Unified interface for all message channels.
 * Each channel (DingTalk, FeiShu, QQ, Discord, Telegram) implements this interface.
 */
interface ChannelHandler {

    val channel: Channel

    /** Whether the current channel is connected/running */
    fun isConnected(): Boolean

    fun init()

    fun disconnect()

    fun reinitFromStorage()

    fun sendMessage(content: String, messageID: String)

    fun sendImage(imageBytes: ByteArray, messageID: String)

    fun sendFile(file: java.io.File, messageID: String)

    /** Immediately flush all pending messages in the buffer. Default is no-op; channels with buffering (e.g. WeChat) must override. */
    fun flushMessages() {}

    /**
     * Get the identifier of the most recent message sender.
     * Each channel returns its own user identifier (Telegram→chatId, QQ→openId, Discord→channelId,
     * DingTalk→staffId、WeChat→userId、FeiShu→messageId）。
     * Used for scheduled task user targeting persistence.
     */
    fun getLastSenderId(): String? = null

    /**
     * Proactively send a message by user identifier (without relying on messageID context).
     * Used for pushing messages to users after a scheduled task is triggered.
     * Channels that do not support proactive sending (e.g. FeiShu) fall back to sendMessage by default.
     */
    fun sendMessageToUser(userId: String, content: String) {
        sendMessage(content, "")
    }

    /**
     * Restore routing context: allows subsequent sendMessage("", content) calls to route correctly to the target user.
     * Call when a scheduled task is triggered, before startNewTask.
     * Each channel sets its own internal state according to its routing mechanism (e.g. Telegram sets lastChatId).
     */
    fun restoreRoutingContext(targetUserId: String) {}
}
