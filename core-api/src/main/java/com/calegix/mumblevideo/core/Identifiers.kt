package com.calegix.mumblevideo.core

/**
 * A user's session id, as assigned by the server for the lifetime of their connection. Not stable
 * across reconnects; [User.registeredId] is the stable identity for registered users.
 */
@JvmInline
value class UserSession(val value: Int) {
    override fun toString(): String = "session#$value"
}

/** A channel id. The root channel is always 0. */
@JvmInline
value class ChannelId(val value: Int) {
    override fun toString(): String = "channel#$value"

    companion object {
        val ROOT = ChannelId(0)
    }
}

/**
 * Identifies one video stream: the sending user plus the per-sender `stream_id` from VideoState.
 * A sender bumps the stream id whenever the codec, source or dimensions change, so a key never
 * refers to two different streams within one connection.
 */
data class StreamKey(val session: UserSession, val streamId: Int) {
    override fun toString(): String = "stream(${session.value}/$streamId)"
}
