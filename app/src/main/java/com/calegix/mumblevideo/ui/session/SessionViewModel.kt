package com.calegix.mumblevideo.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calegix.mumblevideo.core.ChannelId
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.MumbleCoreException
import com.calegix.mumblevideo.core.ServerCapabilities
import com.calegix.mumblevideo.core.TalkState
import com.calegix.mumblevideo.core.User
import com.calegix.mumblevideo.core.UserBlob
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.core.VoiceInputMode
import com.calegix.mumblevideo.core.VoiceState
import com.calegix.mumblevideo.data.settings.AppSettings
import com.calegix.mumblevideo.data.settings.SettingsRepository
import com.calegix.mumblevideo.session.SessionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionUiState(
    val serverName: String = "",
    val connection: ConnectionState = ConnectionState.Disconnected(),
    val rows: List<TreeRow> = emptyList(),
    val voice: VoiceState = VoiceState(),
    val capabilities: ServerCapabilities = ServerCapabilities.NONE,
    val currentChannel: String? = null,
    val unreadMessages: Int = 0,
    val streamCount: Int = 0,
    val settings: AppSettings = AppSettings(),
) {
    val connected: Boolean get() = connection is ConnectionState.Connected
    val showPttButton: Boolean
        get() = voice.inputMode == VoiceInputMode.PushToTalk && !settings.hidePttButton && connected
}

/** What the user sheet shows for one user. */
data class UserDetails(val user: User, val comment: String?, val loadingComment: Boolean)

class SessionViewModel(
    private val session: SessionController,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val core = session.core
    private val collapsed = MutableStateFlow<Set<ChannelId>>(emptySet())

    private val tree = combine(core.serverState, collapsed, core.videoStreams) { state, collapsed, streams ->
        val streaming = streams
            .groupBy { it.key.session }
            .mapValues { (_, list) ->
                if (list.any { it.sourceKind.isScreen }) StreamingKind.Screen else StreamingKind.Camera
            }
        state to buildTree(state, collapsed, streaming)
    }

    val uiState: StateFlow<SessionUiState> = combine(
        tree,
        core.connectionState,
        core.voiceState,
        core.capabilities,
        combine(session.currentServer, session.unreadMessages, core.videoStreams, settings.settings, ::Quad),
    ) { (state, rows), connection, voice, capabilities, (server, unread, streams, settings) ->
        SessionUiState(
            serverName = server?.displayName.orEmpty(),
            connection = connection,
            rows = rows,
            voice = voice,
            capabilities = capabilities,
            currentChannel = state.selfUser?.channel?.let { state.channels[it]?.name },
            unreadMessages = unread,
            streamCount = streams.count { !it.isLocal },
            settings = settings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionUiState())

    /** Talking states are kept apart from [uiState] so they don't rebuild the tree. */
    val talkStates: StateFlow<Map<UserSession, TalkState>> = core.talkStates

    private val _message = MutableStateFlow<String?>(null)

    /** A one-off message for a snackbar. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _userDetails = MutableStateFlow<UserDetails?>(null)
    val userDetails: StateFlow<UserDetails?> = _userDetails.asStateFlow()

    fun toggleCollapsed(channel: ChannelId) = collapsed.update { if (channel in it) it - channel else it + channel }

    fun joinChannel(channel: ChannelId) {
        viewModelScope.launch {
            try {
                core.joinChannel(channel)
            } catch (e: MumbleCoreException) {
                _message.value = e.message
            }
        }
    }

    fun toggleMute() {
        viewModelScope.launch { core.setSelfMuted(!core.voiceState.value.selfMuted) }
    }

    fun toggleDeafen() {
        viewModelScope.launch { core.setSelfDeafened(!core.voiceState.value.selfDeafened) }
    }

    fun setInputMode(mode: VoiceInputMode) {
        viewModelScope.launch { settings.update { it.copy(inputMode = mode) } }
    }

    fun pushToTalk(pressed: Boolean) = core.setPushToTalk(pressed)

    fun disconnect() {
        viewModelScope.launch { session.disconnect() }
    }

    fun setAccessTokens(tokens: List<String>) {
        viewModelScope.launch { session.setAccessTokens(tokens.map { it.trim() }.filter { it.isNotEmpty() }) }
    }

    fun accessTokens(): List<String> = session.currentServer.value?.accessTokens.orEmpty()

    fun showUser(user: User) {
        _userDetails.value = UserDetails(user, user.comment, loadingComment = user.comment == null && user.hasComment)
        if (user.comment == null && user.hasComment) {
            viewModelScope.launch {
                val comment = core.requestUserBlob(user.session, UserBlob.Comment)?.decodeToString()
                _userDetails.update {
                    if (it?.user?.session ==
                        user.session
                    ) {
                        it.copy(comment = comment, loadingComment = false)
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun dismissUser() {
        _userDetails.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }
}

internal data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
