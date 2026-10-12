package com.calegix.mumblevideo.client

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.calegix.mumblevideo.client.audio.AudioCapture
import com.calegix.mumblevideo.client.audio.AudioPlayback
import com.calegix.mumblevideo.client.audio.AudioRouting
import com.calegix.mumblevideo.client.crypto.OcbCrypt
import com.calegix.mumblevideo.client.net.ControlFraming
import com.calegix.mumblevideo.client.net.ControlMessage
import com.calegix.mumblevideo.client.net.ControlReader
import com.calegix.mumblevideo.client.net.ControlType
import com.calegix.mumblevideo.client.net.RecordingTrustManager
import com.calegix.mumblevideo.client.net.Tls
import com.calegix.mumblevideo.client.net.UdpChannel
import com.calegix.mumblevideo.client.state.SessionReducer
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
import com.calegix.mumblevideo.core.LocalVideoState
import com.calegix.mumblevideo.core.MessageTarget
import com.calegix.mumblevideo.core.MumbleCore
import com.calegix.mumblevideo.core.MumbleCoreException
import com.calegix.mumblevideo.core.MumbleCoreException.Kind
import com.calegix.mumblevideo.core.RejectReason
import com.calegix.mumblevideo.core.ScreenShareRequest
import com.calegix.mumblevideo.core.ServerCapabilities
import com.calegix.mumblevideo.core.ServerInfo
import com.calegix.mumblevideo.core.ServerState
import com.calegix.mumblevideo.core.StreamKey
import com.calegix.mumblevideo.core.Subscription
import com.calegix.mumblevideo.core.TalkState
import com.calegix.mumblevideo.core.UserBlob
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.core.VideoStream
import com.calegix.mumblevideo.core.VoiceInputMode
import com.calegix.mumblevideo.core.VoiceState
import com.calegix.mumblevideo.proto.MumbleProto
import com.calegix.mumblevideo.proto.MumbleUDPProto
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The real [MumbleCore]: TLS control channel, UDP voice with OCB2, Opus capture and playback.
 *
 * Threads: a coroutine "actor" owns all protocol state and applies control messages in order;
 * a blocking reader thread feeds it; the UDP receive thread and the two audio threads touch only
 * the thread-safe pieces (crypt halves, jitter buffers, StateFlow updates).
 *
 * Video: announced streams are tracked from VideoState, but receiving them needs mumble-core's
 * video transport over JNI, which is not bound yet, so subscriptions report that.
 */
class NativeMumbleCore(private val context: Context) : MumbleCore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    override val watchers: StateFlow<Map<StreamKey, Set<UserSession>>> = MutableStateFlow(emptyMap())
    override val localVideo: StateFlow<LocalVideoState> = MutableStateFlow(LocalVideoState())

    private val _messages =
        MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val messages: Flow<ChatMessage> = _messages.asSharedFlow()

    private val _events =
        MutableSharedFlow<CoreEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<CoreEvent> = _events.asSharedFlow()

    @Volatile private var audioConfig = AudioConfig()
    private var connectionJob: Job? = null

    @Volatile private var link: Link? = null
    private var pendingTrust: CompletableDeferred<Boolean>? = null
    private val messageIds = AtomicLong(0)
    private val lastHeard = ConcurrentHashMap<UserSession, Long>()
    private val levelListeners = CopyOnWriteArrayList<(AudioLevel) -> Unit>()
    private val routing = AudioRouting(context)

    init {
        scope.launch { expireTalkStates() }
    }

    // ---------------------------------------------------------------------------------------------
    // Connection lifecycle
    // ---------------------------------------------------------------------------------------------

    override suspend fun connect(request: ConnectRequest) {
        connectionJob?.cancelAndJoin()
        connectionJob = scope.launch(Dispatchers.IO) { runWithReconnect(request) }
    }

    override suspend fun disconnect() {
        connectionJob?.cancelAndJoin()
        connectionJob = null
        _connectionState.value = ConnectionState.Disconnected(DisconnectReason.UserRequested)
    }

    override suspend fun respondToCertificate(accept: Boolean) {
        pendingTrust?.complete(accept)
    }

    override suspend fun setAccessTokens(tokens: List<String>) {
        link?.let { it.request = it.request.copy(accessTokens = tokens) }
        link?.send(ControlType.AUTHENTICATE, MumbleProto.Authenticate.newBuilder().addAllTokens(tokens).build())
    }

    private suspend fun runWithReconnect(initial: ConnectRequest) {
        var request = initial
        var attempt = 0
        while (scope.isActive) {
            val outcome = try {
                runOnce(request) { trusted ->
                    request =
                        request.copy(trustedCertificateSha256 = request.trustedCertificateSha256 + trusted)
                }
            } catch (e: CancellationException) {
                throw e
            }
            when (outcome) {
                is Outcome.Final -> {
                    _connectionState.value = outcome.state
                    return
                }

                is Outcome.Dropped -> {
                    if (!request.autoReconnect || (!outcome.wasConnected && attempt == 0)) {
                        _connectionState.value = ConnectionState.Disconnected(outcome.reason)
                        return
                    }
                    attempt++
                    val wait = (2_000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(30_000L)
                    var remaining = wait
                    while (remaining > 0) {
                        _connectionState.value =
                            ConnectionState.Reconnecting(request.server, attempt, remaining, outcome.message)
                        delay(1_000)
                        remaining -= 1_000
                    }
                    if (outcome.wasConnected) attempt = attempt.coerceAtMost(1)
                }
            }
        }
    }

    private sealed interface Outcome {
        class Final(val state: ConnectionState) : Outcome

        class Dropped(val reason: DisconnectReason, val message: String, val wasConnected: Boolean) : Outcome
    }

    /** One connection, from DNS to teardown. */
    private suspend fun runOnce(request: ConnectRequest, onTrusted: (String) -> Unit): Outcome {
        val server = request.server
        _connectionState.value = ConnectionState.Connecting(server, ConnectPhase.Resolving)
        val address = try {
            withContext(Dispatchers.IO) { InetAddress.getByName(server.host) }
        } catch (e: UnknownHostException) {
            return Outcome.Dropped(DisconnectReason.Unreachable("Unknown host ${server.host}"), "Unknown host", false)
        }

        _connectionState.value = ConnectionState.Connecting(server, ConnectPhase.Handshaking)
        val trust = RecordingTrustManager()
        val ssl = try {
            Tls.context(request.certificate, trust)
        } catch (e: Exception) {
            val reason = "Your client certificate could not be loaded (${e.message}). " +
                "Pick another one in Settings › Authentication."
            return Outcome.Final(ConnectionState.Disconnected(DisconnectReason.Unreachable(reason)))
        }
        val socket = try {
            (ssl.socketFactory.createSocket() as SSLSocket).apply {
                connect(InetSocketAddress(address, server.port), CONNECT_TIMEOUT_MS)
                tcpNoDelay = true
                startHandshake()
            }
        } catch (e: IOException) {
            Log.i(TAG, "connect failed: ${e.message}")
            val reason = when {
                e is SSLException -> "TLS failed: ${e.message}"

                localNetworkBlocked(address) ->
                    "This server is on your local network, which Android only lets apps reach with the " +
                        "\"nearby devices\" permission. Allow it for Mumble Video in the app's settings."

                else -> "Could not connect: ${e.message}"
            }
            return Outcome.Dropped(DisconnectReason.Unreachable(reason), reason, false)
        }

        try {
            Tls.untrusted(socket, server.host, trust)?.let { cert ->
                if (cert.sha256 !in request.trustedCertificateSha256) {
                    val decision = CompletableDeferred<Boolean>().also { pendingTrust = it }
                    _connectionState.value = ConnectionState.CertificateTrustRequired(server, cert)
                    val accepted = decision.await()
                    pendingTrust = null
                    if (!accepted) {
                        return Outcome.Final(
                            ConnectionState.Disconnected(DisconnectReason.CertificateRejected),
                        )
                    }
                    onTrusted(cert.sha256)
                }
            }

            _connectionState.value = ConnectionState.Connecting(server, ConnectPhase.Authenticating)
            val session = Link(request, socket, address)
            link = session
            return session.run()
        } finally {
            link = null
            runCatching { socket.close() }
            resetSessionState()
        }
    }

    private fun localNetworkBlocked(address: InetAddress): Boolean =
        android.os.Build.VERSION.SDK_INT >= 37 &&
            (address.isSiteLocalAddress || address.isLinkLocalAddress) &&
            context.checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED

    private fun resetSessionState() {
        _serverState.value = ServerState.EMPTY
        _talkStates.value = emptyMap()
        _capabilities.value = ServerCapabilities.NONE
        _videoStreams.value = emptyList()
        _voiceState.update { it.copy(transmitting = false, pushToTalkActive = false) }
        lastHeard.clear()
    }

    /** Everything that lives exactly as long as one TLS connection. */
    private inner class Link(
        @Volatile var request: ConnectRequest,
        private val socket: SSLSocket,
        private val address: InetAddress,
    ) {
        private val output: OutputStream = socket.outputStream.buffered()
        private val inbox = Channel<ControlMessage>(Channel.UNLIMITED)
        private val crypt = OcbCrypt()
        private var udp: UdpChannel? = null
        private var capture: AudioCapture? = null
        private var playback: AudioPlayback? = null
        private var self: UserSession? = null
        private var serverVersion: MumbleProto.Version? = null
        private var welcome = ""

        @Volatile private var udpVerified = false

        @Volatile private var lastPongAt = 0L

        @Volatile private var tcpPingMillis = 0
        private var udpPingsUnanswered = 0
        private var outFrameNumber = 0L
        private var pendingBlobs = ConcurrentHashMap<UserSession, CompletableDeferred<String?>>()

        /**
         * Queues a control message. Never blocks and never touches the socket on the caller's
         * thread (callers include the main thread and the audio thread); the writer thread does.
         */
        fun send(type: Int, message: MessageLite) {
            outbox.offer(ControlFraming.frame(type, message))
        }

        /** For UDPTunnel, whose payload is the UDP plaintext itself rather than a protobuf message. */
        fun sendRaw(type: Int, payload: ByteArray) {
            outbox.offer(ControlFraming.frame(type, payload))
        }

        private val outbox = LinkedBlockingQueue<ByteArray>()

        private fun writeLoop() {
            try {
                while (true) {
                    val first = outbox.take()
                    if (first === STOP) break
                    output.write(first)
                    // Batch whatever else is already queued into one flush.
                    while (true) {
                        val next = outbox.poll() ?: break
                        if (next === STOP) return
                        output.write(next)
                    }
                    output.flush()
                }
            } catch (e: IOException) {
                Log.d(TAG, "control write failed: ${e.message}")
                runCatching { socket.close() }
            }
        }

        suspend fun run(): Outcome {
            val reader = Thread({ readLoop(socket.inputStream) }, "mv-control-read").apply { start() }
            val writer = Thread(::writeLoop, "mv-control-write").apply { start() }
            val pinger = scope.launch(Dispatchers.IO) { pingLoop() }
            try {
                send(
                    ControlType.VERSION,
                    MumbleProto.Version.newBuilder()
                        .setVersionV1(CLIENT_VERSION_V1)
                        .setVersionV2(CLIENT_VERSION_V2)
                        .setRelease("Mumble Video for Android")
                        .setOs("Android")
                        .setOsVersion(android.os.Build.VERSION.RELEASE)
                        .build(),
                )
                send(
                    ControlType.AUTHENTICATE,
                    MumbleProto.Authenticate.newBuilder()
                        .setUsername(request.username)
                        .setPassword(request.password)
                        .addAllTokens(request.accessTokens)
                        .setOpus(true)
                        .addFeatures(MumbleProto.Feature.FEATURE_VIDEO)
                        .build(),
                )
                var connected = false
                for (message in inbox) {
                    val outcome = handle(message, connected)
                    if (message.type == ControlType.SERVER_SYNC) connected = true
                    if (outcome != null) return outcome
                }
                return Outcome.Dropped(
                    DisconnectReason.ConnectionLost("The server closed the connection"),
                    "Connection lost",
                    connected,
                )
            } finally {
                pinger.cancel()
                outbox.offer(STOP)
                writer.join(1_000)
                runCatching { socket.close() }
                reader.join(1_000)
                stopMedia()
            }
        }

        private fun readLoop(input: InputStream) {
            val reader = ControlReader()
            val buffer = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    reader.feed(buffer, n)
                    reader.drain().forEach { inbox.trySend(it) }
                }
            } catch (e: IOException) {
                Log.d(TAG, "control read ended: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "control stream broken", e)
            } finally {
                inbox.close()
            }
        }

        /** Applies one control message. Returns an outcome when the connection is over. */
        private fun handle(message: ControlMessage, connected: Boolean): Outcome? {
            val p = message.payload
            when (message.type) {
                ControlType.VERSION -> serverVersion = MumbleProto.Version.parseFrom(p)

                ControlType.REJECT -> {
                    val reject = MumbleProto.Reject.parseFrom(p)
                    return Outcome.Final(
                        ConnectionState.Rejected(request.server, rejectReason(reject.type), reject.reason),
                    )
                }

                ControlType.CRYPT_SETUP -> handleCryptSetup(MumbleProto.CryptSetup.parseFrom(p))

                ControlType.CHANNEL_STATE -> _serverState.update {
                    SessionReducer.channelState(it, MumbleProto.ChannelState.parseFrom(p))
                }

                ControlType.CHANNEL_REMOVE -> _serverState.update {
                    SessionReducer.channelRemove(it, MumbleProto.ChannelRemove.parseFrom(p))
                }

                ControlType.USER_STATE -> handleUserState(MumbleProto.UserState.parseFrom(p), connected)

                ControlType.USER_REMOVE -> return handleUserRemove(MumbleProto.UserRemove.parseFrom(p), connected)

                ControlType.SERVER_SYNC -> handleServerSync(MumbleProto.ServerSync.parseFrom(p))

                ControlType.SERVER_CONFIG -> handleServerConfig(MumbleProto.ServerConfig.parseFrom(p))

                ControlType.SUGGEST_CONFIG -> {
                    val suggest = MumbleProto.SuggestConfig.parseFrom(p)
                    if (suggest.hasPushToTalk()) {
                        _capabilities.update {
                            it.copy(suggestsPushToTalk = suggest.pushToTalk)
                        }
                    }
                }

                ControlType.TEXT_MESSAGE -> handleText(MumbleProto.TextMessage.parseFrom(p))

                ControlType.PERMISSION_DENIED -> {
                    val denied = MumbleProto.PermissionDenied.parseFrom(p)
                    Log.i(
                        TAG,
                        "permission denied: type=${denied.type} permission=${denied.permission} reason=${denied.reason}",
                    )
                    _events.tryEmit(CoreEvent.PermissionDenied(denied.reason.ifBlank { denyText(denied) }))
                }

                ControlType.PING -> {
                    val ping = MumbleProto.Ping.parseFrom(p)
                    if (ping.hasTimestamp()) tcpPingMillis = ((nowMicros() - ping.timestamp) / 1000).toInt()
                }

                // The payload is the raw UDP plaintext, not a protobuf message.
                ControlType.UDP_TUNNEL -> handleUdpPlaintext(p, tunnelled = true)

                ControlType.VIDEO_STATE -> {
                    val before = _videoStreams.value.map { it.key }.toSet()
                    _videoStreams.update { SessionReducer.videoState(it, MumbleProto.VideoState.parseFrom(p), self) }
                    val after = _videoStreams.value.map { it.key }.toSet()
                    (after - before).forEach { _events.tryEmit(CoreEvent.StreamStarted(it)) }
                    (before - after).forEach { _events.tryEmit(CoreEvent.StreamEnded(it)) }
                }

                else -> Unit // CodecVersion, PermissionQuery, UserStats, ACL...: not needed yet
            }
            return null
        }

        private fun handleCryptSetup(setup: MumbleProto.CryptSetup) {
            when {
                setup.hasKey() && setup.hasClientNonce() && setup.hasServerNonce() -> {
                    synchronized(crypt) {
                        crypt.setKey(
                            setup.key.toByteArray(),
                            setup.clientNonce.toByteArray(),
                            setup.serverNonce.toByteArray(),
                        )
                    }
                    startUdp()
                }

                setup.hasServerNonce() -> synchronized(crypt) { crypt.setDecryptIv(setup.serverNonce.toByteArray()) }

                else -> {
                    // The server asks for our nonce.
                    val nonce = synchronized(crypt) { crypt.encryptIv() }
                    send(
                        ControlType.CRYPT_SETUP,
                        MumbleProto.CryptSetup.newBuilder().setClientNonce(ByteString.copyFrom(nonce)).build(),
                    )
                }
            }
        }

        private fun handleServerSync(sync: MumbleProto.ServerSync) {
            self = UserSession(sync.session)
            welcome = sync.welcomeText
            _serverState.update { it.copy(self = self) }
            if (sync.hasMaxBandwidth()) _capabilities.update { it.copy(maxAudioBandwidth = sync.maxBandwidth) }
            // Tell the server our own mute state, which may have been set before connecting.
            val voice = _voiceState.value
            if (voice.selfMuted || voice.selfDeafened) {
                send(
                    ControlType.USER_STATE,
                    MumbleProto.UserState.newBuilder().setSession(
                        sync.session,
                    ).setSelfMute(voice.selfMuted).setSelfDeaf(voice.selfDeafened).build(),
                )
            }
            startAudio()
            publishConnected()
        }

        private fun handleServerConfig(config: MumbleProto.ServerConfig) {
            val video = MumbleProto.Feature.FEATURE_VIDEO in config.featuresList
            _capabilities.update {
                it.copy(
                    video = video,
                    fileTransfer = MumbleProto.Feature.FEATURE_FILE_TRANSFER in config.featuresList,
                    maxAudioBandwidth = if (config.hasMaxBandwidth()) config.maxBandwidth else it.maxAudioBandwidth,
                    maxVideoBandwidth = config.maxVideoBandwidth,
                    maxVideoStreamsPerChannel = config.maxVideoStreamsPerChannel,
                    maxMessageLength = config.messageLength,
                    maxImageMessageLength = config.imageMessageLength,
                    allowHtml = if (config.hasAllowHtml()) config.allowHtml else true,
                    recordingAllowed = if (config.hasRecordingAllowed()) config.recordingAllowed else true,
                )
            }
            if (config.hasWelcomeText()) welcome = config.welcomeText
            Log.i(
                TAG,
                "server config: messageLength=${config.messageLength} " +
                    "imageMessageLength=${config.imageMessageLength} " +
                    "allowHtml=${config.allowHtml} features=${config.featuresList}",
            )
            if (_connectionState.value is ConnectionState.Connected) publishConnected()
        }

        private fun publishConnected() {
            val v = serverVersion
            _connectionState.value = ConnectionState.Connected(
                server = request.server,
                info = ServerInfo(
                    welcomeText = welcome,
                    release = v?.release.orEmpty(),
                    os = v?.os.orEmpty(),
                    osVersion = v?.osVersion.orEmpty(),
                    protocolVersion = v?.let { versionString(it) }.orEmpty(),
                    maxUsers = 0,
                    udpActive = udpVerified,
                    latencyMillis = tcpPingMillis,
                ),
            )
        }

        private fun handleUserState(state: MumbleProto.UserState, connected: Boolean) {
            val session = UserSession(state.session)
            val before = _serverState.value.users[session]
            _serverState.update { SessionReducer.userState(it, state) }
            val after = _serverState.value.users[session] ?: return
            if (state.hasComment()) pendingBlobs.remove(session)?.complete(state.comment)
            if (connected && before == null) _events.tryEmit(CoreEvent.UserJoinedServer(session, after.name))
            if (before != null &&
                before.channel != after.channel
            ) {
                _events.tryEmit(CoreEvent.UserMoved(session, before.channel, after.channel))
            }
            if (session == self) {
                // The server is the authority on our own state (it may mute us, or unmute on undeafen).
                _voiceState.update { it.copy(selfMuted = after.selfMuted, selfDeafened = after.selfDeafened) }
                applyVoiceToAudio()
            }
        }

        private fun handleUserRemove(remove: MumbleProto.UserRemove, connected: Boolean): Outcome? {
            val session = UserSession(remove.session)
            if (session == self) {
                val by = if (remove.hasActor()) _serverState.value.users[UserSession(remove.actor)]?.name else null
                return Outcome.Final(
                    ConnectionState.Disconnected(DisconnectReason.Kicked(by, remove.reason, remove.ban)),
                )
            }
            val name = _serverState.value.users[session]?.name.orEmpty()
            _serverState.update { SessionReducer.userRemove(it, remove) }
            _videoStreams.update { streams -> streams.filterNot { it.key.session == session } }
            playback?.removeSpeaker(remove.session)
            if (connected) _events.tryEmit(CoreEvent.UserLeftServer(session, name))
            return null
        }

        private fun handleText(text: MumbleProto.TextMessage) {
            val actor = if (text.hasActor()) UserSession(text.actor) else null
            val target = when {
                text.sessionCount > 0 -> MessageTarget.ToUser(self ?: UserSession(0))
                text.treeIdCount > 0 -> MessageTarget.ToChannelTree(ChannelId(text.getTreeId(0)))
                text.channelIdCount > 0 -> MessageTarget.ToChannel(ChannelId(text.getChannelId(0)))
                else -> MessageTarget.ToChannel(_serverState.value.selfUser?.channel ?: ChannelId.ROOT)
            }
            _messages.tryEmit(
                ChatMessage(
                    id = messageIds.incrementAndGet(),
                    timestampEpochMillis = System.currentTimeMillis(),
                    sender = actor,
                    senderName = actor?.let { _serverState.value.users[it]?.name } ?: "Server",
                    target = target,
                    html = text.message,
                ),
            )
        }

        // --- UDP and voice ----------------------------------------------------------------------

        private fun startUdp() {
            if (udp != null) return
            udp = UdpChannel(
                server = InetSocketAddress(address, request.server.port),
                crypt = crypt,
                onPlaintext = { handleUdpPlaintext(it, tunnelled = false) },
            ).also {
                it.channelByte = true
                it.start()
                // Probe right away rather than at the first ping tick, so voice moves off TCP quickly.
                it.send(
                    byteArrayOf(UdpChannel.TYPE_PING) +
                        MumbleUDPProto.Ping.newBuilder().setTimestamp(nowMicros()).build().toByteArray(),
                )
            }
        }

        private fun handleUdpPlaintext(plain: ByteArray, tunnelled: Boolean) {
            if (plain.isEmpty()) return
            val body = plain.copyOfRange(1, plain.size)
            when (plain[0]) {
                UdpChannel.TYPE_PING -> {
                    if (!tunnelled) {
                        lastPongAt = SystemClock.elapsedRealtime()
                        udpPingsUnanswered = 0
                        if (!udpVerified) {
                            udpVerified = true
                            Log.i(TAG, "UDP verified")
                            if (_connectionState.value is ConnectionState.Connected) publishConnected()
                        }
                    }
                }

                UdpChannel.TYPE_AUDIO -> {
                    val audio = try {
                        MumbleUDPProto.Audio.parseFrom(body)
                    } catch (e: Exception) {
                        return
                    }
                    val sender = UserSession(audio.senderSession)
                    lastHeard[sender] = if (audio.isTerminator) 0L else SystemClock.elapsedRealtime()
                    val talk = when (audio.context) {
                        1 -> TalkState.Shouting
                        2 -> TalkState.Whispering
                        else -> TalkState.Talking
                    }
                    if (audio.isTerminator) {
                        _talkStates.update { it - sender }
                    } else if (_talkStates.value[sender] != talk) {
                        _talkStates.update { it + (sender to talk) }
                    }
                    playback?.push(
                        audio.senderSession,
                        audio.frameNumber,
                        audio.opusData.toByteArray(),
                        audio.isTerminator,
                    )
                }
            }
        }

        private suspend fun pingLoop() {
            var tick = 0
            while (scope.isActive) {
                delay(PING_INTERVAL_MS)
                tick++
                val now = nowMicros()
                val u = udp
                if (u != null && crypt.isValid) {
                    val ping = MumbleUDPProto.Ping.newBuilder().setTimestamp(now).build().toByteArray()
                    u.send(byteArrayOf(UdpChannel.TYPE_PING) + ping)
                    udpPingsUnanswered++
                    if (udpVerified && udpPingsUnanswered > 3) {
                        udpVerified = false
                        Log.i(TAG, "UDP lost, tunnelling voice over TCP")
                        if (_connectionState.value is ConnectionState.Connected) publishConnected()
                    }
                }
                // TCP keepalive every 15 s (the server times out after 30).
                if (tick % (15_000 / PING_INTERVAL_MS).toInt() == 0 || tick == 1) {
                    send(
                        ControlType.PING,
                        MumbleProto.Ping.newBuilder()
                            .setTimestamp(now)
                            .setGood(crypt.good.toInt())
                            .setLate(crypt.late.toInt())
                            .setLost(crypt.lost.toInt())
                            .setTcpPingAvg(tcpPingMillis.toFloat())
                            .build(),
                    )
                }
            }
        }

        private fun startAudio() {
            val config = audioConfig
            routing.begin(config)
            playback = AudioPlayback().also {
                it.deafened = _voiceState.value.selfDeafened
                it.start(config.outputStream)
            }
            val granted =
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.i(TAG, "no microphone permission: listen only")
                return
            }
            capture = AudioCapture(
                sink = ::sendVoice,
                levels = ::onLevel,
            ).also {
                it.config = config
                it.muted = _voiceState.value.selfMuted
                it.pushToTalk = _voiceState.value.pushToTalkActive
                if (!it.start()) Log.w(TAG, "microphone unavailable")
            }
            monitor?.stop()
            monitor = null
        }

        fun applyVoiceToAudio() {
            val voice = _voiceState.value
            capture?.muted = voice.selfMuted
            capture?.pushToTalk = voice.pushToTalkActive
            capture?.config = audioConfig
            playback?.deafened = voice.selfDeafened
        }

        private fun sendVoice(opus: ByteArray, frames: Int, terminator: Boolean) {
            val audio = MumbleUDPProto.Audio.newBuilder()
                .setTarget(0)
                .setFrameNumber(outFrameNumber)
                .setOpusData(ByteString.copyFrom(opus))
                .setIsTerminator(terminator)
                .build()
            outFrameNumber += frames
            val plain = byteArrayOf(UdpChannel.TYPE_AUDIO) + audio.toByteArray()
            val u = udp
            if (udpVerified && !request.forceTcp && u != null) {
                u.send(plain)
            } else {
                sendRaw(ControlType.UDP_TUNNEL, plain)
            }
        }

        private fun stopMedia() {
            capture?.stop()
            capture = null
            playback?.stop()
            playback = null
            udp?.close()
            udp = null
            routing.end()
        }

        suspend fun requestComment(session: UserSession): String? {
            val waiter = CompletableDeferred<String?>()
            pendingBlobs[session] = waiter
            send(
                ControlType.REQUEST_BLOB,
                MumbleProto.RequestBlob.newBuilder().addSessionComment(session.value).build(),
            )
            return withTimeoutOrNull(5_000) { waiter.await() }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------------------------------

    private fun requireLink(): Link = link?.takeIf { _connectionState.value is ConnectionState.Connected }
        ?: throw MumbleCoreException(Kind.NotConnected, "Not connected")

    override suspend fun joinChannel(channel: ChannelId) {
        val l = requireLink()
        val target =
            _serverState.value.channels[channel] ?: throw MumbleCoreException(Kind.InvalidArgument, "No such channel")
        if (!target.canEnter) {
            throw MumbleCoreException(
                Kind.PermissionDenied,
                "You don't have permission to enter ${target.name}",
            )
        }
        val self = _serverState.value.self ?: return
        l.send(
            ControlType.USER_STATE,
            MumbleProto.UserState.newBuilder().setSession(self.value).setChannelId(channel.value).build(),
        )
    }

    override suspend fun requestUserBlob(session: UserSession, blob: UserBlob): ByteArray? = when (blob) {
        UserBlob.Comment -> requireLink().requestComment(session)?.encodeToByteArray()
        UserBlob.Avatar -> null // texture fetching not wired yet
    }

    override suspend fun setSelfMuted(muted: Boolean) {
        _voiceState.update {
            if (muted) it.copy(selfMuted = true) else it.copy(selfMuted = false, selfDeafened = false)
        }
        pushSelfState()
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
        pushSelfState()
    }

    private fun pushSelfState() {
        val l = link ?: return
        l.applyVoiceToAudio()
        val self = _serverState.value.self ?: return
        val voice = _voiceState.value
        l.send(
            ControlType.USER_STATE,
            MumbleProto.UserState.newBuilder().setSession(
                self.value,
            ).setSelfMute(voice.selfMuted).setSelfDeaf(voice.selfDeafened).build(),
        )
    }

    override suspend fun setAudioConfig(config: AudioConfig) {
        audioConfig = config
        _voiceState.update {
            it.copy(
                inputMode = config.inputMode,
                pushToTalkActive = if (config.inputMode ==
                    VoiceInputMode.PushToTalk
                ) {
                    it.pushToTalkActive
                } else {
                    false
                },
            )
        }
        link?.applyVoiceToAudio()
        monitor?.config = config
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
        link?.applyVoiceToAudio()
    }

    @Volatile private var monitor: AudioCapture? = null

    private fun onLevel(level: Float, transmitting: Boolean) {
        val sample = AudioLevel(level, level >= audioConfig.vadThreshold)
        levelListeners.forEach { it(sample) }
        if (transmitting != _voiceState.value.transmitting) {
            _voiceState.update { it.copy(transmitting = transmitting) }
            val self = _serverState.value.self ?: return
            _talkStates.update { if (transmitting) it + (self to TalkState.Talking) else it - self }
        }
    }

    override fun inputLevels(): Flow<AudioLevel> = callbackFlow {
        val listener: (AudioLevel) -> Unit = { trySend(it) }
        levelListeners += listener
        // Not connected: open the microphone just for the meter.
        if (link == null && monitor == null &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            monitor = AudioCapture(sink = { _, _, _ -> }, levels = { level, _ ->
                val sample = AudioLevel(level, level >= audioConfig.vadThreshold)
                levelListeners.forEach { it(sample) }
            }).also {
                it.sending = false
                it.config = audioConfig
                it.start()
            }
        }
        awaitClose {
            levelListeners -= listener
            if (levelListeners.isEmpty()) {
                monitor?.stop()
                monitor = null
            }
        }
    }

    override suspend fun sendMessage(target: MessageTarget, text: String) {
        val l = requireLink()
        val max = _capabilities.value.let {
            if (text.contains("<img", ignoreCase = true)) it.maxImageMessageLength else it.maxMessageLength
        }
        if (max > 0 &&
            text.length > max
        ) {
            throw MumbleCoreException(Kind.MessageTooLong, "Message is too long for this server ($max)")
        }
        val builder = MumbleProto.TextMessage.newBuilder().setMessage(text)
        when (target) {
            is MessageTarget.ToChannel -> builder.addChannelId(target.channel.value)
            is MessageTarget.ToChannelTree -> builder.addTreeId(target.channel.value)
            is MessageTarget.ToUser -> builder.addSession(target.session.value)
        }
        l.send(ControlType.TEXT_MESSAGE, builder.build())
        Log.i(TAG, "text message sent: ${text.length} chars to $target")
        val state = _serverState.value
        _messages.tryEmit(
            ChatMessage(
                id = messageIds.incrementAndGet(),
                timestampEpochMillis = System.currentTimeMillis(),
                sender = state.self,
                senderName = state.selfUser?.name.orEmpty(),
                target = target,
                html = text,
                isOwn = true,
            ),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Video: stream list only until mumble-core's video transport is bound
    // ---------------------------------------------------------------------------------------------

    override suspend fun subscribe(stream: StreamKey, preferredBandwidth: Int) {
        _videoStreams.update { streams ->
            streams.map {
                if (it.key ==
                    stream
                ) {
                    it.copy(subscription = Subscription.Failed("Video playback isn't available in this build yet"))
                } else {
                    it
                }
            }
        }
    }

    override suspend fun unsubscribe(stream: StreamKey) {
        _videoStreams.update { streams ->
            streams.map {
                if (it.key ==
                    stream
                ) {
                    it.copy(subscription = Subscription.None)
                } else {
                    it
                }
            }
        }
    }

    override fun setVideoSurface(stream: StreamKey, surface: Surface?) = Unit

    override suspend fun setStreamAudio(stream: StreamKey, volume: Float, muted: Boolean) {
        _videoStreams.update { streams ->
            streams.map { s ->
                if (s.key ==
                    stream
                ) {
                    s.copy(audio = s.audio?.copy(volume = volume, muted = muted))
                } else {
                    s
                }
            }
        }
    }

    override suspend fun startCamera(request: CameraRequest): Unit =
        throw MumbleCoreException(Kind.NotSupported, "Sending video isn't available in this build yet")

    override suspend fun stopCamera() = Unit

    override suspend fun startScreenShare(request: ScreenShareRequest): Unit =
        throw MumbleCoreException(Kind.NotSupported, "Screen sharing isn't available in this build yet")

    override suspend fun stopScreenShare() = Unit

    // ---------------------------------------------------------------------------------------------

    override suspend fun generateCertificate(commonName: String, password: String): ClientCertificate =
        withContext(Dispatchers.Default) { Certificates.generate(commonName, password) }

    override suspend fun inspectCertificate(pkcs12: ByteArray, password: String): CertificateInfo =
        withContext(Dispatchers.Default) { Certificates.inspect(pkcs12, password) }

    override fun close() {
        scope.cancel()
    }

    /** Clears talking indicators for users whose packets stopped without a terminator. */
    private suspend fun expireTalkStates() {
        while (scope.isActive) {
            delay(100)
            val now = SystemClock.elapsedRealtime()
            val self = _serverState.value.self
            val stale = _talkStates.value.keys.filter { it != self && now - (lastHeard[it] ?: 0L) > TALK_TIMEOUT_MS }
            if (stale.isNotEmpty()) _talkStates.update { it - stale.toSet() }
        }
    }

    private companion object {
        const val TAG = "MvCore"
        val STOP = ByteArray(0)
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val PING_INTERVAL_MS = 5_000L
        const val TALK_TIMEOUT_MS = 300L

        // We speak the protocol of mumble-core's version: 1.7.0.
        const val CLIENT_VERSION_V1 = (1 shl 16) or (7 shl 8)
        const val CLIENT_VERSION_V2 = (1L shl 48) or (7L shl 32)

        fun nowMicros(): Long = SystemClock.elapsedRealtimeNanos() / 1000

        fun versionString(v: MumbleProto.Version): String = when {
            v.hasVersionV2() -> listOf(
                v.versionV2 ushr 48,
                (v.versionV2 ushr 32) and 0xFFFF,
                (v.versionV2 ushr 16) and 0xFFFF,
            )
                .joinToString(".")

            v.hasVersionV1() -> "${v.versionV1 ushr 16}.${(v.versionV1 ushr 8) and 0xFF}.${v.versionV1 and 0xFF}"

            else -> ""
        }

        fun rejectReason(type: MumbleProto.Reject.RejectType): RejectReason = when (type) {
            MumbleProto.Reject.RejectType.WrongVersion -> RejectReason.WrongVersion
            MumbleProto.Reject.RejectType.InvalidUsername -> RejectReason.InvalidUsername
            MumbleProto.Reject.RejectType.WrongUserPW -> RejectReason.WrongUserPassword
            MumbleProto.Reject.RejectType.WrongServerPW -> RejectReason.WrongServerPassword
            MumbleProto.Reject.RejectType.UsernameInUse -> RejectReason.UsernameInUse
            MumbleProto.Reject.RejectType.ServerFull -> RejectReason.ServerFull
            MumbleProto.Reject.RejectType.NoCertificate -> RejectReason.NoCertificate
            MumbleProto.Reject.RejectType.AuthenticatorFail -> RejectReason.AuthenticatorFail
            else -> RejectReason.None
        }

        fun denyText(denied: MumbleProto.PermissionDenied): String = when (denied.type) {
            MumbleProto.PermissionDenied.DenyType.TextTooLong -> "Message too long"
            MumbleProto.PermissionDenied.DenyType.ChannelFull -> "Channel is full"
            MumbleProto.PermissionDenied.DenyType.MissingCertificate -> "You need a certificate for that"
            else -> "Permission denied"
        }
    }
}
