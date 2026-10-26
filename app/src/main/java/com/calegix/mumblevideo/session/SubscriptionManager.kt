package com.calegix.mumblevideo.session

import android.view.Surface
import com.calegix.mumblevideo.core.MumbleCore
import com.calegix.mumblevideo.core.MumbleCoreException
import com.calegix.mumblevideo.core.StreamKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reference-counts video subscriptions across screens, so that going from a grid tile to the
 * fullscreen view of the same stream does not unsubscribe and resubscribe (which would cost a
 * keyframe round trip). A stream is unsubscribed [lingerMillis] after its last holder lets go.
 *
 * Everything runs on the caller's thread; callers are UI code on the main thread.
 */
class SubscriptionManager(
    private val core: MumbleCore,
    private val scope: CoroutineScope,
    private val lingerMillis: Long = 1_500,
) {
    private class Entry(var holders: Int, var bandwidth: Int, var release: Job? = null)

    private val entries = mutableMapOf<StreamKey, Entry>()
    private val surfaces = mutableMapOf<StreamKey, Surface>()

    /** Starts (or keeps) a subscription. [bandwidth] is the preferred ceiling, 0 for none. */
    fun acquire(stream: StreamKey, bandwidth: Int = 0) {
        val entry = entries[stream]
        if (entry == null) {
            entries[stream] = Entry(holders = 1, bandwidth = bandwidth)
            scope.launch { subscribe(stream, bandwidth) }
            return
        }
        entry.release?.cancel()
        entry.release = null
        entry.holders++
        // The most demanding holder wins: fullscreen (0 = no cap) beats a thumbnail.
        val wanted = if (entry.bandwidth == 0 || bandwidth == 0) 0 else maxOf(entry.bandwidth, bandwidth)
        if (wanted != entry.bandwidth) {
            entry.bandwidth = wanted
            scope.launch { subscribe(stream, wanted) }
        }
    }

    fun release(stream: StreamKey) {
        val entry = entries[stream] ?: return
        entry.holders--
        if (entry.holders > 0) return
        entry.release = scope.launch {
            delay(lingerMillis)
            entries.remove(stream)
            runCatching { core.unsubscribe(stream) }
        }
    }

    /**
     * The core renders a stream into one surface at a time. During a navigation transition two
     * views can briefly show the same stream (grid tile and fullscreen), and the old one's
     * surfaceDestroyed arrives after the new one's surfaceCreated. Detaching is therefore keyed
     * on the surface instance, so a late destroy cannot blank the view that took over.
     */
    fun attachSurface(stream: StreamKey, surface: Surface) {
        surfaces[stream] = surface
        core.setVideoSurface(stream, surface)
    }

    fun detachSurface(stream: StreamKey, surface: Surface) {
        if (surfaces[stream] !== surface) return
        surfaces.remove(stream)
        core.setVideoSurface(stream, null)
    }

    fun isHeld(stream: StreamKey): Boolean = entries[stream]?.let { it.holders > 0 } == true

    /**
     * After a reconnect the core starts with no subscriptions and no surfaces, while the screens
     * still hold theirs. Asks again for every held stream once the server announces it anew, and
     * hands its surface back, so video comes back by itself.
     */
    fun resume() {
        entries.forEach { (stream, entry) ->
            if (entry.holders <= 0) return@forEach
            scope.launch {
                val announced = withTimeoutOrNull(RESUME_TIMEOUT_MS) {
                    core.videoStreams.first { streams -> streams.any { it.key == stream } }
                }
                if (announced == null || entries[stream] !== entry) return@launch
                subscribe(stream, entry.bandwidth)
                surfaces[stream]?.let { core.setVideoSurface(stream, it) }
            }
        }
    }

    /** Forgets everything, e.g. on disconnect. The core drops its subscriptions itself. */
    fun clear() {
        entries.values.forEach { it.release?.cancel() }
        entries.clear()
        surfaces.clear()
    }

    private suspend fun subscribe(stream: StreamKey, bandwidth: Int) {
        try {
            core.subscribe(stream, bandwidth)
        } catch (_: MumbleCoreException) {
            // The stream ended or we disconnected in the meantime; the state flows say so.
        }
    }

    private companion object {
        const val RESUME_TIMEOUT_MS = 15_000L
    }
}
