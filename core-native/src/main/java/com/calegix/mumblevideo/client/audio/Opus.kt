package com.calegix.mumblevideo.client.audio

/** Thin binding to libopus 1.5 (built from source in src/main/cpp). */
internal object Opus {
    const val SAMPLE_RATE = 48_000
    const val APPLICATION_VOIP = 2048
    const val APPLICATION_AUDIO = 2049
    private const val SET_BITRATE = 4002
    private const val SET_VBR = 4006
    private const val SET_INBAND_FEC = 4012
    private const val SET_PACKET_LOSS_PERC = 4014
    private const val SET_SIGNAL = 4024
    private const val SIGNAL_VOICE = 3001

    /** The largest Opus packet we will ever produce: a 60 ms frame at 510 kbit/s. */
    const val MAX_PACKET = 4000

    init {
        System.loadLibrary("mvclient")
    }

    val version: String get() = nativeVersion()

    /**
     * [channels] 1 or 2 (interleaved). [application] VOIP for speech, AUDIO for shared device sound;
     * only speech is told it is a voice signal.
     */
    class Encoder(bitrate: Int, channels: Int = 1, application: Int = APPLICATION_VOIP) : AutoCloseable {
        private var handle = nativeEncoderCreate(SAMPLE_RATE, channels, application)

        init {
            check(handle != 0L) { "opus_encoder_create failed" }
            if (application == APPLICATION_VOIP) nativeEncoderCtl(handle, SET_SIGNAL, SIGNAL_VOICE)
            nativeEncoderCtl(handle, SET_VBR, 0) // constant bitrate, as Mumble does
            nativeEncoderCtl(handle, SET_INBAND_FEC, 1)
            nativeEncoderCtl(handle, SET_PACKET_LOSS_PERC, 5)
            setBitrate(bitrate)
        }

        fun setBitrate(bitrate: Int) {
            nativeEncoderCtl(handle, SET_BITRATE, bitrate)
        }

        /** Encodes [frameSize] samples per channel. Returns the packet length written to [out], or a negative error. */
        fun encode(pcm: ShortArray, frameSize: Int, out: ByteArray): Int = nativeEncode(handle, pcm, frameSize, out)

        override fun close() {
            if (handle != 0L) nativeEncoderDestroy(handle)
            handle = 0
        }
    }

    /** [channels] 1 or 2; decoded samples come out interleaved. */
    class Decoder(channels: Int = 1) : AutoCloseable {
        private var handle = nativeDecoderCreate(SAMPLE_RATE, channels)

        init {
            check(handle != 0L) { "opus_decoder_create failed" }
        }

        /** Decodes one packet, or conceals a lost one when [packet] is null. Returns samples written. */
        fun decode(packet: ByteArray?, pcm: ShortArray, maxSamples: Int, fec: Boolean = false): Int =
            nativeDecode(handle, packet, packet?.size ?: 0, pcm, maxSamples, fec)

        override fun close() {
            if (handle != 0L) nativeDecoderDestroy(handle)
            handle = 0
        }
    }

    fun packetSamples(packet: ByteArray): Int = nativePacketSamples(packet, packet.size, SAMPLE_RATE)

    @JvmStatic private external fun nativeEncoderCreate(sampleRate: Int, channels: Int, application: Int): Long

    @JvmStatic private external fun nativeEncoderCtl(handle: Long, request: Int, value: Int): Int

    @JvmStatic private external fun nativeEncode(handle: Long, pcm: ShortArray, frameSize: Int, out: ByteArray): Int

    @JvmStatic private external fun nativeEncoderDestroy(handle: Long)

    @JvmStatic private external fun nativeDecoderCreate(sampleRate: Int, channels: Int): Long

    @JvmStatic private external fun nativeDecode(
        handle: Long,
        data: ByteArray?,
        length: Int,
        pcm: ShortArray,
        frameSize: Int,
        fec: Boolean,
    ): Int

    @JvmStatic private external fun nativePacketSamples(data: ByteArray, length: Int, sampleRate: Int): Int

    @JvmStatic private external fun nativeDecoderDestroy(handle: Long)

    @JvmStatic private external fun nativeVersion(): String
}
