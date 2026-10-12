package com.calegix.mumblevideo.client.net

import android.os.Process
import android.util.Log
import com.calegix.mumblevideo.client.crypto.OcbCrypt
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * The connection's UDP socket. Encrypts and frames outgoing plaintext, and hands decrypted
 * plaintext ([type byte][protobuf]) to [onPlaintext] on its own receive thread.
 *
 * mumble-video servers put a media channel byte (0 audio, 1 video) in front of every datagram,
 * whatever features they advertise; stock Mumble servers do not. We start with the channel byte and
 * follow whatever the server's own datagrams turn out to use, so the framing is learned from the
 * first pong rather than guessed.
 */
internal class UdpChannel(
    private val server: InetSocketAddress,
    private val crypt: OcbCrypt,
    private val onPlaintext: (ByteArray) -> Unit,
    private val onVideoDatagram: (ByteArray) -> Unit = {},
    /** Called once, when the first datagram decrypts: true if the server uses the channel byte. */
    private val onFramingLearned: (channelByte: Boolean) -> Unit = {},
) {
    @Volatile var channelByte = true

    private var framingLearned = false

    @Volatile var badPackets = 0L
        private set

    private val socket = DatagramSocket().apply {
        connect(server)
        soTimeout = 1000
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread(::loop, "mv-udp").also { it.start() }
    }

    /** Encrypts and sends one plaintext UDP message. False if not keyed or the send failed. */
    fun send(plain: ByteArray): Boolean {
        if (!crypt.isValid) return false
        val payload = synchronized(crypt) { crypt.encrypt(plain) }
        val datagram = if (channelByte) byteArrayOf(MEDIA_CHANNEL_AUDIO) + payload else payload
        return try {
            socket.send(DatagramPacket(datagram, datagram.size))
            true
        } catch (e: IOException) {
            Log.d(TAG, "udp send failed: ${e.message}")
            false
        }
    }

    fun close() {
        running = false
        socket.close()
        thread?.join(1500)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        while (running) {
            try {
                packet.setLength(buffer.size)
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                if (running) Log.d(TAG, "udp receive stopped: ${e.message}")
                return
            }
            val length = packet.length
            if (length < 1) continue
            when {
                buffer[0] == MEDIA_CHANNEL_VIDEO -> onVideoDatagram(buffer.copyOf(length))

                else -> {
                    // With the channel byte the audio payload starts at 1. A stock server's bare
                    // payload starts at 0; try that too before calling it bad.
                    val framed = if (buffer[0] == MEDIA_CHANNEL_AUDIO) crypt.decrypt(buffer, 1, length - 1) else null
                    val plain = framed ?: crypt.decrypt(buffer, 0, length)
                    if (plain == null) {
                        badPackets++
                    } else {
                        channelByte = framed != null
                        if (!framingLearned) {
                            framingLearned = true
                            onFramingLearned(channelByte)
                        }
                        onPlaintext(plain)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MvUdp"
        const val MEDIA_CHANNEL_AUDIO: Byte = 0
        const val MEDIA_CHANNEL_VIDEO: Byte = 1

        /** UDP plaintext message types (MumbleProtocol.h). */
        const val TYPE_AUDIO: Byte = 0
        const val TYPE_PING: Byte = 1
    }
}
