package com.calegix.mumblevideo.client.media

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One received audio packet: a single Opus frame, still encoded. Mirrors MumbleCore::AudioPacket. */
class CoreAudioPacket(
    val senderSession: Int,
    val frameNumber: Long,
    /** 0 normal, 1 shout, 2 whisper, 3 listening (a channel listener). */
    val context: Int,
    val opus: ByteArray,
    /** The sender stopped talking after this frame. */
    val isLastFrame: Boolean,
    /** x, y, z, or null if the packet carried no position. */
    val position: FloatArray?,
    /** Volume the server asks this to be played at, as a linear factor. */
    val volumeFactor: Float,
)

/**
 * One reassembled unit of video, ready for the decoder: a whole frame, or for tiled codecs one tile
 * of it at (x, y). Mirrors MumbleCore::VideoUnit.
 */
class CoreVideoUnit(
    val senderSession: Int,
    val streamId: Int,
    val frameNumber: Long,
    val unitId: Int,
    val isKeyframe: Boolean,
    val isFrameEnd: Boolean,
    val captureTimestampUsec: Long,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val payload: ByteArray,
)

/** Everything one MediaSession call produced. The host acts on every list. Mirrors MumbleCore::MediaEvents. */
class MediaEvents(
    val audio: List<CoreAudioPacket>,
    val video: List<CoreVideoUnit>,
    /** Our UDP pings echoed back: the timestamps makePing() was given. */
    val pongs: LongArray,
    /** Serialised MumbleUDP.VideoNack messages: a viewer asking for parts of one of our streams again. */
    val videoNacksForUs: List<ByteArray>,
    /** Datagrams to send to the server's UDP port as they are. */
    val datagramsToSend: List<ByteArray>,
    /** Framed control messages to write to the TLS stream as they are. */
    val controlToSend: List<ByteArray>,
) {
    val isEmpty: Boolean
        get() = audio.isEmpty() && video.isEmpty() && pongs.isEmpty() && videoNacksForUs.isEmpty() &&
            datagramsToSend.isEmpty() && controlToSend.isEmpty()

    companion object {
        val EMPTY = MediaEvents(emptyList(), emptyList(), LongArray(0), emptyList(), emptyList(), emptyList())
    }
}

/** Packet counters for a connection-quality display. Mirrors MumbleCore::MediaStats. */
data class MediaStats(
    val audioGood: Long,
    val audioLate: Long,
    val audioLost: Long,
    val audioResync: Long,
    val videoReplays: Long,
    val videoTooOld: Long,
    val videoAuthFailures: Long,
)

/** Reads the layout media_jni.cpp writes. Keep the two in step. */
internal object MediaEventsCodec {
    fun decode(bytes: ByteArray?): MediaEvents {
        if (bytes == null) return MediaEvents.EMPTY
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val audio = List(b.count()) {
            val sender = b.int
            val frame = b.long
            val context = b.int
            val flags = b.get().toInt()
            val position = floatArrayOf(b.float, b.float, b.float)
            val volume = b.float
            CoreAudioPacket(
                sender,
                frame,
                context,
                b.blob(),
                flags and 1 != 0,
                position.takeIf {
                    flags and 2 != 0
                },
                volume,
            )
        }
        val video = List(b.count()) {
            val sender = b.int
            val stream = b.int
            val frame = b.long
            val unit = b.int
            val flags = b.get().toInt()
            val timestamp = b.long
            val x = b.int
            val y = b.int
            val width = b.int
            val height = b.int
            val keyframe = flags and 1 != 0
            val frameEnd = flags and 2 != 0
            CoreVideoUnit(sender, stream, frame, unit, keyframe, frameEnd, timestamp, x, y, width, height, b.blob())
        }
        val pongs = LongArray(b.count()) { b.long }
        val nacks = List(b.count()) { b.blob() }
        val datagrams = List(b.count()) { b.blob() }
        val control = List(b.count()) { b.blob() }
        check(!b.hasRemaining()) { "trailing bytes in media events" }
        return MediaEvents(audio, video, pongs, nacks, datagrams, control)
    }

    private fun ByteBuffer.count(): Int = int.also { check(it in 0..remaining()) { "bad count $it" } }

    private fun ByteBuffer.blob(): ByteArray = ByteArray(count()).also { get(it) }
}
