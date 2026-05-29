package com.blackclaw.android.adb

import android.content.Context
import com.blackclaw.android.utils.XLog
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Date

/**
 * Persistent identity for our internal ADB client.
 *
 * Stores a 2048-bit RSA keypair + a self-signed X.509 certificate in the app's
 * private files dir. The TLS pairing flow registers the cert's public key with
 * adbd; after that, every subsequent TLS connect proves we own the matching
 * private key and adbd lets us in without prompting.
 *
 *  Files (all under filesDir/adb/):
 *    privkey.pkcs8     — PKCS#8 encoded private key
 *    pubkey.x509       — X.509 encoded public key
 *    cert.pem          — PEM-encoded self-signed cert (what TLS sends)
 *    pubkey.adb        — ADB-formatted pubkey + name (what pairing handshake sends)
 *
 * We never rotate this identity — the user only has to pair once.
 */
class AdbKeyStore(context: Context) {

    companion object {
        private const val TAG = "AdbKeyStore"
        private const val KEY_BITS = 2048
        private const val CERT_VALID_YEARS = 50  // self-signed, very long-lived
    }

    private val baseDir = File(context.filesDir, "adb").apply { mkdirs() }
    private val privKeyFile = File(baseDir, "privkey.pkcs8")
    private val pubKeyFile = File(baseDir, "pubkey.x509")
    private val certFile = File(baseDir, "cert.pem")
    private val adbPubFile = File(baseDir, "pubkey.adb")

    @Volatile private var cachedKeys: KeyPair? = null
    @Volatile private var cachedCert: X509Certificate? = null

    /** True if we already generated the keypair on a previous run. */
    fun exists(): Boolean = privKeyFile.exists() && pubKeyFile.exists() && certFile.exists()

    /**
     * Returns the persistent RSA keypair, generating + saving it on first call.
     * Subsequent calls hit the in-memory cache.
     */
    @Synchronized
    fun keyPair(): KeyPair {
        cachedKeys?.let { return it }
        val pair = if (exists()) loadKeyPair() else generateAndPersist()
        cachedKeys = pair
        return pair
    }

    /** Returns the self-signed cert that the TLS layer will present to adbd. */
    @Synchronized
    fun certificate(): X509Certificate {
        cachedCert?.let { return it }
        if (!certFile.exists()) generateAndPersist()
        val pem = certFile.readText()
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        val cert = factory.generateCertificate(pem.byteInputStream()) as X509Certificate
        cachedCert = cert
        return cert
    }

    /**
     * Returns the pubkey in adbd's special "ADB" format: 4-byte length + base64
     * of the binary pubkey, then a space and a human-readable name.
     *
     * adbd refuses pubkeys without the trailing name (it stores the name as
     * the entry shown in `Settings → Developer options → Revoke USB debugging
     * authorizations`).
     */
    fun adbFormattedPublicKey(): ByteArray {
        if (adbPubFile.exists()) return adbPubFile.readBytes()

        val pub = keyPair().public.encoded
        val n = (pub.size).toString()
        val payload = ("$n " + Base64.getEncoder().encodeToString(pub) + " blackclaw@android\u0000")
            .toByteArray(Charsets.US_ASCII)
        adbPubFile.writeBytes(payload)
        return payload
    }

    /** SHA-256 fingerprint of the cert's public key — useful in logs and the UI. */
    fun fingerprint(): String {
        val md = MessageDigest.getInstance("SHA-256").digest(certificate().publicKey.encoded)
        return md.joinToString(":") { "%02X".format(it) }.take(47)
    }

    // ──────────────────────── internals ────────────────────────

    private fun loadKeyPair(): KeyPair {
        val priv = java.security.KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(privKeyFile.readBytes())
        )
        val pub = java.security.KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(pubKeyFile.readBytes())
        )
        return KeyPair(pub, priv)
    }

    private fun generateAndPersist(): KeyPair {
        XLog.i(TAG, "Generating fresh ADB identity (RSA-$KEY_BITS)")
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(KEY_BITS, SecureRandom())
        val pair = gen.generateKeyPair()

        privKeyFile.writeBytes(pair.private.encoded)
        pubKeyFile.writeBytes(pair.public.encoded)

        // Build a self-signed cert valid for [CERT_VALID_YEARS]
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 60_000)  // tiny back-date for clock skew
        val notAfter = Date(now + CERT_VALID_YEARS * 365L * 24L * 3600L * 1000L)
        val subject = X500Name("CN=BlackClaw, O=BlackClaw, C=ES")
        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            /* issuer = */ subject,
            /* serial = */ BigInteger(64, SecureRandom()),
            notBefore, notAfter, subject,
            pair.public,
        )
        // adbd expects an end-entity cert (not a CA), but tolerates either.
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))

        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(pair.private)
        val holder = builder.build(signer)
        val cert = JcaX509CertificateConverter().getCertificate(holder)

        // PEM serialize
        val encoded = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(cert.encoded)
        certFile.writeText("-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----\n")

        // Drop the cached adb pubkey so it gets rebuilt with fresh data
        adbPubFile.delete()

        return pair
    }
}
