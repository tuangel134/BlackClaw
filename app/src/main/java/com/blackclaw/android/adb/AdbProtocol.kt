package com.blackclaw.android.adb

/**
 * Constants and helpers for the Android Debug Bridge wire protocol.
 *
 * Reference: https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/protocol.txt
 *
 * Quick mental model (for future maintainers):
 *
 *  1) After Android 11, adbd accepts two flavors of connection:
 *
 *       a) "Plain" tcp on port 5555 — only when 'Network ADB' is on. Uses the
 *          legacy A_AUTH challenge/response with our RSA-2048 keypair.
 *
 *       b) "TLS" tcp on the device-chosen port reported by mDNS service
 *          `_adb-tls-connect._tcp`. The whole connection is TLS 1.3 and the
 *          peer pins our RSA pubkey (which we registered via the pairing flow).
 *
 *  2) Wireless pairing (Android 11+ "Pair device with pairing code") runs on
 *     a *different* port advertised as `_adb-tls-pairing._tcp`. It speaks
 *     SPAKE2 over TLS 1.3 with PSK = the 6-digit code shown to the user, then
 *     hands over our pubkey to adbd. After this we never need to pair again.
 *
 *  3) The "framing" inside the TLS stream is the classic 24-byte ADB packet
 *     header followed by [data_length] bytes of payload, both directions.
 *
 *  This file owns the constants. Actual socket / TLS / SPAKE2 logic lives in
 *  sibling files so the protocol layer stays mockable.
 */
object AdbProtocol {

    // ────── packet command codes (little-endian on the wire) ──────
    const val A_SYNC = 0x434e5953   // "SYNC"
    const val A_CNXN = 0x4e584e43   // "CNXN"
    const val A_OPEN = 0x4e45504f   // "OPEN"
    const val A_OKAY = 0x59414b4f   // "OKAY"
    const val A_CLSE = 0x45534c43   // "CLSE"
    const val A_WRTE = 0x45545257   // "WRTE"
    const val A_AUTH = 0x48545541   // "AUTH"
    const val A_STLS = 0x534c5453   // "STLS" — switch to TLS

    // ────── A_AUTH subcodes ──────
    const val ADB_AUTH_TOKEN = 1
    const val ADB_AUTH_SIGNATURE = 2
    const val ADB_AUTH_RSAPUBLICKEY = 3

    // ────── connection params ──────
    const val A_VERSION = 0x01000001
    const val MAX_PAYLOAD = 256 * 1024
    const val A_STLS_VERSION = 0x01000000

    /** 24-byte packet header. */
    data class PacketHeader(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val dataChecksum: Int,
        val magic: Int,
    ) {
        fun isValid(): Boolean = magic == command.inv()
    }

    /** Compute the legacy data checksum (sum of bytes mod 2^32). adbd ignores
     *  this on TLS connections but still checks it on the legacy path, so we
     *  always compute it correctly to avoid surprises. */
    fun checksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) sum += (b.toInt() and 0xff)
        return sum
    }

    /** Pretty 4-byte command name for logs. */
    fun cmdName(cmd: Int): String {
        val bytes = byteArrayOf(
            (cmd and 0xff).toByte(),
            ((cmd ushr 8) and 0xff).toByte(),
            ((cmd ushr 16) and 0xff).toByte(),
            ((cmd ushr 24) and 0xff).toByte(),
        )
        return String(bytes, Charsets.US_ASCII)
    }
}
