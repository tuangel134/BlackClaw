package com.blackclaw.android.adb

import android.content.Context
import com.blackclaw.android.utils.XLog
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Date

/**
 * BlackClaw's concrete connection manager. Extends libadb-android's abstract
 * base class to provide our persistent identity (RSA-2048 key + self-signed
 * cert) and a friendly device name.
 *
 * Identity is stored in app private files. On first construction we generate
 * the keypair. On subsequent runs we reload it. The cert is what TLS presents
 * to adbd; once paired, adbd recognises this identity for life.
 *
 * Singleton via [getInstance] because the lib's AbsAdbConnectionManager is
 * stateful (holds an open AdbConnection internally).
 */
class AdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    companion object {
        private const val TAG = "AdbConnectionManager"
        private const val KEY_BITS = 2048
        private const val CERT_VALID_YEARS = 50

        @Volatile private var instance: AdbConnectionManager? = null

        fun getInstance(context: Context): AdbConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: AdbConnectionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val baseDir = File(context.filesDir, "adb").apply { mkdirs() }
    private val privKeyFile = File(baseDir, "privkey.pkcs8")
    private val pubKeyFile = File(baseDir, "pubkey.x509")
    private val certFile = File(baseDir, "cert.pem")

    @Volatile private var keyPair: KeyPair? = null
    @Volatile private var certificate: Certificate? = null

    init {
        // Match the running Android API version so libadb knows whether to
        // use the legacy A_AUTH path or the new TLS path.
        api = android.os.Build.VERSION.SDK_INT
        ensureIdentity()
    }

    override fun getPrivateKey(): PrivateKey = keyPair!!.private

    override fun getCertificate(): Certificate = certificate!!

    override fun getDeviceName(): String = "BlackClaw@${android.os.Build.MODEL}"

    /** SHA-256 fingerprint of the cert's public key — useful for the UI. */
    fun fingerprint(): String {
        val md = MessageDigest.getInstance("SHA-256").digest(certificate!!.publicKey.encoded)
        return md.joinToString(":") { "%02X".format(it) }.take(47)
    }

    private fun ensureIdentity() {
        if (privKeyFile.exists() && certFile.exists()) {
            keyPair = loadKeyPair()
            certificate = loadCertificate()
        } else {
            generateAndPersist()
        }
    }

    private fun loadKeyPair(): KeyPair {
        val priv = KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(privKeyFile.readBytes())
        )
        val pub = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(pubKeyFile.readBytes())
        )
        return KeyPair(pub, priv)
    }

    private fun loadCertificate(): Certificate {
        FileInputStream(certFile).use { input ->
            return CertificateFactory.getInstance("X.509").generateCertificate(input)
        }
    }

    private fun generateAndPersist() {
        XLog.i(TAG, "Generating BlackClaw ADB identity (RSA-$KEY_BITS, ${CERT_VALID_YEARS}y cert)")
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(KEY_BITS, SecureRandom())
        val pair = gen.generateKeyPair()
        privKeyFile.writeBytes(pair.private.encoded)
        pubKeyFile.writeBytes(pair.public.encoded)

        // Self-sign via BouncyCastle (transitively pulled in by libadb-android).
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 60_000)
        val notAfter = Date(now + CERT_VALID_YEARS * 365L * 24L * 3600L * 1000L)
        val subject = X500Name("CN=BlackClaw, O=BlackClaw, C=ES")
        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            subject, BigInteger(64, SecureRandom()), notBefore, notAfter, subject, pair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(pair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        FileOutputStream(certFile).use { out ->
            val encoded = Base64.getMimeEncoder(64, "\n".toByteArray())
                .encodeToString(cert.encoded)
            out.write("-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----\n".toByteArray())
        }
        keyPair = pair
        certificate = cert
    }
}
