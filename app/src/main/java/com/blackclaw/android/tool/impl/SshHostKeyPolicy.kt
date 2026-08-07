package com.blackclaw.android.tool.impl

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Host key pinning rules for the remote shell tools, as pure functions so they can
 * be unit-tested without a network or an SSH server.
 *
 * ## Why this exists
 *
 * The SSH client used to run with `StrictHostKeyChecking = "no"`, which accepts
 * whatever host key shows up, silently, every time. That is not a weak check, it is
 * the absence of one, and it defeats the entire point of SSH's design.
 *
 * Concretely: the connection carries a plaintext password (the tools authenticate
 * with `session.setPassword`). Anyone able to answer for the target IP — same
 * coffee-shop Wi-Fi, a rogue DHCP or DNS reply, ARP spoofing on a home LAN, a
 * compromised router — presents their own host key, the client accepts it, and the
 * password is handed straight to the attacker during authentication. They then have
 * the user's shell credentials for a machine BlackClaw is explicitly wired to run
 * commands on. Host key verification is the only thing that distinguishes "the
 * server I trusted" from "whoever answered", because the password is sent *after*
 * the key exchange completes.
 *
 * ## Trust on first use
 *
 * There is no CA hierarchy for SSH host keys, so first contact is unavoidably
 * unverified — the same model OpenSSH itself uses. What pinning buys is that first
 * contact is the *only* window: the fingerprint is recorded alongside the stored
 * connection, and every later connect must present the same key or the connection is
 * refused before the password goes out.
 *
 * The fingerprint format matches OpenSSH's so the user can compare it against what
 * `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` prints on the machine itself,
 * which is what turns the first connection from "unverified" into "verified out of
 * band".
 */
object SshHostKeyPolicy {

    /** What to do about a presented key, given what we have on record. */
    enum class Verdict {
        /** Nothing pinned yet. Record it and continue. */
        TRUST_ON_FIRST_USE,

        /** Presented key equals the pinned one. */
        MATCH,

        /** Presented key differs from the pinned one. Abort, do not authenticate. */
        MISMATCH,
    }

    /**
     * Read the algorithm name out of an SSH public key blob.
     *
     * The wire format is a `string` (uint32 big-endian length, then bytes) holding
     * the algorithm name, followed by algorithm-specific data. Parsed here rather
     * than asking JSch so the pure logic stays testable and so a malformed blob
     * cannot throw on us mid-handshake.
     */
    fun keyTypeFromBlob(blob: ByteArray): String {
        if (blob.size < 4) return UNKNOWN_KEY_TYPE
        val length = ((blob[0].toInt() and 0xFF) shl 24) or
            ((blob[1].toInt() and 0xFF) shl 16) or
            ((blob[2].toInt() and 0xFF) shl 8) or
            (blob[3].toInt() and 0xFF)
        if (length <= 0 || length > MAX_KEY_TYPE_LENGTH || blob.size < 4 + length) {
            return UNKNOWN_KEY_TYPE
        }
        val name = String(blob, 4, length, StandardCharsets.US_ASCII)
        val plausible = name.isNotEmpty() && name.all {
            it.isLetterOrDigit() || it == '-' || it == '.' || it == '@' || it == '_'
        }
        return if (plausible) name else UNKNOWN_KEY_TYPE
    }

    /**
     * OpenSSH-compatible fingerprint, prefixed with the algorithm so a server that
     * offers several key types cannot have one silently substituted for another.
     *
     * Shape: `ssh-ed25519 SHA256:BASE64` — unpadded base64, exactly as
     * `ssh-keygen -l` prints it.
     */
    fun fingerprint(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "${keyTypeFromBlob(blob)} SHA256:$encoded"
    }

    /** Storage may hold whitespace from hand-editing; normalise before comparing. */
    fun normalize(fingerprint: String?): String = fingerprint?.trim().orEmpty()

    /**
     * @param pinned what we recorded when this connection was first established,
     *   empty if this is first contact.
     * @param presented fingerprint of the key the server just offered.
     */
    fun verdict(pinned: String?, presented: String): Verdict {
        val stored = normalize(pinned)
        if (stored.isEmpty()) return Verdict.TRUST_ON_FIRST_USE
        return if (constantTimeEquals(stored, normalize(presented))) {
            Verdict.MATCH
        } else {
            Verdict.MISMATCH
        }
    }

    /**
     * Fingerprints are public values, so timing is not a real oracle here. Compared
     * without early exit anyway because it costs nothing and stops this from being
     * copied into somewhere it does matter.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /**
     * Text shown when a connection is refused. Names both fingerprints, because the
     * legitimate cause (the admin reinstalled the OS or rotated host keys) and the
     * attack look identical from here, and only the user can tell them apart.
     */
    fun mismatchMessage(host: String, pinned: String, presented: String): String =
        "ABORTADO: la clave del host $host ha cambiado. No se envió la contraseña.\n" +
            "Esperada:  ${normalize(pinned)}\n" +
            "Recibida:  ${normalize(presented)}\n" +
            "Esto ocurre si alguien está interceptando la conexión (MITM), o si el " +
            "servidor fue reinstalado o rotó sus claves. Verifica la huella en la " +
            "propia máquina con 'ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub'. " +
            "Si el cambio es legítimo, elimina la conexión con remote_disconnect " +
            "alias='$host' y vuelve a crearla."

    /** Text appended to the first successful connect so the user can verify it. */
    fun firstUseMessage(host: String, presented: String): String =
        "Huella del host (primera conexión, ahora fijada): ${normalize(presented)}. " +
            "Compárala en $host con 'ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub'. " +
            "Si no coincide, la conexión fue interceptada."

    const val UNKNOWN_KEY_TYPE = "unknown"

    /** Longest real algorithm name is well under this; a guard against junk input. */
    private const val MAX_KEY_TYPE_LENGTH = 64
}
