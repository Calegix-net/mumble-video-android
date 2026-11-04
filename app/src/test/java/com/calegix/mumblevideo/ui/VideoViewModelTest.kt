package com.calegix.mumblevideo.ui

import com.calegix.mumblevideo.MainDispatcherRule
import com.calegix.mumblevideo.TestGraph
import com.calegix.mumblevideo.core.CameraFacing
import com.calegix.mumblevideo.core.Subscription
import com.calegix.mumblevideo.data.servers.SavedServer
import com.calegix.mumblevideo.keepCollecting
import com.calegix.mumblevideo.settle
import com.calegix.mumblevideo.ui.video.VideoViewModel
import com.calegix.mumblevideo.ui.video.label
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VideoViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `announced streams become tiles with user names`() = runTest {
        val graph = TestGraph(this)
        val vm = VideoViewModel(graph.session, graph.settings)
        keepCollecting(vm.uiState)
        with(graph) { connect() }

        val state = vm.uiState.value
        assertTrue(state.supported)
        assertEquals(setOf("Mira", "Jonas", "Ana"), state.tiles.map { it.userName }.toSet())
        assertTrue(state.tiles.single { it.userName == "Jonas" }.stream.hasAudio)
    }

    @Test
    fun `acquire subscribes, release lingers then unsubscribes`() = runTest {
        val graph = TestGraph(this)
        val vm = VideoViewModel(graph.session, graph.settings)
        keepCollecting(vm.uiState)
        with(graph) { connect() }
        val key = vm.uiState.value.tiles.first().stream.key

        vm.acquire(key, thumbnail = true)
        settle()
        assertTrue(graph.core.videoStreams.value.single { it.key == key }.subscription is Subscription.Active)

        vm.release(key)
        advanceTimeBy(500)
        assertTrue(graph.core.videoStreams.value.single { it.key == key }.subscription is Subscription.Active)
        settle()
        assertEquals(Subscription.None, graph.core.videoStreams.value.single { it.key == key }.subscription)
    }

    @Test
    fun `share audio volume and mute reach the stream`() = runTest {
        val graph = TestGraph(this)
        val vm = VideoViewModel(graph.session, graph.settings)
        keepCollecting(vm.uiState)
        with(graph) { connect() }
        val key = vm.uiState.value.tiles.single { it.stream.hasAudio }.stream.key

        vm.setStreamAudio(key, 1.5f, muted = true)
        settle()
        val audio = vm.uiState.value.tiles.single { it.stream.key == key }.stream.audio!!
        assertEquals(1.5f, audio.volume)
        assertTrue(audio.muted)
    }

    @Test
    fun `camera toggles a local stream that others can watch`() = runTest {
        val graph = TestGraph(this)
        val vm = VideoViewModel(graph.session, graph.settings)
        keepCollecting(vm.uiState)
        with(graph) { connect() }

        vm.toggleCamera()
        settle()
        vm.confirmCamera()
        settle()
        val camera = vm.uiState.value.local.camera
        assertNotNull(camera)
        assertEquals("You", vm.uiState.value.tiles.first().userName)
        assertTrue(vm.uiState.value.watchers.isEmpty())

        vm.toggleCamera()
        settle()
        assertNull(vm.uiState.value.local.camera)
        assertFalse(vm.uiState.value.tiles.any { it.stream.isLocal })
    }

    @Test
    fun `camera asks first, names the camera and cancel leaves it off`() = runTest {
        val graph = TestGraph(this)
        val vm = VideoViewModel(graph.session, graph.settings)
        keepCollecting(vm.uiState)
        with(graph) { connect() }

        vm.toggleCamera()
        settle()
        val ask = vm.uiState.value.cameraConfirm
        assertNotNull(ask)
        assertEquals(CameraFacing.Front, ask!!.camera.facing)
        assertEquals(2, ask.cameraCount)
        assertNull(vm.uiState.value.local.camera)

        vm.cancelCamera()
        settle()
        assertNull(vm.uiState.value.cameraConfirm)
        assertNull(vm.uiState.value.local.camera)
        assertNull(vm.uiState.value.cameraFacing)
    }

    @Test
    fun `switching in the question starts the rear camera and labels it`() = runTest {
        val graph = TestGraph(this)
        val vm = VideoViewModel(graph.session, graph.settings)
        keepCollecting(vm.uiState)
        with(graph) { connect() }

        vm.toggleCamera()
        settle()
        vm.switchCamera()
        settle()
        assertEquals(CameraFacing.Back, vm.uiState.value.cameraConfirm!!.camera.facing)
        assertNull("switching while asking must not start anything", vm.uiState.value.local.camera)

        vm.confirmCamera()
        settle()
        assertNull(vm.uiState.value.cameraConfirm)
        assertNotNull(vm.uiState.value.local.camera)
        assertEquals(CameraFacing.Back, vm.uiState.value.cameraFacing)
        assertEquals("Rear", vm.uiState.value.cameraFacing!!.label)
        assertEquals("fake-back", graph.settings.settings.value.cameraId)

        vm.switchCamera()
        settle()
        assertEquals(CameraFacing.Front, vm.uiState.value.cameraFacing)

        vm.toggleCamera()
        settle()
        assertNull(vm.uiState.value.cameraFacing)
    }

    @Test
    fun `don't ask again starts the camera straight away next time`() = runTest {
        val graph = TestGraph(this)
        val vm = VideoViewModel(graph.session, graph.settings)
        keepCollecting(vm.uiState)
        with(graph) { connect() }

        vm.toggleCamera()
        settle()
        vm.confirmCamera(dontAskAgain = true)
        settle()
        assertFalse(vm.uiState.value.confirmCameraStart)
        vm.toggleCamera()
        settle()
        assertNull(vm.uiState.value.local.camera)

        vm.toggleCamera()
        settle()
        assertNull(vm.uiState.value.cameraConfirm)
        assertNotNull(vm.uiState.value.local.camera)
        assertEquals(CameraFacing.Front, vm.uiState.value.cameraFacing)
    }

    @Test
    fun `legacy servers have no video and refuse to stream`() = runTest {
        val graph = TestGraph(this)
        val vm = VideoViewModel(graph.session, graph.settings)
        keepCollecting(vm.uiState)
        with(graph) { connect(SavedServer(label = "old", host = "legacy.example.org")) }

        assertFalse(vm.uiState.value.supported)
        vm.toggleCamera()
        settle()
        assertNotNull(vm.message.value)
    }
}
