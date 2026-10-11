package com.calegix.mumblevideo

import android.content.Context
import com.calegix.mumblevideo.core.MumbleCore
import com.calegix.mumblevideo.core.fake.FakeMumbleCore
import com.calegix.mumblevideo.data.certificates.CertificateRepository
import com.calegix.mumblevideo.data.certificates.FileCertificateRepository
import com.calegix.mumblevideo.data.db.AppDatabase
import com.calegix.mumblevideo.data.servers.PublicServerSource
import com.calegix.mumblevideo.data.servers.RoomServerRepository
import com.calegix.mumblevideo.data.servers.RoomTrustStore
import com.calegix.mumblevideo.data.servers.ServerRepository
import com.calegix.mumblevideo.data.servers.StubPublicServerSource
import com.calegix.mumblevideo.data.servers.TrustStore
import com.calegix.mumblevideo.data.settings.DataStoreSettingsRepository
import com.calegix.mumblevideo.data.settings.SettingsRepository
import com.calegix.mumblevideo.service.ConnectionService
import com.calegix.mumblevideo.service.MessageNotifier
import com.calegix.mumblevideo.session.SessionController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency injection: one instance per process, built in [MumbleVideoApp]. Small
 * enough that a DI framework would be more code than it saves.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Process-wide scope on the main thread; the core does its own threading. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val database = AppDatabase.create(appContext)

    // The one line that changes when :core-native arrives.
    private val fakeCore = FakeMumbleCore()
    val core: MumbleCore = fakeCore

    val settings: SettingsRepository = DataStoreSettingsRepository.create(
        appContext,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )
    val servers: ServerRepository = RoomServerRepository(database.servers())
    val trustStore: TrustStore = RoomTrustStore(database.trustedCertificates())
    val certificates: CertificateRepository =
        FileCertificateRepository(database.certificates(), File(appContext.filesDir, "certificates"))
    val publicServers: PublicServerSource = StubPublicServerSource()

    val session = SessionController(
        core = core,
        settings = settings,
        servers = servers,
        certificates = certificates,
        trustStore = trustStore,
        scope = appScope,
        onSessionStarting = { ConnectionService.start(appContext) },
    )

    init {
        appScope.launch {
            settings.settings.collect { fakeCore.simulateConnectionDrops = it.simulateConnectionDrops }
        }
        MessageNotifier(appContext, session, settings, appScope).start()
    }
}
