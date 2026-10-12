package com.calegix.mumblevideo.client.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import android.view.Surface
import com.calegix.mumblevideo.core.VideoCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One subscribed stream's decoder: whole frames from [FrameAssembler] into MediaCodec, rendered
 * straight to the Surface the UI handed down (MumbleCore.setVideoSurface). Not wired up yet.
 *
 * Threads: [submit] from the network thread, [setSurface] and [close] from the main thread, and
 * the codec runs on its own thread. [setSurface] with null and [close] return only once the codec
 * has let go of the surface, as the MumbleCore contract requires.
 *
 * TiledImage streams are not decoded here; they are pictures drawn at (x, y), not codec input.
 */
class VideoDecoder(
    private val codec: VideoCodec,
    /** Called (on the network thread) when a keyframe is needed: send VideoSubscribe.request_keyframe. */
    private val onKeyframeNeeded: () -> Unit,
) : AutoCloseable {
    private val mime = when (codec) {
        VideoCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
        VideoCodec.VP8 -> MediaFormat.MIMETYPE_VIDEO_VP8
        VideoCodec.VP9 -> MediaFormat.MIMETYPE_VIDEO_VP9
        VideoCodec.AV1 -> MediaFormat.MIMETYPE_VIDEO_AV1
        VideoCodec.TiledImage -> throw IllegalArgumentException("TiledImage is not a MediaCodec stream")
    }
    private val assembler = FrameAssembler(joinsUnits = codec == VideoCodec.H264)
    private val frames = LinkedBlockingQueue<FrameAssembler.Result.Frame>(MAX_QUEUED)

    private val lock = Object()
    private var surface: Surface? = null
    private var worker: Thread? = null

    @Volatile private var running = false

    /** One reassembled unit from mumble-core. */
    fun submit(unit: CoreVideoUnit) {
        when (val result = assembler.push(unit)) {
            is FrameAssembler.Result.Frame -> if (!frames.offer(result)) {
                // The decoder fell behind: start over from a fresh keyframe rather than build latency.
                frames.clear()
                onKeyframeNeeded()
            }

            FrameAssembler.Result.NeedKeyframe -> onKeyframeNeeded()

            FrameAssembler.Result.Dropped, FrameAssembler.Result.Pending -> Unit
        }
    }

    fun setSurface(newSurface: Surface?) {
        synchronized(lock) {
            stopWorker()
            surface = newSurface
            if (newSurface != null) {
                running = true
                frames.clear()
                // A fresh codec needs a keyframe to start from.
                onKeyframeNeeded()
                worker = Thread({ decodeLoop(newSurface) }, "mv-video-$codec").also { it.start() }
            }
        }
    }

    override fun close() = setSurface(null)

    private fun stopWorker() {
        running = false
        worker?.let {
            it.interrupt()
            it.join(STOP_TIMEOUT_MS)
        }
        worker = null
    }

    private fun decodeLoop(target: Surface) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        var decoder: MediaCodec? = null
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val frame = frames.poll(10, TimeUnit.MILLISECONDS)
                if (frame != null) {
                    if (decoder == null) {
                        // Configure on the first keyframe, which carries the real dimensions.
                        if (!frame.isKeyframe) continue
                        decoder = MediaCodec.createDecoderByType(mime).apply {
                            configure(MediaFormat.createVideoFormat(mime, frame.width, frame.height), target, null, 0)
                            start()
                        }
                    }
                    val index = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = checkNotNull(decoder.getInputBuffer(index))
                        buffer.clear()
                        buffer.put(frame.data)
                        val flags = if (frame.isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        decoder.queueInputBuffer(index, 0, frame.data.size, frame.presentationUsec, flags)
                    }
                }
                decoder?.let { drain(it, info) }
            }
        } catch (_: InterruptedException) {
            // Stopping.
        } catch (e: Exception) {
            Log.w(TAG, "$codec decoder failed", e)
        } finally {
            decoder?.let {
                runCatching { it.stop() }
                it.release()
            }
        }
    }

    private fun drain(decoder: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            if (index < 0) return // try again later, or a format/buffers change we need not act on
            // Render immediately: these are live frames, and pacing is the network's.
            decoder.releaseOutputBuffer(index, info.size > 0)
        }
    }

    private companion object {
        const val TAG = "MvVideo"
        const val MAX_QUEUED = 8
        const val INPUT_TIMEOUT_US = 10_000L
        const val STOP_TIMEOUT_MS = 1_000L
    }
}
