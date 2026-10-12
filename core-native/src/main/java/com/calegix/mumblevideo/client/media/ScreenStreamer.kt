package com.calegix.mumblevideo.client.media

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.calegix.mumblevideo.core.VideoCodec
import kotlin.math.roundToInt

/**
 * The phone's screen as a video stream: MediaProjection mirrors the display into a VirtualDisplay
 * whose surface is the encoder's input, so no frame is copied or touched on the way. Produces
 * [CameraStreamer.EncodedFrame]s, the same shape as the camera, for the same send path.
 *
 * The stream keeps the screen's aspect ratio, scaled so its longer side is at most the requested
 * size's; turning the phone mid-share letterboxes rather than changing the stream.
 */
class ScreenStreamer(
    private val context: Context,
    private val projection: MediaProjection,
    val config: Config,
    private val onFrame: (CameraStreamer.EncodedFrame) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    data class Config(
        val width: Int,
        val height: Int,
        val framerate: Int,
        val bitrate: Int,
        val codec: VideoCodec = VideoCodec.VP8,
    )

    @Volatile var outputWidth = 0
        private set

    @Volatile var outputHeight = 0
        private set

    private val thread = HandlerThread("mv-screen").apply { start() }
    private val handler = Handler(thread.looper)
    private var encoder: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var frameNumber = 0L
    private var configBytes: ByteArray? = null

    @Volatile private var closed = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!closed) onError("Screen sharing was stopped")
        }
    }

    fun start() {
        val (screenWidth, screenHeight) = screenSize()
        val (width, height) = streamSize(screenWidth, screenHeight, maxOf(config.width, config.height))
        outputWidth = width
        outputHeight = height
        val mime = when (config.codec) {
            VideoCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            else -> MediaFormat.MIMETYPE_VIDEO_VP8
        }
        val info = findEncoder(mime) ?: throw IllegalStateException("No $mime encoder on this device")
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.framerate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_SECONDS)
            // A still screen produces no frames; repeat the last so viewers who join get a picture.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / config.framerate * 4)
            if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
        }
        val codec = MediaCodec.createByCodecName(info.name)
        codec.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // surface input

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) =
                    handleOutput(codec, index, info)

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) =
                    onError("Encoder failed: ${e.diagnosticInfo}")

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
            },
            handler,
        )
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = codec.createInputSurface()
        codec.start()
        encoder = codec

        projection.registerCallback(projectionCallback, handler)
        display = projection.createVirtualDisplay(
            "MumbleVideoScreen",
            width,
            height,
            context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            input,
            null,
            handler,
        )
        Log.i(TAG, "sharing the screen at ${width}x$height@${config.framerate} with ${info.name}")
    }

    private fun screenSize(): Pair<Int, Int> {
        val windows = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windows.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val size = Point()
        @Suppress("DEPRECATION")
        windows.defaultDisplay.getRealSize(size)
        return size.x to size.y
    }

    fun requestKeyframe() {
        encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }

    private fun handleOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        try {
            val buffer = codec.getOutputBuffer(index) ?: return
            val data = ByteArray(info.size).also {
                buffer.position(info.offset)
                buffer.get(it, 0, info.size)
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                // H.264 parameter sets: decoders joining late need them with every keyframe.
                configBytes = data
                return
            }
            if (info.size == 0) return
            val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            val payload = if (keyframe) configBytes?.let { it + data } ?: data else data
            onFrame(
                CameraStreamer.EncodedFrame(
                    frameNumber++,
                    keyframe,
                    info.presentationTimeUs,
                    outputWidth,
                    outputHeight,
                    payload,
                ),
            )
        } finally {
            codec.releaseOutputBuffer(index, false)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        display?.release()
        display = null
        encoder?.let {
            runCatching { it.stop() }
            it.release()
        }
        encoder = null
        projection.unregisterCallback(projectionCallback)
        projection.stop()
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "MvScreen"
        private const val KEYFRAME_INTERVAL_SECONDS = 3

        /** The screen's shape, scaled so the longer side is at most [longest], both sides multiples of 16. */
        internal fun streamSize(screenWidth: Int, screenHeight: Int, longest: Int): Pair<Int, Int> {
            val scale = minOf(1.0, longest.toDouble() / maxOf(screenWidth, screenHeight))
            fun round16(v: Double) = maxOf(16, (v / 16).roundToInt() * 16)
            return round16(screenWidth * scale) to round16(screenHeight * scale)
        }

        /** A hardware encoder for [mime] if there is one, else any. */
        private fun findEncoder(mime: String): MediaCodecInfo? {
            val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }
            return encoders.firstOrNull { it.isHardwareAccelerated } ?: encoders.firstOrNull()
        }
    }
}
