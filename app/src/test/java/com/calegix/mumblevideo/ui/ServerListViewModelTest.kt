package com.calegix.mumblevideo.ui

import com.calegix.mumblevideo.MainDispatcherRule
import com.calegix.mumblevideo.TestGraph
import com.calegix.mumblevideo.data.servers.PingResult
import com.calegix.mumblevideo.data.servers.SavedServer
import com.calegix.mumblevideo.data.servers.ServerPinger
import com.calegix.mumblevideo.keepCollecting
import com.calegix.mumblevideo.settle
import com.calegix.mumblevideo.ui.servers.ServerListViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ServerListViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `adding a server validates and saves it`() = runTest {
        val graph = TestGraph(this)
        val vm = ServerListViewModel(graph.servers, graph.session)
        keepCollecting(vm.uiState)

        vm.addServer()
        vm.updateEditor { it.copy(host = "", port = "99999") }
        assertFalse(vm.saveEditor())
        settle()
        val editor = vm.uiState.value.editor
        assertNotNull(editor?.hostError)
        assertNotNull(editor?.portError)

        vm.updateEditor { it.copy(label = "Home", host = "mumble.home.arpa", port = "64738", username = "nate") }
        assertTrue(vm.saveEditor())
        settle()

        val servers = vm.uiState.value.servers
        assertEquals(1, servers.size)
        assertEquals("mumble.home.arpa", servers.single().host)
        assertNull(vm.uiState.value.editor)
    }

    @Test
    fun `favourites sort first and delete needs confirmation`() = runTest {
        val graph = TestGraph(this)
        graph.servers.save(SavedServer(label = "Alpha", host = "a.example"))
        graph.servers.save(SavedServer(label = "Zulu", host = "z.example"))
        val vm = ServerListViewModel(graph.servers, graph.session)
        keepCollecting(vm.uiState)
        settle()

        vm.toggleFavourite(vm.uiState.value.servers.first { it.label == "Zulu" })
        settle()
        assertEquals("Zulu", vm.uiState.value.servers.first().label)

        val alpha = vm.uiState.value.servers.first { it.label == "Alpha" }
        vm.requestDelete(alpha)
        settle()
        assertEquals(alpha, vm.uiState.value.pendingDelete)
        vm.cancelDelete()
        settle()
        assertEquals(2, vm.uiState.value.servers.size)

        vm.requestDelete(alpha)
        vm.confirmDelete()
        settle()
        assertEquals(listOf("Zulu"), vm.uiState.value.servers.map { it.label })
    }

    @Test
    fun `editing keeps the id and prefill from a link becomes a new server`() = runTest {
        val graph = TestGraph(this)
        val id = graph.servers.save(SavedServer(label = "Old", host = "old.example"))
        val vm = ServerListViewModel(graph.servers, graph.session)
        keepCollecting(vm.uiState)
        settle()

        vm.editServer(vm.uiState.value.servers.single())
        settle()
        vm.updateEditor { it.copy(label = "New") }
        vm.saveEditor()
        settle()
        assertEquals("New", graph.servers.get(id)?.label)

        vm.addServer(prefill = SavedServer(id = 99, label = "x", host = "link.example", port = 1234))
        settle()
        assertEquals(0L, vm.uiState.value.editor?.id)
        assertEquals("1234", vm.uiState.value.editor?.port)
    }

    @Test
    fun `connecting marks the server and exposes the active session`() = runTest {
        val graph = TestGraph(this)
        val id = graph.servers.save(TestGraph.SERVER)
        val vm = ServerListViewModel(graph.servers, graph.session)
        keepCollecting(vm.activeSession)

        vm.connect(graph.servers.get(id)!!)
        settle()
        graph.session.answerCertificatePrompt(true)
        settle()

        assertEquals("Test", vm.activeSession.value)
        assertEquals(1_000L, graph.servers.get(id)?.lastConnectedEpochMillis)
    }

    @Test
    fun `servers are pinged and answers shown, silence shows nothing`() = runTest {
        val graph = TestGraph(this)
        val up = graph.servers.save(SavedServer(label = "Up", host = "up.example"))
        val down = graph.servers.save(SavedServer(label = "Down", host = "down.example"))
        val pinger = ServerPinger { host, _ -> if (host == "up.example") PingResult(3, 20, 42, "1.7.0") else null }
        val vm = ServerListViewModel(graph.servers, graph.session, pinger)
        keepCollecting(vm.uiState)
        settle()

        assertEquals(PingResult(3, 20, 42, "1.7.0"), vm.uiState.value.pings[up])
        assertNull(vm.uiState.value.pings[down])
    }
}
