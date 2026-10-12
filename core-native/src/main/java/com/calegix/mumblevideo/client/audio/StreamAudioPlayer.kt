package com.calegix.mumblevideo.client.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Plays the sound that comes with someone's screen share: their OpusAudio stream, 48 kHz stereo
 * Opus in 20 ms packets, on the media stream (it is a video's sound, not a voice).
 *
 * A short prebuffer absorbs network jitter; when packets stop it conceals briefly and then waits
 * for the buffer to fill again. [volume] is linear gain 0..2, applied to the samples so that it
 * can go above the system level as the API promises. [push] is called from the network thread.
 */
internal class StreamAudioPlayer : AutoCloseable {
    @Volatile var volume: Float = 1f

    @Volatile var muted: Boolean = false

    private val packets = LinkedBlockingDeque<ByteArray>(MAX_QUEUED)

    @Volatile private var running = true
    private val thread = Thread(::loop, "mv-stream-audio").also { it.start() }

    fun push(opus: ByteArray) {
        // Latency crept up (a stall, then a burst): drop the oldest rather than fall behind.
        while (!packets.offerLast(opus)) packets.pollFirst()
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(Opus.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(FRAME * CHANNELS * 2 * 6)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val decoder = Opus.Decoder(CHANNELS)
        val pcm = ShortArray(MAX_FRAME * CHANNELS)
        var buffering = true
        var concealed = 0
        try {
            track.play()
            while (running) {
                if (buffering) {
                    if (packets.size < PREBUFFER) {
                        Thread.sleep(5)
                        continue
                    }
                    buffering = false
                }
                val packet = packets.pollFirst(40, TimeUnit.MILLISECONDS)
                val samples = when {
                    packet != null -> {
                        concealed = 0
                        decoder.decode(packet, pcm, MAX_FRAME)
                    }

                    concealed < MAX_CONCEAL -> {
                        concealed++
                        decoder.decode(null, pcm, FRAME)
                    }

                    else -> {
                        buffering = true
                        continue
                    }
                }
                if (samples <= 0) continue
                val gain = if (muted) 0f else volume
                val count = samples * CHANNELS
                if (gain != 1f) {
                    for (i in 0 until count) pcm[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
                }
                track.write(pcm, 0, count)
            }
        } catch (_: InterruptedException) {
            // closing
        } finally {
            runCatching { track.stop() }
            track.release()
            decoder.close()
        }
    }

    override fun close() {
        running = false
        thread.interrupt()
        thread.join(500)
    }

    private companion object {
        const val CHANNELS = 2
        const val FRAME = 960 // 20 ms
        const val MAX_FRAME = 2880 // 60 ms, the longest Opus packet
        const val PREBUFFER = 3
        const val MAX_QUEUED = 25 // half a second
        const val MAX_CONCEAL = 3
    }
}
