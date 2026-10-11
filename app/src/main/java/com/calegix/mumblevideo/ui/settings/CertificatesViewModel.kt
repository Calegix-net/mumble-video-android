package com.calegix.mumblevideo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calegix.mumblevideo.core.ClientCertificate
import com.calegix.mumblevideo.core.MumbleCore
import com.calegix.mumblevideo.core.MumbleCoreException
import com.calegix.mumblevideo.data.certificates.CertificateRepository
import com.calegix.mumblevideo.data.certificates.StoredCertificate
import com.calegix.mumblevideo.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CertificatesUiState(
    val certificates: List<StoredCertificate> = emptyList(),
    val selectedId: Long? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class CertificatesViewModel(
    private val core: MumbleCore,
    private val repository: CertificateRepository,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<CertificatesUiState> =
        combine(repository.certificates, settings.settings, busy, message) { certs, prefs, busy, message ->
            CertificatesUiState(certs, prefs.selectedCertificateId, busy, message)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CertificatesUiState())

    private val _pendingExport = MutableStateFlow<ClientCertificate?>(null)

    /** Bytes waiting for the user to pick a destination file. */
    val pendingExport: StateFlow<ClientCertificate?> = _pendingExport.asStateFlow()

    /** Generates a certificate, stores it and selects it, as Mumla does on first run. */
    fun generate(commonName: String) = work {
        val name = commonName.trim().ifEmpty { settings.settings.value.defaultUsername }
        val certificate = core.generateCertificate(name)
        val info = core.inspectCertificate(certificate.pkcs12, certificate.password)
        val id = repository.add(name, certificate, info, clock())
        settings.update { it.copy(selectedCertificateId = id) }
        message.value = "Generated certificate for $name"
    }

    fun import(bytes: ByteArray, password: String, displayName: String) = work {
        val info = core.inspectCertificate(bytes, password)
        val id = repository.add(
            displayName.ifBlank {
                info.commonName
            },
            ClientCertificate(bytes, password),
            info,
            clock(),
        )
        if (settings.settings.value.selectedCertificateId ==
            null
        ) {
            settings.update { it.copy(selectedCertificateId = id) }
        }
        message.value = "Imported ${info.commonName}"
    }

    fun select(id: Long?) {
        viewModelScope.launch { settings.update { it.copy(selectedCertificateId = id) } }
    }

    fun delete(id: Long) = work {
        repository.delete(id)
        if (settings.settings.value.selectedCertificateId ==
            id
        ) {
            settings.update { it.copy(selectedCertificateId = null) }
        }
    }

    fun prepareExport(id: Long) = work {
        _pendingExport.value =
            repository.load(id)
                ?: throw MumbleCoreException(MumbleCoreException.Kind.Internal, "Certificate file is missing")
    }

    fun exportFinished(success: Boolean) {
        _pendingExport.value = null
        message.value = if (success) "Certificate exported" else "Export cancelled"
    }

    fun consumeMessage() {
        message.value = null
    }

    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } catch (e: MumbleCoreException) {
                message.value = e.message
            } finally {
                busy.value = false
            }
        }
    }
}
