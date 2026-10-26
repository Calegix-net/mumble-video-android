package com.calegix.mumblevideo.ui.session

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageBoundsTest {
    private fun le(size: Int, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

    private fun be(size: Int, fill: ByteBuffer.() -> Unit): ByteArray = ByteBuffer.allocate(size).apply(fill).array()

    private fun pngHeader(width: Int, height: Int) = be(33) {
        put(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        putInt(13)
        put("IHDR".toByteArray())
        putInt(width)
        putInt(height)
        put(byteArrayOf(1, 0, 0, 0, 0))
    }

    private fun gif(width: Int, height: Int) = le(13) {
        put("GIF89a".toByteArray())
        putShort(width.toShort())
        putShort(height.toShort())
    }

    private fun bmp(width: Int, height: Int) = le(54) {
        put("BM".toByteArray())
        position(18)
        putInt(width)
        putInt(height)
    }

    private fun jpeg(width: Int, height: Int) = be(40) {
        put(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        // An APP0 segment first, which the walk has to skip.
        put(byteArrayOf(0xFF.toByte(), 0xE0.toByte()))
        putShort(16)
        put(ByteArray(14))
        put(byteArrayOf(0xFF.toByte(), 0xC2.toByte())) // progressive start-of-frame
        putShort(17)
        put(8)
        putShort(height.toShort())
        putShort(width.toShort())
    }

    private fun webp(chunk: String, body: ByteArray) = le(30 + body.size) {
        put("RIFF".toByteArray())
        putInt(22 + body.size)
        put("WEBP".toByteArray())
        put(chunk.toByteArray())
        putInt(body.size)
        put(body)
    }

    private fun webpLossless(width: Int, height: Int) = webp(
        "VP8L",
        le(5) {
            put(0x2F)
            putInt((width - 1) or ((height - 1) shl 14))
        },
    )

    private fun webpExtended(width: Int, height: Int) = webp(
        "VP8X",
        le(10) {
            putInt(0)
            put(((width - 1) and 0xFF).toByte())
            put(((width - 1) shr 8 and 0xFF).toByte())
            put(((width - 1) shr 16 and 0xFF).toByte())
            put(((height - 1) and 0xFF).toByte())
            put(((height - 1) shr 8 and 0xFF).toByte())
            put(((height - 1) shr 16 and 0xFF).toByte())
        },
    )

    /** A complete, valid PNG: one-bit white, [side] x [side], deflated. The real attack payload. */
    private fun whitePng(side: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun chunk(type: String, data: ByteArray) {
            out.write(ByteBuffer.allocate(4).putInt(data.size).array())
            val typed = type.toByteArray() + data
            out.write(typed)
            out.write(ByteBuffer.allocate(4).putInt(CRC32().apply { update(typed) }.value.toInt()).array())
        }
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        chunk(
            "IHDR",
            be(13) {
                putInt(side)
                putInt(side)
                put(byteArrayOf(1, 0, 0, 0, 0))
            },
        )
        val idat = ByteArrayOutputStream()
        DeflaterOutputStream(idat, Deflater(9)).use { z ->
            val row = ByteArray(1 + (side + 7) / 8) { if (it == 0) 0 else 0xFF.toByte() }
            repeat(side) { z.write(row) }
        }
        chunk("IDAT", idat.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    @Test fun headersOfEveryFormatAreRead() {
        val cases = mapOf(
            "png" to pngHeader(640, 480),
            "gif" to gif(640, 480),
            "bmp" to bmp(640, -480), // bottom-up rows are a negative height
            "jpeg" to jpeg(640, 480),
            "webp lossless" to webpLossless(640, 480),
            "webp extended" to webpExtended(640, 480),
        )
        for ((name, bytes) in cases) {
            assertEquals(name, ImageBounds.Size(640, 480), ImageBounds.declaredSize(bytes))
        }
    }

    @Test fun hugeDeclaredSizesAreRefusedInEveryFormat() {
        val cases = listOf(
            pngHeader(60_000, 60_000),
            pngHeader(8000, 8000), // the desktop repro
            pngHeader(Int.MAX_VALUE, 1),
            pngHeader(-1, -1), // top bit set: huge, not negative
            gif(65_535, 65_535),
            bmp(50_000, 50_000),
            jpeg(65_535, 65_535),
            webpLossless(16_384, 16_384),
            webpExtended(16_000_000, 16_000_000),
        )
        for (bytes in cases) {
            val size = ImageBounds.declaredSize(bytes)
            assertNotNull(size)
            assertNull("$size", ImageBounds.sampleSize(size!!.width, size.height))
        }
    }

    @Test fun truncatedOrUnknownHeadersGiveNoSize() {
        assertNull(ImageBounds.declaredSize(pngHeader(1, 1).copyOf(20)))
        assertNull(ImageBounds.declaredSize(jpeg(1, 1).copyOf(10)))
        assertNull(ImageBounds.declaredSize(byteArrayOf(1, 2, 3)))
        assertNull(ImageBounds.declaredSize(ByteArray(0)))
    }

    @Test fun ordinaryPicturesAreDecodedScaledWithinTheBudget() {
        // Phone photo 4032 x 3024 (12 MP), a 1440p screenshot, a tall screenshot, a small icon.
        for ((w, h) in listOf(4032 to 3024, 2560 to 1440, 1080 to 2400, 64 to 64, 6000 to 6000, 20_000 to 2000)) {
            val sample = ImageBounds.sampleSize(w, h) ?: error("$w x $h refused")
            assertEquals(0, sample and (sample - 1)) // a power of two
            val dw = w.toLong() / sample
            val dh = h.toLong() / sample
            assertTrue("$w x $h / $sample", dw <= ImageBounds.MAX_DECODED_SIDE && dh <= ImageBounds.MAX_DECODED_SIDE)
            assertTrue(dw * dh <= ImageBounds.MAX_DECODED_PIXELS)
            // and not shrunk further than needed
            if (sample > 1) {
                val bigger = (w.toLong() / (sample / 2)) to (h.toLong() / (sample / 2))
                assertTrue(
                    bigger.first > ImageBounds.MAX_DECODED_SIDE ||
                        bigger.second > ImageBounds.MAX_DECODED_SIDE ||
                        bigger.first * bigger.second > ImageBounds.MAX_DECODED_PIXELS,
                )
            }
        }
        assertEquals(1, ImageBounds.sampleSize(1920, 1080))
        assertEquals(2, ImageBounds.sampleSize(4032, 3024))
    }

    @Test fun nonsenseSizesAreRefused() {
        assertNull(ImageBounds.sampleSize(0, 100))
        assertNull(ImageBounds.sampleSize(100, -1))
        assertNull(ImageBounds.sampleSize(20_001, 10))
        assertNull(ImageBounds.sampleSize(8000, 8000)) // 64 MP
        assertNull(ImageBounds.sampleSize(20_000, 2001))
    }

    @Test fun theCrashMessageIsRefusedBeforeAnyPixelIsAllocated() {
        // The reported attack: a few kilobytes of white PNG declaring 40000 x 40000 (6.4 GB as ARGB).
        val png = whitePng(40_000)
        val b64 = java.util.Base64.getEncoder().encodeToString(png)
        assertTrue("payload ${b64.length} chars", b64.length < ImageBounds.MAX_ENCODED_CHARS)
        val image = ChatImages.split("""<img src="data:image/png;base64,$b64" width="999999" height="999999">""")
            .single() as ChatImages.Part.Image
        val size = ImageBounds.declaredSize(image.bytes)
        assertEquals(ImageBounds.Size(40_000, 40_000), size)
        assertNull(ImageBounds.sampleSize(size!!.width, size.height))
    }
}
