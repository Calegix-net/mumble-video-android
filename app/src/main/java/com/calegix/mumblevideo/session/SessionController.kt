package com.calegix.mumblevideo.session

import android.content.Intent
import com.calegix.mumblevideo.core.ChatMessage
import com.calegix.mumblevideo.core.ConnectRequest
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.MumbleCore
import com.calegix.mumblevideo.core.MumbleCoreException
import com.calegix.mumblevideo.core.ScreenShareRequest
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.data.certificates.CertificateRepository
import com.calegix.mumblevideo.data.servers.SavedServer
import com.calegix.mumblevideo.data.servers.ServerRepository
import com.calegix.mumblevideo.data.servers.TrustStore
import com.calegix.mumblevideo.data.settings.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App-level owner of "the" session: turns a saved server plus settings into a [ConnectRequest],
 * keeps the chat log across screens, pushes settings changes into the core, and tells the
 * foreground service when to run. Lives as long as the process.
 */
class SessionController(
    val core: MumbleCore,
    private val settings: SettingsRepository,
    private val servers: ServerRepository,
    private val certificates: CertificateRepository,
    private val trustStore: TrustStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onSessionStarting: () -> Unit = {},
    /** Generate a certificate on the first connect if the user has none, as Mumla does. */
    private val autoCertificate: Boolean = false,
    /** Hands the screen-capture consent to the core (the native core needs it; the fake does not). */
    private val authorizeScreenCapture: (resultCode: Int, data: Intent) -> Unit = { _, _ -> },
) {
    private val _screenCaptureForeground = MutableStateFlow(false)

    /** Whether the foreground service must declare the media-projection type. */
    val screenCaptureForeground: StateFlow<Boolean> = _screenCaptureForeground.asStateFlow()
    private var foregroundUpdated: CompletableDeferred<Unit>? = null

    /**
     * Starts a screen share from the user's consent. Android 14+ requires the foreground service to
     * declare the media-projection type before the projection is created, so the service is
     * promoted first and this waits until it says so.
     */
    suspend fun startScreenShare(resultCode: Int, data: Intent, request: ScreenShareRequest) {
        val ready = CompletableDeferred<Unit>().also { foregroundUpdated = it }
        _screenCaptureForeground.value = true
        withTimeoutOrNull(3_000) { ready.await() }
        authorizeScreenCapture(resultCode, data)
        try {
            core.startScreenShare(request)
        } catch (e: MumbleCoreException) {
            _screenCaptureForeground.value = false
            throw e
        }
    }

    /** Called by the service once its foreground types are what [screenCaptureForeground] asks. */
    fun onForegroundTypesUpdated() {
        foregroundUpdated?.complete(Unit)
    }
    private val _currentServer = MutableStateFlow<SavedServer?>(null)

    /** The server of the current or most recent connection attempt. */
    val currentServer: StateFlow<SavedServer?> = _currentServer.asStateFlow()

    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())

    /** Chat history of the current connection, oldest first. Cleared on every new connect. */
    val chat: StateFlow<List<ChatMessage>> = _chat.asStateFlow()

    private val _unreadMessages = MutableStateFlow(0)
    val unreadMessages: StateFlow<Int> = _unreadMessages.asStateFlow()

    val subscriptions = SubscriptionManager(core, scope)

    init {
        scope.launch { core.messages.collect { message -> appendMessage(message) } }
        // The projection type is dropped again once the share has ended, however it ended.
        scope.launch {
            core.localVideo.map { it.screen != null }.distinctUntilChanged().collect { sharing ->
                if (!sharing && _screenCaptureForeground.value && foregroundUpdated?.isCompleted != false) {
                    _screenCaptureForeground.value = false
                }
            }
        }
        scope.launch {
            settings.settings.map { it.toAudioConfig() }.distinctUntilChanged().collect { core.setAudioConfig(it) }
        }
        // A reconnect is a new session on the server: pick the videos being watched up again.
        scope.launch {
            var session: UserSession? = null
            core.connectionState.collect { state ->
                if (state !is ConnectionState.Connected) return@collect
                val now = core.serverState.value.self
                if (session != null && now != session) subscriptions.resume()
                session = now
            }
        }
    }

    suspend fun connect(server: SavedServer) {
        if (autoCertificate) ensureCertificate()
        val current = settings.settings.value
        val certificate = current.selectedCertificateId?.let { certificates.load(it) }
        _currentServer.value = server
        _chat.value = emptyList()
        _unreadMessages.value = 0
        subscriptions.clear()
        onSessionStarting()
        core.setAudioConfig(current.toAudioConfig())
        core.connect(
            ConnectRequest(
                server = server.address,
                username = server.username.ifBlank { current.defaultUsername },
                password = server.password,
                certificate = certificate,
                accessTokens = server.accessTokens,
                trustedCertificateSha256 = trustStore.fingerprints(),
                forceTcp = current.forceTcp,
                autoReconnect = current.autoReconnect,
            ),
        )
        if (server.id != 0L) servers.markConnected(server.id, clock())
    }

    /**
     * Servers know registered users by certificate, so without one the user can never register and
     * every connection is a stranger. If none exists yet, make one quietly and select it. Someone
     * who deliberately chose "No certificate" while having some keeps that choice.
     */
    private suspend fun ensureCertificate() {
        if (settings.settings.value.selectedCertificateId != null) return
        if (certificates.certificates.first().isNotEmpty()) return
        val name = settings.settings.value.defaultUsername
        runCatching {
            val generated = core.generateCertificate(name)
            val info = core.inspectCertificate(generated.pkcs12, generated.password)
            val id = certificates.add(name, generated, info, clock())
            settings.update { it.copy(selectedCertificateId = id) }
        }
    }

    suspend fun disconnect() {
        subscriptions.clear()
        core.disconnect()
    }

    /** Answers a trust prompt; when accepted, the fingerprint is remembered for next time. */
    suspend fun answerCertificatePrompt(accept: Boolean) {
        val state = core.connectionState.value as? ConnectionState.CertificateTrustRequired ?: return
        if (accept) trustStore.trust(state.certificate.sha256, state.server, clock())
        core.respondToCertificate(accept)
    }

    suspend fun setAccessTokens(tokens: List<String>) {
        val server = _currentServer.value ?: return
        val updated = server.copy(accessTokens = tokens)
        _currentServer.value = updated
        if (server.id != 0L) servers.save(updated)
        core.setAccessTokens(tokens)
    }

    fun markChatRead() {
        _unreadMessages.value = 0
    }

    fun clearChat() {
        _chat.value = emptyList()
        _unreadMessages.value = 0
    }

    private fun appendMessage(message: ChatMessage) {
        _chat.update { trimHistory(it + message) }
        if (!message.isOwn) _unreadMessages.update { it + 1 }
    }

    internal companion object {
        const val MAX_HISTORY = 500

        /**
         * History is also capped by size: a message can be up to 8 MB, so 500 of them could hold
         * gigabytes. 16 M characters (32 MB) keeps hundreds of ordinary pictures.
         */
        const val MAX_HISTORY_CHARS = 16_000_000L

        /** The newest messages within both caps; the newest one is always kept. */
        fun trimHistory(history: List<ChatMessage>): List<ChatMessage> {
            val recent = history.takeLast(MAX_HISTORY)
            var chars = 0L
            var keep = 0
            for (message in recent.asReversed()) {
                chars += message.html.length
                if (keep > 0 && chars > MAX_HISTORY_CHARS) break
                keep++
            }
            return if (keep == recent.size) recent else recent.takeLast(keep)
        }
    }
}
