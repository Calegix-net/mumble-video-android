package com.calegix.mumblevideo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.DisconnectReason
import com.calegix.mumblevideo.core.MessageTarget
import com.calegix.mumblevideo.data.servers.SavedServer
import com.calegix.mumblevideo.data.settings.AppSettings
import com.calegix.mumblevideo.data.settings.SettingsRepository
import com.calegix.mumblevideo.session.SessionController
import com.calegix.mumblevideo.ui.session.ChatViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A dialog the whole app shows regardless of the current screen. */
sealed interface ConnectionDialog {
    data class TrustCertificate(val state: ConnectionState.CertificateTrustRequired) : ConnectionDialog

    data class Rejected(val state: ConnectionState.Rejected) : ConnectionDialog

    data class Failed(val title: String, val message: String) : ConnectionDialog
}

/** Activity-wide state: theme, connection dialogs, deep links and the hardware PTT key. */
class MainViewModel(
    private val session: SessionController,
    settings: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settings.settings

    val connectionState: StateFlow<ConnectionState> = session.core.connectionState

    /** Keep screen on (Settings > General), for the window while the app is in front. */
    val keepScreenOn: StateFlow<Boolean> = combine(
        settings.settings,
        session.core.connectionState,
        session.core.localVideo,
        session.subscriptions.watching,
    ) { s, state, local, watching ->
        s.keepsScreenOn(
            connected = state is ConnectionState.Connected,
            sendingVideo = local.camera != null || local.screen != null,
            watchingVideo = watching,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val dismissed = MutableStateFlow<ConnectionState?>(null)

    val dialog: StateFlow<ConnectionDialog?> = combine(session.core.connectionState, dismissed) { state, gone ->
        if (state == gone) null else state.toDialog()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _pendingLink = MutableStateFlow<SavedServer?>(null)

    /** A server parsed from a `mumble://` link, waiting for the server list to offer it. */
    val pendingLink: StateFlow<SavedServer?> = _pendingLink.asStateFlow()

    fun imageLimit(): Int = session.core.capabilities.value.maxImageMessageLength

    /** Posts a note and/or an image message to the current channel (debug progress reports). */
    fun post(text: String?, imageHtml: String?) {
        val channel = session.core.serverState.value.selfUser?.channel ?: return
        viewModelScope.launch {
            runCatching {
                if (!text.isNullOrBlank()) {
                    session.core.sendMessage(MessageTarget.ToChannel(channel), ChatViewModel.escapeHtml(text))
                }
                if (imageHtml != null) session.core.sendMessage(MessageTarget.ToChannel(channel), imageHtml)
            }
        }
    }

    private val _sharedPicture = MutableStateFlow<SharedPicture?>(null)

    /** A picture shared to the app from elsewhere, waiting for the user to confirm where it goes. */
    val sharedPicture: StateFlow<SharedPicture?> = _sharedPicture.asStateFlow()

    fun onPictureShared(uri: String) {
        val state = session.core.serverState.value
        val channel = state.selfUser?.channel?.let { state.channels[it] }
        _sharedPicture.value = SharedPicture(uri, channel?.name)
    }

    fun dismissSharedPicture() {
        _sharedPicture.value = null
    }

    /** Sends the shared picture; [encode] gets the server's image limit (see ChatImages.toMessage). */
    fun sendSharedPicture(encode: suspend (limit: Int) -> String?) {
        _sharedPicture.value = null
        viewModelScope.launch {
            val html = encode(imageLimit()) ?: return@launch
            post(null, html)
        }
    }

    fun answerCertificate(accept: Boolean) {
        viewModelScope.launch { session.answerCertificatePrompt(accept) }
    }

    fun dismissDialog() {
        dismissed.value = session.core.connectionState.value
    }

    fun onMumbleLink(url: String) {
        _pendingLink.value = MumbleUrl.parse(url)
    }

    fun consumeLink() {
        _pendingLink.value = null
    }

    fun isPttKey(keyCode: Int): Boolean = settings.value.pttKeyCode == keyCode

    /** Returns true if the key event was the configured PTT key and has been handled. */
    fun onKey(keyCode: Int, down: Boolean): Boolean {
        val pttKey = settings.value.pttKeyCode ?: return false
        if (keyCode != pttKey || session.core.connectionState.value !is ConnectionState.Connected) return false
        session.core.setPushToTalk(down)
        return true
    }

    private fun ConnectionState.toDialog(): ConnectionDialog? = when (this) {
        is ConnectionState.CertificateTrustRequired -> ConnectionDialog.TrustCertificate(this)

        is ConnectionState.Rejected -> ConnectionDialog.Rejected(this)

        is ConnectionState.Disconnected -> when (val r = reason) {
            is DisconnectReason.Unreachable -> ConnectionDialog.Failed("Could not connect", r.message)

            is DisconnectReason.ConnectionLost -> ConnectionDialog.Failed("Connection lost", r.message)

            is DisconnectReason.Kicked -> ConnectionDialog.Failed(
                if (r.banned) "Banned" else "Kicked",
                listOfNotNull(r.by?.let { "By $it" }, r.reason.ifBlank { null }).joinToString(": "),
            )

            else -> null
        }

        else -> null
    }
}

/** A picture shared into the app; [channel] is where it would go, null when not connected. */
data class SharedPicture(val uri: String, val channel: String?)

/** Parses `mumble://[user[:password]@]host[:port][/path]` links. */
object MumbleUrl {
    fun parse(url: String): SavedServer? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("mumble", ignoreCase = true)) return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val userInfo = uri.userInfo.orEmpty()
        return SavedServer(
            label = host,
            host = host,
            port = if (uri.port in 1..65535) uri.port else com.calegix.mumblevideo.core.ServerAddress.DEFAULT_PORT,
            username = userInfo.substringBefore(':'),
            password = if (':' in userInfo) userInfo.substringAfter(':') else "",
        )
    }
}
