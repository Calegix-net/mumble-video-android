package com.calegix.mumblevideo.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calegix.mumblevideo.MainDispatcherRule
import com.calegix.mumblevideo.TestGraph
import com.calegix.mumblevideo.core.ChannelId
import com.calegix.mumblevideo.core.ChatMessage
import com.calegix.mumblevideo.core.MessageTarget
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.settle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class MessageNotifierTest {
    @get:Rule val main = MainDispatcherRule()

    private fun message(target: MessageTarget, sender: UserSession? = UserSession(11)) =
        ChatMessage(1, 1_000, sender, "zed", target, "hi")

    @Test
    fun `private messages are a conversation per sender, everything else is the channel`() {
        assertEquals(
            MessageNotifier.Conversation.Private(UserSession(11), "zed"),
            MessageNotifier.conversationOf(message(MessageTarget.ToUser(UserSession(10)))),
        )
        assertEquals(
            MessageNotifier.Conversation.Channel,
            MessageNotifier.conversationOf(message(MessageTarget.ToChannel(ChannelId(1)))),
        )
        assertEquals(
            MessageNotifier.Conversation.Channel,
            MessageNotifier.conversationOf(message(MessageTarget.ToUser(UserSession(10)), sender = null)),
        )
    }

    @Test
    fun `a reply to the channel is sent there, escaped like typed chat`() = runTest {
        val graph = TestGraph(this)
        val notifier = MessageNotifier(
            ApplicationProvider.getApplicationContext(),
            graph.session,
            graph.settings,
            backgroundScope,
        )
        with(graph) { connect() }
        notifier.reply(MessageNotifier.Conversation.Channel, "on my way <3")
        settle()
        val sent = graph.session.chat.value.last()
        assertTrue(sent.isOwn)
        assertEquals("on my way &lt;3", sent.html)
        assertEquals(graph.core.serverState.value.selfUser!!.channel, (sent.target as MessageTarget.ToChannel).channel)
    }
}
