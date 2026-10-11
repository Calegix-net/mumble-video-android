package com.calegix.mumblevideo

import com.calegix.mumblevideo.core.fake.FakeMumbleCore
import com.calegix.mumblevideo.data.certificates.InMemoryCertificateRepository
import com.calegix.mumblevideo.data.servers.InMemoryServerRepository
import com.calegix.mumblevideo.data.servers.InMemoryTrustStore
import com.calegix.mumblevideo.data.servers.SavedServer
import com.calegix.mumblevideo.data.settings.AppSettings
import com.calegix.mumblevideo.data.settings.InMemorySettingsRepository
import com.calegix.mumblevideo.session.SessionController
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Routes Dispatchers.Main (and so viewModelScope) to a test dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** A complete app graph on in-memory storage and a deterministic fake core. */
class TestGraph(scope: TestScope, settings: AppSettings = AppSettings()) {
    val core =
        FakeMumbleCore(scope = scope.backgroundScope, random = Random(42), simulateActivity = false, clock = { 1_000L })
    val settings = InMemorySettingsRepository(settings)
    val servers = InMemoryServerRepository()
    val trust = InMemoryTrustStore()
    val certificates = InMemoryCertificateRepository()
    val session =
        SessionController(core, this.settings, servers, certificates, trust, scope.backgroundScope, clock = { 1_000L })

    /** Connects to [server], accepts its certificate and waits for the session to sync. */
    suspend fun TestScope.connect(server: SavedServer = SERVER) {
        session.connect(server)
        settle()
        session.answerCertificatePrompt(true)
        settle()
    }

    companion object {
        val SERVER = SavedServer(id = 0, label = "Test", host = "test.example.org", username = "nate")
    }
}

/** Keeps a WhileSubscribed StateFlow hot for the duration of a test. */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.keepCollecting(flow: Flow<T>) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
}

/**
 * Lets virtual time run far enough for the fake core's scripted delays. advanceUntilIdle is not
 * enough: it ignores work in backgroundScope, which is where the fake core lives in tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.settle(millis: Long = 5_000) {
    advanceTimeBy(millis)
    runCurrent()
}
