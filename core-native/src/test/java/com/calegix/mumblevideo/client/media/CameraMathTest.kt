package com.calegix.mumblevideo.client.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraMathTest {
    private val sizes = listOf(1920 to 1080, 1280 to 720, 1440 to 1080, 640 to 480, 320 to 240, 176 to 144)

    @Test
    fun `picks the matching aspect closest in area, whatever the requested orientation`() {
        assertEquals(1280 to 720, CameraMath.chooseSize(sizes, 1280, 720))
        assertEquals(1280 to 720, CameraMath.chooseSize(sizes, 720, 1280))
        assertEquals(1920 to 1080, CameraMath.chooseSize(sizes, 2560, 1440))
        assertEquals(640 to 480, CameraMath.chooseSize(sizes, 800, 600))
        // No 16:9 at all: the nearest area wins.
        assertEquals(640 to 480, CameraMath.chooseSize(listOf(640 to 480, 176 to 144), 854, 480))
    }

    @Test
    fun `fps range prefers a fixed rate, then the narrowest that reaches it`() {
        val ranges = listOf(15 to 15, 7 to 30, 30 to 30, 15 to 30, 24 to 24)
        assertEquals(30 to 30, CameraMath.chooseFpsRange(ranges, 30))
        assertEquals(24 to 24, CameraMath.chooseFpsRange(ranges, 24))
        assertEquals(15 to 30, CameraMath.chooseFpsRange(listOf(7 to 30, 15 to 30), 20))
        assertEquals(15 to 30, CameraMath.chooseFpsRange(listOf(7 to 30, 15 to 30), 60))
    }

    @Test
    fun `upright rotation follows Camera2's JPEG orientation`() {
        // Typical back camera (mounted 90) held upright, and turned to landscape both ways.
        assertEquals(90, CameraMath.uprightRotation(90, displayRotation = 0, frontFacing = false))
        assertEquals(0, CameraMath.uprightRotation(90, displayRotation = 90, frontFacing = false))
        assertEquals(180, CameraMath.uprightRotation(90, displayRotation = 270, frontFacing = false))
        // Typical front camera (mounted 270).
        assertEquals(270, CameraMath.uprightRotation(270, displayRotation = 0, frontFacing = true))
        assertEquals(0, CameraMath.uprightRotation(270, displayRotation = 90, frontFacing = true))
        assertEquals(180, CameraMath.uprightRotation(270, displayRotation = 270, frontFacing = true))
    }

    @Test
    fun `sizes swap on a quarter turn and encoders get multiples of 16`() {
        assertEquals(720 to 1280, CameraMath.uprightSize(1280, 720, 90))
        assertEquals(1280 to 720, CameraMath.uprightSize(1280, 720, 180))
        assertEquals(1280 to 720, CameraMath.encoderSize(1280, 720))
        assertEquals(1072 to 1920, CameraMath.encoderSize(1080, 1920))
        assertEquals(16 to 16, CameraMath.encoderSize(10, 4))
    }

    private fun apply(m: FloatArray, x: Float, y: Float): Pair<Float, Float> =
        (m[0] * x + m[4] * y + m[12]) to (m[1] * x + m[5] * y + m[13])

    @Test
    fun `texture matrix samples counter-clockwise to show the image turned clockwise`() {
        assertArrayEquals(
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            CameraMath.texMatrix(0, mirror = false),
            1e-6f,
        )
        // Turned 90 clockwise, the output's top-left (0, 1) shows the source's bottom-left (0, 0),
        // and its top-right the source's top-left.
        val m90 = CameraMath.texMatrix(90, mirror = false)
        assertEquals(0f to 0f, apply(m90, 0f, 1f))
        assertEquals(0f to 1f, apply(m90, 1f, 1f))
        // Mirroring flips left and right of the output.
        val mirrored = CameraMath.texMatrix(0, mirror = true)
        assertEquals(1f to 0f, apply(mirrored, 0f, 0f))
        assertEquals(0f to 1f, apply(mirrored, 1f, 1f))
    }
}
