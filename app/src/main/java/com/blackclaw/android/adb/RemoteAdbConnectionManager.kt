package com.blackclaw.android.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.io.FileInputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * A SECOND libadb connection manager, dedicated to connecting BlackClaw to
 * *other* devices over Wireless Debugging (adb-over-wifi) — kept separate from
 * [AdbConnectionManager] (which owns the self/loopback connection used by
 * fast_tap etc.) so a remote session never disturbs the local one.
 *
 * It reuses the exact same RSA identity + certificate that [AdbConnectionManager]
 * already generated under files/adb, so remote devices see a stable identity.
 * We touch [AdbConnectionManager.getInstance] first to guarantee those files
 * exist before loading them here.
 */
class RemoteAdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val keyPair: KeyPair
    private val cert: Certificate

    init {
        api = Build.VERSION.SDK_INT
        // Ensure the shared identity has been generated.
        AdbConnectionManager.getInstance(context)
        val baseDir = File(context.filesDir, "adb")
        val priv = KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(File(baseDir, "privkey.pkcs8").readBytes()))
        val pub = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(File(baseDir, "pubkey.x509").readBytes()))
        keyPair = KeyPair(pub, priv)
        cert = FileInputStream(File(baseDir, "cert.pem")).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
    }

    override fun getPrivateKey(): PrivateKey = keyPair.private
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "BlackClaw@${Build.MODEL}"

    companion object {
        fun create(context: Context): RemoteAdbConnectionManager =
            RemoteAdbConnectionManager(context.applicationContext)
    }
}
