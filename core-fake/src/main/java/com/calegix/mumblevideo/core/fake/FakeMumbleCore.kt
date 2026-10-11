package com.calegix.mumblevideo.core.fake

import android.view.Surface
import com.calegix.mumblevideo.core.AudioConfig
import com.calegix.mumblevideo.core.AudioLevel
import com.calegix.mumblevideo.core.CameraRequest
import com.calegix.mumblevideo.core.CertificateInfo
import com.calegix.mumblevideo.core.ChannelId
import com.calegix.mumblevideo.core.ChatMessage
import com.calegix.mumblevideo.core.ClientCertificate
import com.calegix.mumblevideo.core.ConnectPhase
import com.calegix.mumblevideo.core.ConnectRequest
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.CoreEvent
import com.calegix.mumblevideo.core.DisconnectReason
import com.calegix.mumblevideo.core.LocalStream
import com.calegix.mumblevideo.core.LocalVideoState
import com.calegix.mumblevideo.core.MessageTarget
import com.calegix.mumblevideo.core.MumbleCore
import com.calegix.mumblevideo.core.MumbleCoreException
import com.calegix.mumblevideo.core.MumbleCoreException.Kind
import com.calegix.mumblevideo.core.RejectReason
import com.calegix.mumblevideo.core.ScreenShareRequest
import com.calegix.mumblevideo.core.ServerAddress
import com.calegix.mumblevideo.core.ServerCapabilities
import com.calegix.mumblevideo.core.ServerCertificate
import com.calegix.mumblevideo.core.ServerInfo
import com.calegix.mumblevideo.core.ServerState
import com.calegix.mumblevideo.core.StreamKey
import com.calegix.mumblevideo.core.Subscription
import com.calegix.mumblevideo.core.TalkState
import com.calegix.mumblevideo.core.User
import com.calegix.mumblevideo.core.UserBlob
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.core.VideoCodec
import com.calegix.mumblevideo.core.VideoSourceKind
import com.calegix.mumblevideo.core.VideoStream
import com.calegix.mumblevideo.core.VoiceInputMode
import com.calegix.mumblevideo.core.VoiceState
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A [MumbleCore] with no network behind it: a scripted server with nested channels, users who
 * talk and chat on timers, a few video streams rendered as test patterns, and failure modes that
 * are triggered by the server's host name:
 *
 * | host contains  | behaviour                                                  |
 * |----------------|------------------------------------------------------------|
 * | `unreachable`  | the connection attempt fails                               |
 * | `reject`       | the server rejects the login (wrong password)              |
 * | `full`         | the server rejects the login (server full)                 |
 * | `flaky`        | the connection drops after 20 s and reconnects             |
 * | `legacy`       | the server does not advertise video                        |
 *
 * Every server presents a self-signed certificate, so the first connect to each one asks for
 * trust. [simulateConnectionDrops] makes every server behave as if `flaky`.
 *
 * @param scope where all simulation runs; cancelled by [close]. Tests pass their own.
 * @param simulateActivity set false for deterministic tests: no talking, chat or stream churn.
 */
class FakeMumbleCore(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val random: Random = Random.Default,
    private val simulateActivity: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
) : MumbleCore {

    /** Developer toggle: make every connection drop and reconnect periodically. */
    @Volatile
    var simulateConnectionDrops: Boolean = false

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _serverState = MutableStateFlow(ServerState.EMPTY)
    override val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _talkStates = MutableStateFlow<Map<UserSession, TalkState>>(emptyMap())
    override val talkStates: StateFlow<Map<UserSession, TalkState>> = _talkStates.asStateFlow()

    private val _capabilities = MutableStateFlow(ServerCapabilities.NONE)
    override val capabilities: StateFlow<ServerCapabilities> = _capabilities.asStateFlow()

    private val _voiceState = MutableStateFlow(VoiceState())
    override val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _videoStreams = MutableStateFlow<List<VideoStream>>(emptyList())
    override val videoStreams: StateFlow<List<VideoStream>> = _videoStreams.asStateFlow()

    private val _watchers = MutableStateFlow<Map<StreamKey, Set<UserSession>>>(emptyMap())
    override val watchers: StateFlow<Map<StreamKey, Set<UserSession>>> = _watchers.asStateFlow()

    private val _localVideo = MutableStateFlow(LocalVideoState())
    override val localVideo: StateFlow<LocalVideoState> = _localVideo.asStateFlow()

    private val _messages =
        MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val messages: Flow<ChatMessage> = _messages.asSharedFlow()

    private val _events =
        MutableSharedFlow<CoreEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<CoreEvent> = _events.asSharedFlow()

    @Volatile
    private var audioConfig = AudioConfig()

    private var connectionJob: Job? = null
    private var request: ConnectRequest? = null
    private var pendingTrust: CompletableDeferred<Boolean>? = null
    private val messageIds = AtomicLong(0)
    private val localStreamIds = AtomicInteger(100)
    private val renderers = mutableMapOf<StreamKey, TestPatternRenderer>()

    // ---------------------------------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------------------------------

    override suspend fun connect(request: ConnectRequest) {
        connectionJob?.cancelAndJoin()
        resetSession()
        this.request = request
        _voiceState.update { it.copy(inputMode = audioConfig.inputMode) }
        connectionJob = scope.launch { runConnection(request) }
    }

    override suspend fun disconnect() {
        connectionJob?.cancelAndJoin()
        connectionJob = null
        resetSession()
        _connectionState.value = ConnectionState.Disconnected(DisconnectReason.UserRequested)
    }

    override suspend fun respondToCertificate(accept: Boolean) {
        pendingTrust?.complete(accept)
    }

    override suspend fun setAccessTokens(tokens: List<String>) {
        request = request?.copy(accessTokens = tokens)
    }

    private suspend fun runConnection(request: ConnectRequest) {
        val server = request.server
        val host = server.host.lowercase()

        _connectionState.value = ConnectionState.Connecting(server, ConnectPhase.Resolving)
        delay(STEP_MS)
        if ("unreachable" in host) {
            _connectionState.value =
                ConnectionState.Disconnected(DisconnectReason.Unreachable("Connection refused ($server)"))
            return
        }

        _connectionState.value = ConnectionState.Connecting(server, ConnectPhase.Handshaking)
        delay(STEP_MS)
        val certificate = fakeServerCertificate(server)
        if (certificate.sha256 !in request.trustedCertificateSha256) {
            val decision = CompletableDeferred<Boolean>().also { pendingTrust = it }
            _connectionState.value = ConnectionState.CertificateTrustRequired(server, certificate)
            val accepted = decision.await()
            pendingTrust = null
            if (!accepted) {
                _connectionState.value = ConnectionState.Disconnected(DisconnectReason.CertificateRejected)
                return
            }
        }

        _connectionState.value = ConnectionState.Connecting(server, ConnectPhase.Authenticating)
        delay(STEP_MS)
        rejection(request)?.let { (reason, message) ->
            _connectionState.value = ConnectionState.Rejected(server, reason, message)
            return
        }

        var attempt = 0
        while (true) {
            _connectionState.value = ConnectionState.Connecting(server, ConnectPhase.Synchronising)
            delay(STEP_MS)
            establishSession(request)
            val drops = "flaky" in host || simulateConnectionDrops
            if (!drops) return

            delay(DROP_AFTER_MS)
            resetSession()
            if (!request.autoReconnect) {
                _connectionState.value =
                    ConnectionState.Disconnected(DisconnectReason.ConnectionLost("Connection timed out"))
                return
            }
            // The first retry always fails, so the reconnect UI gets a proper workout.
            do {
                attempt++
                countDown(server, attempt, "Connection timed out")
            } while (attempt % 2 == 1)
        }
    }

    private suspend fun countDown(server: ServerAddress, attempt: Int, error: String) {
        var remaining = RECONNECT_DELAY_MS
        while (remaining > 0) {
            _connectionState.value = ConnectionState.Reconnecting(server, attempt, remaining, error)
            delay(1_000)
            remaining -= 1_000
        }
    }

    private fun rejection(request: ConnectRequest): Pair<RejectReason, String>? {
        val host = request.server.host.lowercase()
        return when {
            "reject" in host || request.password == "wrong" ->
                RejectReason.WrongServerPassword to "Wrong certificate or password for existing user"

            "full" in host -> RejectReason.ServerFull to "Server is full"

            request.username.isBlank() -> RejectReason.InvalidUsername to "Invalid username"

            else -> null
        }
    }

    private fun establishSession(request: ConnectRequest) {
        val server = request.server
        val legacy = "legacy" in server.host.lowercase()
        val voice = _voiceState.value
        val self = User(
            session = FakeWorld.SELF_SESSION,
            name = request.username,
            channel = ChannelId.ROOT,
            registeredId = if (request.certificate != null) 77 else null,
            selfMuted = voice.selfMuted,
            selfDeafened = voice.selfDeafened,
        )
        _serverState.value = ServerState(
            channels = FakeWorld.channels(serverName = server.host),
            users = FakeWorld.users() + (self.session to self),
            self = self.session,
        )
        _capabilities.value = ServerCapabilities(
            video = !legacy,
            maxAudioBandwidth = 72_000,
            maxVideoBandwidth = if (legacy) 0 else 3_000_000,
            maxVideoStreamsPerChannel = if (legacy) 0 else 4,
            maxMessageLength = MAX_MESSAGE_LENGTH,
            maxImageMessageLength = 131_072,
        )
        _connectionState.value = ConnectionState.Connected(
            server = server,
            info = ServerInfo(
                welcomeText = "Welcome to <b>${server.host}</b>. This is a simulated server.",
                release = if (legacy) "1.5.735" else "1.6.0-video (simulated)",
                os = "Linux",
                osVersion = "Fedora 46",
                protocolVersion = if (legacy) "1.5.0" else "1.6.0",
                maxUsers = 100,
                udpActive = !request.forceTcp,
                latencyMillis = 18 + random.nextInt(40),
            ),
        )
        if (!legacy) {
            // The server replays VideoState for streams already running when we join.
            _videoStreams.value = FakeWorld.initialStreams()
        }
        if (simulateActivity) startSimulation(legacy)
    }

    private var simulationJob: Job? = null

    private fun startSimulation(legacy: Boolean) {
        simulationJob = scope.launch {
            launch { simulateTalking() }
            launch { simulateSelfTransmit() }
            launch { simulateChat() }
            launch { simulateMovement() }
            launch { simulateWatchers() }
            if (!legacy) launch { simulateStreams() }
        }
    }

    private fun resetSession() {
        simulationJob?.cancel()
        simulationJob = null
        pendingTrust?.cancel()
        pendingTrust = null
        synchronized(renderers) {
            renderers.values.forEach { it.stop() }
            renderers.clear()
        }
        _serverState.value = ServerState.EMPTY
        _talkStates.value = emptyMap()
        _capabilities.value = ServerCapabilities.NONE
        _videoStreams.value = emptyList()
        _watchers.value = emptyMap()
        _localVideo.value = LocalVideoState()
        _voiceState.update { it.copy(transmitting = false, pushToTalkActive = false) }
    }

    // ---------------------------------------------------------------------------------------------
    // Server model
    // ---------------------------------------------------------------------------------------------

    override suspend fun joinChannel(channel: ChannelId) {
        val state = requireConnected()
        val target = state.channels[channel] ?: throw MumbleCoreException(Kind.InvalidArgument, "No such channel")
        val self = state.selfUser ?: throw MumbleCoreException(Kind.NotConnected, "Not synchronised")
        if (!target.canEnter) {
            _events.tryEmit(CoreEvent.PermissionDenied("You don't have permission to enter ${target.name}"))
            throw MumbleCoreException(Kind.PermissionDenied, "You don't have permission to enter ${target.name}")
        }
        moveUser(self.session, channel)
    }

    override suspend fun requestUserBlob(session: UserSession, blob: UserBlob): ByteArray? {
        val user = _serverState.value.users[session] ?: return null
        delay(150)
        return when (blob) {
            UserBlob.Comment -> user.comment?.encodeToByteArray()
            UserBlob.Avatar -> null
        }
    }

    private fun moveUser(session: UserSession, to: ChannelId) {
        var from: ChannelId? = null
        _serverState.update { state ->
            val user = state.users[session] ?: return@update state
            from = user.channel
            state.copy(users = state.users + (session to user.copy(channel = to)))
        }
        from?.takeIf { it != to }?.let { _events.tryEmit(CoreEvent.UserMoved(session, it, to)) }
    }

    // ---------------------------------------------------------------------------------------------
    // Voice
    // ---------------------------------------------------------------------------------------------

    override suspend fun setSelfMuted(muted: Boolean) {
        _voiceState.update {
            if (muted) {
                it.copy(selfMuted = true)
            } else {
                it.copy(selfMuted = false, selfDeafened = false)
            }
        }
        syncSelfUser()
    }

    override suspend fun setSelfDeafened(deafened: Boolean) {
        _voiceState.update {
            if (deafened) {
                it.copy(
                    selfMuted = true,
                    selfDeafened = true,
                )
            } else {
                it.copy(selfDeafened = false, selfMuted = false)
            }
        }
        syncSelfUser()
    }

    override suspend fun setAudioConfig(config: AudioConfig) {
        audioConfig = config
        _voiceState.update {
            it.copy(
                inputMode = config.inputMode,
                pushToTalkActive = if (config.inputMode == VoiceInputMode.PushToTalk) it.pushToTalkActive else false,
            )
        }
    }

    override fun setPushToTalk(pressed: Boolean) {
        _voiceState.update { state ->
            if (state.inputMode != VoiceInputMode.PushToTalk) return@update state
            val active = if (audioConfig.pushToTalkToggles) {
                if (pressed) !state.pushToTalkActive else state.pushToTalkActive
            } else {
                pressed
            }
            state.copy(pushToTalkActive = active)
        }
    }

    override fun inputLevels(): Flow<AudioLevel> = flow {
        var t = 0.0
        while (true) {
            t += LEVEL_INTERVAL_MS / 1000.0
            // A slow "syllable" envelope over a noise floor, so the meter looks like speech.
            val envelope = (sin(t * 2 * PI * 0.6) + 1) / 2
            val syllables = abs(sin(t * 2 * PI * 3.1))
            val level = (0.08 + 0.75 * envelope * syllables + random.nextDouble(0.0, 0.06)).toFloat().coerceIn(0f, 1f)
            emit(AudioLevel(level, level >= audioConfig.vadThreshold))
            delay(LEVEL_INTERVAL_MS)
        }
    }

    private fun syncSelfUser() {
        val voice = _voiceState.value
        _serverState.update { state ->
            val self = state.selfUser ?: return@update state
            state.copy(
                users =
                    state.users +
                        (self.session to self.copy(selfMuted = voice.selfMuted, selfDeafened = voice.selfDeafened)),
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Chat
    // ---------------------------------------------------------------------------------------------

    override suspend fun sendMessage(target: MessageTarget, text: String) {
        val state = requireConnected()
        if (text.length > MAX_MESSAGE_LENGTH) throw MumbleCoreException(Kind.MessageTooLong, "Message is too long")
        val self = state.selfUser ?: throw MumbleCoreException(Kind.NotConnected, "Not synchronised")
        emitMessage(self.session, self.name, target, text, isOwn = true)

        if (target is MessageTarget.ToUser && simulateActivity) {
            val other = state.users[target.session] ?: return
            scope.launch {
                delay(1_000L + random.nextLong(2_000))
                // Only answer if we're still on the same connection.
                if (_serverState.value.users.containsKey(other.session)) {
                    emitMessage(
                        other.session,
                        other.name,
                        MessageTarget.ToUser(self.session),
                        FakeWorld.privateReplies.random(random),
                    )
                }
            }
        }
    }

    private fun emitMessage(
        sender: UserSession?,
        name: String,
        target: MessageTarget,
        html: String,
        isOwn: Boolean = false,
    ) {
        _messages.tryEmit(
            ChatMessage(
                id = messageIds.incrementAndGet(),
                timestampEpochMillis = clock(),
                sender = sender,
                senderName = name,
                target = target,
                html = html,
                isOwn = isOwn,
            ),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Video
    // ---------------------------------------------------------------------------------------------

    override suspend fun subscribe(stream: StreamKey, preferredBandwidth: Int) {
        requireConnected()
        val known = _videoStreams.value.firstOrNull { it.key == stream }
            ?: throw MumbleCoreException(Kind.InvalidArgument, "Unknown stream $stream")
        if (known.subscription is Subscription.Active || known.subscription == Subscription.Pending) return
        updateStream(stream) { it.copy(subscription = Subscription.Pending) }
        scope.launch {
            delay(SUBSCRIBE_LATENCY_MS)
            updateStream(stream) {
                if (it.subscription != Subscription.Pending) {
                    it
                } else {
                    it.copy(
                        subscription = Subscription.Active(
                            it.width,
                            it.height,
                            it.maxFramerate.toFloat(),
                            lossPermille = 0,
                        ),
                    )
                }
            }
        }
    }

    override suspend fun unsubscribe(stream: StreamKey) {
        setVideoSurface(stream, null)
        updateStream(stream) { if (it.isLocal) it else it.copy(subscription = Subscription.None) }
    }

    override fun setVideoSurface(stream: StreamKey, surface: Surface?) {
        synchronized(renderers) {
            renderers.remove(stream)?.stop()
            if (surface != null) {
                renderers[stream] = TestPatternRenderer(surface) { frameLabel(stream) }.also { it.start() }
            }
        }
    }

    private fun frameLabel(key: StreamKey): TestPatternRenderer.Label? {
        val stream = _videoStreams.value.firstOrNull { it.key == key } ?: return null
        val user = _serverState.value.users[key.session]?.name ?: "?"
        val active = stream.subscription is Subscription.Active
        return TestPatternRenderer.Label(
            title = user,
            subtitle = "${stream.sourceName} · ${stream.codec} ${stream.width}×${stream.height}@${stream.maxFramerate}",
            screen = stream.sourceKind.isScreen,
            audio = stream.audio?.let { if (it.muted) "audio muted" else "audio ${(it.volume * 100).toInt()}%" },
            waiting = !active,
            seed = key.hashCode(),
        )
    }

    override suspend fun setStreamAudio(stream: StreamKey, volume: Float, muted: Boolean) {
        updateStream(stream) { s -> s.copy(audio = s.audio?.copy(volume = volume.coerceIn(0f, 2f), muted = muted)) }
    }

    override suspend fun startCamera(request: CameraRequest) {
        startLocal(
            VideoSourceKind.Camera,
            "${request.facing} camera",
            request.width,
            request.height,
            request.framerate,
            request.codec,
        ) { copy(camera = it) }
    }

    override suspend fun stopCamera() = stopLocal(_localVideo.value.camera) { copy(camera = null) }

    override suspend fun startScreenShare(request: ScreenShareRequest) {
        startLocal(
            VideoSourceKind.Display,
            "This device",
            request.width,
            request.height,
            request.framerate,
            request.codec,
        ) { copy(screen = it) }
    }

    override suspend fun stopScreenShare() = stopLocal(_localVideo.value.screen) { copy(screen = null) }

    private fun startLocal(
        kind: VideoSourceKind,
        name: String,
        width: Int,
        height: Int,
        framerate: Int,
        codec: VideoCodec?,
        assign: LocalVideoState.(LocalStream) -> LocalVideoState,
    ) {
        val state = requireConnected()
        if (!_capabilities.value.video) {
            throw MumbleCoreException(
                Kind.NotSupported,
                "This server does not support video",
            )
        }
        val self = state.self ?: throw MumbleCoreException(Kind.NotConnected, "Not synchronised")
        val key = StreamKey(self, localStreamIds.incrementAndGet())
        val chosen = codec ?: VideoCodec.VP9
        val local = LocalStream(key, width, height, framerate, chosen)
        _localVideo.update { it.assign(local) }
        _videoStreams.update {
            it + VideoStream(
                key = key,
                codec = chosen,
                sourceKind = kind,
                sourceName = "$name (simulated)",
                width = width,
                height = height,
                maxFramerate = framerate,
                targetBandwidth = 1_000_000,
                subscription = Subscription.Active(width, height, framerate.toFloat(), 0),
                isLocal = true,
            )
        }
        _watchers.update { it + (key to emptySet()) }
    }

    private fun stopLocal(local: LocalStream?, clear: LocalVideoState.() -> LocalVideoState) {
        local ?: return
        setVideoSurface(local.key, null)
        _localVideo.update { it.clear() }
        _videoStreams.update { streams -> streams.filterNot { it.key == local.key } }
        _watchers.update { it - local.key }
    }

    private fun updateStream(key: StreamKey, transform: (VideoStream) -> VideoStream) {
        _videoStreams.update { streams -> streams.map { if (it.key == key) transform(it) else it } }
    }

    // ---------------------------------------------------------------------------------------------
    // Certificates
    // ---------------------------------------------------------------------------------------------

    override suspend fun generateCertificate(commonName: String, password: String): ClientCertificate {
        delay(600) // key generation is not instant on a phone either
        return FakeCertificates.generate(commonName, password, clock())
    }

    override suspend fun inspectCertificate(pkcs12: ByteArray, password: String): CertificateInfo =
        FakeCertificates.inspect(pkcs12, password)

    // ---------------------------------------------------------------------------------------------
    // Simulation
    // ---------------------------------------------------------------------------------------------

    private suspend fun simulateTalking() = coroutineScope {
        while (isActive) {
            delay(200L + random.nextLong(700))
            val candidates = _serverState.value.users.values.filter {
                it.session != FakeWorld.SELF_SESSION && !it.isSilenced
            }
            val user = candidates.randomOrNull(random) ?: continue
            if (user.session in _talkStates.value) continue
            val state = when (random.nextInt(12)) {
                0 -> TalkState.Shouting
                1 -> TalkState.Whispering
                else -> TalkState.Talking
            }
            launch {
                _talkStates.update { it + (user.session to state) }
                delay(500L + random.nextLong(3_000))
                _talkStates.update { it - user.session }
            }
        }
    }

    /** Derives whether "we" are transmitting from the input mode, as the real audio thread would. */
    private suspend fun simulateSelfTransmit() {
        var t = 0.0
        while (scope.isActive) {
            delay(LEVEL_INTERVAL_MS)
            t += LEVEL_INTERVAL_MS / 1000.0
            val voice = _voiceState.value
            val transmitting = !voice.selfMuted && when (voice.inputMode) {
                VoiceInputMode.Continuous -> true
                VoiceInputMode.PushToTalk -> voice.pushToTalkActive
                VoiceInputMode.VoiceActivity -> sin(t * 2 * PI / 7) > 0.55 // talk now and then
            }
            if (transmitting != voice.transmitting) {
                _voiceState.update { it.copy(transmitting = transmitting) }
                _talkStates.update {
                    if (transmitting) {
                        it + (FakeWorld.SELF_SESSION to TalkState.Talking)
                    } else {
                        it -
                            FakeWorld.SELF_SESSION
                    }
                }
            }
        }
    }

    private suspend fun simulateChat() {
        delay(2_500)
        emitMessage(
            null,
            "Server",
            MessageTarget.ToChannel(ChannelId.ROOT),
            "Welcome! This server is simulated by <i>core-fake</i>.",
        )
        while (scope.isActive) {
            delay(7_000L + random.nextLong(10_000))
            val state = _serverState.value
            val self = state.selfUser ?: continue
            val sender = state.users.values.filter { it.session != self.session }.randomOrNull(random) ?: continue
            val target = if (random.nextInt(6) == 0) {
                MessageTarget.ToUser(self.session)
            } else {
                MessageTarget.ToChannel(self.channel)
            }
            emitMessage(sender.session, sender.name, target, FakeWorld.chatLines.random(random))
        }
    }

    private suspend fun simulateMovement() {
        while (scope.isActive) {
            delay(15_000L + random.nextLong(20_000))
            val state = _serverState.value
            val user =
                state.users.values.filter { it.session != FakeWorld.SELF_SESSION }.randomOrNull(random) ?: continue
            val channel =
                state.channels.values.filter { it.canEnter && it.id != user.channel }.randomOrNull(random) ?: continue
            moveUser(user.session, channel.id)
        }
    }

    private suspend fun simulateStreams() {
        var streamId = 0
        while (scope.isActive) {
            delay(25_000)
            val stream = FakeWorld.intermittentStream(streamId++)
            _videoStreams.update { it + stream }
            _events.tryEmit(CoreEvent.StreamStarted(stream.key))
            delay(35_000)
            setVideoSurface(stream.key, null)
            _videoStreams.update { streams -> streams.filterNot { it.key == stream.key } }
            _events.tryEmit(CoreEvent.StreamEnded(stream.key))
        }
    }

    private suspend fun simulateWatchers() {
        while (scope.isActive) {
            delay(2_000L + random.nextLong(4_000))
            val streams = _watchers.value.keys
            val stream = streams.randomOrNull(random) ?: continue
            val user = _serverState.value.users.values.filter { it.session != FakeWorld.SELF_SESSION }
                .randomOrNull(random) ?: continue
            var watching = false
            _watchers.update { current ->
                val set = current[stream] ?: return@update current
                watching = user.session !in set || set.size < 2
                current + (stream to if (watching) set + user.session else set - user.session)
            }
            _events.tryEmit(CoreEvent.WatcherChanged(stream, user.session, watching))
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun requireConnected(): ServerState {
        if (_connectionState.value !is ConnectionState.Connected) {
            throw MumbleCoreException(Kind.NotConnected, "Not connected")
        }
        return _serverState.value
    }

    override fun close() {
        synchronized(renderers) {
            renderers.values.forEach { it.stop() }
            renderers.clear()
        }
        scope.cancel()
    }

    private fun fakeServerCertificate(server: ServerAddress): ServerCertificate {
        val now = clock()
        val digest = MessageDigest.getInstance("SHA-256").digest("${server.host}:${server.port}".encodeToByteArray())
        return ServerCertificate(
            subject = "CN=${server.host}",
            issuer = "CN=${server.host}",
            notBeforeEpochMillis = now - 200L * DAY_MS,
            notAfterEpochMillis = now + 3_450L * DAY_MS,
            sha256 = digest.joinToString("") { "%02x".format(it) },
            problem = "The certificate is self-signed.",
        )
    }

    private companion object {
        const val STEP_MS = 350L
        const val DROP_AFTER_MS = 20_000L
        const val RECONNECT_DELAY_MS = 3_000L
        const val SUBSCRIBE_LATENCY_MS = 450L
        const val LEVEL_INTERVAL_MS = 50L
        const val MAX_MESSAGE_LENGTH = 5_000
        const val DAY_MS = 86_400_000L
    }
}
