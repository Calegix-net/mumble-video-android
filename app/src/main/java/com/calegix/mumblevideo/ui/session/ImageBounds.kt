package com.calegix.mumblevideo.ui.session

/**
 * Limits for pictures received in chat, checked before anything is allocated for the pixels. A
 * chat picture is a base64 `<img>` anyone in the channel can send, and a tiny file can declare an
 * enormous size (a white PNG of 60000 x 60000 compresses to a few kilobytes but decodes to 14 GB),
 * so the declared size is read from the file header first and the picture is refused, or decoded
 * scaled down, from that.
 */
object ImageBounds {
    /** Longest base64 payload decoded at all, about 768 KB of picture. Larger ones are not decoded. */
    const val MAX_ENCODED_CHARS = 1_048_576

    /**
     * Pictures declaring more than this many source pixels are refused outright. Subsampling does
     * not help every format: a progressive JPEG decoder holds the whole coefficient array, about
     * 3 bytes a source pixel (120 MB here), whatever the output size.
     */
    const val MAX_SOURCE_PIXELS = 40_000_000L

    /** Pictures declaring a side longer than this are refused outright. */
    const val MAX_SOURCE_SIDE = 20_000

    /** Decoded pictures are scaled down until no side is longer than this... */
    const val MAX_DECODED_SIDE = 2048

    /** ...and they hold no more pixels than this (16 MB at 4 bytes a pixel). */
    const val MAX_DECODED_PIXELS = 4_194_304L

    data class Size(val width: Int, val height: Int)

    /**
     * The power-of-two subsampling (BitmapFactory's inSampleSize) that brings a picture of the
     * given source size within the decode budget, or null if it must not be decoded at all.
     */
    fun sampleSize(width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0) return null
        if (width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE) return null
        if (width.toLong() * height > MAX_SOURCE_PIXELS) return null
        var sample = 1
        while (true) {
            val w = width.toLong() / sample
            val h = height.toLong() / sample
            if (w <= MAX_DECODED_SIDE && h <= MAX_DECODED_SIDE && w * h <= MAX_DECODED_PIXELS) return sample
            sample *= 2
        }
    }

    /**
     * The size declared in the header of a PNG, GIF, JPEG, WebP or BMP, read without decoding, or
     * null if the format is not one of those or the header is cut short. The platform decoder's
     * own bounds pass is still the final word; this is the check that does not depend on it.
     */
    fun declaredSize(bytes: ByteArray): Size? = runCatching {
        when {
            isPng(bytes) -> Size(be32(bytes, 16), be32(bytes, 20))
            isGif(bytes) -> Size(le16(bytes, 6), le16(bytes, 8))
            isBmp(bytes) -> Size(le32(bytes, 18), Math.abs(le32(bytes, 22)))
            isWebp(bytes) -> webpSize(bytes)
            isJpeg(bytes) -> jpegSize(bytes)
            else -> null
        }
    }.getOrNull()

    private fun isPng(b: ByteArray) = b.size >= 24 &&
        b[0] == 0x89.toByte() &&
        b[1] == 'P'.code.toByte() &&
        b[2] == 'N'.code.toByte() &&
        b[3] == 'G'.code.toByte()

    private fun isGif(b: ByteArray) = b.size >= 10 && ascii(b, 0, 4) == "GIF8"

    private fun isBmp(b: ByteArray) = b.size >= 26 && ascii(b, 0, 2) == "BM"

    private fun isWebp(b: ByteArray) = b.size >= 30 && ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP"

    private fun isJpeg(b: ByteArray) = b.size >= 4 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8

    private fun webpSize(b: ByteArray): Size? = when (ascii(b, 12, 4)) {
        "VP8 " -> Size(le16(b, 26) and 0x3FFF, le16(b, 28) and 0x3FFF)

        "VP8L" -> {
            val bits = le32(b, 21)
            Size((bits and 0x3FFF) + 1, ((bits ushr 14) and 0x3FFF) + 1)
        }

        "VP8X" -> Size(le24(b, 24) + 1, le24(b, 27) + 1)

        else -> null
    }

    /** Walks the marker segments to the first start-of-frame, which holds the size. */
    private fun jpegSize(b: ByteArray): Size? {
        var i = 2
        while (i + 9 < b.size) {
            if (u8(b, i) != 0xFF) return null
            val marker = u8(b, i + 1)
            if (marker == 0xFF) {
                i++
                continue
            }
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) {
                i += 2
                continue
            }
            val sof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (sof) return Size(be16(b, i + 7), be16(b, i + 5))
            val length = be16(b, i + 2)
            if (length < 2) return null
            i += 2 + length
        }
        return null
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun ascii(b: ByteArray, at: Int, n: Int) = String(b, at, n, Charsets.ISO_8859_1)

    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)

    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)

    private fun le24(b: ByteArray, i: Int) = le16(b, i) or (u8(b, i + 2) shl 16)

    private fun le32(b: ByteArray, i: Int) = le24(b, i) or (u8(b, i + 3) shl 24)

    /** Clamped to Int.MAX_VALUE so a size with the top bit set reads as huge, not negative. */
    private fun be32(b: ByteArray, i: Int): Int {
        val v = (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or
            (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()
        return minOf(v, Int.MAX_VALUE.toLong()).toInt()
    }
}
