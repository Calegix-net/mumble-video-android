package com.calegix.mumblevideo.client.media

import android.content.Context
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.OrientationEventListener

/**
 * How the phone is physically held, from the accelerometer: 0, 90, 180 or 270 degrees clockwise
 * from its natural orientation. Independent of the display, so it works with auto-rotate off and
 * with the app in the background, which is what a camera stream needs.
 *
 * A new orientation is reported only once the phone is clearly in it (within [MARGIN] of the
 * quarter turn) and has stayed there for [SETTLE_MS], so holding it near 45° does not flap.
 */
internal class OrientationTracker(context: Context, private val onChange: (Int) -> Unit) {
    @Volatile var orientation: Int = 0
        private set

    private var candidate = -1
    private var candidateSince = 0L

    private val listener = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
        override fun onOrientationChanged(degrees: Int) {
            if (degrees == ORIENTATION_UNKNOWN) return // flat on a table: keep what we have
            val quantised = quantise(degrees, orientation) ?: return
            val now = SystemClock.elapsedRealtime()
            if (quantised == orientation) {
                candidate = -1
                return
            }
            if (quantised != candidate) {
                candidate = quantised
                candidateSince = now
                return
            }
            if (now - candidateSince >= SETTLE_MS) {
                orientation = quantised
                candidate = -1
                onChange(quantised)
            }
        }
    }

    fun start(initial: Int) {
        orientation = initial
        if (listener.canDetectOrientation()) listener.enable()
    }

    fun stop() = listener.disable()

    companion object {
        private const val MARGIN = 30
        private const val SETTLE_MS = 600L

        /** The quarter turn [degrees] is clearly in, or null while between two (hysteresis). */
        internal fun quantise(degrees: Int, current: Int): Int? {
            val nearest = ((degrees + 45) / 90 % 4) * 90
            if (nearest == current) return current
            val distance = minOf((degrees - nearest + 360) % 360, (nearest - degrees + 360) % 360)
            return if (distance <= MARGIN) nearest else null
        }
    }
}
