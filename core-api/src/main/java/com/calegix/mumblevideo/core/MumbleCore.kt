package com.calegix.mumblevideo.core

import android.view.Surface
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The boundary between the Android app and a Mumble Video client core.
 *
 * One instance is one client: it holds at most one server connection at a time and lives for the
 * whole process. The production implementation is a thin Kotlin binding over the Qt-free C++ core
 * (`:core-native`, over JNI); `:core-fake` implements the same contract in pure Kotlin so the app
 * can be built and tested without it.
 *
 * ## Shape of the contract
 *
 * - **State is a [StateFlow]**, always holding the latest complete snapshot. The native side is
 *   expected to push *deltas* (a user moved, a channel was renamed) and the binding folds them into
 *   immutable snapshots on its own thread. Collectors never see a half-applied update.
 * - **Things that happen once are a [Flow]** ([messages], [events]). They are hot: an event emitted
 *   while nobody collects is lost, so the app collects them from process start. Implementations
 *   should buffer a small number (64 is plenty) so a slow collector does not stall the core.
 * - **Commands are `suspend` functions.** They return once the core has accepted the command, not
 *   once the server has acted on it; the result shows up in the state flows. A command that cannot
 *   be carried out at all throws [MumbleCoreException]. Commands are main-safe: implementations
 *   switch to their own threads.
 * - **High-frequency state is split out.** Talking indicators change many times a second, so they
 *   live in [talkStates] rather than on [User]; otherwise every syllable would re-emit the whole
 *   channel tree.
 * - **No Android types** apart from [Surface] in [setVideoSurface]. Identifiers are plain integers
 *   wrapped in value classes, models are immutable data classes, and bytes are [ByteArray].
 *
 * ## Threading
 *
 * Every member may be called from any thread. State flows are conflated, so a collector only ever
 * observes the newest value. [setVideoSurface] is the one synchronous, ordering-sensitive call; see
 * its documentation.
 */
interface MumbleCore : AutoCloseable {

    // ---------------------------------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------------------------------

    /** Where the client is in the connection lifecycle. Starts at [ConnectionState.Disconnected]. */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Starts connecting to [request]'s server, tearing down any existing connection first.
     *
     * Returns as soon as the attempt has started; progress is reported through [connectionState],
     * which may pass through [ConnectionState.CertificateTrustRequired] (answer it with
     * [respondToCertificate]) and ends in [ConnectionState.Connected],
     * [ConnectionState.Rejected] or [ConnectionState.Disconnected].
     */
    suspend fun connect(request: ConnectRequest)

    /**
     * Closes the connection, cancels any pending reconnect, and moves to
     * [ConnectionState.Disconnected] with [DisconnectReason.UserRequested]. Safe to call when
     * already disconnected.
     */
    suspend fun disconnect()

    /**
     * Answers a pending [ConnectionState.CertificateTrustRequired]. Accepting continues the
     * handshake; rejecting ends it with [DisconnectReason.CertificateRejected].
     *
     * The core does not persist trust decisions. The app stores accepted fingerprints and hands
     * them back through [ConnectRequest.trustedCertificateSha256] on every connect.
     */
    suspend fun respondToCertificate(accept: Boolean)

    /**
     * Replaces the access tokens for the current connection (Mumble's `Authenticate.tokens`).
     * They take effect immediately and also seed future reconnects of this session.
     */
    suspend fun setAccessTokens(tokens: List<String>)

    // ---------------------------------------------------------------------------------------------
    // Server model
    // ---------------------------------------------------------------------------------------------

    /**
     * The channel tree and user list of the current server, or [ServerState.EMPTY] while not
     * synchronised. Users carry everything except their talking state, which is in [talkStates].
     */
    val serverState: StateFlow<ServerState>

    /**
     * Talking state of every user that is currently not [TalkState.Passive]. Absent means passive.
     * This is the hot path for the UI's talking indicators.
     */
    val talkStates: StateFlow<Map<UserSession, TalkState>>

    /**
     * What the server advertised it supports. Starts as [ServerCapabilities.NONE] on every
     * connection and is filled in when the server's `ServerConfig`/`Version` arrive, so video UI
     * starts disabled and is enabled only once the server says so.
     */
    val capabilities: StateFlow<ServerCapabilities>

    /** Moves this client into [channel]. Throws [MumbleCoreException] with `PermissionDenied`. */
    suspend fun joinChannel(channel: ChannelId)

    /**
     * Fetches a user's comment or avatar when the server only sent its hash. Returns null when the
     * user has none. Results are cached by the core per hash.
     */
    suspend fun requestUserBlob(session: UserSession, blob: UserBlob): ByteArray?

    // ---------------------------------------------------------------------------------------------
    // Voice
    // ---------------------------------------------------------------------------------------------

    /** This client's own voice state: mute/deafen, input mode, whether audio is going out. */
    val voiceState: StateFlow<VoiceState>

    /** Self-mute. Unmuting while self-deafened also undeafens, as in every Mumble client. */
    suspend fun setSelfMuted(muted: Boolean)

    /** Self-deafen. Deafening implies self-mute; undeafening restores the previous mute state. */
    suspend fun setSelfDeafened(deafened: Boolean)

    /**
     * Applies audio settings. The app calls this once before connecting and again whenever the
     * user changes a setting; the core applies what it can live and the rest on the next connect.
     */
    suspend fun setAudioConfig(config: AudioConfig)

    /**
     * Push-to-talk key state. Only meaningful in [VoiceInputMode.PushToTalk]. This is
     * deliberately not `suspend`: it is called from key and touch handlers and must not be
     * delayed. Implementations hand it to the audio thread without blocking.
     */
    fun setPushToTalk(pressed: Boolean)

    /**
     * Live microphone level for the voice-activity threshold meter, emitted around 20 times a
     * second while collected. Collecting opens the microphone if no connection has it open
     * already, and stops it again on cancellation, so it works on the settings screen while
     * disconnected.
     */
    fun inputLevels(): Flow<AudioLevel>

    // ---------------------------------------------------------------------------------------------
    // Text chat
    // ---------------------------------------------------------------------------------------------

    /** Incoming text messages, plus echoes of this client's own successfully sent messages. */
    val messages: Flow<ChatMessage>

    /**
     * Sends [text] to a channel, a channel tree, or one user. [text] is the message body as
     * Mumble sends it (a restricted HTML subset); the app escapes plain text before calling.
     * Throws [MumbleCoreException] if the message is too long or permission is denied.
     */
    suspend fun sendMessage(target: MessageTarget, text: String)

    // ---------------------------------------------------------------------------------------------
    // Video (mumble-video's FEATURE_VIDEO: VideoState, VideoSubscribe, MumbleUDP.Video)
    // ---------------------------------------------------------------------------------------------

    /**
     * Every stream currently announced on the server that this client can see, including its own.
     * A screen share's companion audio (codec `OpusAudio` on the wire) is folded into its picture
     * stream as [VideoStream.audio] rather than listed separately.
     */
    val videoStreams: StateFlow<List<VideoStream>>

    /**
     * Subscribes to a stream, asking the server to forward its packets and the sender for a
     * keyframe. [preferredBandwidth] (bits per second, 0 = no preference) is passed on to the
     * sender's rate control, e.g. when the stream is shown as a thumbnail; calling again with a
     * new value updates it without resubscribing.
     */
    suspend fun subscribe(stream: StreamKey, preferredBandwidth: Int = 0)

    /** Unsubscribes and detaches any surface still attached to [stream]. */
    suspend fun unsubscribe(stream: StreamKey)

    /**
     * Hands the core the [Surface] to render [stream]'s decoded frames into, or null to stop.
     *
     * Decoding and rendering stay entirely in the core (on the native side: `ANativeWindow`
     * plus `AMediaCodec` or a software decoder); no frames cross JNI.
     *
     * This call is synchronous and must be made from the thread that owns the surface's lifecycle
     * (the main thread, from `SurfaceHolder.Callback`). Passing null **must not return until the
     * core has stopped touching the previous surface**, because the caller destroys it as soon as
     * this returns. A surface may be attached before [subscribe] completes; frames start once both
     * are in place. Attaching a new surface replaces the old one. The core scales to the surface
     * size and letterboxes as needed.
     */
    fun setVideoSurface(stream: StreamKey, surface: Surface?)

    /**
     * Volume and mute for the shared audio that rides along with a stream (typically a screen
     * share). [volume] is linear gain in 0.0..2.0, where 1.0 is unchanged. Purely local: other
     * listeners are unaffected. No effect if the stream has no audio.
     */
    suspend fun setStreamAudio(stream: StreamKey, volume: Float, muted: Boolean)

    /**
     * Who is subscribed to each of this client's own outgoing streams. Derived from the
     * VideoSubscribe feedback the server relays to the sender. Empty when not streaming.
     */
    val watchers: StateFlow<Map<StreamKey, Set<UserSession>>>

    /** State of this client's own camera and screen-share outputs. */
    val localVideo: StateFlow<LocalVideoState>

    /**
     * Starts sending the camera. Throws [MumbleCoreException] with
     * [MumbleCoreException.Kind.NotSupported] if the server did not advertise video.
     */
    suspend fun startCamera(request: CameraRequest)

    suspend fun stopCamera()

    /**
     * Starts a screen share. On Android the capture token (a `MediaProjection`) is handed to the
     * native binding by the app through a platform-specific side channel before this is called,
     * so this API stays free of it.
     */
    suspend fun startScreenShare(request: ScreenShareRequest)

    suspend fun stopScreenShare()

    // ---------------------------------------------------------------------------------------------
    // Misc
    // ---------------------------------------------------------------------------------------------

    /** One-off notifications that are not chat messages: joins, kicks, permission denials. */
    val events: Flow<CoreEvent>

    /**
     * Generates a new self-signed client certificate and returns it as PKCS#12, protected by
     * [password] (empty for none). The app stores the bytes; the core keeps no copy.
     */
    suspend fun generateCertificate(commonName: String, password: String = ""): ClientCertificate

    /**
     * Parses a PKCS#12 blob and describes the certificate inside. Throws [MumbleCoreException]
     * with [MumbleCoreException.Kind.InvalidCertificate] if it cannot be opened with [password].
     */
    suspend fun inspectCertificate(pkcs12: ByteArray, password: String = ""): CertificateInfo

    /** Disconnects and releases every native resource. The instance is unusable afterwards. */
    override fun close()
}
