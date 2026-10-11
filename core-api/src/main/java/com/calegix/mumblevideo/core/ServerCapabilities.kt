package com.calegix.mumblevideo.core

/**
 * Server-advertised features and limits. Everything defaults to "off" so a pre-1.6 server, which
 * advertises nothing, simply gets no video UI.
 */
data class ServerCapabilities(
    /** `Feature.FEATURE_VIDEO` was advertised. */
    val video: Boolean = false,
    /** `Feature.FEATURE_FILE_TRANSFER` was advertised. Not used by the app yet. */
    val fileTransfer: Boolean = false,
    /** Maximum audio bandwidth in bits per second, 0 if unknown. */
    val maxAudioBandwidth: Int = 0,
    /** `max_video_bandwidth`: per-stream cap in bits per second, 0 if not advertised. */
    val maxVideoBandwidth: Int = 0,
    /** `max_video_streams_per_channel`, 0 if not advertised. */
    val maxVideoStreamsPerChannel: Int = 0,
    /** Maximum length of a text message, 0 if unlimited. */
    val maxMessageLength: Int = 0,
    /** Maximum length of a message containing an image, 0 if unlimited. */
    val maxImageMessageLength: Int = 0,
    val allowHtml: Boolean = true,
    val recordingAllowed: Boolean = true,
    /** The admin suggests push-to-talk (`SuggestConfig.push_to_talk`). */
    val suggestsPushToTalk: Boolean = false,
) {
    companion object {
        val NONE = ServerCapabilities()
    }
}
