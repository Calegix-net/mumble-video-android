package com.calegix.mumblevideo.client.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {
    private fun JitterBuffer.takeFrame(): Byte? = (take() as? JitterBuffer.Next.Packet)?.opus?.get(0)

    @Test
    fun `waits for the prebuffer, then plays in frame order`() {
        val jitter = JitterBuffer(prebuffer = 2)
        jitter.put(20, byteArrayOf(2), false)
        assertEquals(JitterBuffer.Next.Idle, jitter.take())
        jitter.put(10, byteArrayOf(1), false)
        assertEquals(1.toByte(), jitter.takeFrame())
        assertEquals(2.toByte(), jitter.takeFrame())
        assertEquals(JitterBuffer.Next.Gap, jitter.take())
    }

    @Test
    fun `late and duplicate packets are dropped once playing`() {
        val jitter = JitterBuffer(prebuffer = 1)
        jitter.put(10, byteArrayOf(1), false)
        jitter.takeFrame()
        jitter.put(10, byteArrayOf(9), false)
        jitter.put(5, byteArrayOf(9), false)
        assertEquals(0, jitter.size())
    }

    @Test
    fun `a terminator ends the talk spurt and resets ordering`() {
        val jitter = JitterBuffer(prebuffer = 3)
        jitter.put(10, byteArrayOf(1), terminator = true)
        // A lone final packet plays without waiting for the prebuffer.
        assertEquals(1.toByte(), jitter.takeFrame())
        assertEquals(JitterBuffer.Next.Idle, jitter.take())
        // The sender restarted its counter: still accepted.
        jitter.put(1, byteArrayOf(7), terminator = true)
        assertEquals(7.toByte(), jitter.takeFrame())
    }

    @Test
    fun `queue growth is bounded`() {
        val jitter = JitterBuffer(prebuffer = 1, maxQueued = 4)
        for (i in 1..10L) jitter.put(i, byteArrayOf(i.toByte()), false)
        assertEquals(4, jitter.size())
        assertTrue(jitter.takeFrame() == 7.toByte())
    }

    @Test
    fun `an underrun raises the target and rebuffers before playing on`() {
        val jitter = JitterBuffer(prebuffer = 1)
        jitter.put(1, byteArrayOf(1), false)
        assertEquals(1.toByte(), jitter.takeFrame())
        // The path stalls: underrun, and the next packet alone is not enough to resume.
        assertEquals(JitterBuffer.Next.Gap, jitter.take())
        assertEquals(2, jitter.target)
        jitter.put(2, byteArrayOf(2), false)
        assertEquals(JitterBuffer.Next.Gap, jitter.take())
        jitter.put(3, byteArrayOf(3), false)
        assertEquals(2.toByte(), jitter.takeFrame())
        assertEquals(3.toByte(), jitter.takeFrame())
    }

    @Test
    fun `the target is capped and kept for the next talk spurt`() {
        val jitter = JitterBuffer(prebuffer = 1, maxPrebuffer = 3)
        // Five bursts, each followed by a stall: one step up per underrun, up to the cap.
        var frame = 0L
        repeat(5) {
            repeat(jitter.target) { jitter.put(++frame, byteArrayOf(1), false) }
            while (jitter.take() is JitterBuffer.Next.Packet) Unit
        }
        assertEquals(3, jitter.target)
        jitter.put(++frame, byteArrayOf(2), terminator = true)
        assertEquals(2.toByte(), jitter.takeFrame()) // the final packet still plays
        assertEquals(JitterBuffer.Next.Idle, jitter.take())
        jitter.put(1, byteArrayOf(4), false)
        jitter.put(2, byteArrayOf(5), false)
        assertEquals(JitterBuffer.Next.Idle, jitter.take()) // waits for 3 now
        jitter.put(3, byteArrayOf(6), false)
        assertEquals(4.toByte(), jitter.takeFrame())
    }
}
