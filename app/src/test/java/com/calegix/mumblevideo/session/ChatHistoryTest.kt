package com.calegix.mumblevideo.session

import com.calegix.mumblevideo.core.ChannelId
import com.calegix.mumblevideo.core.ChatMessage
import com.calegix.mumblevideo.core.MessageTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistoryTest {
    private val bodies = HashMap<Int, String>()

    /** Bodies of one length share one string, so the test itself does not need gigabytes. */
    private fun message(id: Long, chars: Int) = ChatMessage(
        id,
        0L,
        null,
        "s",
        MessageTarget.ToChannel(ChannelId(0)),
        bodies.getOrPut(chars) { "x".repeat(chars) },
    )

    @Test fun historyIsCappedByCount() {
        val history = (1L..600L).map { message(it, 10) }
        val kept = SessionController.trimHistory(history)
        assertEquals(SessionController.MAX_HISTORY, kept.size)
        assertEquals(600L, kept.last().id)
    }

    @Test fun historyIsCappedBySizeSoAFloodCannotFillMemory() {
        // Eight-megabyte messages, the most the control channel carries: 500 would be 8 GB of text.
        val history = (1L..10L).map { message(it, 8_000_000) }
        val kept = SessionController.trimHistory(history)
        assertEquals(listOf(9L, 10L), kept.map { it.id })
        // The newest message is kept even alone over the cap.
        assertEquals(1, SessionController.trimHistory(listOf(message(1, 20_000_000))).size)
    }
}
