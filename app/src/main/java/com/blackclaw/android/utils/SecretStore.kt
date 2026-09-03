package com.blackclaw.android.utils

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted store for credentials and other high-value secrets.
 *
 * Ciphertext lives in app-private SharedPreferences, while the AES key itself is
 * generated inside AndroidKeyStore and cannot be exported by the app. Each value
 * uses a fresh GCM IV and binds the logical storage key as authenticated data, so a
 * ciphertext copied from one setting to another will fail authentication.
 *
 * This deliberately does not fall back to plaintext when encryption fails. Callers
 * may keep reading a legacy plaintext value during migration, but all NEW writes
 * must succeed here or report failure.
 */
object SecretStore {

    private const val TAG = "SecretStore"
    private const val PREFS_NAME = "blackclaw_secure_secrets_v1"
    private const val KEY_ALIAS = "blackclaw_secret_store_aes_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val VERSION = "v1"
    private const val GCM_TAG_BITS = 128

    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        // Create the key eagerly so migration failures surface at startup instead of
        // halfway through a settings save. A failure is logged but not fatal: legacy
        // values remain untouched until a later successful migration attempt.
        runCatching { key() }
            .onFailure { XLog.e(TAG, "AndroidKeyStore initialization failed: ${it.message}", it) }
    }

    fun getString(name: String): String? = synchronized(lock) {
        val context = appContext ?: return@synchronized null
        val encoded = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(name, null)
            ?: return@synchronized null
        try {
            decrypt(name, encoded)
        } catch (e: Exception) {
            // Authentication/decryption failure must never be interpreted as an empty
            // but valid credential, and the broken ciphertext is kept for diagnosis.
            XLog.e(TAG, "Could not decrypt secret '$name': ${e.message}", e)
            null
        }
    }

    fun putString(name: String, value: String): Boolean = putStrings(mapOf(name to value))

    /**
     * Atomically replace a group of secrets. Every non-empty value is encrypted
     * before the editor is touched; if any encryption fails, nothing is committed.
     * Empty values remove their keys in the same SharedPreferences transaction.
     */
    fun putStrings(values: Map<String, String>): Boolean = synchronized(lock) {
        val context = appContext ?: return@synchronized false
        if (values.isEmpty()) return@synchronized true
        try {
            val encrypted = values.mapValues { (name, value) ->
                if (value.isEmpty()) null else encrypt(name, value)
            }
            val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            encrypted.forEach { (name, encoded) ->
                if (encoded == null) editor.remove(name) else editor.putString(name, encoded)
            }
            editor.commit()
        } catch (e: Exception) {
            XLog.e(TAG, "Could not persist ${values.size} encrypted secret(s): ${e.message}", e)
            false
        }
    }

    fun contains(name: String): Boolean = synchronized(lock) {
        val context = appContext ?: return@synchronized false
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(name)
    }

    fun remove(name: String): Boolean = synchronized(lock) {
        val context = appContext ?: return@synchronized false
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(name)
            .commit()
    }

    fun clear(): Boolean = synchronized(lock) {
        val context = appContext ?: return@synchronized false
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun encrypt(name: String, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$VERSION:$iv:$body"
    }

    private fun decrypt(name: String, encoded: String): String {
        val parts = encoded.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == VERSION) { "Unsupported secret envelope" }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(name.toByteArray(StandardCharsets.UTF_8))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
