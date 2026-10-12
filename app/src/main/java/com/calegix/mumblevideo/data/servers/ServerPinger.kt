package com.calegix.mumblevideo.data.servers

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a server says about itself to an unauthenticated ping. */
data class PingResult(val users: Int, val maxUsers: Int, val latencyMillis: Int, val version: String)

fun interface ServerPinger {
    /** Null if the server did not answer in time (offline, firewalled, or pings disabled). */
    suspend fun ping(host: String, port: Int): PingResult?
}

/**
 * Mumble's connectionless UDP ping, as the server list in every Mumble client uses: 12 bytes out
 * (a zero type word and an 8-byte identifier), 24 back (version, the identifier, users, max users,
 * bandwidth). Works before connecting and needs no certificate.
 */
class UdpServerPinger(private val timeoutMillis: Int = 1_500) : ServerPinger {
    override suspend fun ping(host: String, port: Int): PingResult? = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket -> exchange(socket, InetSocketAddress(InetAddress.getByName(host), port)) }
        } catch (_: IOException) {
            null // timeout, unreachable, unknown host: all just "no answer" in a list
        }
    }

    private fun exchange(socket: DatagramSocket, address: InetSocketAddress): PingResult? {
        socket.soTimeout = timeoutMillis
        val ident = Random.nextLong()
        val request = ByteBuffer.allocate(12).putInt(0).putLong(ident).array()
        val started = System.nanoTime()
        socket.send(DatagramPacket(request, request.size, address))
        val reply = ByteArray(64)
        val packet = DatagramPacket(reply, reply.size)
        while (true) {
            socket.receive(packet) // throws SocketTimeoutException, an IOException, when nothing comes
            if (packet.length < 24) continue
            val buffer = ByteBuffer.wrap(reply, 0, packet.length)
            val version = buffer.int
            if (buffer.long != ident) continue // a late reply to an earlier ping
            return PingResult(
                users = buffer.int,
                maxUsers = buffer.int,
                latencyMillis = ((System.nanoTime() - started) / 1_000_000).toInt(),
                version = "${(version ushr 16) and 0xFF}.${(version ushr 8) and 0xFF}.${version and 0xFF}",
            )
        }
    }
}
