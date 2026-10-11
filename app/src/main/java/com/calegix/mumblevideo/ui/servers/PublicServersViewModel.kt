package com.calegix.mumblevideo.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calegix.mumblevideo.data.servers.PublicServer
import com.calegix.mumblevideo.data.servers.PublicServerSource
import com.calegix.mumblevideo.data.servers.SavedServer
import com.calegix.mumblevideo.data.servers.ServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PublicServersUiState(
    val loading: Boolean = true,
    val query: String = "",
    val all: List<PublicServer> = emptyList(),
    val added: Set<String> = emptySet(),
) {
    val visible: List<PublicServer>
        get() = all.filter { query.isBlank() || it.name.contains(query, true) || it.country.contains(query, true) }
            .sortedWith(compareBy({ it.region }, { it.name }))
}

class PublicServersViewModel(
    private val source: PublicServerSource,
    private val repository: ServerRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PublicServersUiState())
    val uiState: StateFlow<PublicServersUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val list = source.fetch()
            _uiState.update { it.copy(loading = false, all = list) }
        }
    }

    fun setQuery(query: String) = _uiState.update { it.copy(query = query) }

    fun addToFavourites(server: PublicServer) {
        viewModelScope.launch {
            repository.save(SavedServer(label = server.name, host = server.host, port = server.port, favourite = true))
            _uiState.update { it.copy(added = it.added + server.host) }
        }
    }
}
