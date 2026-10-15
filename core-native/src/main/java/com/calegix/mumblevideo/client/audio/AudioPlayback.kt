package com.calegix.mumblevideo.client.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.calegix.mumblevideo.core.AudioOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10

/**
 * Received voice to the speaker: one jitter buffer and Opus decoder per talking user, mixed every
 * 10 ms into a single AudioTrack. The blocking AudioTrack write paces the loop.
 *
 * [push] is called from the network thread; everything else runs on the playback thread.
 */
internal class AudioPlayback {
    private class Speaker {
        val jitter = JitterBuffer()
        val decoder = Opus.Decoder()
        val pcm = ShortArray(Opus.SAMPLE_RATE / 10) // 100 ms of decoded audio
        var pcmStart = 0
        var pcmEnd = 0
        var lastPacketSamples = 2 * FRAME
        var concealed = 0

        fun available() = pcmEnd - pcmStart
    }

    private val speakers = ConcurrentHashMap<Int, Speaker>()

    @Volatile var deafened: Boolean = false

    /** Per-sender linear volume (local adjustment). */
    val volumes = ConcurrentHashMap<Int, Float>()

    @Volatile private var running = false
    private var thread: Thread? = null

    /** Peak of the last mixed frame, 0..1; for diagnostics. */
    @Volatile var lastPeak: Float = 0f
        private set

    @Volatile var framesDecoded: Long = 0
        private set

    @Volatile var framesConcealed: Long = 0
        private set

    /**
     * Which stream the track plays on. Set mid-session when routing moves between a call and media
     * (a car connecting); the playback thread rebuilds its track, keeping every speaker's state.
     */
    @Volatile var stream: AudioOutputStream = AudioOutputStream.VoiceCall

    fun start(stream: AudioOutputStream) {
        if (running) return
        this.stream = stream
        running = true
        thread = Thread({ loop() }, "mv-playback").also { it.start() }
    }

    private fun buildTrack(stream: AudioOutputStream): AudioTrack {
        val usage = if (stream ==
            AudioOutputStream.VoiceCall
        ) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(Opus.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(
                maxOf(
                    AudioTrack.getMinBufferSize(
                        Opus.SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ),
                    FRAME * 2 * 4,
                ),
            )
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        speakers.values.forEach { it.decoder.close() }
        speakers.clear()
    }

    /** One received Opus packet from [session]. */
    fun push(session: Int, frameNumber: Long, opus: ByteArray, terminator: Boolean) {
        if (deafened) return
        val speaker = speakers.getOrPut(session) { Speaker() }
        speaker.jitter.put(frameNumber, opus, terminator)
    }

    fun removeSpeaker(session: Int) {
        speakers.remove(session)?.decoder?.close()
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var trackStream = stream
        var track = buildTrack(trackStream)
        val mix = IntArray(FRAME)
        val out = ShortArray(FRAME)
        val scratch = ShortArray(Opus.SAMPLE_RATE / 10)
        var reportedFrames = 0L
        var windowPeak = 0
        var tick = 0
        track.play()
        try {
            while (running) {
                if (stream != trackStream) {
                    track.stop()
                    track.release()
                    trackStream = stream
                    track = buildTrack(trackStream)
                    track.play()
                    Log.i(TAG, "playing as $trackStream")
                }
                mix.fill(0)
                if (!deafened) {
                    for ((session, speaker) in speakers) {
                        val gain = volumes[session] ?: 1f
                        if (fill(speaker, scratch)) {
                            for (i in 0 until FRAME) mix[i] += (speaker.pcm[speaker.pcmStart + i] * gain).toInt()
                            speaker.pcmStart += FRAME
                        }
                    }
                }
                var peak = 0
                for (i in 0 until FRAME) {
                    val s = mix[i].coerceIn(-32768, 32767)
                    out[i] = s.toShort()
                    if (s > peak) {
                        peak = s
                    } else if (-s > peak) {
                        peak = -s
                    }
                }
                lastPeak = peak / 32768f
                if (peak > windowPeak) windowPeak = peak
                // Every 5 s of playback, if anything was heard: enough to verify receive in logcat.
                if (++tick % 500 == 0) {
                    if (framesDecoded != reportedFrames) {
                        val db = if (windowPeak > 0) 20 * log10(windowPeak / 32768.0) else Double.NEGATIVE_INFINITY
                        val decoded = framesDecoded - reportedFrames
                        val summary = "played: $decoded frames decoded, $framesConcealed concealed so far"
                        Log.i(TAG, "$summary, peak %.1f dBFS".format(db))
                        reportedFrames = framesDecoded
                    }
                    windowPeak = 0
                }
                track.write(out, 0, FRAME)
            }
        } finally {
            track.stop()
            track.release()
        }
    }

    /** Makes sure [speaker] has 10 ms ready. Returns false if it has nothing to play right now. */
    private fun fill(speaker: Speaker, scratch: ShortArray): Boolean {
        while (speaker.available() < FRAME) {
            // Compact before appending.
            if (speaker.pcmStart > 0) {
                speaker.pcm.copyInto(speaker.pcm, 0, speaker.pcmStart, speaker.pcmEnd)
                speaker.pcmEnd -= speaker.pcmStart
                speaker.pcmStart = 0
            }
            val samples = when (val next = speaker.jitter.take()) {
                is JitterBuffer.Next.Packet -> {
                    speaker.concealed = 0
                    val n = speaker.decoder.decode(next.opus, scratch, scratch.size)
                    if (n > 0) {
                        speaker.lastPacketSamples = n
                        framesDecoded += n / FRAME
                    }
                    n
                }

                JitterBuffer.Next.Gap -> {
                    // Underrun mid-talk: conceal a little, then give up and rebuffer.
                    if (speaker.concealed >= MAX_CONCEAL) return dropPartial(speaker)
                    speaker.concealed++
                    val n = speaker.decoder.decode(null, scratch, speaker.lastPacketSamples)
                    if (n > 0) framesConcealed += n / FRAME
                    n
                }

                JitterBuffer.Next.Idle -> return dropPartial(speaker)
            }
            if (samples <= 0) return dropPartial(speaker)
            val room = speaker.pcm.size - speaker.pcmEnd
            val n = minOf(samples, room)
            scratch.copyInto(speaker.pcm, speaker.pcmEnd, 0, n)
            speaker.pcmEnd += n
        }
        return true
    }

    private fun dropPartial(speaker: Speaker): Boolean {
        // Play out what is left by padding the final partial frame with silence.
        if (speaker.available() == 0) return false
        speaker.pcm.fill(0, speaker.pcmEnd, speaker.pcmStart + FRAME)
        speaker.pcmEnd = speaker.pcmStart + FRAME
        return true
    }

    companion object {
        private const val TAG = "MvAudio"
        const val FRAME = 480
        private const val MAX_CONCEAL = 3
    }
}
