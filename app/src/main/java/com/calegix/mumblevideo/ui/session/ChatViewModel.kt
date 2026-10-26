package com.calegix.mumblevideo.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calegix.mumblevideo.core.ChatMessage
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.MessageTarget
import com.calegix.mumblevideo.core.MumbleCoreException
import com.calegix.mumblevideo.core.ServerState
import com.calegix.mumblevideo.data.settings.SettingsRepository
import com.calegix.mumblevideo.session.SessionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Who a chat message will go to, with a label for the UI. */
data class ChatTarget(val target: MessageTarget, val label: String)

data class ChatEntry(val message: ChatMessage, val targetLabel: String)

data class ChatUiState(
    val entries: List<ChatEntry> = emptyList(),
    /** Null means "my current channel", which follows the user around. */
    val target: ChatTarget? = null,
    val availableTargets: List<ChatTarget> = emptyList(),
    val currentChannelLabel: String = "",
    val error: String? = null,
    /** Show embedded images (Settings > General > Load images). */
    val loadImages: Boolean = true,
    /** Longest image message the server accepts, in characters. */
    val imageLimit: Int = 0,
    val sendingImage: Boolean = false,
    /**
     * The server's welcome message (HTML), shown first in the chat as the desktop client logs it on
     * connecting. Empty when the server has none or we are not connected.
     */
    val welcome: String = "",
)

class ChatViewModel(
    private val session: SessionController,
    settings: SettingsRepository,
) : ViewModel() {
    private val core = session.core
    private val selectedTarget = MutableStateFlow<MessageTarget?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val sendingImage = MutableStateFlow(false)

    /** Kept through a reconnect, so the welcome message does not blink out and back. */
    private var welcome = ""

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    val uiState: StateFlow<ChatUiState> =
        combine(
            combine(session.chat, core.serverState, selectedTarget, error, ::Quad),
            settings.settings,
            core.capabilities,
            sendingImage,
            core.connectionState,
        ) { (chat, state, selected, error), prefs, caps, sending, connection ->
            val channelLabel = state.selfUser?.channel?.let { state.channels[it]?.name }.orEmpty()
            val targets = buildList {
                state.selfUser?.channel?.let {
                    add(ChatTarget(MessageTarget.ToChannel(it), "#$channelLabel"))
                    add(ChatTarget(MessageTarget.ToChannelTree(it), "#$channelLabel and subchannels"))
                }
                state.users.values.filter { it.session != state.self }.sortedBy { it.name.lowercase() }
                    .forEach { add(ChatTarget(MessageTarget.ToUser(it.session), "@${it.name}")) }
            }
            ChatUiState(
                entries = chat.map { ChatEntry(it, label(it.target, state)) },
                target = selected?.let { sel -> ChatTarget(sel, label(sel, state)) },
                availableTargets = targets,
                currentChannelLabel = channelLabel,
                error = error,
                loadImages = prefs.loadImages,
                imageLimit = caps.maxImageMessageLength,
                sendingImage = sending,
                welcome = welcomeFor(connection),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    fun setDraft(text: String) {
        _draft.value = text
    }

    fun selectTarget(target: MessageTarget?) {
        selectedTarget.value = target
    }

    fun send() {
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        val target = selectedTarget.value
            ?: core.serverState.value.selfUser?.channel?.let { MessageTarget.ToChannel(it) }
            ?: return
        viewModelScope.launch {
            try {
                core.sendMessage(target, escapeHtml(text))
                _draft.value = ""
                error.value = null
            } catch (e: MumbleCoreException) {
                error.value = e.message
            }
        }
    }

    /**
     * Sends an image message. [encode] turns the picture into an `<img>` message no longer than the
     * limit it is given (see [ChatImages.toMessage]), and returns null if it cannot fit.
     */
    fun sendImage(encode: suspend (limit: Int) -> String?) {
        val target = currentTarget() ?: return
        viewModelScope.launch {
            sendingImage.value = true
            try {
                val html = encode(core.capabilities.value.maxImageMessageLength)
                if (html == null) {
                    error.value = "That picture is too large for this server, even shrunk"
                } else {
                    core.sendMessage(target, html)
                }
            } catch (e: MumbleCoreException) {
                error.value = e.message
            } finally {
                sendingImage.value = false
            }
        }
    }

    private fun welcomeFor(connection: ConnectionState): String {
        welcome = when (connection) {
            is ConnectionState.Connected -> connection.info.welcomeText.trim()
            is ConnectionState.Reconnecting -> welcome
            else -> ""
        }
        return welcome
    }

    private fun currentTarget(): MessageTarget? =
        selectedTarget.value ?: core.serverState.value.selfUser?.channel?.let { MessageTarget.ToChannel(it) }

    fun markRead() = session.markChatRead()

    fun clear() = session.clearChat()

    fun consumeError() {
        error.value = null
    }

    private fun label(target: MessageTarget, state: ServerState): String = when (target) {
        is MessageTarget.ToChannel -> "#" + (state.channels[target.channel]?.name ?: "?")
        is MessageTarget.ToChannelTree -> "#" + (state.channels[target.channel]?.name ?: "?") + " (tree)"
        is MessageTarget.ToUser -> "@" + (state.users[target.session]?.name ?: "?")
    }

    companion object {
        /** Mumble messages are HTML; typed text must not be interpreted as markup. */
        fun escapeHtml(text: String): String = buildString(text.length) {
            text.forEach { c ->
                when (c) {
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '&' -> append("&amp;")
                    '"' -> append("&quot;")
                    '\n' -> append("<br>")
                    else -> append(c)
                }
            }
        }
    }
}
