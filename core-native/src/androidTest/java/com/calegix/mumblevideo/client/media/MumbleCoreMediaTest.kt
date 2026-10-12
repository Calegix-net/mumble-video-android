package com.calegix.mumblevideo.client.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calegix.mumblevideo.client.crypto.OcbCrypt
import com.calegix.mumblevideo.proto.MumbleProto
import com.calegix.mumblevideo.proto.MumbleUDPProto
import com.google.protobuf.ByteString
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * mumble-core through JNI, on a device. The Kotlin OcbCrypt is already checked byte for byte
 * against mumble-core's known-answer vectors on the JVM, so wherever the two must agree, it is
 * used as the reference.
 */
@RunWith(AndroidJUnit4::class)
class MumbleCoreMediaTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun vectors(name: String): List<JSONObject> {
        val text = checkNotNull(javaClass.getResource("/vectors/$name.json")).readText()
        val cases = JSONObject(text).getJSONArray("cases")
        return (0 until cases.length()).map { cases.getJSONObject(it) }
    }

    private fun cryptSetup(key: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray =
        MumbleProto.CryptSetup.newBuilder()
            .setKey(ByteString.copyFrom(key))
            .setClientNonce(ByteString.copyFrom(clientNonce))
            .setServerNonce(ByteString.copyFrom(serverNonce))
            .build()
            .toByteArray()

    private val key = ByteArray(16) { (0x10 + it).toByte() }
    private val clientNonce = ByteArray(16) { (0x40 + it).toByte() }
    private val serverNonce = ByteArray(16) { (0x80 + it).toByte() }

    private fun keyedSession() = MumbleCoreMedia().apply {
        setUdpFraming(channelByte = true)
        assertNotNull(handleCryptSetup(cryptSetup(key, clientNonce, serverNonce)))
        assertTrue(isKeyed)
    }

    @Test
    fun reportsItsVersion() {
        assertEquals("1.7.0", MumbleCoreMedia.version())
    }

    @Test
    fun decryptsTheKnownAnswerAudioDatagrams() {
        for (case in vectors("audio-datagram")) {
            val iv = hex(case.getString("encryptIv"))
            MumbleCoreMedia().use { media ->
                // The vectors are what a sender with encryptIv produces; we receive them, so that IV is
                // the server's nonce here. Their plaintexts are not audio messages, so they decrypt and
                // are then dropped: the good-packet count is what shows the decryption worked.
                media.setUdpFraming(channelByte = true)
                media.handleCryptSetup(cryptSetup(hex(case.getString("key")), ByteArray(16), iv))
                val datagrams = case.getJSONArray("datagrams")
                for (i in 0 until datagrams.length()) media.receiveDatagram(hex(datagrams.getString(i)), nowUsec = 0)
                assertEquals(case.getString("name"), datagrams.length().toLong(), media.stats().audioGood)
            }
        }
    }

    @Test
    fun encryptsAudioExactlyAsTheReferenceDoes() {
        keyedSession().use { media ->
            val receiver = OcbCrypt().apply { setKey(key, ByteArray(16), clientNonce) }
            val reference = OcbCrypt().apply { setKey(key, clientNonce, ByteArray(16)) }
            val opus = ByteArray(60) { it.toByte() }
            for (frame in 0L until 3L) {
                val datagram = checkNotNull(media.makeAudio(opus, frame * 2, target = 0, isLastFrame = frame == 2L))
                assertEquals(0.toByte(), datagram[0]) // audio channel byte
                val plain = checkNotNull(receiver.decrypt(datagram, 1))
                assertEquals(0.toByte(), plain[0]) // UDP message type: Audio
                val audio = MumbleUDPProto.Audio.parseFrom(plain.copyOfRange(1, plain.size))
                assertArrayEquals(opus, audio.opusData.toByteArray())
                assertEquals(frame * 2, audio.frameNumber)
                assertEquals(frame == 2L, audio.isTerminator)
                // Same key, same IV sequence, same plaintext: the bytes must be identical.
                assertArrayEquals(datagram.copyOfRange(1, datagram.size), reference.encrypt(plain))
            }
        }
    }

    @Test
    fun receivesAudioTheReferenceEncrypted() {
        keyedSession().use { media ->
            val server = OcbCrypt().apply { setKey(key, serverNonce, ByteArray(16)) }
            val audio = MumbleUDPProto.Audio.newBuilder()
                .setContext(1)
                .setSenderSession(42)
                .setFrameNumber(1234)
                .setOpusData(ByteString.copyFrom(byteArrayOf(1, 2, 3, 4)))
                .setIsTerminator(true)
                .build()
            val datagram = byteArrayOf(0) + server.encrypt(byteArrayOf(0) + audio.toByteArray())
            val events = media.receiveDatagram(datagram, nowUsec = 1_000)
            val packet = events.audio.single()
            assertEquals(42, packet.senderSession)
            assertEquals(1234L, packet.frameNumber)
            assertEquals(1, packet.context)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), packet.opus)
            assertTrue(packet.isLastFrame)
            assertNull(packet.position)

            // A replay is dropped.
            assertTrue(media.receiveDatagram(datagram, nowUsec = 2_000).audio.isEmpty())
        }
    }

    @Test
    fun pingsComeBackAsPongs() {
        keyedSession().use { media ->
            val receiver = OcbCrypt().apply { setKey(key, ByteArray(16), clientNonce) }
            val server = OcbCrypt().apply { setKey(key, serverNonce, ByteArray(16)) }
            val ping = checkNotNull(media.makePing(987_654_321L))
            val plain = checkNotNull(receiver.decrypt(ping, 1))
            assertEquals(1.toByte(), plain[0]) // UDP message type: Ping
            assertEquals(987_654_321L, MumbleUDPProto.Ping.parseFrom(plain.copyOfRange(1, plain.size)).timestamp)

            // The server echoes the ping back.
            val events = media.receiveDatagram(byteArrayOf(0) + server.encrypt(plain), nowUsec = 0)
            assertArrayEquals(longArrayOf(987_654_321L), events.pongs)
        }
    }

    @Test
    fun anEmptyCryptSetupAsksForOurNonce() {
        keyedSession().use { media ->
            val events = checkNotNull(media.handleCryptSetup(ByteArray(0)))
            val framed = events.controlToSend.single()
            assertEquals(15, ((framed[0].toInt() and 0xFF) shl 8) or (framed[1].toInt() and 0xFF)) // CryptSetup
        }
    }

    @Test
    fun unkeyedSessionsMakeNothingAndCloseIsFinal() {
        val media = MumbleCoreMedia()
        assertFalse(media.isKeyed)
        assertNull(media.makePing(1))
        assertTrue(media.receiveDatagram(ByteArray(20), nowUsec = 0).isEmpty)
        media.close()
        media.close()
    }
}
