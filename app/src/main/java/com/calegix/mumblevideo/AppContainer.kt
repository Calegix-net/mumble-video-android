package com.calegix.mumblevideo

import android.content.Context
import com.calegix.mumblevideo.client.NativeMumbleCore
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
import kotlinx.coroutines.runBlocking

/**
 * Manual dependency injection: one instance per process, built in [MumbleVideoApp]. Small
 * enough that a DI framework would be more code than it saves.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Process-wide scope on the main thread; the core does its own threading. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings: SettingsRepository = DataStoreSettingsRepository.create(
        appContext,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    // Which core runs is fixed for the life of the process. Reading one small file at startup is
    // the price of never having to hot-swap a live connection.
    private val simulated = runBlocking { settings.load().useSimulatedCore }
    private val fakeCore = if (simulated) FakeMumbleCore() else null
    val core: MumbleCore = fakeCore ?: NativeMumbleCore(appContext)

    private val database = AppDatabase.create(appContext, seedExamples = simulated)
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
        fakeCore?.let { fake ->
            appScope.launch { settings.settings.collect { fake.simulateConnectionDrops = it.simulateConnectionDrops } }
        }
        MessageNotifier(appContext, session, settings, appScope).start()
    }
}
