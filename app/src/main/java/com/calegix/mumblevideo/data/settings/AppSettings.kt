package com.calegix.mumblevideo.data.settings

import com.calegix.mumblevideo.core.AudioConfig
import com.calegix.mumblevideo.core.AudioOutputStream
import com.calegix.mumblevideo.core.CameraFacing
import com.calegix.mumblevideo.core.EchoCancellation
import com.calegix.mumblevideo.core.VideoCodec
import com.calegix.mumblevideo.core.VoiceInputMode
import kotlinx.serialization.Serializable

enum class ThemeMode { System, Light, Dark }

enum class VideoResolution(val width: Int, val height: Int, val label: String) {
    P360(640, 360, "360p"),
    P480(854, 480, "480p"),
    P720(1280, 720, "720p"),
    P1080(1920, 1080, "1080p"),
}

/** Size of the on-screen push-to-talk button. */
enum class PttButtonSize(val heightDp: Int) { Small(56), Medium(88), Large(140) }

/**
 * Every user setting, grouped the way the settings screen groups them. Stored as JSON in a typed
 * DataStore; defaults are not written, so changing a default here changes it for everyone who
 * never touched that setting, and unknown or renamed fields are dropped rather than crashing.
 */
@Serializable
data class AppSettings(
    // General
    val keepScreenOn: Boolean = false,
    val textToSpeech: Boolean = false,
    val shortTextToSpeech: Boolean = false,
    val loadImages: Boolean = true,

    // Authentication
    val defaultUsername: String = "Mumbler",
    val selectedCertificateId: Long? = null,

    // Audio
    val inputMode: VoiceInputMode = VoiceInputMode.VoiceActivity,
    val vadThreshold: Float = 0.5f,
    val inputGain: Float = 1.0f,
    val pttKeyCode: Int? = null,
    val headsetButtonPtt: Boolean = false,
    val pttToggle: Boolean = false,
    val pttSound: Boolean = false,
    val hidePttButton: Boolean = false,
    val halfDuplex: Boolean = false,
    val bitrate: Int = 40_000,
    val framesPerPacket: Int = 2,
    val noiseSuppression: Boolean = true,
    val automaticGainControl: Boolean = true,
    val echoCancellation: EchoCancellation = EchoCancellation.Off,
    val outputStream: AudioOutputStream = AudioOutputStream.VoiceCall,
    val bluetoothHeadset: Boolean = true,

    // Appearance
    val themeMode: ThemeMode = ThemeMode.System,
    val trueBlack: Boolean = false,
    val dynamicColor: Boolean = true,
    val showUserCount: Boolean = true,
    val pttButtonSize: PttButtonSize = PttButtonSize.Medium,
    val compactChannelList: Boolean = false,
    val colorizeBusyChannels: Boolean = false,
    val busyChannelThreshold: Int = 5,

    // Video
    val cameraFacing: CameraFacing = CameraFacing.Front,
    val cameraResolution: VideoResolution = VideoResolution.P720,
    val cameraFramerate: Int = 30,
    val screenResolution: VideoResolution = VideoResolution.P1080,
    val screenFramerate: Int = 15,
    /** Null means "let the core choose". */
    val codecPreference: VideoCodec? = null,
    val autoSubscribe: Boolean = true,
    val dataSaver: Boolean = false,
    val shareDeviceAudio: Boolean = true,

    // Notifications
    val notifyMessages: Boolean = true,
    val notifyPrivateOnly: Boolean = false,
    val notifyStreams: Boolean = true,
    val notifyWatchers: Boolean = true,
    val vibrateOnPrivateMessage: Boolean = true,

    // Advanced
    val autoReconnect: Boolean = true,
    val forceTcp: Boolean = false,
    val simulateConnectionDrops: Boolean = false,
) {
    fun toAudioConfig(): AudioConfig = AudioConfig(
        inputMode = inputMode,
        vadThreshold = vadThreshold,
        inputGain = inputGain,
        bitrate = bitrate,
        framesPerPacket = framesPerPacket,
        noiseSuppression = noiseSuppression,
        automaticGainControl = automaticGainControl,
        echoCancellation = echoCancellation,
        outputStream = outputStream,
        bluetoothHeadset = bluetoothHeadset,
        halfDuplex = halfDuplex,
        pushToTalkToggles = pttToggle,
    )

    companion object {
        val BITRATES = listOf(16_000, 24_000, 32_000, 40_000, 56_000, 72_000, 96_000)
        val FRAMES_PER_PACKET = listOf(1, 2, 4, 6)
        val FRAMERATES = listOf(5, 10, 15, 24, 30, 60)

        /** Per-thumbnail bandwidth request when data saver is on, in bits per second. */
        const val DATA_SAVER_BANDWIDTH = 250_000
    }
}
