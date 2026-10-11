package com.calegix.mumblevideo.session

import com.calegix.mumblevideo.core.Channel
import com.calegix.mumblevideo.core.ChannelId
import com.calegix.mumblevideo.core.ServerState
import com.calegix.mumblevideo.core.User
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.ui.session.StreamingKind
import com.calegix.mumblevideo.ui.session.TreeRow
import com.calegix.mumblevideo.ui.session.buildTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelTreeTest {
    private val root = Channel(ChannelId.ROOT, null, "Root")
    private val b = Channel(ChannelId(2), ChannelId.ROOT, "b-second", position = 1)
    private val a = Channel(ChannelId(1), ChannelId.ROOT, "z-first", position = 0)
    private val child = Channel(ChannelId(3), ChannelId(1), "child")
    private val state = ServerState(
        channels = listOf(root, a, b, child).associateBy { it.id },
        users = listOf(
            User(UserSession(1), "me", ChannelId(3)),
            User(UserSession(2), "Bob", ChannelId(1)),
            User(UserSession(3), "alice", ChannelId(1)),
        ).associateBy { it.session },
        self = UserSession(1),
    )

    @Test
    fun `orders by position, users before subchannels, names case-insensitively`() {
        val keys = buildTree(state, emptySet()).map { it.key }
        assertEquals(listOf("c0", "c1", "u3", "u2", "c3", "u1", "c2"), keys)
    }

    @Test
    fun `counts users recursively and marks the current channel`() {
        val rows = buildTree(state, emptySet()).filterIsInstance<TreeRow.ChannelRow>().associateBy { it.channel.id }
        assertEquals(3, rows.getValue(ChannelId.ROOT).totalUsers)
        assertEquals(3, rows.getValue(ChannelId(1)).totalUsers)
        assertTrue(rows.getValue(ChannelId(3)).isCurrent)
    }

    @Test
    fun `collapsed channels hide descendants and streaming is flagged`() {
        val rows = buildTree(state, setOf(ChannelId(1)), mapOf(UserSession(2) to StreamingKind.Screen))
        assertEquals(listOf("c0", "c1", "c2"), rows.map { it.key })
        val bob = buildTree(state, emptySet(), mapOf(UserSession(2) to StreamingKind.Screen))
            .filterIsInstance<TreeRow.UserRow>().single { it.user.name == "Bob" }
        assertEquals(StreamingKind.Screen, bob.streaming)
    }
}
