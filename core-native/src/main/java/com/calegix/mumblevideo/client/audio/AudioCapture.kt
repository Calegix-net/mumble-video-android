package com.calegix.mumblevideo.client.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.calegix.mumblevideo.core.AudioConfig
import com.calegix.mumblevideo.core.EchoCancellation
import com.calegix.mumblevideo.core.VoiceInputMode
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Microphone to Opus packets. Reads 10 ms frames from the VOICE_COMMUNICATION source (which brings
 * the platform's echo canceller and noise suppressor with it), decides whether to transmit
 * (push-to-talk, voice activity or continuous), and hands out one Opus packet per
 * [AudioConfig.framesPerPacket] frames.
 *
 * Runs its own thread. [sink] is called on that thread; [levels] too, for every 10 ms frame.
 */
internal class AudioCapture(
    private val sink: (opus: ByteArray, frames: Int, terminator: Boolean) -> Unit,
    private val levels: (level: Float, transmitting: Boolean) -> Unit,
) {
    @Volatile var config: AudioConfig = AudioConfig()

    @Volatile var muted: Boolean = false

    @Volatile var pushToTalk: Boolean = false

    /** When false, only levels are reported and nothing is encoded (the settings meter). */
    @Volatile var sending: Boolean = true

    @Volatile private var running = false
    private var thread: Thread? = null

    /** Starts capturing. Returns false if the microphone could not be opened (no permission, busy). */
    @SuppressLint("MissingPermission") // checked by the caller; a SecurityException is caught regardless
    fun start(): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            Opus.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                Opus.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME * 2 * 8),
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "no microphone permission", e)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        running = true
        thread = Thread({ loop(record) }, "mv-capture").also { it.start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop(record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val effects = attachEffects(record.audioSessionId)
        val frame = ShortArray(FRAME)
        var packet = ShortArray(FRAME * 6)
        var framesInPacket = 0
        val out = ByteArray(Opus.MAX_PACKET)
        var encoder: Opus.Encoder? = null
        var bitrate = 0
        var wasTransmitting = false
        var vadHoldUntil = 0L

        record.startRecording()
        try {
            while (running) {
                val read = record.read(frame, 0, FRAME)
                if (read <= 0) continue
                val cfg = config
                applyGain(frame, read, cfg.inputGain)
                val level = levelOf(frame, read)
                val now = SystemClock.elapsedRealtime()
                if (level >= cfg.vadThreshold) vadHoldUntil = now + VAD_HOLD_MS

                val wants = sending && !muted && when (cfg.inputMode) {
                    VoiceInputMode.PushToTalk -> pushToTalk
                    VoiceInputMode.Continuous -> true
                    VoiceInputMode.VoiceActivity -> now < vadHoldUntil
                }
                levels(level, wants)

                if (!wants && !wasTransmitting) continue

                val enc = encoder ?: Opus.Encoder(cfg.bitrate).also {
                    encoder = it
                    bitrate = cfg.bitrate
                }
                if (bitrate != cfg.bitrate) {
                    enc.setBitrate(cfg.bitrate)
                    bitrate = cfg.bitrate
                }
                val perPacket = cfg.framesPerPacket.coerceIn(1, 6)
                if (packet.size < perPacket * FRAME) packet = ShortArray(perPacket * FRAME)

                if (wants) {
                    frame.copyInto(packet, framesInPacket * FRAME, 0, FRAME)
                    framesInPacket++
                    if (framesInPacket >= perPacket) {
                        emit(enc, packet, framesInPacket, out, terminator = false)
                        framesInPacket = 0
                    }
                } else {
                    // Stopped talking: flush what we have, padded with silence, as the terminator.
                    val frames = if (framesInPacket == 0) 1 else framesInPacket
                    packet.fill(0, framesInPacket * FRAME, frames * FRAME)
                    emit(enc, packet, frames, out, terminator = true)
                    framesInPacket = 0
                }
                wasTransmitting = wants
            }
        } finally {
            record.stop()
            record.release()
            effects.forEach { it.release() }
            encoder?.close()
        }
    }

    private fun emit(encoder: Opus.Encoder, pcm: ShortArray, frames: Int, out: ByteArray, terminator: Boolean) {
        val length = encoder.encode(pcm, frames * FRAME, out)
        if (length > 0) sink(out.copyOf(length), frames, terminator)
    }

    private fun attachEffects(session: Int): List<android.media.audiofx.AudioEffect> = buildList {
        val cfg = config
        if (cfg.echoCancellation == EchoCancellation.System && AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(session)?.also {
                it.enabled = true
                add(it)
            }
        }
        if (cfg.noiseSuppression && NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(session)?.also {
                it.enabled = true
                add(it)
            }
        }
        if (cfg.automaticGainControl && AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(session)?.also {
                it.enabled = true
                add(it)
            }
        }
    }

    companion object {
        private const val TAG = "MvCapture"

        /** 10 ms at 48 kHz. */
        const val FRAME = 480
        private const val VAD_HOLD_MS = 300L

        private fun applyGain(pcm: ShortArray, count: Int, gain: Float) {
            if (gain == 1f) return
            for (i in 0 until count) pcm[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }

        /** Perceptual level in 0..1: dBFS mapped linearly from -60 dB (0) to 0 dB (1). */
        fun levelOf(pcm: ShortArray, count: Int): Float {
            var sum = 0.0
            for (i in 0 until count) sum += pcm[i].toDouble() * pcm[i]
            val rms = sqrt(sum / count) / 32768.0
            if (rms <= 0) return 0f
            val db = 20 * log10(rms)
            return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
        }
    }
}
