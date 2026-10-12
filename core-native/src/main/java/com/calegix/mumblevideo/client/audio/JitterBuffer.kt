package com.calegix.mumblevideo.client.audio

import java.util.TreeMap

/**
 * Orders one speaker's packets by frame number and smooths network jitter with a prebuffer that
 * adapts to the speaker's connection.
 *
 * Frame numbers are used only for ordering and duplicate removal, never to infer loss: senders
 * count them differently (per 10 ms frame or per packet), so a gap in numbers is not a lost
 * packet. Loss shows up as an underrun instead, which the player conceals.
 *
 * Every underrun mid-talk raises this speaker's target by a packet (up to [maxPrebuffer]) and
 * rebuffers to it before playing on, so someone on a bursty path (Tor, a congested uplink) costs a
 * little latency instead of arriving as a stutter of single packets. A long run without underruns
 * brings the target back down a step at a time.
 *
 * Thread safe: the network thread puts, the playback thread takes.
 */
internal class JitterBuffer(
    private val prebuffer: Int = 2,
    private val maxQueued: Int = 12,
    private val maxPrebuffer: Int = 12,
) {
    sealed interface Next {
        class Packet(val opus: ByteArray) : Next

        /** Mid-talk underrun, or rebuffering after one: conceal. */
        data object Gap : Next

        /** Not talking (or still prebuffering). */
        data object Idle : Next
    }

    private val queue = TreeMap<Long, ByteArray>()
    private var playing = false
    private var rebuffering = false
    private var terminated = false
    private var lastTaken = Long.MIN_VALUE

    /** Packets to hold before playing; learned per speaker, kept across talk spurts. */
    @get:Synchronized
    var target = prebuffer
        private set
    private var steadyPackets = 0

    @Synchronized
    fun put(frameNumber: Long, opus: ByteArray, terminator: Boolean) {
        if (playing && frameNumber <= lastTaken) return // late or duplicate
        queue[frameNumber] = opus
        if (terminator) terminated = true
        // Latency crept up (clock drift, a burst after a stall): drop the oldest, keeping the target.
        while (queue.size > maxOf(maxQueued, target * 2)) queue.pollFirstEntry()
    }

    @Synchronized
    fun take(): Next {
        if (!playing) {
            if (queue.size < target && !(terminated && queue.isNotEmpty())) return Next.Idle
            playing = true
        }
        if (rebuffering) {
            if (queue.size < target && !terminated) return Next.Gap
            rebuffering = false
        }
        val entry = queue.pollFirstEntry()
        if (entry == null) {
            if (!terminated) {
                target = minOf(target + 1, maxPrebuffer)
                steadyPackets = 0
                rebuffering = true
                return Next.Gap
            }
            playing = false
            terminated = false
            lastTaken = Long.MIN_VALUE
            return Next.Idle
        }
        lastTaken = entry.key
        if (++steadyPackets >= STEADY_TO_SHRINK && target > prebuffer) {
            target--
            steadyPackets = 0
        }
        return Next.Packet(entry.value)
    }

    @Synchronized
    fun size(): Int = queue.size

    private companion object {
        /** About 30 s of 20 ms packets without an underrun before trusting the path a step more. */
        const val STEADY_TO_SHRINK = 1500
    }
}
