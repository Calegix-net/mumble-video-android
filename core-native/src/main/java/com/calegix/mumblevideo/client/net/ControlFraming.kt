package com.calegix.mumblevideo.client.net

import com.google.protobuf.MessageLite

/** One control message: its wire type and serialised payload. */
class ControlMessage(val type: Int, val payload: ByteArray)

/**
 * The TLS control stream's framing: 2-byte type and 4-byte length, both big-endian, then the
 * payload. Mirrors ControlReader/frameControlMessage in mumble-core.
 */
object ControlFraming {
    /** Mumble's limit on a single control message. */
    const val MAX_PAYLOAD = 8 * 1024 * 1024

    fun frame(type: Int, message: MessageLite): ByteArray = frame(type, message.toByteArray())

    fun frame(type: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "control message too long" }
        val out = ByteArray(6 + payload.size)
        out[0] = (type ushr 8).toByte()
        out[1] = type.toByte()
        out[2] = (payload.size ushr 24).toByte()
        out[3] = (payload.size ushr 16).toByte()
        out[4] = (payload.size ushr 8).toByte()
        out[5] = payload.size.toByte()
        payload.copyInto(out, 6)
        return out
    }
}

/** Reassembles control messages from whatever pieces the socket returns. */
class ControlReader {
    private var buffer = ByteArray(16 * 1024)
    private var length = 0

    fun feed(bytes: ByteArray, count: Int = bytes.size) {
        if (length + count > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, length + count))
        bytes.copyInto(buffer, length, 0, count)
        length += count
    }

    /** Every complete message fed so far. Throws [ProtocolException] on an oversized announcement. */
    fun drain(): List<ControlMessage> {
        val messages = ArrayList<ControlMessage>()
        var offset = 0
        while (length - offset >= 6) {
            val type = ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
            val size = ((buffer[offset + 2].toInt() and 0xFF) shl 24) or
                ((buffer[offset + 3].toInt() and 0xFF) shl 16) or
                ((buffer[offset + 4].toInt() and 0xFF) shl 8) or
                (buffer[offset + 5].toInt() and 0xFF)
            if (size < 0 || size > ControlFraming.MAX_PAYLOAD) throw ProtocolException("message of $size bytes")
            if (length - offset - 6 < size) break
            messages += ControlMessage(type, buffer.copyOfRange(offset + 6, offset + 6 + size))
            offset += 6 + size
        }
        buffer.copyInto(buffer, 0, offset, length)
        length -= offset
        return messages
    }
}

class ProtocolException(message: String) : Exception(message)

/** Control message types, as in mumble-core's ControlType. Append-only. */
object ControlType {
    const val VERSION = 0
    const val UDP_TUNNEL = 1
    const val AUTHENTICATE = 2
    const val PING = 3
    const val REJECT = 4
    const val SERVER_SYNC = 5
    const val CHANNEL_REMOVE = 6
    const val CHANNEL_STATE = 7
    const val USER_REMOVE = 8
    const val USER_STATE = 9
    const val BAN_LIST = 10
    const val TEXT_MESSAGE = 11
    const val PERMISSION_DENIED = 12
    const val ACL = 13
    const val QUERY_USERS = 14
    const val CRYPT_SETUP = 15
    const val CONTEXT_ACTION_MODIFY = 16
    const val CONTEXT_ACTION = 17
    const val USER_LIST = 18
    const val VOICE_TARGET = 19
    const val PERMISSION_QUERY = 20
    const val CODEC_VERSION = 21
    const val USER_STATS = 22
    const val REQUEST_BLOB = 23
    const val SERVER_CONFIG = 24
    const val SUGGEST_CONFIG = 25
    const val PLUGIN_DATA_TRANSMISSION = 26
    const val VIDEO_STATE = 27
    const val VIDEO_SUBSCRIBE = 28
    const val FILE_TRANSFER = 29
}
