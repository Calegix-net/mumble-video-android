package com.calegix.mumblevideo.client.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGLSurface
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Display
import android.view.Surface
import com.calegix.mumblevideo.core.CameraFacing
import com.calegix.mumblevideo.core.VideoCodec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** One camera the device has, for the camera picker. */
data class CameraInfo(
    val id: String,
    val facing: CameraFacing,
    /** "Front camera", "Back camera", "Back camera 2", "USB camera". */
    val name: String,
    /** Supported capture sizes, as the camera lists them (landscape). */
    val sizes: List<Pair<Int, Int>>,
    val maxFramerate: Int,
)

/**
 * A camera, encoded: Camera2 into a SurfaceTexture, drawn upright by GLES into the encoder's input
 * surface (and, mirrored, into an optional self-view), encoded by MediaCodec as VP8 or H.264.
 *
 * Threads: camera callbacks and the GL pass on one handler thread, encoder output on another
 * ([onFrame] and [onError] run there). Public methods are safe from any thread; [setPreview] with
 * null and [close] wait for the render thread, nothing else blocks.
 *
 * Orientation is decided once, at [start], from the display's rotation then.
 */
class CameraStreamer(
    context: Context,
    val config: Config,
    private val onFrame: (EncodedFrame) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {

    data class Config(
        val cameraId: String,
        val width: Int,
        val height: Int,
        val framerate: Int,
        val bitrate: Int,
        val codec: VideoCodec = VideoCodec.VP8,
        val keyframeIntervalSeconds: Int = 2,
        /**
         * How the device is physically held, clockwise degrees from its natural orientation
         * (OrientationEventListener, quantised). Null uses the display's rotation, which stays put
         * when auto-rotate is off or the app is in the background.
         */
        val deviceOrientation: Int? = null,
    )

    class EncodedFrame(
        val frameNumber: Long,
        val isKeyframe: Boolean,
        val captureTimestampUsec: Long,
        val width: Int,
        val height: Int,
        val data: ByteArray,
    )

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)

    /** Actual encoded size after choosing a supported camera size and rotating upright. Valid after start(). */
    @Volatile var outputWidth = 0
        private set

    @Volatile var outputHeight = 0
        private set

    private val renderThread = HandlerThread("mv-camera")
    private val encoderThread = HandlerThread("mv-camera-encoder")
    private lateinit var render: Handler
    private lateinit var renderExecutor: Executor

    // Render-thread state.
    private val gl = GlRenderer()
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var encoderEgl: EGLSurface? = null
    private var previewEgl: EGLSurface? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encodedOrientation = FloatArray(16)
    private var previewOrientation = FloatArray(16)
    private val stMatrix = FloatArray(16)
    private var lastFrameAt = 0L

    // Encoder state.
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var codecConfig: ByteArray? = null
    private var frameNumber = 0L

    @Volatile private var closed = false

    @Volatile private var started = false

    /**
     * Opens the camera and the encoder. Throws [IllegalStateException] if the encoder or GL cannot be
     * set up at all; a missing permission or a camera failure arrives at [onError] instead.
     */
    @SuppressLint("MissingPermission") // checked just below
    fun start() {
        check(!started && !closed) { "already started" }
        started = true
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onError("Camera permission not granted")
            return
        }
        val characteristics = cameraManager.getCameraCharacteristics(config.cameraId)
        val map =
            checkNotNull(characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]) {
                "camera has no stream configurations"
            }
        val sizes = map.getOutputSizes(SurfaceTexture::class.java).map { it.width to it.height }
        val (captureWidth, captureHeight) = CameraMath.chooseSize(sizes, config.width, config.height)
        val front = characteristics[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT
        val sensor = characteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
        val rotation = CameraMath.uprightRotation(sensor, effectiveDisplayRotation(), front)
        val (uprightWidth, uprightHeight) = CameraMath.uprightSize(captureWidth, captureHeight, rotation)
        val (encodeWidth, encodeHeight) = CameraMath.encoderSize(uprightWidth, uprightHeight)
        outputWidth = encodeWidth
        outputHeight = encodeHeight
        // The sizes follow the full upright rotation; the texture only needs what the transform lacks.
        val textureRotation = CameraMath.textureRotation(effectiveDisplayRotation())
        encodedOrientation = CameraMath.texMatrix(textureRotation, mirror = false)
        previewOrientation = CameraMath.texMatrix(textureRotation, mirror = front)
        val fpsRanges = characteristics[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty()
            .map { it.lower to it.upper }
        val fps = if (fpsRanges.isEmpty()) null else CameraMath.chooseFpsRange(fpsRanges, config.framerate)
        Log.i(
            TAG,
            "camera ${config.cameraId}: capture ${captureWidth}x$captureHeight, sensor $sensor°, rotate $rotation°, " +
                "encode ${encodeWidth}x$encodeHeight ${config.codec} at ${config.framerate} fps (AE $fps)",
        )

        encoderThread.start()
        startEncoder(encodeWidth, encodeHeight)

        renderThread.start()
        render = Handler(renderThread.looper)
        renderExecutor = Executor { render.post(it) }
        val failure = runOnRender {
            gl.init()
            encoderEgl = gl.createSurface(checkNotNull(inputSurface))
            surfaceTexture = SurfaceTexture(gl.texture).apply {
                setDefaultBufferSize(captureWidth, captureHeight)
                setOnFrameAvailableListener({ drawFrame() }, render)
            }
            cameraSurface = Surface(surfaceTexture)
        }
        if (failure != null) {
            close()
            throw IllegalStateException("could not set up GL: ${failure.message}", failure)
        }
        render.post { openCamera(fps) }
    }

    fun requestKeyframe() {
        encoder?.runCatching {
            setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    fun setBitrate(bitsPerSecond: Int) {
        encoder?.runCatching {
            setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitsPerSecond) })
        }
    }

    /** The local self-view. With null this returns only once nothing renders into the old surface. */
    fun setPreview(surface: Surface?) {
        if (!started || closed || !renderThread.isAlive) return
        runOnRender {
            previewEgl?.let(gl::releaseSurface)
            previewEgl = surface?.let { runCatching { gl.createSurface(it) }.getOrNull() }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (renderThread.isAlive) {
            runOnRender {
                runCatching { session?.close() }
                runCatching { camera?.close() }
                session = null
                camera = null
                previewEgl?.let(gl::releaseSurface)
                encoderEgl?.let(gl::releaseSurface)
                previewEgl = null
                encoderEgl = null
                cameraSurface?.release()
                surfaceTexture?.release()
                gl.release()
            }
            renderThread.quitSafely()
        }
        encoder?.let {
            runCatching { it.stop() }
            it.release()
        }
        encoder = null
        inputSurface?.release()
        inputSurface = null
        if (encoderThread.isAlive) encoderThread.quitSafely()
    }

    // --- camera (render thread) ---------------------------------------------------------------

    @SuppressLint("MissingPermission") // start() checked it
    private fun openCamera(fps: Pair<Int, Int>?) {
        if (closed) return
        try {
            cameraManager.openCamera(
                config.cameraId,
                renderExecutor,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (closed) {
                            device.close()
                            return
                        }
                        camera = device
                        createSession(device, fps)
                    }

                    override fun onDisconnected(device: CameraDevice) = fail("The camera was disconnected")

                    override fun onError(device: CameraDevice, error: Int) = fail("Camera error $error")
                },
            )
        } catch (e: Exception) {
            fail("Could not open the camera: ${e.message}")
        }
    }

    private fun createSession(device: CameraDevice, fps: Pair<Int, Int>?) {
        val target = cameraSurface ?: return
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(capture: CameraCaptureSession) {
                if (closed) {
                    capture.close()
                    return
                }
                session = capture
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(target)
                    fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.first, it.second)) }
                }
                runCatching { capture.setRepeatingRequest(request.build(), null, render) }
                    .onFailure { fail("Could not start the camera: ${it.message}") }
            }

            override fun onConfigureFailed(capture: CameraCaptureSession) = fail("The camera refused the capture setup")
        }
        device.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(target)),
                renderExecutor,
                callback,
            ),
        )
    }

    private fun drawFrame() {
        val texture = surfaceTexture ?: return
        if (closed) return
        texture.updateTexImage()
        // Cameras do not always honour the AE range exactly; don't feed the encoder more than asked.
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt < 1000L * 9 / (config.framerate * 10)) return
        lastFrameAt = now
        texture.getTransformMatrix(stMatrix)
        encoderEgl?.let { gl.draw(it, outputWidth, outputHeight, stMatrix, encodedOrientation, texture.timestamp) }
        previewEgl?.let {
            val (w, h) = gl.surfaceSize(it)
            if (!gl.draw(it, w, h, stMatrix, previewOrientation, null)) {
                // The preview surface went away under us; forget it rather than fail every frame.
                gl.releaseSurface(it)
                previewEgl = null
            }
        }
    }

    // --- encoder ----------------------------------------------------------------------------------

    private fun startEncoder(width: Int, height: Int) {
        val mime = when (config.codec) {
            VideoCodec.VP8 -> MediaFormat.MIMETYPE_VIDEO_VP8
            VideoCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            else -> throw IllegalStateException("${config.codec} can't be encoded from a camera")
        }
        val info =
            chooseEncoder(mime, width, height) ?: throw IllegalStateException("no $mime encoder for ${width}x$height")
        val caps = info.getCapabilitiesForType(mime)
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.framerate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeIntervalSeconds)
            if (caps.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ==
                true
            ) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            // Repeat the last frame if the camera stalls, so viewers don't freeze on a long gap.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / config.framerate * 3)
        }
        val codec = MediaCodec.createByCodecName(info.name)
        codec.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // surface input

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    handleOutput(codec, index, info)
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = fail(
                    "The video encoder failed: ${e.diagnosticInfo}",
                )

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
            },
            Handler(encoderThread.looper),
        )
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        encoder = codec
        Log.i(
            TAG,
            "encoder ${info.name} (${if (info.isHardwareAccelerated) "hardware" else "software"}) at ${config.bitrate / 1000} kbit/s",
        )
    }

    private fun handleOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        if (closed) return
        try {
            val buffer = codec.getOutputBuffer(index)
            val data = if (buffer != null && info.size > 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                ByteArray(info.size).also { buffer.get(it) }
            } else {
                null
            }
            codec.releaseOutputBuffer(index, false)
            if (data == null) return
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                // H.264's SPS/PPS: kept and sent in front of every keyframe, so a viewer joining late
                // can start. VP8 has no such thing.
                if (config.codec == VideoCodec.H264) codecConfig = data
                return
            }
            val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            val payload = if (keyframe &&
                config.codec == VideoCodec.H264
            ) {
                (codecConfig ?: ByteArray(0)) + data
            } else {
                data
            }
            onFrame(EncodedFrame(frameNumber++, keyframe, info.presentationTimeUs, outputWidth, outputHeight, payload))
        } catch (e: IllegalStateException) {
            if (!closed) fail("The video encoder stopped: ${e.message}")
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun fail(reason: String) {
        if (closed) return
        Log.w(TAG, reason)
        onError(reason)
        // Close off the calling thread: it may be the render thread, which close() waits on.
        Thread({ close() }, "mv-camera-close").start()
    }

    /** Runs [block] on the render thread and waits for it. Returns what it threw, if anything. */
    private fun runOnRender(block: () -> Unit): Throwable? {
        if (Thread.currentThread() == renderThread) {
            return runCatching(block).exceptionOrNull()
        }
        var failure: Throwable? = null
        val done = CountDownLatch(1)
        if (!render.post {
                failure = runCatching(block).exceptionOrNull()
                done.countDown()
            }
        ) {
            return null
        }
        done.await(RENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return failure
    }

    /** The display rotation the device's physical orientation corresponds to, if known. */
    private fun effectiveDisplayRotation(): Int =
        config.deviceOrientation?.let { (360 - it) % 360 } ?: displayRotationDegrees()

    private fun displayRotationDegrees(): Int {
        val display = appContext.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        return when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun chooseEncoder(mime: String, width: Int, height: Int): MediaCodecInfo? {
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && mime in info.supportedTypes.map { it.lowercase() } &&
                runCatching {
                    info.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(width, height) == true
                }.getOrDefault(false)
        }
        // Hardware first; the platform's software encoder (c2.android.*) is always there as a fallback.
        return candidates.firstOrNull { it.isHardwareAccelerated && !it.isSoftwareOnly } ?: candidates.firstOrNull()
    }

    companion object {
        private const val TAG = "MvCamera"
        private const val RENDER_TIMEOUT_MS = 2_000L

        /** Every camera the app may use, front first, then back, then external. */
        fun cameras(context: Context): List<CameraInfo> {
            val manager = context.getSystemService(CameraManager::class.java) ?: return emptyList()
            val raw = manager.cameraIdList.mapNotNull { id ->
                val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return@mapNotNull null
                val facing = when (c[CameraCharacteristics.LENS_FACING]) {
                    CameraCharacteristics.LENS_FACING_FRONT -> CameraFacing.Front
                    CameraCharacteristics.LENS_FACING_BACK -> CameraFacing.Back
                    else -> CameraFacing.External
                }
                val sizes = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
                    ?.getOutputSizes(SurfaceTexture::class.java)?.map { it.width to it.height }.orEmpty()
                val maxFps =
                    c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.maxOfOrNull { it.upper } ?: 30
                Triple(id, facing, sizes to maxFps)
            }
            val counts = HashMap<CameraFacing, Int>()
            return raw.sortedBy { it.second.ordinal }.map { (id, facing, extra) ->
                val n = counts.merge(facing, 1, Int::plus)!!
                val base = when (facing) {
                    CameraFacing.Front -> "Front camera"
                    CameraFacing.Back -> "Back camera"
                    CameraFacing.External -> "USB camera"
                }
                CameraInfo(id, facing, if (n == 1) base else "$base $n", extra.first, extra.second)
            }
        }
    }
}
