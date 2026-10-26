package com.calegix.mumblevideo.session

import com.calegix.mumblevideo.core.MumbleCore
import com.calegix.mumblevideo.core.StreamKey
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.core.VideoCodec
import com.calegix.mumblevideo.core.VideoSourceKind
import com.calegix.mumblevideo.core.VideoStream
import com.calegix.mumblevideo.core.fake.FakeMumbleCore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionManagerTest {
    /** Records subscriptions; the stream list is the test's to set. */
    private class RecordingCore(inner: MumbleCore) : MumbleCore by inner {
        override val videoStreams = MutableStateFlow<List<VideoStream>>(emptyList())
        val subscribed = mutableListOf<StreamKey>()

        override suspend fun subscribe(stream: StreamKey, preferredBandwidth: Int) {
            subscribed += stream
        }
    }

    private val key = StreamKey(UserSession(4), 1)
    private val stream = VideoStream(key, VideoCodec.VP8, VideoSourceKind.Camera, "cam", 640, 480, 30, 500_000)

    private fun TestScope.setUp(): Pair<RecordingCore, SubscriptionManager> {
        val core = RecordingCore(FakeMumbleCore(scope = backgroundScope, simulateActivity = false))
        return core to SubscriptionManager(core, backgroundScope)
    }

    @Test
    fun `after a reconnect, held streams are asked for again once announced`() = runTest(StandardTestDispatcher()) {
        val (core, subscriptions) = setUp()
        subscriptions.acquire(key)
        runCurrent()
        assertEquals(listOf(key), core.subscribed)

        // Reconnected: a new session, which knows nothing of the stream until the server announces it.
        subscriptions.resume()
        runCurrent()
        assertEquals(listOf(key), core.subscribed)
        core.videoStreams.value = listOf(stream)
        runCurrent()
        assertEquals(listOf(key, key), core.subscribed)
    }

    @Test
    fun `released streams are not resumed`() = runTest(StandardTestDispatcher()) {
        val (core, subscriptions) = setUp()
        subscriptions.acquire(key)
        subscriptions.release(key)
        advanceTimeBy(2_000)
        runCurrent()
        core.videoStreams.value = listOf(stream)
        subscriptions.resume()
        runCurrent()
        assertEquals(listOf(key), core.subscribed)
    }
}
