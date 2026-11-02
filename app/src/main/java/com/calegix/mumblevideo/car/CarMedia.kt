package com.calegix.mumblevideo.car

import android.app.PendingIntent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.utils.MediaConstants
import com.calegix.mumblevideo.R
import com.calegix.mumblevideo.core.ChannelId
import com.calegix.mumblevideo.data.servers.SavedServer

/**
 * What Android Auto is handed, built from [CarHome]: a media source whose "tracks" are your saved
 * servers and the channels you can move to, and whose now-playing screen is the channel you are in.
 *
 * Android Auto only lists sideloaded apps that are media apps (template apps must come from Play),
 * so this is how the car gets a Mumble screen at all.
 */
object CarMedia {
    const val ROOT = "root"
    const val SERVERS = "servers"
    const val CHANNELS = "channels"
    const val MUTE = "mute"
    const val DEAFEN = "deafen"

    sealed interface Target {
        data class Server(val id: Long) : Target

        data class Channel(val id: ChannelId) : Target
    }

    fun mediaId(server: SavedServer) = "server:${server.id}"

    fun mediaId(channel: ChannelId) = "channel:${channel.value}"

    fun parse(mediaId: String?): Target? {
        val (kind, value) = mediaId?.split(':', limit = 2)?.takeIf { it.size == 2 } ?: return null
        return when (kind) {
            "server" -> value.toLongOrNull()?.let(Target::Server)
            "channel" -> value.toIntOrNull()?.let { Target.Channel(ChannelId(it)) }
            else -> null
        }
    }

    /** The car shows these as tabs. Servers first: it is the one with something in it when you get in. */
    fun tabs(): List<MediaItem> = listOf(
        browsable(SERVERS, "Servers"),
        browsable(CHANNELS, "Channels"),
    )

    /** Favourites first, then the ones used most recently. [connected] is the server you are on. */
    fun servers(servers: List<SavedServer>, connected: Long?): List<MediaItem> = servers
        .sortedWith(compareBy<SavedServer>({ !it.favourite }, { -(it.lastConnectedEpochMillis ?: 0) }))
        .map { server ->
            val detail = if (server.id == connected) "Connected" else serverDetail(server)
            playable(mediaId(server), server.displayName, detail)
        }

    fun channels(channels: List<CarChannel>): List<MediaItem> = channels.map { channel ->
        val detail = listOfNotNull(
            channel.path,
            when {
                channel.here -> "You're here"
                !channel.canEnter -> "Locked"
                channel.users == 1 -> "1 person"
                channel.users > 1 -> "${channel.users} people"
                else -> null
            },
        ).joinToString(" · ")
        playable(mediaId(channel.id), channel.name, detail.ifEmpty { null })
    }

    /**
     * The now-playing state. [problem] is a failed action (a locked channel, say): the car shows it
     * as a passing message without leaving the session. [openPhone] opens the app, for questions
     * only the phone can answer.
     */
    fun playback(home: CarHome, problem: String?, openPhone: PendingIntent): PlaybackStateCompat {
        val choose = PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
        val state = PlaybackStateCompat.Builder()
        when (home) {
            is CarHome.Servers -> {
                state.setActions(choose)
                val error = home.problem ?: problem
                if (error != null) {
                    state.setState(PlaybackStateCompat.STATE_ERROR, UNKNOWN, 0f)
                    state.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, error)
                } else {
                    state.setState(PlaybackStateCompat.STATE_STOPPED, UNKNOWN, 0f)
                }
            }

            is CarHome.Connecting -> {
                state.setActions(choose or PlaybackStateCompat.ACTION_STOP)
                state.setState(PlaybackStateCompat.STATE_CONNECTING, UNKNOWN, 0f)
            }

            is CarHome.OnPhone -> {
                state.setActions(choose or PlaybackStateCompat.ACTION_STOP)
                state.setState(PlaybackStateCompat.STATE_ERROR, UNKNOWN, 0f)
                state.setErrorMessage(PlaybackStateCompat.ERROR_CODE_ACTION_ABORTED, home.message)
                state.setExtras(
                    Bundle().apply {
                        putString(MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL, "Open")
                        putParcelable(
                            MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT,
                            openPhone,
                        )
                    },
                )
            }

            is CarHome.Connected -> {
                state.setActions(choose or PlaybackStateCompat.ACTION_STOP)
                state.setState(PlaybackStateCompat.STATE_PLAYING, UNKNOWN, 1f)
                // Not an error state: the car shows it briefly and stays on the channel.
                if (problem != null) state.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, problem)
                state.addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(
                        MUTE,
                        if (home.selfMuted) "Unmute" else "Mute",
                        if (home.selfMuted) R.drawable.ic_car_mic_off else R.drawable.ic_car_mic,
                    ).build(),
                )
                state.addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(
                        DEAFEN,
                        if (home.selfDeafened) "Undeafen" else "Deafen",
                        if (home.selfDeafened) R.drawable.ic_car_headset_off else R.drawable.ic_car_headset,
                    ).build(),
                )
            }
        }
        return state.build()
    }

    /** Channel and server, and who is talking: what the car shows in place of a song. */
    fun metadata(home: CarHome): MediaMetadataCompat? {
        val (title, subtitle, artist) = when (home) {
            is CarHome.Servers -> return null
            is CarHome.Connecting -> Triple(home.label, home.detail, home.label)
            is CarHome.OnPhone -> Triple(home.label, home.message, home.label)
            is CarHome.Connected -> Triple(home.channel, talkLine(home), home.label)
        }
        return MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
            .build()
    }

    /** "zed, amy talking", or how many are here when nobody is; you are left out. */
    fun talkLine(home: CarHome.Connected): String {
        val talking = home.people.filter { it.talking && !it.self }.map { it.name }
        if (talking.isNotEmpty()) return talking.joinToString(", ") + " talking"
        val others = home.people.count { !it.self }
        return when (others) {
            0 -> "Nobody else here"
            1 -> "1 other person"
            else -> "$others other people"
        } + " · ${home.label}"
    }

    private fun browsable(id: String, title: String) = MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setExtras(listStyle()).build(),
        MediaItem.FLAG_BROWSABLE,
    )

    private fun playable(id: String, title: String, subtitle: String?) = MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle).build(),
        MediaItem.FLAG_PLAYABLE,
    )

    /** Rows rather than the default grid of album art: these items have no pictures. */
    fun listStyle() = Bundle().apply {
        putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        )
        putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        )
    }

    private const val UNKNOWN = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
}

/** "skyn3t.lol · as nugg": the host when the label hides it, and the name you join as. */
internal fun serverDetail(server: SavedServer): String = listOfNotNull(
    server.host.takeIf { !it.equals(server.label, ignoreCase = true) },
    server.username.ifBlank { null }?.let { "as $it" },
).joinToString(" · ").ifEmpty { server.host }
