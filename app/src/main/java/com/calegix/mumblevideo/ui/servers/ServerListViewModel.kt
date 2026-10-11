package com.calegix.mumblevideo.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.ServerAddress
import com.calegix.mumblevideo.data.servers.SavedServer
import com.calegix.mumblevideo.data.servers.ServerRepository
import com.calegix.mumblevideo.session.SessionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The add/edit server form. Port is kept as text so half-typed values survive. */
data class ServerEditor(
    val id: Long = 0,
    val label: String = "",
    val host: String = "",
    val port: String = ServerAddress.DEFAULT_PORT.toString(),
    val username: String = "",
    val password: String = "",
    val favourite: Boolean = false,
    val accessTokens: List<String> = emptyList(),
    val hostError: String? = null,
    val portError: String? = null,
) {
    val isNew: Boolean get() = id == 0L

    companion object {
        fun from(server: SavedServer) = ServerEditor(
            id = server.id,
            label = server.label,
            host = server.host,
            port = server.port.toString(),
            username = server.username,
            password = server.password,
            favourite = server.favourite,
            accessTokens = server.accessTokens,
        )
    }
}

data class ServerListUiState(
    val servers: List<SavedServer> = emptyList(),
    val editor: ServerEditor? = null,
    val pendingDelete: SavedServer? = null,
    val loading: Boolean = true,
)

class ServerListViewModel(
    private val repository: ServerRepository,
    private val session: SessionController,
) : ViewModel() {

    private val editor = MutableStateFlow<ServerEditor?>(null)
    private val pendingDelete = MutableStateFlow<SavedServer?>(null)

    val uiState: StateFlow<ServerListUiState> =
        combine(repository.servers, editor, pendingDelete) { servers, editor, delete ->
            ServerListUiState(servers, editor, delete, loading = false)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerListUiState())

    /** Name of the server we're connected (or connecting) to, for the "return to session" card. */
    val activeSession: StateFlow<String?> =
        combine(session.core.connectionState, session.currentServer) { state, server ->
            val active = state !is ConnectionState.Disconnected && state !is ConnectionState.Rejected
            if (active) server?.displayName ?: state.toString() else null
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _connectRequest = MutableStateFlow<SavedServer?>(null)

    /** Set when the user asked to connect; the screen asks for permissions, then calls [connect]. */
    val connectRequest: StateFlow<SavedServer?> = _connectRequest.asStateFlow()

    fun requestConnect(server: SavedServer) {
        _connectRequest.value = server
    }

    fun connect(server: SavedServer) {
        _connectRequest.value = null
        viewModelScope.launch { session.connect(server) }
    }

    fun addServer(prefill: SavedServer? = null) {
        editor.value = prefill?.let(ServerEditor::from)?.copy(id = 0) ?: ServerEditor()
    }

    fun editServer(server: SavedServer) {
        editor.value = ServerEditor.from(server)
    }

    fun updateEditor(transform: (ServerEditor) -> ServerEditor) {
        editor.update { it?.let(transform) }
    }

    fun dismissEditor() {
        editor.value = null
    }

    /** Validates and saves the form. Returns false (and shows errors) if invalid. */
    fun saveEditor(): Boolean {
        val form = editor.value ?: return false
        val host = form.host.trim()
        val port = form.port.trim().toIntOrNull()
        val hostError = when {
            host.isEmpty() -> "Enter a host name or address"
            host.any { it.isWhitespace() || it == '/' } -> "Host can't contain spaces or slashes"
            else -> null
        }
        val portError = if (port == null || port !in 1..65535) "Port must be between 1 and 65535" else null
        if (hostError != null || portError != null) {
            editor.value = form.copy(hostError = hostError, portError = portError)
            return false
        }
        val server = SavedServer(
            id = form.id,
            label = form.label.trim(),
            host = host,
            port = port!!,
            username = form.username.trim(),
            password = form.password,
            favourite = form.favourite,
            accessTokens = form.accessTokens,
        )
        editor.value = null
        viewModelScope.launch { repository.save(server) }
        return true
    }

    fun toggleFavourite(server: SavedServer) {
        viewModelScope.launch { repository.save(server.copy(favourite = !server.favourite)) }
    }

    fun requestDelete(server: SavedServer) {
        pendingDelete.value = server
    }

    fun cancelDelete() {
        pendingDelete.value = null
    }

    fun confirmDelete() {
        val server = pendingDelete.value ?: return
        pendingDelete.value = null
        viewModelScope.launch { repository.delete(server.id) }
    }
}
