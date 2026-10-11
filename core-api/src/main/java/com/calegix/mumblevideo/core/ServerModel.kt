package com.calegix.mumblevideo.core

/** A complete, immutable snapshot of the server's channels and users. */
data class ServerState(
    val channels: Map<ChannelId, Channel>,
    val users: Map<UserSession, User>,
    /** This client's own session, or null before ServerSync. */
    val self: UserSession?,
) {
    val selfUser: User? get() = self?.let(users::get)

    fun usersIn(channel: ChannelId): List<User> = users.values.filter { it.channel == channel }

    fun childrenOf(channel: ChannelId): List<Channel> =
        channels.values.filter { it.parent == channel }.sortedWith(Channel.ORDER)

    companion object {
        val EMPTY = ServerState(emptyMap(), emptyMap(), null)
    }
}

data class Channel(
    val id: ChannelId,
    /** Null only for the root channel. */
    val parent: ChannelId?,
    val name: String,
    /** Channel description (HTML), or null if the server only sent a hash not yet fetched. */
    val description: String? = null,
    val position: Int = 0,
    val temporary: Boolean = false,
    val linked: Set<ChannelId> = emptySet(),
    /** 0 means unlimited. */
    val maxUsers: Int = 0,
    /** Whether this client may enter; false shows the channel as locked. */
    val canEnter: Boolean = true,
) {
    companion object {
        /** Mumble's sort order: position, then name. */
        val ORDER: Comparator<Channel> = compareBy<Channel> {
            it.position
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }
}

data class User(
    val session: UserSession,
    val name: String,
    val channel: ChannelId,
    /** Registered user id, or null for unregistered users. */
    val registeredId: Int? = null,
    /** Muted by an admin. */
    val muted: Boolean = false,
    /** Deafened by an admin. */
    val deafened: Boolean = false,
    /** Suppressed by the channel (no speak permission). */
    val suppressed: Boolean = false,
    val selfMuted: Boolean = false,
    val selfDeafened: Boolean = false,
    val prioritySpeaker: Boolean = false,
    val recording: Boolean = false,
    /** Comment HTML if known; when null but [hasComment], fetch it with [UserBlob.Comment]. */
    val comment: String? = null,
    val hasComment: Boolean = comment != null,
    /** Fetch with [UserBlob.Avatar]. */
    val hasAvatar: Boolean = false,
) {
    /** Muted by any cause: what the UI's "can't hear them" icon should reflect. */
    val isSilenced: Boolean get() = muted || selfMuted || suppressed || deafened || selfDeafened
}

enum class UserBlob { Comment, Avatar }

/** Mirrors Mumble's TalkState. */
enum class TalkState {
    Passive,
    Talking,

    /** Talking to the whole channel tree or a linked channel. */
    Shouting,

    /** Whispering directly to this client. */
    Whispering,
}
