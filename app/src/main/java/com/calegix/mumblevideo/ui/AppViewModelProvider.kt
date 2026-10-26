package com.calegix.mumblevideo.ui

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.calegix.mumblevideo.AppContainer
import com.calegix.mumblevideo.MumbleVideoApp
import com.calegix.mumblevideo.ui.servers.PublicServersViewModel
import com.calegix.mumblevideo.ui.servers.ServerListViewModel
import com.calegix.mumblevideo.ui.session.ChatViewModel
import com.calegix.mumblevideo.ui.session.SessionViewModel
import com.calegix.mumblevideo.ui.settings.BackupViewModel
import com.calegix.mumblevideo.ui.settings.CertificatesViewModel
import com.calegix.mumblevideo.ui.settings.SettingsViewModel
import com.calegix.mumblevideo.ui.video.VideoViewModel

/** Builds every ViewModel from the [AppContainer]. */
object AppViewModelProvider {
    val Factory = viewModelFactory {
        initializer { MainViewModel(container().session, container().settings) }
        initializer { ServerListViewModel(container().servers, container().session, container().pinger) }
        initializer { PublicServersViewModel(container().publicServers, container().servers) }
        initializer { SessionViewModel(container().session, container().settings) }
        initializer { ChatViewModel(container().session, container().settings) }
        initializer { VideoViewModel(container().session, container().settings) }
        initializer { SettingsViewModel(container().settings, container().core, container().trustStore) }
        initializer {
            CertificatesViewModel(container().core, container().certificates, container().settings)
        }
        initializer {
            val container = container()
            BackupViewModel(container.backup) { container.backupDropInDirectory }
        }
    }

    private fun CreationExtras.container(): AppContainer = (this[APPLICATION_KEY] as MumbleVideoApp).container
}
