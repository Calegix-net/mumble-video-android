package com.calegix.mumblevideo.client.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Mumble's UDP audio crypto: OCB2-AES128 with a rolling 16-byte IV, as CryptStateOCB2 in mumble-core.
 *
 * Datagram layout (after the media channel byte): IV low byte, the first three tag bytes, ciphertext.
 * Each side increments its own encrypt IV before every packet, little-endian. The decrypt side
 * tolerates loss and reordering within a window and rejects replays.
 *
 * Includes the XEX* countermeasure of eprint 2019/311 section 9, exactly as the C++ does, because
 * the ciphertext of affected packets differs and the known-answer vectors pin it.
 *
 * Not thread safe: one sender and one receiver thread may each use their own half (encrypt or
 * decrypt), which share only the immutable key.
 */
class OcbCrypt {
    private var key: ByteArray? = null
    private val encryptIv = ByteArray(BLOCK)
    private val decryptIv = ByteArray(BLOCK)
    private val decryptHistory = ByteArray(256)

    // Separate cipher instances per direction, so the two halves can run on different threads.
    private var encCipherForEncrypt: Cipher? = null
    private var encCipherForDecrypt: Cipher? = null
    private var decCipher: Cipher? = null

    var good = 0L
        private set
    var late = 0L
        private set
    var lost = 0L
        private set

    val isValid: Boolean get() = key != null

    fun setKey(key: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray) {
        require(key.size == BLOCK && clientNonce.size == BLOCK && serverNonce.size == BLOCK) { "bad key material" }
        this.key = key.copyOf()
        clientNonce.copyInto(encryptIv)
        serverNonce.copyInto(decryptIv)
        decryptHistory.fill(0)
        val spec = SecretKeySpec(key, "AES")
        encCipherForEncrypt = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, spec) }
        encCipherForDecrypt = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, spec) }
        decCipher = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, spec) }
    }

    /** The server sent only its nonce: a resync after we failed to decrypt for a while. */
    fun setDecryptIv(serverNonce: ByteArray) {
        require(serverNonce.size == BLOCK)
        serverNonce.copyInto(decryptIv)
    }

    fun encryptIv(): ByteArray = encryptIv.copyOf()

    /** Encrypts one datagram payload. Returns IV byte + 3 tag bytes + ciphertext. */
    fun encrypt(plain: ByteArray, offset: Int = 0, length: Int = plain.size - offset): ByteArray {
        val cipher = checkNotNull(encCipherForEncrypt) { "not keyed" }
        for (i in 0 until BLOCK) {
            encryptIv[i] = (encryptIv[i] + 1).toByte()
            if (encryptIv[i] != 0.toByte()) break
        }
        val out = ByteArray(length + 4)
        val tag = ByteArray(BLOCK)
        ocbEncrypt(cipher, plain, offset, length, out, 4, encryptIv, tag, modifyPlainOnXexStarAttack = true)
        out[0] = encryptIv[0]
        out[1] = tag[0]
        out[2] = tag[1]
        out[3] = tag[2]
        return out
    }

    /** Decrypts one datagram payload, or returns null if it is forged, replayed or too old. */
    fun decrypt(source: ByteArray, offset: Int = 0, length: Int = source.size - offset): ByteArray? {
        val enc = encCipherForDecrypt ?: return null
        val dec = decCipher ?: return null
        if (length < 4) return null
        val plainLength = length - 4
        val saved = decryptIv.copyOf()
        val ivByte = source[offset].toInt() and 0xFF
        val current = decryptIv[0].toInt() and 0xFF
        var restore = false
        var lateDelta = 0
        var lostDelta = 0

        if (((current + 1) and 0xFF) == ivByte) {
            // In order, as expected.
            if (ivByte > current) {
                decryptIv[0] = ivByte.toByte()
            } else if (ivByte < current) {
                decryptIv[0] = ivByte.toByte()
                incrementFrom(decryptIv, 1)
            } else {
                return null
            }
        } else {
            // Out of order, or a repeat.
            var diff = ivByte - current
            if (diff > 128) {
                diff -= 256
            } else if (diff < -128) {
                diff += 256
            }

            when {
                ivByte < current && diff > -30 && diff < 0 -> {
                    // Late, no wrap.
                    lateDelta = 1
                    lostDelta = -1
                    decryptIv[0] = ivByte.toByte()
                    restore = true
                }

                ivByte > current && diff > -30 && diff < 0 -> {
                    // Late, from before a wrap of the low byte.
                    lateDelta = 1
                    lostDelta = -1
                    decryptIv[0] = ivByte.toByte()
                    decrementFrom(decryptIv, 1)
                    restore = true
                }

                ivByte > current && diff > 0 -> {
                    lostDelta = ivByte - current - 1
                    decryptIv[0] = ivByte.toByte()
                }

                ivByte < current && diff > 0 -> {
                    lostDelta = 256 - current + ivByte - 1
                    decryptIv[0] = ivByte.toByte()
                    incrementFrom(decryptIv, 1)
                }

                else -> return null
            }

            if (decryptHistory[decryptIv[0].toInt() and 0xFF] == decryptIv[1]) {
                saved.copyInto(decryptIv)
                return null
            }
        }

        val plain = ByteArray(plainLength)
        val tag = ByteArray(BLOCK)
        val ok = ocbDecrypt(enc, dec, source, offset + 4, plainLength, plain, decryptIv, tag)
        if (!ok || tag[0] != source[offset + 1] || tag[1] != source[offset + 2] || tag[2] != source[offset + 3]) {
            saved.copyInto(decryptIv)
            return null
        }
        decryptHistory[decryptIv[0].toInt() and 0xFF] = decryptIv[1]
        if (restore) saved.copyInto(decryptIv)

        good++
        late = (late + lateDelta).coerceAtLeast(0)
        lost = (lost + lostDelta).coerceAtLeast(0)
        return plain
    }

    companion object {
        const val BLOCK = 16

        /** Raw OCB2 encryption, exposed for the known-answer tests. Returns false on the XEX* case if not modifying. */
        internal fun ocbEncrypt(
            aes: Cipher,
            plain: ByteArray,
            plainOffset: Int,
            length: Int,
            out: ByteArray,
            outOffset: Int,
            nonce: ByteArray,
            tag: ByteArray,
            modifyPlainOnXexStarAttack: Boolean,
        ): Boolean {
            var success = true
            val delta = aesBlock(aes, nonce, 0)
            val checksum = ByteArray(BLOCK)
            val tmp = ByteArray(BLOCK)
            var len = length
            var p = plainOffset
            var o = outOffset

            while (len > BLOCK) {
                var flipABit = false
                if (len - BLOCK <= BLOCK) {
                    var sum = 0
                    for (i in 0 until BLOCK - 1) sum = sum or plain[p + i].toInt()
                    if (sum == 0) {
                        if (modifyPlainOnXexStarAttack) flipABit = true else success = false
                    }
                }
                s2(delta)
                for (i in 0 until BLOCK) tmp[i] = (delta[i].toInt() xor plain[p + i].toInt()).toByte()
                if (flipABit) tmp[0] = (tmp[0].toInt() xor 1).toByte()
                aesInPlace(aes, tmp)
                for (i in 0 until BLOCK) out[o + i] = (delta[i].toInt() xor tmp[i].toInt()).toByte()
                for (i in 0 until BLOCK) checksum[i] = (checksum[i].toInt() xor plain[p + i].toInt()).toByte()
                if (flipABit) checksum[0] = (checksum[0].toInt() xor 1).toByte()
                len -= BLOCK
                p += BLOCK
                o += BLOCK
            }

            s2(delta)
            tmp.fill(0)
            putLengthBits(tmp, len)
            for (i in 0 until BLOCK) tmp[i] = (tmp[i].toInt() xor delta[i].toInt()).toByte()
            val pad = aesBlock(aes, tmp, 0)
            plain.copyInto(tmp, 0, p, p + len)
            pad.copyInto(tmp, len, len, BLOCK)
            for (i in 0 until BLOCK) checksum[i] = (checksum[i].toInt() xor tmp[i].toInt()).toByte()
            for (i in 0 until BLOCK) tmp[i] = (pad[i].toInt() xor tmp[i].toInt()).toByte()
            tmp.copyInto(out, o, 0, len)

            s3(delta)
            for (i in 0 until BLOCK) tmp[i] = (delta[i].toInt() xor checksum[i].toInt()).toByte()
            aesBlock(aes, tmp, 0).copyInto(tag)
            return success
        }

        internal fun ocbDecrypt(
            aesEnc: Cipher,
            aesDec: Cipher,
            encrypted: ByteArray,
            encOffset: Int,
            length: Int,
            plain: ByteArray,
            nonce: ByteArray,
            tag: ByteArray,
        ): Boolean {
            var success = true
            val delta = aesBlock(aesEnc, nonce, 0)
            val checksum = ByteArray(BLOCK)
            val tmp = ByteArray(BLOCK)
            var len = length
            var e = encOffset
            var p = 0

            while (len > BLOCK) {
                s2(delta)
                for (i in 0 until BLOCK) tmp[i] = (delta[i].toInt() xor encrypted[e + i].toInt()).toByte()
                aesInPlace(aesDec, tmp)
                for (i in 0 until BLOCK) plain[p + i] = (delta[i].toInt() xor tmp[i].toInt()).toByte()
                for (i in 0 until BLOCK) checksum[i] = (checksum[i].toInt() xor plain[p + i].toInt()).toByte()
                len -= BLOCK
                e += BLOCK
                p += BLOCK
            }

            s2(delta)
            tmp.fill(0)
            putLengthBits(tmp, len)
            for (i in 0 until BLOCK) tmp[i] = (tmp[i].toInt() xor delta[i].toInt()).toByte()
            val pad = aesBlock(aesEnc, tmp, 0)
            tmp.fill(0)
            encrypted.copyInto(tmp, 0, e, e + len)
            for (i in 0 until BLOCK) tmp[i] = (tmp[i].toInt() xor pad[i].toInt()).toByte()
            for (i in 0 until BLOCK) checksum[i] = (checksum[i].toInt() xor tmp[i].toInt()).toByte()
            tmp.copyInto(plain, p, 0, len)

            // XEX* countermeasure: the decrypted last block must not equal delta in its first 15 bytes.
            var same = true
            for (i in 0 until BLOCK - 1) if (tmp[i] != delta[i]) same = false
            if (same) success = false

            s3(delta)
            for (i in 0 until BLOCK) tmp[i] = (delta[i].toInt() xor checksum[i].toInt()).toByte()
            aesBlock(aesEnc, tmp, 0).copyInto(tag)
            return success
        }

        /** The last block's length in bits, big-endian in the final 32 bits, as the C++ writes it. */
        private fun putLengthBits(block: ByteArray, len: Int) {
            val bits = len * 8
            block[12] = (bits ushr 24).toByte()
            block[13] = (bits ushr 16).toByte()
            block[14] = (bits ushr 8).toByte()
            block[15] = bits.toByte()
        }

        /** Doubling in GF(2^128), big-endian, as S2 in the C++. */
        private fun s2(block: ByteArray) {
            val carry = (block[0].toInt() ushr 7) and 1
            for (i in 0 until BLOCK - 1) {
                block[i] = ((block[i].toInt() shl 1) or ((block[i + 1].toInt() ushr 7) and 1)).toByte()
            }
            block[BLOCK - 1] = ((block[BLOCK - 1].toInt() shl 1) xor (carry * 0x87)).toByte()
        }

        /** Tripling: x xor 2x, as S3 in the C++. */
        private fun s3(block: ByteArray) {
            val doubled = block.copyOf()
            s2(doubled)
            for (i in 0 until BLOCK) block[i] = (block[i].toInt() xor doubled[i].toInt()).toByte()
        }

        private fun aesBlock(cipher: Cipher, input: ByteArray, offset: Int): ByteArray {
            val out = ByteArray(BLOCK)
            cipher.update(input, offset, BLOCK, out, 0)
            return out
        }

        private fun aesInPlace(cipher: Cipher, block: ByteArray) {
            aesBlock(cipher, block, 0).copyInto(block)
        }

        private fun incrementFrom(iv: ByteArray, start: Int) {
            for (i in start until BLOCK) {
                iv[i] = (iv[i] + 1).toByte()
                if (iv[i] != 0.toByte()) break
            }
        }

        private fun decrementFrom(iv: ByteArray, start: Int) {
            for (i in start until BLOCK) {
                val before = iv[i]
                iv[i] = (iv[i] - 1).toByte()
                if (before != 0.toByte()) break
            }
        }
    }
}
