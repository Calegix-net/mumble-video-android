package com.calegix.mumblevideo.client.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JVM half of the JNI event layout; the native half is exercised by the instrumented test. */
class MediaEventsCodecTest {
    private fun layout(build: ByteBuffer.() -> Unit): ByteArray {
        val b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        b.build()
        return b.array().copyOf(b.position())
    }

    private fun ByteBuffer.blob(bytes: ByteArray) {
        putInt(bytes.size)
        put(bytes)
    }

    @Test
    fun `null means no events`() {
        assertTrue(MediaEventsCodec.decode(null).isEmpty)
    }

    @Test
    fun `every list round-trips`() {
        val bytes = layout {
            putInt(1) // audio
            putInt(7).putLong(99).putInt(2).put(3).putFloat(1f).putFloat(2f).putFloat(3f).putFloat(0.5f)
            blob(byteArrayOf(9, 8))
            putInt(1) // video
            putInt(7).putInt(4).putLong(1000).putInt(2).put(3).putLong(55).putInt(10).putInt(20).putInt(640).putInt(360)
            blob(byteArrayOf(1, 2, 3))
            putInt(2).putLong(11).putLong(12) // pongs
            putInt(1)
            blob(byteArrayOf(5)) // nacks
            putInt(0) // datagrams
            putInt(1)
            blob(byteArrayOf(0, 15, 0, 0, 0, 0)) // control
        }
        val events = MediaEventsCodec.decode(bytes)
        val audio = events.audio.single()
        assertEquals(7, audio.senderSession)
        assertEquals(99L, audio.frameNumber)
        assertEquals(2, audio.context)
        assertTrue(audio.isLastFrame)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), audio.position, 0f)
        assertEquals(0.5f, audio.volumeFactor)
        assertArrayEquals(byteArrayOf(9, 8), audio.opus)
        val unit = events.video.single()
        assertEquals(4, unit.streamId)
        assertTrue(unit.isKeyframe && unit.isFrameEnd)
        assertEquals(640, unit.width)
        assertArrayEquals(byteArrayOf(1, 2, 3), unit.payload)
        assertArrayEquals(longArrayOf(11, 12), events.pongs)
        assertEquals(1, events.videoNacksForUs.size)
        assertTrue(events.datagramsToSend.isEmpty())
        assertEquals(6, events.controlToSend.single().size)
    }

    @Test
    fun `position is absent unless flagged`() {
        val bytes = layout {
            putInt(1)
            putInt(1).putLong(1).putInt(0).put(0).putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f)
            blob(byteArrayOf())
            repeat(5) { putInt(0) }
        }
        assertNull(MediaEventsCodec.decode(bytes).audio.single().position)
    }

    @Test(expected = IllegalStateException::class)
    fun `a corrupt count is rejected rather than over-allocated`() {
        MediaEventsCodec.decode(layout { putInt(Int.MAX_VALUE) })
    }
}
