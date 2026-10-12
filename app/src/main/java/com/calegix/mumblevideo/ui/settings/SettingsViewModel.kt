package com.calegix.mumblevideo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calegix.mumblevideo.core.AudioLevel
import com.calegix.mumblevideo.core.CameraOption
import com.calegix.mumblevideo.core.MumbleCore
import com.calegix.mumblevideo.data.servers.TrustStore
import com.calegix.mumblevideo.data.settings.AppSettings
import com.calegix.mumblevideo.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val core: MumbleCore,
    private val trustStore: TrustStore,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings

    val trustedCertificateCount: StateFlow<Int> =
        trustStore.count.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Live microphone level for the VAD meter; only runs while collected. */
    val inputLevels: Flow<AudioLevel> = core.inputLevels()

    private val _cameras = MutableStateFlow<List<CameraOption>>(emptyList())

    /** The device's cameras, for the camera picker. */
    val cameras: StateFlow<List<CameraOption>> = _cameras.asStateFlow()

    init {
        viewModelScope.launch { _cameras.value = runCatching { core.availableCameras() }.getOrDefault(emptyList()) }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun clearTrustedCertificates() {
        viewModelScope.launch { trustStore.clear() }
    }
}
