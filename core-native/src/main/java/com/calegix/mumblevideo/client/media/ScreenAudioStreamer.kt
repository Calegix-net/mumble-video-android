package com.calegix.mumblevideo.client.media

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.calegix.mumblevideo.client.audio.Opus

/**
 * What the phone is playing (videos, music, games), to go with a screen share, the way the desktop
 * sends it: 48 kHz stereo Opus in music mode, one 20 ms packet per unit of a separate OpusAudio
 * stream. AudioPlaybackCapture only hears media, game and unclassified playback from apps that
 * allow capture, so other people's voices coming out of this app are never sent back.
 *
 * Needs the RECORD_AUDIO permission and the projection the screen share is using.
 */
internal class ScreenAudioStreamer(
    private val projection: MediaProjection,
    private val onUnit: (frameNumber: Long, captureUsec: Long, opus: ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // the caller checks; a SecurityException is reported regardless
    fun start() {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(Opus.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val record = try {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(FRAME * CHANNELS * 2 * 8)
                .build()
        } catch (e: SecurityException) {
            throw IllegalStateException("Sharing audio needs the microphone permission", e)
        } catch (e: UnsupportedOperationException) {
            throw IllegalStateException("This device cannot capture its own audio", e)
        }
        running = true
        thread = Thread({ loop(record) }, "mv-screen-audio").also { it.start() }
    }

    private fun loop(record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val pcm = ShortArray(FRAME * CHANNELS)
        val out = ByteArray(Opus.MAX_PACKET)
        var frameNumber = 0L
        val encoder = Opus.Encoder(BITRATE, channels = CHANNELS, application = Opus.APPLICATION_AUDIO)
        try {
            record.startRecording()
            while (running) {
                var filled = 0
                while (running && filled < pcm.size) {
                    val n = record.read(pcm, filled, pcm.size - filled)
                    if (n < 0) {
                        onError("audio capture failed ($n)")
                        return
                    }
                    filled += n
                }
                if (!running) break
                val length = encoder.encode(pcm, FRAME, out)
                if (length > 0) onUnit(frameNumber++, SystemClock.elapsedRealtimeNanos() / 1000, out.copyOf(length))
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "audio capture stopped", e)
            onError(e.message ?: "audio capture stopped")
        } finally {
            runCatching { record.stop() }
            record.release()
            encoder.close()
        }
    }

    override fun close() {
        running = false
        thread?.join(500)
        thread = null
    }

    private companion object {
        const val TAG = "MvScreenAudio"
        const val CHANNELS = 2
        const val FRAME = 960 // 20 ms
        const val BITRATE = 96_000
    }
}
