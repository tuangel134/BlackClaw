package com.blackclaw.android.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import com.blackclaw.android.R
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.channel.ChannelManager
import com.blackclaw.android.server.ConfigServerManager
import com.blackclaw.android.utils.KVUtils

/**
 * Channel config screen (Discord/Telegram bot token + owner pairing).
 *
 * The UI is [ChannelConfigScreen]; this class only maps the Activity-result contract
 * onto it. The contract is unchanged, so every existing caller and launcher keeps
 * working — the previous XML layout (`activity_channel_config.xml`) and its
 * findViewById plumbing are gone.
 */
class ChannelConfigActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        val channelType = intent.getSerializableExtra(EXTRA_CHANNEL_TYPE) as? ChannelType
            ?: run {
                finish()
                return
            }

        val channel = when (channelType) {
            ChannelType.DISCORD -> com.blackclaw.android.channel.Channel.DISCORD
            ChannelType.TELEGRAM -> com.blackclaw.android.channel.Channel.TELEGRAM
        }
        val currentToken = when (channelType) {
            ChannelType.DISCORD -> KVUtils.getDiscordBotToken()
            ChannelType.TELEGRAM -> KVUtils.getTelegramBotToken()
        }

        setContent {
            com.blackclaw.android.ui.settings.ChannelConfigScreen(
                channel = channel,
                title = when (channelType) {
                    ChannelType.DISCORD -> getString(R.string.channel_config_discord_title)
                    ChannelType.TELEGRAM -> getString(R.string.channel_config_telegram_title)
                },
                tokenHint = when (channelType) {
                    ChannelType.DISCORD -> getString(R.string.channel_config_discord_hint1)
                    ChannelType.TELEGRAM -> getString(R.string.channel_config_telegram_hint1)
                },
                helpText = when (channelType) {
                    ChannelType.DISCORD -> getString(R.string.channel_config_discord_tip)
                    ChannelType.TELEGRAM -> getString(R.string.channel_config_telegram_tip)
                },
                initialToken = currentToken,
                lanAddress = ConfigServerManager.getAddress(),
                lanAccessCode = ConfigServerManager.accessCodeForDisplay(),
                onBack = { finish() },
                onSave = { token -> saveToken(channelType, token) },
            )
        }
    }

    private fun saveToken(channelType: ChannelType, token: String) {
        when (channelType) {
            ChannelType.DISCORD -> {
                KVUtils.setDiscordBotToken(token)
                ChannelManager.reinitDiscordFromStorage()
            }
            ChannelType.TELEGRAM -> {
                KVUtils.setTelegramBotToken(token)
                ChannelManager.reinitTelegramFromStorage()
            }
        }
        // Contract preserved: callers still get the same result payload they always did.
        val result = ChannelConfigResult(
            channelType = channelType,
            isConfigured = token.isNotEmpty(),
        )
        setResult(RESULT_OK, Intent().apply { putExtra(EXTRA_RESULT_CONFIG, result) })
        Toast.makeText(this, R.string.channel_config_saved, Toast.LENGTH_SHORT).show()
    }


    enum class ChannelType {
        DISCORD, TELEGRAM
    }

    /**
     * Channel config result
     */
    data class ChannelConfigResult(
        val channelType: ChannelType,
        val isConfigured: Boolean
    ) : java.io.Serializable

    /**
     * ActivityResultContract for use with registerForActivityResult
     */
    class ChannelConfigContract : ActivityResultContract<ChannelType, ChannelConfigResult?>() {
        override fun createIntent(context: Context, input: ChannelType): Intent {
            return Intent(context, ChannelConfigActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL_TYPE, input)
            }
        }

        override fun parseResult(resultCode: Int, intent: Intent?): ChannelConfigResult? {
            return if (resultCode == RESULT_OK && intent != null) {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra(EXTRA_RESULT_CONFIG) as? ChannelConfigResult
            } else {
                null
            }
        }
    }

    companion object {
        private const val EXTRA_CHANNEL_TYPE = "extra_channel_type"
        private const val EXTRA_RESULT_CONFIG = "extra_result_config"

        /**
         * Register a channel config Activity Result Launcher
         * Usage:
         * ```
         * private val channelConfigLauncher = ChannelConfigActivity.registerLauncher(this) { result ->
         *     result?.let {
         *         // Handle config result
         *         println("Channel: ${it.channelType}, Configured: ${it.isConfigured}")
         *     }
         * }
         *
         * // Launch the config screen
         * channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.DISCORD)
         * ```
         */
        fun registerLauncher(
            caller: ActivityResultCaller,
            onResult: (ChannelConfigResult?) -> Unit
        ): ActivityResultLauncher<ChannelType> {
            return caller.registerForActivityResult(ChannelConfigContract()) { result ->
                onResult(result)
            }
        }

        fun start(context: Context, channelType: ChannelType) {
            val intent = Intent(context, ChannelConfigActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL_TYPE, channelType)
            }
            context.startActivity(intent)
        }
    }
}
