package com.calegix.mumblevideo.core

/** Where a text message goes or came from. */
sealed interface MessageTarget {
    /** One channel. */
    data class ToChannel(val channel: ChannelId) : MessageTarget

    /** A channel and all of its subchannels. */
    data class ToChannelTree(val channel: ChannelId) : MessageTarget

    /** A private message to one user. */
    data class ToUser(val session: UserSession) : MessageTarget
}

data class ChatMessage(
    /** Locally unique id, assigned by the core, increasing in arrival order. */
    val id: Long,
    val timestampEpochMillis: Long,
    /** The sender, or null for messages from the server itself. */
    val sender: UserSession?,
    /** Sender's name at the time of sending, so history survives the sender leaving. */
    val senderName: String,
    val target: MessageTarget,
    /** Message body as received: Mumble's restricted HTML subset. */
    val html: String,
    /** True for the echo of a message this client sent. */
    val isOwn: Boolean = false,
)
