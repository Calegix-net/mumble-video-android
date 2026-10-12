package com.calegix.mumblevideo.client.media

/**
 * Frames per second over one-second windows. [tick] returns a rate when a window closes, and 0 on
 * the very first frame so a subscription shows as active straight away.
 */
internal class FrameCounter {
    private var windowStart = -1L
    private var frames = 0
    private var reported = false

    fun tick(nowMillis: Long): Float? {
        if (windowStart < 0) windowStart = nowMillis
        frames++
        val elapsed = nowMillis - windowStart
        if (elapsed < 1_000) {
            if (reported) return null
            reported = true
            return 0f
        }
        val fps = frames * 1_000f / elapsed
        windowStart = nowMillis
        frames = 0
        return fps
    }
}
