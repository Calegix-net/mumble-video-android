package com.calegix.mumblevideo.client.state

import com.calegix.mumblevideo.core.ChannelId
import com.calegix.mumblevideo.core.ServerState
import com.calegix.mumblevideo.core.StreamKey
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.core.VideoCodec
import com.calegix.mumblevideo.proto.MumbleProto
import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReducerTest {
    private fun channel(id: Int, block: MumbleProto.ChannelState.Builder.() -> Unit = {}) =
        MumbleProto.ChannelState.newBuilder().setChannelId(id).apply(block).build()

    private fun user(session: Int, block: MumbleProto.UserState.Builder.() -> Unit = {}) =
        MumbleProto.UserState.newBuilder().setSession(session).apply(block).build()

    @Test
    fun `absent fields mean unchanged, not cleared`() {
        var state = SessionReducer.channelState(
            ServerState.EMPTY,
            channel(1) {
                parent = 0
                name = "Lobby"
                position = 3
                maxUsers =
                    8
            },
        )
        state = SessionReducer.channelState(state, channel(1) { description = "hi" })
        val lobby = state.channels.getValue(ChannelId(1))
        assertEquals("Lobby", lobby.name)
        assertEquals(3, lobby.position)
        assertEquals(8, lobby.maxUsers)
        assertEquals("hi", lobby.description)
        assertEquals(ChannelId.ROOT, lobby.parent)
    }

    @Test
    fun `links are replaced by a full list and adjusted by deltas`() {
        var state = SessionReducer.channelState(ServerState.EMPTY, channel(1) { addAllLinks(listOf(2, 3)) })
        state = SessionReducer.channelState(
            state,
            channel(1) {
                addLinksAdd(4)
                addLinksRemove(2)
            },
        )
        assertEquals(setOf(ChannelId(3), ChannelId(4)), state.channels.getValue(ChannelId(1)).linked)
        state = SessionReducer.channelState(state, channel(3))
        state = SessionReducer.channelRemove(state, MumbleProto.ChannelRemove.newBuilder().setChannelId(3).build())
        assertEquals(setOf(ChannelId(4)), state.channels.getValue(ChannelId(1)).linked)
    }

    @Test
    fun `user state merges, comments by hash are fetched later, and removal drops the user`() {
        var state = SessionReducer.userState(
            ServerState.EMPTY,
            user(5) {
                name = "Mira"
                channelId = 2
                selfMute = true
            },
        )
        state =
            SessionReducer.userState(
                state,
                user(5) {
                    selfMute = false
                    commentHash =
                        ByteString.copyFrom(byteArrayOf(1))
                },
            )
        val mira = state.users.getValue(UserSession(5))
        assertEquals("Mira", mira.name)
        assertEquals(ChannelId(2), mira.channel)
        assertFalse(mira.selfMuted)
        assertTrue(mira.hasComment)
        assertNull(mira.comment)
        state = SessionReducer.userRemove(state, MumbleProto.UserRemove.newBuilder().setSession(5).build())
        assertTrue(state.users.isEmpty())
    }

    private fun video(session: Int, stream: Int, block: MumbleProto.VideoState.Builder.() -> Unit) =
        MumbleProto.VideoState.newBuilder().setSession(session).setStreamId(stream).apply(block).build()

    @Test
    fun `screen share audio folds into the picture stream and unknown codecs are dropped`() {
        var streams = SessionReducer.videoState(
            emptyList(),
            video(3, 1) {
                active = true
                codec = MumbleProto.VideoState.Codec.H264
                sourceKind = MumbleProto.VideoState.SourceKind.Display
                width = 1920
                height = 1080
            },
            self = UserSession(9),
        )
        streams =
            SessionReducer.videoState(
                streams,
                video(3, 2) {
                    active = true
                    codec =
                        MumbleProto.VideoState.Codec.OpusAudio
                },
                UserSession(9),
            )
        streams =
            SessionReducer.videoState(
                streams,
                video(4, 1) {
                    active = true
                    codec =
                        MumbleProto.VideoState.Codec.CODEC_UNKNOWN
                },
                UserSession(9),
            )
        assertEquals(1, streams.size)
        val share = streams.single()
        assertEquals(VideoCodec.H264, share.codec)
        assertEquals(2, share.audio?.audioStreamId)

        streams = SessionReducer.videoState(streams, video(3, 2) { active = false }, UserSession(9))
        assertNull(streams.single().audio)
        streams = SessionReducer.videoState(streams, video(3, 1) { active = false }, UserSession(9))
        assertTrue(streams.isEmpty())
    }

    @Test
    fun `own streams are marked local`() {
        val streams = SessionReducer.videoState(
            emptyList(),
            video(9, 0) {
                active = true
                codec = MumbleProto.VideoState.Codec.VP8
                sourceKind = MumbleProto.VideoState.SourceKind.Camera
            },
            self = UserSession(9),
        )
        assertEquals(StreamKey(UserSession(9), 0), streams.single().key)
        assertTrue(streams.single().isLocal)
    }
}
