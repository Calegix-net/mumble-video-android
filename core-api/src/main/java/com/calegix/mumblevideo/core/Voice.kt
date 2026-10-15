package com.calegix.mumblevideo.core

enum class VoiceInputMode { PushToTalk, VoiceActivity, Continuous }

/** This client's own voice state. */
data class VoiceState(
    val selfMuted: Boolean = false,
    val selfDeafened: Boolean = false,
    val inputMode: VoiceInputMode = VoiceInputMode.VoiceActivity,
    /** Push-to-talk is held (or toggled on). */
    val pushToTalkActive: Boolean = false,
    /** Audio is actually being sent right now: what the "you are talking" indicator shows. */
    val transmitting: Boolean = false,
)

/** Where received voice is played. */
enum class AudioOutputStream {
    /** Voice-call stream: earpiece-capable, uses the call volume, enables platform AEC. */
    VoiceCall,

    /** Media stream: speaker, media volume, no call routing. */
    Media,
}

enum class EchoCancellation { Off, System }

/**
 * Audio settings the core needs. Values the core cannot honour (for example a frames-per-packet
 * count the codec does not support) are clamped rather than rejected.
 */
data class AudioConfig(
    val inputMode: VoiceInputMode = VoiceInputMode.VoiceActivity,
    /** Voice-activity threshold, 0.0..1.0 on the same scale as [AudioLevel.level]. */
    val vadThreshold: Float = 0.5f,
    /** Linear input gain, 0.0..2.0. */
    val inputGain: Float = 1.0f,
    /** Opus bitrate in bits per second. */
    val bitrate: Int = 40_000,
    /** 10 ms audio frames per packet: 1, 2, 4 or 6. */
    val framesPerPacket: Int = 2,
    val noiseSuppression: Boolean = true,
    val automaticGainControl: Boolean = true,
    val echoCancellation: EchoCancellation = EchoCancellation.Off,
    val outputStream: AudioOutputStream = AudioOutputStream.VoiceCall,
    /** Route input and output over a Bluetooth headset (SCO) when one is connected. */
    val bluetoothHeadset: Boolean = true,
    /**
     * With a car's Bluetooth connected, play voice as media (A2DP) and record from the phone,
     * instead of placing a hands-free call: the car does not show a call or drop to call quality.
     */
    val carAsMedia: Boolean = true,
    /** Silence playback while transmitting, for speakerphone use without echo cancellation. */
    val halfDuplex: Boolean = false,
    /** Push-to-talk toggles on press instead of being held. */
    val pushToTalkToggles: Boolean = false,
)

/** One microphone level sample. */
data class AudioLevel(
    /** Perceptual level, 0.0..1.0, comparable with [AudioConfig.vadThreshold]. */
    val level: Float,
    /** Whether voice activity detection would transmit at the current threshold. */
    val aboveThreshold: Boolean,
)
