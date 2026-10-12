package com.calegix.mumblevideo.client.media

/**
 * Turns mumble-core's reassembled video units into decodable frames for one stream, for the
 * whole-frame codecs (VP8, VP9, AV1, H.264). TiledImage is not handled here: its units are
 * independent pictures drawn at (x, y), not input to a decoder.
 *
 * - Nothing is decoded before the first keyframe, nor after a frame is lost until the next one:
 *   inter frames that depend on a missing frame only decode to garbage. Losing a frame asks for a
 *   keyframe ([Result.NeedKeyframe]), which the host sends as VideoSubscribe.request_keyframe.
 * - frame_number is a sequence number (MumbleUDP.Video), so a frame is lost when the next one
 *   skips a number.
 * - H.264 frames may span several units: unit i of n carries x = i and y = n, and all n are joined
 *   in order before decoding. n comes off the wire, so it is bounded.
 *
 * Not thread safe: one stream's units arrive on one thread.
 */
internal class FrameAssembler(private val joinsUnits: Boolean) {
    sealed interface Result {
        class Frame(
            val frameNumber: Long,
            val data: ByteArray,
            val isKeyframe: Boolean,
            val presentationUsec: Long,
            val width: Int,
            val height: Int,
        ) : Result

        /** Dropped while waiting for a keyframe, or stale. Nothing to do. */
        data object Dropped : Result

        /** A frame was lost: ask the sender for a keyframe. Reported once per loss. */
        data object NeedKeyframe : Result

        /** Part of a multi-unit frame; more to come. */
        data object Pending : Result
    }

    private var awaitingKeyframe = true
    private var lastFrame = -1L

    private var partialFrame = -1L
    private var parts: Array<CoreVideoUnit?> = emptyArray()
    private var received = 0

    fun push(unit: CoreVideoUnit): Result {
        if (lastFrame >= 0 && unit.frameNumber <= lastFrame) return Result.Dropped
        return if (joinsUnits) pushPart(unit) else complete(unit.frameNumber, unit.payload, unit.isKeyframe, unit)
    }

    private fun pushPart(unit: CoreVideoUnit): Result {
        val count = unit.y
        val index = unit.x
        if (count !in 1..MAX_UNITS_PER_FRAME || index !in 0 until count) return Result.Dropped
        if (unit.frameNumber != partialFrame) {
            val abandoned = partialFrame >= 0 && received < parts.size
            partialFrame = unit.frameNumber
            parts = arrayOfNulls(count)
            received = 0
            if (abandoned && !unit.isKeyframe) return lost()
        }
        if (parts.size != count) return Result.Dropped // the count changed mid-frame: corrupt
        if (parts[index] == null) received++
        parts[index] = unit
        if (received < count) return Result.Pending
        val all = parts.map { checkNotNull(it) }
        partialFrame = -1
        parts = emptyArray()
        received = 0
        val joined = ByteArray(all.sumOf { it.payload.size })
        var offset = 0
        for (part in all) {
            part.payload.copyInto(joined, offset)
            offset += part.payload.size
        }
        return complete(unit.frameNumber, joined, all.any { it.isKeyframe }, all[0])
    }

    private fun complete(frameNumber: Long, data: ByteArray, isKeyframe: Boolean, first: CoreVideoUnit): Result {
        val gap = lastFrame >= 0 && frameNumber != lastFrame + 1
        if (!isKeyframe) {
            if (awaitingKeyframe) return Result.Dropped
            if (gap) return lost()
        }
        awaitingKeyframe = false
        lastFrame = frameNumber
        return Result.Frame(frameNumber, data, isKeyframe, first.captureTimestampUsec, first.width, first.height)
    }

    private fun lost(): Result {
        if (awaitingKeyframe) return Result.Dropped
        awaitingKeyframe = true
        return Result.NeedKeyframe
    }

    private companion object {
        /** A 4K keyframe at a generous bitrate spans a few dozen units at most. */
        const val MAX_UNITS_PER_FRAME = 256
    }
}
