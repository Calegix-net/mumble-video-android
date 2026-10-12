package com.calegix.mumblevideo.client.media

import kotlin.math.abs
import kotlin.math.ln

/**
 * The arithmetic behind camera capture, kept free of Android types so it can be tested on the JVM:
 * which camera size to capture at, which frame-rate range to ask for, how far to rotate the sensor
 * image to make it upright, and what size the encoder gets.
 */
internal object CameraMath {

    /**
     * The supported size closest to the requested one: same aspect ratio first (within 2 %), then
     * the smallest difference in area (on a log scale, so 2x too big and 2x too small rank alike).
     * Sizes are compared regardless of orientation; cameras list landscape sizes.
     */
    fun chooseSize(supported: List<Pair<Int, Int>>, width: Int, height: Int): Pair<Int, Int> {
        require(supported.isNotEmpty()) { "no supported sizes" }
        val wantLong = maxOf(width, height)
        val wantShort = minOf(width, height)
        val wantAspect = wantLong.toDouble() / wantShort
        val wantArea = wantLong.toDouble() * wantShort
        return supported.minWith(
            compareBy<Pair<Int, Int>> { (w, h) ->
                val aspect = maxOf(w, h).toDouble() / minOf(w, h)
                if (abs(aspect / wantAspect - 1) <= 0.02) 0 else 1
            }.thenBy { (w, h) -> abs(ln(w.toDouble() * h / wantArea)) },
        )
    }

    /**
     * The AE target frame-rate range for [framerate]: a fixed range at exactly that rate if the
     * camera has one, else the narrowest range whose top reaches it, else the fastest it has.
     */
    fun chooseFpsRange(ranges: List<Pair<Int, Int>>, framerate: Int): Pair<Int, Int> {
        require(ranges.isNotEmpty()) { "no fps ranges" }
        ranges.firstOrNull { it.first == framerate && it.second == framerate }?.let { return it }
        val reaching = ranges.filter { it.second >= framerate }
        if (reaching.isNotEmpty()) {
            return reaching.minWith(compareBy<Pair<Int, Int>> { it.second }.thenByDescending { it.first })
        }
        return ranges.maxWith(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
    }

    /**
     * Clockwise degrees to rotate the camera's image so it is upright on screen, as Camera2's JPEG
     * orientation: the sensor's mounting, corrected for how the device is held. [displayRotation] is
     * Display.getRotation() in degrees (0, 90, 180, 270). The result is the unmirrored image, which
     * is what others should see, for front cameras too.
     */
    fun uprightRotation(sensorOrientation: Int, displayRotation: Int, frontFacing: Boolean): Int {
        // Display rotation is counter to how the device was turned.
        val deviceOrientation = (360 - displayRotation) % 360
        val signed = if (frontFacing) -deviceOrientation else deviceOrientation
        return ((sensorOrientation + signed) % 360 + 360) % 360
    }

    /** Size of the upright image: a quarter turn swaps the sides. */
    fun uprightSize(width: Int, height: Int, rotation: Int): Pair<Int, Int> =
        if (rotation % 180 == 0) width to height else height to width

    /**
     * What the encoder is configured with: both sides rounded down to a multiple of 16, which every
     * hardware encoder accepts and some require. The GL pass scales into it, so nothing is cropped.
     */
    fun encoderSize(width: Int, height: Int): Pair<Int, Int> =
        maxOf(16, width / 16 * 16) to maxOf(16, height / 16 * 16)

    /**
     * Column-major 4x4 matrix for texture coordinates that rotates the sampled image clockwise by
     * [rotation] degrees about its centre, and mirrors it horizontally first if [mirror]. Multiply
     * the SurfaceTexture transform by this (ST x this) in the shader.
     *
     * An output point p samples the source at R(-rotation) p: to show the image turned clockwise,
     * look up each output pixel counter-clockwise.
     */
    fun texMatrix(rotation: Int, mirror: Boolean): FloatArray {
        // Counter-clockwise quarter turns, applied in texture space about (0.5, 0.5).
        val (c, s) = when (((rotation % 360) + 360) % 360) {
            0 -> 1f to 0f
            90 -> 0f to 1f
            180 -> -1f to 0f
            270 -> 0f to -1f
            else -> throw IllegalArgumentException("rotation must be a multiple of 90, was $rotation")
        }
        val m = if (mirror) -1f else 1f
        // M = T(0.5) * R(ccw) * S(m, 1) * T(-0.5)
        //   x' = c*m*(x-0.5) - s*(y-0.5) + 0.5
        //   y' = s*m*(x-0.5) + c*(y-0.5) + 0.5
        val a = c * m
        val b = -s
        val d = s * m
        val e = c
        val tx = 0.5f - 0.5f * a - 0.5f * b
        val ty = 0.5f - 0.5f * d - 0.5f * e
        return floatArrayOf(
            a, d, 0f, 0f,
            b, e, 0f, 0f,
            0f, 0f, 1f, 0f,
            tx, ty, 0f, 1f,
        )
    }
}
