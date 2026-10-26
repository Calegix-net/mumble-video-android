package com.calegix.mumblevideo.client.state

import com.calegix.mumblevideo.core.Channel
import com.calegix.mumblevideo.core.ChannelId
import com.calegix.mumblevideo.core.ServerState
import com.calegix.mumblevideo.core.StreamAudio
import com.calegix.mumblevideo.core.StreamKey
import com.calegix.mumblevideo.core.User
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.core.VideoCodec
import com.calegix.mumblevideo.core.VideoSourceKind
import com.calegix.mumblevideo.core.VideoStream
import com.calegix.mumblevideo.proto.MumbleProto

/**
 * Folds control messages into immutable snapshots. Pure functions: no I/O and no clocks, so the
 * protocol semantics (partial updates, link deltas, removals) are unit-testable.
 *
 * Mumble sends partial updates: a field that is absent means "unchanged", never "cleared".
 */
internal object SessionReducer {

    fun channelState(state: ServerState, msg: MumbleProto.ChannelState): ServerState {
        if (!msg.hasChannelId()) return state
        val id = ChannelId(msg.channelId)
        val old = state.channels[id]
        var links = old?.linked ?: emptySet()
        if (msg.linksCount > 0) links = msg.linksList.map(::ChannelId).toSet()
        links = links + msg.linksAddList.map(::ChannelId) - msg.linksRemoveList.map(::ChannelId).toSet()
        val channel = Channel(
            id = id,
            parent = if (msg.hasParent()) ChannelId(msg.parent) else old?.parent,
            name = if (msg.hasName()) msg.name else old?.name ?: "",
            description = when {
                msg.hasDescription() -> msg.description

                msg.hasDescriptionHash() -> null

                // only the hash: fetch on demand
                else -> old?.description
            },
            position = if (msg.hasPosition()) msg.position else old?.position ?: 0,
            temporary = if (msg.hasTemporary()) msg.temporary else old?.temporary ?: false,
            linked = links,
            maxUsers = if (msg.hasMaxUsers()) msg.maxUsers else old?.maxUsers ?: 0,
            canEnter = if (msg.hasCanEnter()) msg.canEnter else old?.canEnter ?: true,
        )
        return state.copy(channels = state.channels + (id to channel))
    }

    fun channelRemove(state: ServerState, msg: MumbleProto.ChannelRemove): ServerState {
        val id = ChannelId(msg.channelId)
        return state.copy(
            channels = (state.channels - id).mapValues { (_, c) ->
                if (id in
                    c.linked
                ) {
                    c.copy(linked = c.linked - id)
                } else {
                    c
                }
            },
        )
    }

    fun userState(state: ServerState, msg: MumbleProto.UserState): ServerState {
        if (!msg.hasSession()) return state
        val session = UserSession(msg.session)
        val old = state.users[session]
        val user = User(
            session = session,
            name = if (msg.hasName()) msg.name else old?.name ?: "",
            channel = if (msg.hasChannelId()) ChannelId(msg.channelId) else old?.channel ?: ChannelId.ROOT,
            registeredId = if (msg.hasUserId()) msg.userId else old?.registeredId,
            muted = if (msg.hasMute()) msg.mute else old?.muted ?: false,
            deafened = if (msg.hasDeaf()) msg.deaf else old?.deafened ?: false,
            suppressed = if (msg.hasSuppress()) msg.suppress else old?.suppressed ?: false,
            selfMuted = if (msg.hasSelfMute()) msg.selfMute else old?.selfMuted ?: false,
            selfDeafened = if (msg.hasSelfDeaf()) msg.selfDeaf else old?.selfDeafened ?: false,
            prioritySpeaker = if (msg.hasPrioritySpeaker()) msg.prioritySpeaker else old?.prioritySpeaker ?: false,
            recording = if (msg.hasRecording()) msg.recording else old?.recording ?: false,
            comment = when {
                msg.hasComment() -> msg.comment.ifEmpty { null }
                msg.hasCommentHash() -> null
                else -> old?.comment
            },
            hasComment = when {
                msg.hasComment() -> msg.comment.isNotEmpty()
                msg.hasCommentHash() -> !msg.commentHash.isEmpty
                else -> old?.hasComment ?: false
            },
            hasAvatar = when {
                msg.hasTexture() -> !msg.texture.isEmpty
                msg.hasTextureHash() -> !msg.textureHash.isEmpty
                else -> old?.hasAvatar ?: false
            },
        )
        return state.copy(users = state.users + (session to user))
    }

    fun userRemove(state: ServerState, msg: MumbleProto.UserRemove): ServerState =
        state.copy(users = state.users - UserSession(msg.session))

    /**
     * Where to move ourselves after a reconnect: back to [wanted], the channel we were in, if it
     * still exists, we may enter it and the server did not already put us there.
     */
    fun rejoinTarget(state: ServerState, wanted: ChannelId?): ChannelId? {
        wanted ?: return null
        val here = state.selfUser?.channel ?: return null
        val channel = state.channels[wanted] ?: return null
        return wanted.takeIf { it != here && channel.canEnter }
    }

    /**
     * Applies a VideoState. Screen-share audio arrives as its own stream with codec OpusAudio and is
     * folded into the same sender's screen stream as [VideoStream.audio].
     */
    fun videoState(streams: List<VideoStream>, msg: MumbleProto.VideoState, self: UserSession?): List<VideoStream> {
        if (!msg.hasSession() || !msg.hasStreamId()) return streams
        val key = StreamKey(UserSession(msg.session), msg.streamId)
        if (!msg.active) {
            // Either a picture stream ended, or an audio stream we folded into one.
            return streams.filterNot { it.key == key }
                .map { s ->
                    if (s.key.session == key.session &&
                        s.audio?.audioStreamId == key.streamId
                    ) {
                        s.copy(audio = null)
                    } else {
                        s
                    }
                }
        }
        if (msg.codec == MumbleProto.VideoState.Codec.OpusAudio) {
            return streams.map { s ->
                if (s.key.session == key.session && s.sourceKind.isScreen && s.audio == null) {
                    s.copy(audio = StreamAudio(audioStreamId = key.streamId))
                } else {
                    s
                }
            }
        }
        val codec = codecOf(msg.codec) ?: return streams // unknown codec: drop, as the protocol requires
        val kind = kindOf(msg.sourceKind) ?: return streams
        val old = streams.firstOrNull { it.key == key }
        val stream = VideoStream(
            key = key,
            codec = codec,
            sourceKind = kind,
            sourceName = msg.sourceName,
            width = msg.width,
            height = msg.height,
            maxFramerate = msg.maxFramerate,
            targetBandwidth = msg.targetBandwidth,
            audio = old?.audio,
            subscription = old?.subscription ?: com.calegix.mumblevideo.core.Subscription.None,
            isLocal = key.session == self,
        )
        return if (old == null) streams + stream else streams.map { if (it.key == key) stream else it }
    }

    private fun codecOf(codec: MumbleProto.VideoState.Codec): VideoCodec? = when (codec) {
        MumbleProto.VideoState.Codec.TiledImage -> VideoCodec.TiledImage
        MumbleProto.VideoState.Codec.VP8 -> VideoCodec.VP8
        MumbleProto.VideoState.Codec.VP9 -> VideoCodec.VP9
        MumbleProto.VideoState.Codec.AV1 -> VideoCodec.AV1
        MumbleProto.VideoState.Codec.H264 -> VideoCodec.H264
        else -> null
    }

    private fun kindOf(kind: MumbleProto.VideoState.SourceKind): VideoSourceKind? = when (kind) {
        MumbleProto.VideoState.SourceKind.Camera -> VideoSourceKind.Camera
        MumbleProto.VideoState.SourceKind.Display -> VideoSourceKind.Display
        MumbleProto.VideoState.SourceKind.Window -> VideoSourceKind.Window
        MumbleProto.VideoState.SourceKind.Application -> VideoSourceKind.Application
        else -> null
    }
}
