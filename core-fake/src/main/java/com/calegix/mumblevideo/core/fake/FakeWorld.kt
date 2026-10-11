package com.calegix.mumblevideo.core.fake

import com.calegix.mumblevideo.core.Channel
import com.calegix.mumblevideo.core.ChannelId
import com.calegix.mumblevideo.core.StreamAudio
import com.calegix.mumblevideo.core.StreamKey
import com.calegix.mumblevideo.core.User
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.core.VideoCodec
import com.calegix.mumblevideo.core.VideoSourceKind
import com.calegix.mumblevideo.core.VideoStream

/** The made-up server the fake core pretends to be connected to. */
internal object FakeWorld {

    val SELF_SESSION = UserSession(1)

    private val LOBBY = ChannelId(1)
    private val GAMING = ChannelId(2)
    private val SQUAD_A = ChannelId(3)
    private val SQUAD_B = ChannelId(4)
    private val MOVIE_NIGHT = ChannelId(5)
    private val WORK = ChannelId(6)
    private val STANDUP = ChannelId(7)
    private val FOCUS = ChannelId(8)
    private val AFK = ChannelId(9)
    private val ADMINS = ChannelId(10)

    fun channels(serverName: String): Map<ChannelId, Channel> = listOf(
        Channel(
            ChannelId.ROOT,
            parent = null,
            name = serverName,
            description = "Welcome to the Mumble Video test server.",
        ),
        Channel(LOBBY, ChannelId.ROOT, "Lobby", position = 0, description = "Say hi."),
        Channel(GAMING, ChannelId.ROOT, "Gaming", position = 1),
        Channel(SQUAD_A, GAMING, "Squad A", position = 0, linked = setOf(SQUAD_B)),
        Channel(SQUAD_B, GAMING, "Squad B", position = 1, linked = setOf(SQUAD_A)),
        Channel(MOVIE_NIGHT, ChannelId.ROOT, "Movie Night", position = 2, description = "Screen shares welcome."),
        Channel(WORK, ChannelId.ROOT, "Work", position = 3),
        Channel(STANDUP, WORK, "Standup", position = 0, maxUsers = 8),
        Channel(FOCUS, WORK, "Focus (quiet)", position = 1),
        Channel(ADMINS, ChannelId.ROOT, "Admins", position = 4, canEnter = false),
        Channel(AFK, ChannelId.ROOT, "AFK", position = 99),
    ).associateBy { it.id }

    fun users(): Map<UserSession, User> = listOf(
        User(UserSession(2), "Mira", LOBBY, registeredId = 12, comment = "Building things at Calegix."),
        User(UserSession(3), "Jonas", MOVIE_NIGHT, registeredId = 3, prioritySpeaker = true),
        User(UserSession(4), "Kei", LOBBY, selfMuted = true),
        User(UserSession(5), "Ana", SQUAD_A, registeredId = 40, hasAvatar = true),
        User(UserSession(6), "Rook", SQUAD_A),
        User(UserSession(7), "Priya", SQUAD_B, comment = "PTT only, sorry if I'm slow to answer."),
        User(UserSession(8), "Theo", STANDUP, registeredId = 7),
        User(UserSession(9), "Lena", STANDUP, recording = true),
        User(UserSession(10), "Sam", MOVIE_NIGHT),
        User(UserSession(11), "Bot-9000", AFK, selfMuted = true, selfDeafened = true),
        User(UserSession(12), "Oskar", ADMINS, registeredId = 1, muted = true),
    ).associateBy { it.session }

    /** Streams other users announce shortly after we connect. */
    fun initialStreams(): List<VideoStream> = listOf(
        VideoStream(
            key = StreamKey(UserSession(2), 0),
            codec = VideoCodec.VP9,
            sourceKind = VideoSourceKind.Camera,
            sourceName = "Front camera",
            width = 1280,
            height = 720,
            maxFramerate = 30,
            targetBandwidth = 1_200_000,
        ),
        VideoStream(
            key = StreamKey(UserSession(3), 4),
            codec = VideoCodec.AV1,
            sourceKind = VideoSourceKind.Display,
            sourceName = "DP-1 (2560×1440)",
            width = 1920,
            height = 1080,
            maxFramerate = 15,
            targetBandwidth = 2_500_000,
            audio = StreamAudio(audioStreamId = 5),
        ),
        VideoStream(
            key = StreamKey(UserSession(5), 1),
            codec = VideoCodec.VP8,
            sourceKind = VideoSourceKind.Camera,
            sourceName = "Logitech BRIO",
            width = 640,
            height = 480,
            maxFramerate = 24,
            targetBandwidth = 600_000,
        ),
    )

    /** A stream that comes and goes, so the grid is never static. */
    fun intermittentStream(streamId: Int): VideoStream = VideoStream(
        key = StreamKey(UserSession(8), streamId),
        codec = VideoCodec.TiledImage,
        sourceKind = VideoSourceKind.Window,
        sourceName = "Terminal — standup notes",
        width = 1280,
        height = 800,
        maxFramerate = 5,
        targetBandwidth = 400_000,
    )

    val chatLines = listOf(
        "anyone up for a round?",
        "brb, coffee",
        "can you hear me ok?",
        "the screen share is a bit blurry on my end",
        "nice, video works on the phone now",
        "who's on the AV1 build?",
        "lol",
        "give me 5 minutes",
        "moving to Squad B",
        "<b>reminder:</b> standup at 10",
        "check <a href=\"https://www.mumble.info\">mumble.info</a>",
        "my mic was muted the whole time, wasn't it",
    )

    val privateReplies = listOf(
        "hey! what's up?",
        "got it, thanks",
        "sure, one sec",
        "can't talk right now, in a meeting",
    )
}
