package com.calegix.mumblevideo.car

import com.calegix.mumblevideo.core.ChannelId
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.ServerState
import com.calegix.mumblevideo.core.TalkState
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.core.VoiceState
import com.calegix.mumblevideo.data.servers.SavedServer

/**
 * What the car screen shows, worked out from the session without any car classes so it can be
 * tested on the JVM. The screens only turn it into templates.
 */
sealed interface CarHome {
    /** Not connected: pick a saved server. [problem] is why the last attempt ended, if it failed. */
    data class Servers(val servers: List<SavedServer>, val problem: String? = null) : CarHome

    data class Connecting(val label: String, val detail: String) : CarHome

    /** Something only the phone can answer (an unknown server certificate). */
    data class OnPhone(val label: String, val message: String) : CarHome

    data class Connected(
        val label: String,
        val channel: String,
        /** Everyone in this channel, you first, then talkers, then by name. */
        val people: List<CarPerson>,
        val selfMuted: Boolean,
        val selfDeafened: Boolean,
    ) : CarHome
}

data class CarPerson(
    val session: UserSession,
    val name: String,
    val self: Boolean,
    val talking: Boolean,
    /** Can't be heard: self-muted, muted by an admin or suppressed. */
    val muted: Boolean,
    val deafened: Boolean,
) {
    /** The row's second line: "Talking", "Muted", "Deafened" or nothing. */
    val status: String?
        get() = when {
            talking -> "Talking"
            deafened -> "Deafened"
            muted -> "Muted"
            else -> null
        }
}

data class CarChannel(
    val id: ChannelId,
    val name: String,
    /** "Gaming › Rooms": where it sits, for channels below the top level. */
    val path: String?,
    val users: Int,
    val here: Boolean,
    val canEnter: Boolean,
)

object CarModel {
    fun home(
        connection: ConnectionState,
        server: ServerState,
        talk: Map<UserSession, TalkState>,
        voice: VoiceState,
        servers: List<SavedServer>,
        current: SavedServer?,
    ): CarHome {
        val label = current?.label?.ifBlank { current.host } ?: "Server"
        return when (connection) {
            is ConnectionState.Connected -> {
                val self = server.selfUser
                    ?: return CarHome.Connecting(label, "Joining…")
                val people = server.usersIn(self.channel).map { user ->
                    val mine = user.session == server.self
                    CarPerson(
                        session = user.session,
                        name = user.name,
                        self = mine,
                        talking = if (mine) voice.transmitting else talk[user.session].isTalking(),
                        muted = user.muted || user.selfMuted || user.suppressed,
                        deafened = user.deafened || user.selfDeafened,
                    )
                }.sortedWith(compareBy<CarPerson>({ !it.self }, { !it.talking }, { it.name.lowercase() }))
                CarHome.Connected(
                    label = label,
                    channel = server.channels[self.channel]?.name?.ifBlank { null } ?: "Root",
                    people = people,
                    selfMuted = voice.selfMuted,
                    selfDeafened = voice.selfDeafened,
                )
            }

            is ConnectionState.Connecting -> CarHome.Connecting(label, "Connecting…")

            is ConnectionState.Reconnecting -> CarHome.Connecting(label, "Reconnecting (try ${connection.attempt})…")

            is ConnectionState.CertificateTrustRequired ->
                CarHome.OnPhone(label, "Check this server's certificate in the app on your phone.")

            is ConnectionState.Rejected -> CarHome.Servers(servers, connection.message.ifBlank { "Refused" })

            is ConnectionState.Disconnected -> CarHome.Servers(servers)
        }
    }

    /**
     * Channels to move to, in the server's tree order, with where you are first and busy channels
     * next: a car list only shows a handful of rows, and those are the likely picks.
     */
    fun channels(server: ServerState): List<CarChannel> {
        val here = server.selfUser?.channel
        val counts = server.users.values.groupingBy { it.channel }.eachCount()
        val ordered = mutableListOf<CarChannel>()
        fun walk(id: ChannelId, path: List<String>) {
            val channel = server.channels[id] ?: return
            val isRoot = channel.parent == null
            if (!isRoot) {
                ordered += CarChannel(
                    id = id,
                    name = channel.name,
                    path = path.takeIf { it.isNotEmpty() }?.joinToString(" › "),
                    users = counts[id] ?: 0,
                    here = id == here,
                    canEnter = channel.canEnter,
                )
            }
            val below = if (isRoot) path else path + channel.name
            server.childrenOf(id).forEach { walk(it.id, below) }
        }
        server.channels.values.filter { it.parent == null }.forEach { root ->
            // The root channel itself is a place you can be; list it under its own name.
            ordered +=
                CarChannel(
                    root.id,
                    root.name.ifBlank {
                        "Root"
                    },
                    null,
                    counts[root.id] ?: 0,
                    root.id == here,
                    root.canEnter,
                )
            walk(root.id, emptyList())
        }
        return ordered.sortedWith(compareBy<CarChannel>({ !it.here }, { it.users == 0 }))
    }

    private fun TalkState?.isTalking() = this != null && this != TalkState.Passive
}
