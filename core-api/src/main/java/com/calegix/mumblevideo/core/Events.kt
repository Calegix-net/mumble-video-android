package com.calegix.mumblevideo.core

/** One-off events from the core. Chat messages have their own flow, [MumbleCore.messages]. */
sealed interface CoreEvent {
    data class UserJoinedServer(val session: UserSession, val name: String) : CoreEvent

    data class UserLeftServer(val session: UserSession, val name: String) : CoreEvent

    data class UserMoved(val session: UserSession, val from: ChannelId, val to: ChannelId) : CoreEvent

    /** The server denied an action (`PermissionDenied`). */
    data class PermissionDenied(val message: String) : CoreEvent

    data class StreamStarted(val key: StreamKey) : CoreEvent

    data class StreamEnded(val key: StreamKey) : CoreEvent

    /** Someone subscribed to or left one of this client's streams. */
    data class WatcherChanged(val stream: StreamKey, val session: UserSession, val watching: Boolean) : CoreEvent
}

/** Thrown by [MumbleCore] commands that cannot be carried out. */
class MumbleCoreException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind {
        NotConnected,
        PermissionDenied,
        NotSupported,
        InvalidArgument,
        InvalidCertificate,
        MessageTooLong,
        Internal,
    }
}
