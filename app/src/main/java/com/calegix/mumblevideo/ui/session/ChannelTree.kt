package com.calegix.mumblevideo.ui.session

import com.calegix.mumblevideo.core.Channel
import com.calegix.mumblevideo.core.ChannelId
import com.calegix.mumblevideo.core.ServerState
import com.calegix.mumblevideo.core.User
import com.calegix.mumblevideo.core.UserSession

/** One row of the flattened channel tree. */
sealed interface TreeRow {
    val key: String
    val depth: Int

    data class ChannelRow(
        val channel: Channel,
        override val depth: Int,
        /** Users in this channel and all of its subchannels. */
        val totalUsers: Int,
        val expanded: Boolean,
        val hasChildren: Boolean,
        val isCurrent: Boolean,
    ) : TreeRow {
        override val key: String get() = "c${channel.id.value}"
    }

    data class UserRow(
        val user: User,
        override val depth: Int,
        val isSelf: Boolean,
        val streaming: StreamingKind?,
    ) : TreeRow {
        override val key: String get() = "u${user.session.value}"
    }
}

enum class StreamingKind { Camera, Screen }

/**
 * Flattens the channel tree depth-first in Mumble's order: a channel's users come before its
 * subchannels. Collapsed channels hide everything beneath them. Empty channels are kept, as in
 * every Mumble client, so there is somewhere to tap to join.
 *
 * Each channel is visited once, so a parent loop (a server bug, or a half-applied move) shows a
 * channel once instead of recursing until the stack runs out.
 */
fun buildTree(
    state: ServerState,
    collapsed: Set<ChannelId>,
    streaming: Map<UserSession, StreamingKind> = emptyMap(),
): List<TreeRow> {
    val root = state.channels[ChannelId.ROOT] ?: return emptyList()
    val children = state.channels.values
        .filter { it.id != it.parent }
        .groupBy { it.parent }
        .mapValues { (_, list) -> list.sortedWith(Channel.ORDER) }
    val usersByChannel = state.users.values.groupBy { it.channel }
        .mapValues { (_, list) -> list.sortedBy { it.name.lowercase() } }
    val selfChannel = state.selfUser?.channel
    val totals = HashMap<ChannelId, Int>()
    val counting = HashSet<ChannelId>()

    fun total(id: ChannelId): Int = totals[id] ?: run {
        if (!counting.add(id)) return 0
        val sum = (usersByChannel[id]?.size ?: 0) + (children[id]?.sumOf { total(it.id) } ?: 0)
        totals[id] = sum
        sum
    }

    val rows = ArrayList<TreeRow>(state.channels.size + state.users.size)
    val visited = HashSet<ChannelId>()
    fun visit(channel: Channel, depth: Int) {
        if (!visited.add(channel.id)) return
        val expanded = channel.id !in collapsed
        val subchannels = children[channel.id].orEmpty()
        rows += TreeRow.ChannelRow(
            channel = channel,
            depth = depth,
            totalUsers = total(channel.id),
            expanded = expanded,
            hasChildren = subchannels.isNotEmpty() || usersByChannel[channel.id].orEmpty().isNotEmpty(),
            isCurrent = channel.id == selfChannel,
        )
        if (!expanded) return
        usersByChannel[channel.id].orEmpty().forEach { user ->
            rows +=
                TreeRow.UserRow(
                    user,
                    depth + 1,
                    isSelf = user.session == state.self,
                    streaming = streaming[user.session],
                )
        }
        subchannels.forEach { visit(it, depth + 1) }
    }
    visit(root, 0)
    return rows
}
