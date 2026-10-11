package com.calegix.mumblevideo.ui

import com.calegix.mumblevideo.MainDispatcherRule
import com.calegix.mumblevideo.TestGraph
import com.calegix.mumblevideo.core.ChannelId
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.VoiceInputMode
import com.calegix.mumblevideo.data.servers.SavedServer
import com.calegix.mumblevideo.keepCollecting
import com.calegix.mumblevideo.settle
import com.calegix.mumblevideo.ui.session.SessionViewModel
import com.calegix.mumblevideo.ui.session.TreeRow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SessionViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `connecting shows the channel tree with ourselves in root`() = runTest {
        val graph = TestGraph(this)
        val vm = SessionViewModel(graph.session, graph.settings)
        keepCollecting(vm.uiState)
        with(graph) { connect() }

        val state = vm.uiState.value
        assertTrue(state.connection is ConnectionState.Connected)
        assertEquals("Test", state.serverName)
        val self = state.rows.filterIsInstance<TreeRow.UserRow>().single { it.isSelf }
        assertEquals("nate", self.user.name)
        assertEquals(state.rows.first().key, "c0")
        assertTrue(state.capabilities.video)
    }

    @Test
    fun `accepted certificates are remembered and not asked again`() = runTest {
        val graph = TestGraph(this)
        with(graph) { connect() }
        assertEquals(1, graph.trust.fingerprints().size)

        graph.session.disconnect()
        graph.session.connect(TestGraph.SERVER)
        settle()
        assertTrue(graph.core.connectionState.value is ConnectionState.Connected)
    }

    @Test
    fun `joining a channel moves us, and a locked channel reports an error`() = runTest {
        val graph = TestGraph(this)
        val vm = SessionViewModel(graph.session, graph.settings)
        keepCollecting(vm.uiState)
        with(graph) { connect() }

        val lobby = graph.core.serverState.value.channels.values.single { it.name == "Lobby" }
        vm.joinChannel(lobby.id)
        settle()
        assertEquals("Lobby", vm.uiState.value.currentChannel)

        val admins = graph.core.serverState.value.channels.values.single { !it.canEnter }
        vm.joinChannel(admins.id)
        settle()
        assertNotNull(vm.message.value)
        assertEquals("Lobby", vm.uiState.value.currentChannel)
    }

    @Test
    fun `deafen implies mute and unmute undeafens`() = runTest {
        val graph = TestGraph(this)
        val vm = SessionViewModel(graph.session, graph.settings)
        keepCollecting(vm.uiState)
        with(graph) { connect() }

        vm.toggleDeafen()
        settle()
        assertTrue(vm.uiState.value.voice.selfDeafened)
        assertTrue(vm.uiState.value.voice.selfMuted)

        vm.toggleMute()
        settle()
        assertFalse(vm.uiState.value.voice.selfDeafened)
        assertFalse(vm.uiState.value.voice.selfMuted)
    }

    @Test
    fun `input mode is a setting and push-to-talk follows it`() = runTest {
        val graph = TestGraph(this)
        val vm = SessionViewModel(graph.session, graph.settings)
        keepCollecting(vm.uiState)
        with(graph) { connect() }

        vm.setInputMode(VoiceInputMode.PushToTalk)
        settle()
        assertEquals(VoiceInputMode.PushToTalk, graph.settings.settings.value.inputMode)
        assertTrue(vm.uiState.value.showPttButton)

        vm.pushToTalk(true)
        assertTrue(graph.core.voiceState.value.pushToTalkActive)
        vm.pushToTalk(false)
        assertFalse(graph.core.voiceState.value.pushToTalkActive)
    }

    @Test
    fun `collapsing a channel hides its users`() = runTest {
        val graph = TestGraph(this)
        val vm = SessionViewModel(graph.session, graph.settings)
        keepCollecting(vm.uiState)
        with(graph) { connect() }

        val before = vm.uiState.value.rows.size
        vm.toggleCollapsed(ChannelId.ROOT)
        settle()
        assertEquals(1, vm.uiState.value.rows.size)
        vm.toggleCollapsed(ChannelId.ROOT)
        settle()
        assertEquals(before, vm.uiState.value.rows.size)
    }

    @Test
    fun `rejection and unreachable servers end the session`() = runTest {
        val graph = TestGraph(this)
        graph.session.connect(SavedServer(label = "r", host = "reject.example.org"))
        settle()
        graph.session.answerCertificatePrompt(true)
        settle()
        assertTrue(graph.core.connectionState.value is ConnectionState.Rejected)

        graph.session.connect(SavedServer(label = "u", host = "unreachable.example.org"))
        settle()
        val state = graph.core.connectionState.value
        assertTrue(state is ConnectionState.Disconnected && state.reason != null)
    }
}
