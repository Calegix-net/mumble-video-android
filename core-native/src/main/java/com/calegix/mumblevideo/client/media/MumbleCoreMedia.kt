package com.calegix.mumblevideo.client.media

/**
 * The UDP side of one connection, from mumble-core over JNI: both crypt states (audio and video),
 * the audio datagram format, and video reception with reassembly and retransmission requests. The
 * host still owns the sockets: datagrams go in, and events (audio frames, video units, pongs, and
 * datagrams or control messages to send) come out.
 *
 * mumble-core's MediaSession is single threaded. This wrapper serialises every call on itself, so
 * the UDP receive thread, the audio send thread and the control thread may all use one instance.
 * Calls are cheap (one JNI crossing each); none blocks on I/O.
 */
class MumbleCoreMedia : AutoCloseable {
    private var handle: Long = nativeCreate()

    /** The server's version_v2. Until set, the server is assumed to be 1.5.0 or newer. */
    @Synchronized
    fun setServerVersion(versionV2: Long) = nativeSetServerVersion(live(), versionV2)

    /**
     * Channel-byte framing (this fork's servers) or the bare payload of stock Mumble. Set before
     * any datagram is exchanged: the two cannot be told apart from the bytes.
     */
    @Synchronized
    fun setUdpFraming(channelByte: Boolean) = nativeSetUdpFraming(live(), channelByte)

    /**
     * Applies a CryptSetup control message's payload. A full key keys audio and video; a lone
     * server nonce resyncs audio; an empty message asks for our nonce, which comes back in
     * [MediaEvents.controlToSend]. Returns null if the message did not parse or fit.
     */
    @Synchronized
    fun handleCryptSetup(payload: ByteArray): MediaEvents? =
        nativeHandleCryptSetup(live(), payload, payload.size)?.let(MediaEventsCodec::decode)

    val isKeyed: Boolean
        @Synchronized get() = nativeIsKeyed(live())

    /** One datagram read from the server's UDP port. Undecryptable datagrams are dropped silently. */
    @Synchronized
    fun receiveDatagram(datagram: ByteArray, length: Int = datagram.size, nowUsec: Long): MediaEvents =
        MediaEventsCodec.decode(nativeReceiveDatagram(live(), datagram, length, nowUsec))

    /** A UDPTunnel control message's payload: audio the server sent over TCP. */
    @Synchronized
    fun receiveTunnelled(payload: ByteArray): MediaEvents =
        MediaEventsCodec.decode(nativeReceiveTunnelled(live(), payload, payload.size))

    /** An encrypted ping datagram carrying [timestamp], echoed back in [MediaEvents.pongs]; null if not keyed. */
    @Synchronized
    fun makePing(timestamp: Long): ByteArray? = nativeMakePing(live(), timestamp)

    /**
     * An encrypted audio datagram carrying one Opus frame; null if not keyed. [target]: 0 normal
     * speech, 1-30 a VoiceTarget, 31 server loopback.
     */
    @Synchronized
    fun makeAudio(opus: ByteArray, frameNumber: Long, target: Int, isLastFrame: Boolean): ByteArray? =
        nativeMakeAudio(live(), opus, opus.size, frameNumber, target, isLastFrame)

    /**
     * One coded unit of our own video stream, fragmented and encrypted into datagrams ready to send
     * as they are. Shares the session's video sequence space with its retransmission requests.
     * Empty if the session is not keyed or the unit is too large.
     */
    @Synchronized
    fun makeVideo(
        streamId: Int,
        frameNumber: Long,
        isKeyframe: Boolean,
        captureTimestampUsec: Long,
        width: Int,
        height: Int,
        payload: ByteArray,
        unitId: Int = 0,
        isFrameEnd: Boolean = true,
        x: Int = 0,
        y: Int = 0,
    ): List<ByteArray> = nativeMakeVideo(
        live(), streamId, frameNumber, unitId, isKeyframe, isFrameEnd, captureTimestampUsec,
        x, y, width, height, payload, payload.size,
    )?.toList().orEmpty()

    /** Forgets a user's partial video units, when they leave or stop sending. */
    @Synchronized
    fun removeSender(session: Int) = nativeRemoveSender(live(), session)

    @Synchronized
    fun stats(): MediaStats = nativeStats(live()).let { MediaStats(it[0], it[1], it[2], it[3], it[4], it[5], it[6]) }

    @Synchronized
    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0
    }

    private fun live(): Long = handle.also { check(it != 0L) { "MumbleCoreMedia is closed" } }

    companion object {
        init {
            System.loadLibrary("mvclient")
        }

        /** The Mumble version the core was built as, e.g. "1.7.0". */
        fun version(): String = nativeVersion()

        @JvmStatic private external fun nativeVersion(): String

        @JvmStatic private external fun nativeCreate(): Long

        @JvmStatic private external fun nativeDestroy(handle: Long)

        @JvmStatic private external fun nativeSetServerVersion(handle: Long, versionV2: Long)

        @JvmStatic private external fun nativeSetUdpFraming(handle: Long, channelByte: Boolean)

        @JvmStatic
        private external fun nativeHandleCryptSetup(handle: Long, payload: ByteArray, length: Int): ByteArray?

        @JvmStatic private external fun nativeIsKeyed(handle: Long): Boolean

        @JvmStatic
        private external fun nativeReceiveDatagram(
            handle: Long,
            datagram: ByteArray,
            length: Int,
            nowUsec: Long,
        ): ByteArray?

        @JvmStatic
        private external fun nativeReceiveTunnelled(handle: Long, payload: ByteArray, length: Int): ByteArray?

        @JvmStatic private external fun nativeMakePing(handle: Long, timestamp: Long): ByteArray?

        @JvmStatic private external fun nativeMakeVideo(
            handle: Long,
            streamId: Int,
            frameNumber: Long,
            unitId: Int,
            isKeyframe: Boolean,
            isFrameEnd: Boolean,
            captureUsec: Long,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            payload: ByteArray,
            length: Int,
        ): Array<ByteArray>?

        @JvmStatic private external fun nativeMakeAudio(
            handle: Long,
            opus: ByteArray,
            length: Int,
            frameNumber: Long,
            target: Int,
            isLastFrame: Boolean,
        ): ByteArray?

        @JvmStatic private external fun nativeRemoveSender(handle: Long, session: Int)

        @JvmStatic private external fun nativeStats(handle: Long): LongArray
    }
}
