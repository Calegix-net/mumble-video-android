package com.calegix.mumblevideo.client.audio

import java.util.TreeMap

/**
 * Orders one speaker's packets by frame number and smooths network jitter with a small prebuffer.
 *
 * Frame numbers are used only for ordering and duplicate removal, never to infer loss: senders
 * count them differently (per 10 ms frame or per packet), so a gap in numbers is not a lost
 * packet. Loss shows up as an underrun instead, which the player conceals.
 *
 * Thread safe: the network thread puts, the playback thread takes.
 */
internal class JitterBuffer(
    private val prebuffer: Int = 2,
    private val maxQueued: Int = 12,
) {
    sealed interface Next {
        class Packet(val opus: ByteArray) : Next

        /** Mid-talk underrun: conceal. */
        data object Gap : Next

        /** Not talking (or still prebuffering). */
        data object Idle : Next
    }

    private val queue = TreeMap<Long, ByteArray>()
    private var playing = false
    private var terminated = false
    private var lastTaken = Long.MIN_VALUE

    @Synchronized
    fun put(frameNumber: Long, opus: ByteArray, terminator: Boolean) {
        if (playing && frameNumber <= lastTaken) return // late or duplicate
        queue[frameNumber] = opus
        if (terminator) terminated = true
        // Latency crept up (clock drift, a burst after a stall): drop the oldest down to the prebuffer.
        while (queue.size > maxQueued) queue.pollFirstEntry()
    }

    @Synchronized
    fun take(): Next {
        if (!playing) {
            if (queue.size < prebuffer && !(terminated && queue.isNotEmpty())) return Next.Idle
            playing = true
        }
        val entry = queue.pollFirstEntry()
        if (entry == null) {
            if (!terminated) return Next.Gap
            playing = false
            terminated = false
            lastTaken = Long.MIN_VALUE
            return Next.Idle
        }
        lastTaken = entry.key
        return Next.Packet(entry.value)
    }

    @Synchronized
    fun size(): Int = queue.size
}
