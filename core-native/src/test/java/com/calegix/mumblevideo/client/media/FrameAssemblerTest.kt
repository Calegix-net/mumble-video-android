package com.calegix.mumblevideo.client.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAssemblerTest {
    private fun unit(
        frame: Long,
        key: Boolean,
        payload: ByteArray = byteArrayOf(frame.toByte()),
        x: Int = 0,
        y: Int = 0,
    ) =
        CoreVideoUnit(7, 1, frame, 0, key, true, frame * 1000, x, y, 640, 360, payload)

    @Test
    fun `nothing is decoded before the first keyframe`() {
        val a = FrameAssembler(joinsUnits = false)
        assertEquals(FrameAssembler.Result.Dropped, a.push(unit(1, key = false)))
        val frame = a.push(unit(2, key = true)) as FrameAssembler.Result.Frame
        assertTrue(frame.isKeyframe)
        assertEquals(640, frame.width)
        assertTrue(a.push(unit(3, key = false)) is FrameAssembler.Result.Frame)
    }

    @Test
    fun `a lost frame asks once for a keyframe and drops until it comes`() {
        val a = FrameAssembler(joinsUnits = false)
        a.push(unit(1, key = true))
        assertEquals(FrameAssembler.Result.NeedKeyframe, a.push(unit(3, key = false)))
        assertEquals(FrameAssembler.Result.Dropped, a.push(unit(4, key = false)))
        assertTrue(a.push(unit(5, key = true)) is FrameAssembler.Result.Frame)
    }

    @Test
    fun `stale and duplicate frames are dropped`() {
        val a = FrameAssembler(joinsUnits = false)
        a.push(unit(5, key = true))
        assertEquals(FrameAssembler.Result.Dropped, a.push(unit(5, key = false)))
        assertEquals(FrameAssembler.Result.Dropped, a.push(unit(4, key = true)))
    }

    @Test
    fun `H264 units are joined in order whatever order they arrive in`() {
        val a = FrameAssembler(joinsUnits = true)
        assertEquals(FrameAssembler.Result.Pending, a.push(unit(1, key = true, byteArrayOf(3), x = 2, y = 3)))
        assertEquals(FrameAssembler.Result.Pending, a.push(unit(1, key = true, byteArrayOf(1), x = 0, y = 3)))
        val frame = a.push(unit(1, key = true, byteArrayOf(2), x = 1, y = 3)) as FrameAssembler.Result.Frame
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.data)
    }

    @Test
    fun `an incomplete H264 frame counts as lost`() {
        val a = FrameAssembler(joinsUnits = true)
        a.push(unit(1, key = true, x = 0, y = 1))
        a.push(unit(2, key = false, x = 0, y = 2)) // its second unit never arrives
        assertEquals(FrameAssembler.Result.NeedKeyframe, a.push(unit(3, key = false, x = 0, y = 1)))
    }

    @Test
    fun `wire unit counts are bounded`() {
        val a = FrameAssembler(joinsUnits = true)
        assertEquals(FrameAssembler.Result.Dropped, a.push(unit(1, key = true, x = 0, y = 100_000)))
        assertEquals(FrameAssembler.Result.Dropped, a.push(unit(1, key = true, x = 5, y = 2)))
    }
}
