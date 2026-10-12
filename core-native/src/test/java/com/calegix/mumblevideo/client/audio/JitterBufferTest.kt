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
}
