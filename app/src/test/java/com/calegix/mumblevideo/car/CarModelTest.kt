package com.calegix.mumblevideo.car

import com.calegix.mumblevideo.core.Channel
import com.calegix.mumblevideo.core.ChannelId
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.RejectReason
import com.calegix.mumblevideo.core.ServerAddress
import com.calegix.mumblevideo.core.ServerCertificate
import com.calegix.mumblevideo.core.ServerInfo
import com.calegix.mumblevideo.core.ServerState
import com.calegix.mumblevideo.core.TalkState
import com.calegix.mumblevideo.core.User
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.core.VoiceState
import com.calegix.mumblevideo.data.servers.SavedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarModelTest {
    private val root = ChannelId(0)
    private val lobby = ChannelId(1)
    private val gaming = ChannelId(2)
    private val room1 = ChannelId(3)
    private val afk = ChannelId(4)
    private val me = UserSession(10)
    private val sky = SavedServer(id = 1, label = "skyn3t", host = "skyn3t.lol", username = "nugg")
    private val address = ServerAddress("skyn3t.lol")
    private val info = ServerInfo("", "1.6", "Linux", "", "1.6", 50, udpActive = true, latencyMillis = 40)

    private fun user(id: Int, name: String, channel: ChannelId, selfMuted: Boolean = false, deafened: Boolean = false) =
        User(UserSession(id), name, channel, selfMuted = selfMuted, selfDeafened = deafened)

    private val state = ServerState(
        channels = listOf(
            Channel(root, null, "Root"),
            Channel(lobby, root, "Lobby", position = 0),
            Channel(gaming, root, "General", position = 1),
            Channel(room1, gaming, "Room #1"),
            Channel(afk, root, "real afk", position = 2, canEnter = false),
        ).associateBy { it.id },
        users = listOf(
            user(10, "nugg", lobby),
            user(11, "zed", lobby),
            user(12, "amy", lobby, selfMuted = true),
            user(13, "berg", lobby),
            user(14, "Centar", room1),
        ).associateBy { it.session },
        self = me,
    )

    private fun connected(talk: Map<UserSession, TalkState> = emptyMap(), voice: VoiceState = VoiceState()) =
        CarModel.home(
            ConnectionState.Connected(address, info),
            state,
            talk,
            voice,
            listOf(sky),
            sky,
        ) as CarHome.Connected

    @Test
    fun `connected shows the channel, you first, then whoever is talking, then names`() {
        val home = connected(talk = mapOf(UserSession(13) to TalkState.Talking))
        assertEquals("Lobby", home.channel)
        assertEquals("skyn3t", home.label)
        assertEquals(listOf("nugg", "berg", "amy", "zed"), home.people.map { it.name })
        assertTrue(home.people.first().self)
        assertEquals("Talking", home.people[1].status)
        assertEquals("Muted", home.people[2].status)
        assertEquals(null, home.people[3].status)
    }

    @Test
    fun `your own talking comes from what you transmit, and mute state from voice`() {
        val home = connected(voice = VoiceState(selfMuted = true, selfDeafened = false, transmitting = true))
        assertTrue(home.people.first().talking)
        assertTrue(home.selfMuted)
    }

    @Test
    fun `whispers and shouts count as talking`() {
        val home = connected(talk = mapOf(UserSession(11) to TalkState.Whispering))
        assertEquals("zed", home.people[1].name)
        assertTrue(home.people[1].talking)
    }

    @Test
    fun `disconnected lists saved servers, a refusal says why`() {
        val idle = CarModel.home(
            ConnectionState.Disconnected(),
            ServerState.EMPTY,
            emptyMap(),
            VoiceState(),
            listOf(sky),
            null,
        )
        assertEquals(CarHome.Servers(listOf(sky)), idle)
        val refused = CarModel.home(
            ConnectionState.Rejected(address, RejectReason.WrongUserPassword, "Wrong password"),
            ServerState.EMPTY,
            emptyMap(),
            VoiceState(),
            listOf(sky),
            sky,
        )
        assertEquals(CarHome.Servers(listOf(sky), "Wrong password"), refused)
    }

    @Test
    fun `an unknown certificate sends you to the phone`() {
        val home = CarModel.home(
            ConnectionState.CertificateTrustRequired(address, certificate()),
            ServerState.EMPTY,
            emptyMap(),
            VoiceState(),
            listOf(sky),
            sky,
        )
        assertTrue(home is CarHome.OnPhone)
    }

    @Test
    fun `channels put yours first, then busy ones, in tree order with their path`() {
        val channels = CarModel.channels(state)
        assertEquals(listOf("Lobby", "Room #1", "Root", "General", "real afk"), channels.map { it.name })
        assertTrue(channels.first().here)
        assertEquals("General", channels[1].path)
        assertEquals(1, channels[1].users)
        assertEquals(false, channels.last().canEnter)
    }

    private fun certificate() =
        ServerCertificate(
            subject = "CN=skyn3t.lol",
            issuer = "CN=skyn3t.lol",
            notBeforeEpochMillis = 0,
            notAfterEpochMillis = 0,
            sha256 = "00",
            problem = "self-signed",
        )
}
