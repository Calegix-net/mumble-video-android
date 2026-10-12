package com.calegix.mumblevideo.client.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Checks the Kotlin OCB2 against mumble-core's known-answer vectors (tools/sync-protos.sh copies them). */
class OcbCryptTest {
    private fun vectors(name: String): List<JSONObject> {
        val text = checkNotNull(javaClass.getResource("/vectors/$name.json")) { "missing $name.json" }.readText()
        val cases = JSONObject(text).getJSONArray("cases")
        return (0 until cases.length()).map { cases.getJSONObject(it) }
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private fun aes(key: ByteArray, mode: Int) = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"))
    }

    @Test
    fun `raw OCB2 matches every known answer`() {
        for (case in vectors("ocb2-aes128")) {
            val key = hex(case.getString("key"))
            val plain = hex(case.getString("plaintext"))
            val out = ByteArray(plain.size)
            val tag = ByteArray(16)
            OcbCrypt.ocbEncrypt(
                aes(
                    key,
                    Cipher.ENCRYPT_MODE,
                ),
                plain, 0, plain.size, out, 0, hex(case.getString("nonce")), tag, true,
            )
            assertArrayEquals(case.getString("name"), hex(case.getString("ciphertext")), out)
            assertArrayEquals(case.getString("name"), hex(case.getString("tag")), tag)
        }
    }

    @Test
    fun `raw OCB2 decrypts what it encrypted, except the XEX star cases`() {
        for (case in vectors("ocb2-aes128")) {
            val key = hex(case.getString("key"))
            val cipherText = hex(case.getString("ciphertext"))
            val plain = ByteArray(cipherText.size)
            val tag = ByteArray(16)
            val ok = OcbCrypt.ocbDecrypt(
                aes(key, Cipher.ENCRYPT_MODE),
                aes(key, Cipher.DECRYPT_MODE),
                cipherText,
                0,
                cipherText.size,
                plain,
                hex(case.getString("nonce")),
                tag,
            )
            assertArrayEquals(case.getString("name"), hex(case.getString("tag")), tag)
            if (!case.getString("name").startsWith("XEX") && !case.getString("name").startsWith("digital silence")) {
                assertEquals(case.getString("name"), true, ok)
                assertArrayEquals(case.getString("name"), hex(case.getString("plaintext")), plain)
            }
        }
    }

    @Test
    fun `audio datagrams match the vectors byte for byte, and decrypt on the other side`() {
        for (case in vectors("audio-datagram")) {
            val key = hex(case.getString("key"))
            val iv = hex(case.getString("encryptIv"))
            val sender = OcbCrypt().apply { setKey(key, iv, ByteArray(16)) }
            val receiver = OcbCrypt().apply { setKey(key, ByteArray(16), iv) }
            val plaintexts = case.getJSONArray("plaintexts")
            val datagrams = case.getJSONArray("datagrams")
            for (i in 0 until plaintexts.length()) {
                val plain = hex(plaintexts.getString(i))
                val expected = hex(datagrams.getString(i))
                // The vectors include the media channel byte (0x00 for audio) in front.
                val datagram = byteArrayOf(0) + sender.encrypt(plain)
                assertArrayEquals("${case.getString("name")} #$i", expected, datagram)
                assertArrayEquals(plain, receiver.decrypt(expected, 1))
            }
        }
    }

    @Test
    fun `replays and forgeries are rejected, late packets accepted once`() {
        val key = ByteArray(16) { it.toByte() }
        // Not all zeros: like the C++, the replay history starts zeroed, so a zero IV makes the first
        // skipped packet look like a replay. Servers send random nonces.
        val iv = ByteArray(16) { (0x5A + it).toByte() }
        val sender = OcbCrypt().apply { setKey(key, iv, ByteArray(16)) }
        val receiver = OcbCrypt().apply { setKey(key, ByteArray(16), iv) }
        val packets = (1..5).map { sender.encrypt(byteArrayOf(it.toByte(), 2, 3)) }

        assertNotNull(receiver.decrypt(packets[0]))
        assertNull("replay", receiver.decrypt(packets[0]))
        assertNotNull(receiver.decrypt(packets[2]))
        assertEquals(1L, receiver.lost)
        assertNotNull("late", receiver.decrypt(packets[1]))
        assertNull("late replay", receiver.decrypt(packets[1]))

        val forged = packets[3].copyOf().also { it[5] = (it[5] + 1).toByte() }
        assertNull("forged", receiver.decrypt(forged))
        assertNotNull(receiver.decrypt(packets[3]))
    }
}
