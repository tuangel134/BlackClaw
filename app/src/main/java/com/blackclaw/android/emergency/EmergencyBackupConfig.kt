package com.blackclaw.android.emergency

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.blackclaw.android.utils.KVUtils
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Optional user-owned WebDAV/Nextcloud destination for already-encrypted evidence. */
object EmergencyBackupConfig {
    private const val KEY_URL = "emergency_backup_webdav_url"
    private const val KEY_USER = "emergency_backup_webdav_user"
    private const val KEY_SECRET = "emergency_backup_webdav_secret"
    private const val KEY_ALIAS = "blackclaw_emergency_credentials_v1"

    var url: String
        get() = KVUtils.getString(KEY_URL)
        set(value) { KVUtils.putString(KEY_URL, normalizeUrl(value)) }

    var username: String
        get() = KVUtils.getString(KEY_USER)
        set(value) { KVUtils.putString(KEY_USER, value.trim()) }

    var password: String
        get() = decrypt(KVUtils.getString(KEY_SECRET))
        set(value) { KVUtils.putString(KEY_SECRET, if (value.isBlank()) "" else encrypt(value)) }

    val isReady: Boolean get() = url.startsWith("https://")

    internal fun normalizeUrl(value: String): String {
        val clean = value.trim().trimEnd('/')
        return if (clean.startsWith("https://", true)) "https://" + clean.substringAfter("://") else ""
    }

    private fun encrypt(value: String): String = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(payload, Base64.NO_WRAP)
    }.getOrDefault("")

    private fun decrypt(value: String): String = runCatching {
        if (value.isBlank()) return ""
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
        cipher.doFinal(payload.copyOfRange(12, payload.size)).toString(Charsets.UTF_8)
    }.getOrDefault("")

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }
}
