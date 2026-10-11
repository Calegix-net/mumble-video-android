package com.calegix.mumblevideo.ui

import com.calegix.mumblevideo.MainDispatcherRule
import com.calegix.mumblevideo.TestGraph
import com.calegix.mumblevideo.core.MessageTarget
import com.calegix.mumblevideo.keepCollecting
import com.calegix.mumblevideo.settle
import com.calegix.mumblevideo.ui.session.ChatViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `sending to the current channel escapes markup and clears the draft`() = runTest {
        val graph = TestGraph(this)
        val vm = ChatViewModel(graph.session)
        keepCollecting(vm.uiState)
        with(graph) { connect() }

        vm.setDraft("hello <b>world</b>")
        vm.send()
        settle()

        val entry = vm.uiState.value.entries.single()
        assertTrue(entry.message.isOwn)
        assertEquals("hello &lt;b&gt;world&lt;/b&gt;", entry.message.html)
        assertTrue(entry.message.target is MessageTarget.ToChannel)
        assertEquals("", vm.draft.value)
        // Own messages don't count as unread.
        assertEquals(0, graph.session.unreadMessages.value)
    }

    @Test
    fun `private message target is labelled and offered`() = runTest {
        val graph = TestGraph(this)
        val vm = ChatViewModel(graph.session)
        keepCollecting(vm.uiState)
        with(graph) { connect() }

        val mira = graph.core.serverState.value.users.values.single { it.name == "Mira" }
        assertTrue(vm.uiState.value.availableTargets.any { it.label == "@Mira" })
        vm.selectTarget(MessageTarget.ToUser(mira.session))
        vm.setDraft("hi")
        vm.send()
        settle()

        assertEquals("@Mira", vm.uiState.value.target?.label)
        assertEquals("@Mira", vm.uiState.value.entries.last().targetLabel)
    }

    @Test
    fun `blank drafts are not sent and clear empties history`() = runTest {
        val graph = TestGraph(this)
        val vm = ChatViewModel(graph.session)
        keepCollecting(vm.uiState)
        with(graph) { connect() }

        vm.setDraft("   ")
        vm.send()
        settle()
        assertTrue(vm.uiState.value.entries.isEmpty())

        vm.setDraft("x")
        vm.send()
        settle()
        vm.clear()
        settle()
        assertTrue(vm.uiState.value.entries.isEmpty())
    }

    @Test
    fun `escapeHtml handles every special character`() {
        assertEquals("a&amp;b&lt;c&gt;&quot;d<br>e", ChatViewModel.escapeHtml("a&b<c>\"d\ne"))
    }
}
