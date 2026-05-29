package com.blackclaw.android.adb

import com.blackclaw.android.adb.AdbProtocol.PacketHeader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Read/write of the 24-byte ADB packet header (always little-endian) plus a
 * length-prefixed payload.
 *
 * adbd is byte-strict here: any framing mismatch → silent disconnect on its
 * side, no error returned. The most common bug is forgetting LE byte order.
 */
object AdbIo {

    private const val HEADER_BYTES = 24

    fun readPacket(input: InputStream): Pair<PacketHeader, ByteArray> {
        val data = ByteArray(HEADER_BYTES)
        readFully(input, data)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val header = PacketHeader(
            command = bb.int,
            arg0 = bb.int,
            arg1 = bb.int,
            dataLength = bb.int,
            dataChecksum = bb.int,
            magic = bb.int,
        )
        if (!header.isValid()) {
            throw IOException("Bad ADB packet magic: cmd=${AdbProtocol.cmdName(header.command)} " +
                "magic=${"%08x".format(header.magic)} expected=${"%08x".format(header.command.inv())}")
        }
        if (header.dataLength < 0 || header.dataLength > AdbProtocol.MAX_PAYLOAD) {
            throw IOException("Bad ADB packet length: ${header.dataLength}")
        }
        val payload = ByteArray(header.dataLength)
        if (header.dataLength > 0) readFully(input, payload)
        return Pair(header, payload)
    }

    fun writePacket(
        output: OutputStream,
        command: Int,
        arg0: Int = 0,
        arg1: Int = 0,
        data: ByteArray = ByteArray(0),
    ) {
        if (data.size > AdbProtocol.MAX_PAYLOAD) {
            throw IOException("ADB payload too large: ${data.size}")
        }
        val header = ByteArray(HEADER_BYTES)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(command)
        bb.putInt(arg0)
        bb.putInt(arg1)
        bb.putInt(data.size)
        bb.putInt(AdbProtocol.checksum(data))
        bb.putInt(command.inv())
        synchronized(output) {
            output.write(header)
            if (data.isNotEmpty()) output.write(data)
            output.flush()
        }
    }

    /**
     * Reads exactly [out.size] bytes or throws. The default InputStream.read
     * may return less, which has bitten every ADB client implementation ever.
     */
    fun readFully(input: InputStream, out: ByteArray) {
        var read = 0
        while (read < out.size) {
            val n = input.read(out, read, out.size - read)
            if (n < 0) throw IOException("EOF while reading ADB packet (got $read of ${out.size})")
            read += n
        }
    }

    /** Convenience for tests / debug. */
    fun DataOutputStream.writeAdb(
        command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)
    ) = writePacket(this, command, arg0, arg1, data)

    fun DataInputStream.readAdb() = readPacket(this)
}
