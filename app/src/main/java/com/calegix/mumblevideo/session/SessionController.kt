package com.calegix.mumblevideo.session

import com.calegix.mumblevideo.core.ChatMessage
import com.calegix.mumblevideo.core.ConnectRequest
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.MumbleCore
import com.calegix.mumblevideo.data.certificates.CertificateRepository
import com.calegix.mumblevideo.data.servers.SavedServer
import com.calegix.mumblevideo.data.servers.ServerRepository
import com.calegix.mumblevideo.data.servers.TrustStore
import com.calegix.mumblevideo.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
) {
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
        scope.launch {
            settings.settings.map { it.toAudioConfig() }.distinctUntilChanged().collect { core.setAudioConfig(it) }
        }
    }

    suspend fun connect(server: SavedServer) {
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
        _chat.update { (it + message).takeLast(MAX_HISTORY) }
        if (!message.isOwn) _unreadMessages.update { it + 1 }
    }

    private companion object {
        const val MAX_HISTORY = 500
    }
}
