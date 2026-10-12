package com.calegix.mumblevideo.core

/** Picture codecs from `VideoState.Codec`. `OpusAudio` is not here: see [VideoStream.audio]. */
enum class VideoCodec {
    TiledImage,
    VP8,
    VP9,
    AV1,

    /** Annex B from a hardware encoder; a frame may span several units, which the core joins. */
    H264,
}

/** `VideoState.SourceKind`. */
enum class VideoSourceKind {
    Camera,
    Display,
    Window,
    Application,
    ;

    val isScreen: Boolean get() = this != Camera
}

/** One announced stream, from the VideoState the server relayed. */
data class VideoStream(
    val key: StreamKey,
    val codec: VideoCodec,
    val sourceKind: VideoSourceKind,
    /** Camera, monitor, window or application name. Informational. */
    val sourceName: String,
    /** Nominal size; the decoder may report something different mid-stream. */
    val width: Int,
    val height: Int,
    val maxFramerate: Int,
    /** Bits per second after the server's clamp. */
    val targetBandwidth: Int,
    /** Companion shared audio, if the sender is sharing sound with this stream. */
    val audio: StreamAudio? = null,
    val subscription: Subscription = Subscription.None,
    /** True for this client's own outgoing streams. */
    val isLocal: Boolean = false,
) {
    val hasAudio: Boolean get() = audio != null
}

/** Local playback settings for a stream's companion audio. */
data class StreamAudio(
    /** Stream id of the `OpusAudio` stream carrying it. */
    val audioStreamId: Int,
    val volume: Float = 1.0f,
    val muted: Boolean = false,
)

sealed interface Subscription {
    data object None : Subscription

    /** Subscribe sent, waiting for the first keyframe. */
    data object Pending : Subscription

    data class Active(
        /** Size of the most recently decoded frame. */
        val decodedWidth: Int,
        val decodedHeight: Int,
        val framesPerSecond: Float,
        /** Fraction of units lost since the last report, in parts per thousand. */
        val lossPermille: Int,
    ) : Subscription

    /** The server refused or tore down the subscription (no ReceiveVideo permission, moved away). */
    data class Failed(val reason: String) : Subscription
}

/** This client's own outgoing video. */
data class LocalVideoState(
    val camera: LocalStream? = null,
    val screen: LocalStream? = null,
)

data class LocalStream(val key: StreamKey, val width: Int, val height: Int, val framerate: Int, val codec: VideoCodec)

/** Which camera to use. The core maps this onto the platform's camera ids. */
enum class CameraFacing { Front, Back, External }

data class CameraRequest(
    val facing: CameraFacing = CameraFacing.Front,
    /** Platform camera id if the user picked a specific one, else null to choose by [facing]. */
    val cameraId: String? = null,
    val width: Int = 1280,
    val height: Int = 720,
    val framerate: Int = 30,
    /** Preferred codec; null lets the core pick the best one the server and device support. */
    val codec: VideoCodec? = null,
    /** Cap in bits per second below the server's own, 0 for none. */
    val maxBandwidth: Int = 0,
)

data class ScreenShareRequest(
    val width: Int = 1920,
    val height: Int = 1080,
    val framerate: Int = 15,
    val codec: VideoCodec? = null,
    /** Also share device audio (AudioPlaybackCapture, Android 10+). */
    val includeAudio: Boolean = true,
    val maxBandwidth: Int = 0,
)
