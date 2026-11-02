package com.calegix.mumblevideo.car

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.os.BundleCompat
import androidx.media.utils.MediaConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calegix.mumblevideo.MainDispatcherRule
import com.calegix.mumblevideo.TestGraph
import com.calegix.mumblevideo.core.ChannelId
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.data.servers.SavedServer
import com.calegix.mumblevideo.settle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class CarMediaTest {
    @get:Rule val main = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val open = PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)

    private val sky = SavedServer(id = 1, label = "skyn3t", host = "skyn3t.lol", username = "nugg", favourite = true)
    private val home = SavedServer(id = 2, label = "home", host = "home.lan", lastConnectedEpochMillis = 5)
    private val old = SavedServer(id = 3, label = "old.example.org", host = "old.example.org")

    private fun person(name: String, self: Boolean = false, talking: Boolean = false) =
        CarPerson(UserSession(name.hashCode()), name, self, talking, muted = false, deafened = false)

    private val connected = CarHome.Connected(
        label = "skyn3t",
        channel = "Lobby",
        people = listOf(person("nugg", self = true, talking = true), person("zed", talking = true), person("amy")),
        selfMuted = false,
        selfDeafened = false,
    )

    @Test
    fun `media ids round trip, and anything else is ignored`() {
        assertEquals(CarMedia.Target.Server(1), CarMedia.parse(CarMedia.mediaId(sky)))
        assertEquals(CarMedia.Target.Channel(ChannelId(7)), CarMedia.parse(CarMedia.mediaId(ChannelId(7))))
        assertNull(CarMedia.parse("song:1"))
        assertNull(CarMedia.parse("server:x"))
        assertNull(CarMedia.parse(null))
    }

    @Test
    fun `servers list favourites first, then recent, and mark the one you are on`() {
        val items = CarMedia.servers(listOf(old, home, sky), connected = 2)
        assertEquals(listOf("skyn3t", "home", "old.example.org"), items.map { it.description.title.toString() })
        assertEquals("skyn3t.lol · as nugg", items[0].description.subtitle.toString())
        assertEquals("Connected", items[1].description.subtitle.toString())
        // The host is not repeated when it is the label.
        assertEquals("old.example.org", items[2].description.subtitle.toString())
        assertTrue(items.all { it.isPlayable && !it.isBrowsable })
    }

    @Test
    fun `channels say where you are and which are locked`() {
        val items = CarMedia.channels(
            listOf(
                CarChannel(ChannelId(1), "Lobby", null, 3, here = true, canEnter = true),
                CarChannel(ChannelId(3), "Room #1", "General", 1, here = false, canEnter = true),
                CarChannel(ChannelId(4), "real afk", null, 0, here = false, canEnter = false),
            ),
        )
        assertEquals(
            listOf("You're here", "General · 1 person", "Locked"),
            items.map { it.description.subtitle.toString() },
        )
    }

    @Test
    fun `disconnected, the car can pick a server but play alone does nothing`() {
        val state = CarMedia.playback(CarHome.Servers(listOf(sky)), null, open)
        assertEquals(PlaybackStateCompat.STATE_STOPPED, state.state)
        assertTrue(state.actions and PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID != 0L)
        assertEquals(0L, state.actions and PlaybackStateCompat.ACTION_PLAY)
        assertNull(CarMedia.metadata(CarHome.Servers(listOf(sky))))
    }

    @Test
    fun `a refused connection is an error the car shows`() {
        val state = CarMedia.playback(CarHome.Servers(listOf(sky), problem = "Wrong password"), null, open)
        assertEquals(PlaybackStateCompat.STATE_ERROR, state.state)
        assertEquals("Wrong password", state.errorMessage.toString())
    }

    @Test
    fun `connected, the car shows the channel, who is talking, mute and deafen`() {
        val state = CarMedia.playback(connected, null, open)
        assertEquals(PlaybackStateCompat.STATE_PLAYING, state.state)
        assertTrue(state.actions and PlaybackStateCompat.ACTION_STOP != 0L)
        assertEquals(listOf("Mute", "Deafen"), state.customActions.map { it.name.toString() })
        assertEquals(listOf(CarMedia.MUTE, CarMedia.DEAFEN), state.customActions.map { it.action })

        val muted = CarMedia.playback(connected.copy(selfMuted = true, selfDeafened = true), null, open)
        assertEquals(listOf("Unmute", "Undeafen"), muted.customActions.map { it.name.toString() })

        val meta = CarMedia.metadata(connected)!!
        assertEquals("Lobby", meta.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE))
        assertEquals("zed talking", meta.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE))
        assertEquals("skyn3t", meta.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
    }

    @Test
    fun `with nobody talking, the subtitle counts the others`() {
        val quiet = connected.copy(people = listOf(person("nugg", self = true), person("zed"), person("amy")))
        assertEquals("2 other people · skyn3t", CarMedia.talkLine(quiet))
        assertEquals("Nobody else here · skyn3t", CarMedia.talkLine(quiet.copy(people = quiet.people.take(1))))
    }

    @Test
    fun `a failed action while connected is a passing message, not an error state`() {
        val state = CarMedia.playback(connected, "Permission denied", open)
        assertEquals(PlaybackStateCompat.STATE_PLAYING, state.state)
        assertEquals("Permission denied", state.errorMessage.toString())
    }

    @Test
    fun `a certificate question sends you to the phone`() {
        val state = CarMedia.playback(CarHome.OnPhone("skyn3t", "Check it on your phone"), null, open)
        assertEquals(PlaybackStateCompat.STATE_ERROR, state.state)
        assertNotNull(
            BundleCompat.getParcelable(
                state.extras!!,
                MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT,
                PendingIntent::class.java,
            ),
        )
    }

    // The bridge, against the scripted fake server.

    private class Car(scope: TestScope, val graph: TestGraph, open: PendingIntent) {
        var state: PlaybackStateCompat? = null
        var meta: MediaMetadataCompat? = null
        val changed = mutableListOf<String>()
        val bridge = CarSessionBridge(graph.session, graph.servers, scope.backgroundScope, open, { state = it }) {
            meta = it
        }

        init {
            bridge.start { changed += it }
        }
    }

    private fun TestScope.car(): Car {
        val graph = TestGraph(this)
        return Car(this, graph, open).also { settle() }
    }

    private fun TestScope.idle(millis: Long = 5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        settle(millis)
    }

    @Test
    fun `picking a server in the car connects, and stop disconnects`() = runTest {
        val car = car()
        val id = car.graph.servers.save(TestGraph.SERVER)
        idle()
        val servers = car.bridge.children(CarMedia.SERVERS)
        assertEquals(listOf("Test"), servers.map { it.description.title.toString() })
        assertTrue(CarMedia.SERVERS in car.changed)

        car.bridge.callback.onPlayFromMediaId(servers.single().mediaId, null)
        idle()
        // The fake server's certificate is new: only the phone can accept it.
        assertTrue(car.graph.core.connectionState.value is ConnectionState.CertificateTrustRequired)
        assertEquals(PlaybackStateCompat.STATE_ERROR, car.state!!.state)
        car.graph.session.answerCertificatePrompt(true)
        idle()
        assertEquals(PlaybackStateCompat.STATE_PLAYING, car.state!!.state)
        assertEquals("Connected", car.bridge.children(CarMedia.SERVERS).single().description.subtitle.toString())
        assertEquals(id, car.graph.session.currentServer.value!!.id)

        car.bridge.callback.onStop()
        idle()
        assertTrue(car.graph.core.connectionState.value is ConnectionState.Disconnected)
        assertEquals(PlaybackStateCompat.STATE_STOPPED, car.state!!.state)
    }

    @Test
    fun `play without a pick never connects`() = runTest {
        val car = car()
        car.graph.servers.save(TestGraph.SERVER.copy(favourite = true))
        car.bridge.callback.onPlay()
        car.bridge.callback.onPrepare()
        idle()
        assertTrue(car.graph.core.connectionState.value is ConnectionState.Disconnected)
    }

    @Test
    fun `mute, deafen and channels from the car`() = runTest {
        val car = car()
        with(car.graph) { connect() }
        idle()

        car.bridge.callback.onCustomAction(CarMedia.MUTE, null)
        idle()
        assertTrue(car.graph.core.voiceState.value.selfMuted)
        assertEquals("Unmute", car.state!!.customActions[0].name.toString())
        car.bridge.callback.onCustomAction(CarMedia.DEAFEN, null)
        idle()
        assertTrue(car.graph.core.voiceState.value.selfDeafened)

        val lobby = car.graph.core.serverState.value.channels.values.single { it.name == "Lobby" }
        val items = car.bridge.children(CarMedia.CHANNELS)
        assertTrue(items.any { it.mediaId == CarMedia.mediaId(lobby.id) })
        car.bridge.callback.onPlayFromMediaId(CarMedia.mediaId(lobby.id), null)
        idle()
        assertEquals(lobby.id, car.graph.core.serverState.value.selfUser!!.channel)
        assertEquals("Lobby", car.meta!!.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE))
    }

    @Test
    fun `voice search finds a channel, or says it found nothing`() = runTest {
        val car = car()
        with(car.graph) { connect() }
        idle()
        car.bridge.callback.onPlayFromSearch("lobby", null)
        idle()
        val here = car.graph.core.serverState.value.let { it.channels[it.selfUser!!.channel]!!.name }
        assertEquals("Lobby", here)

        car.bridge.callback.onPlayFromSearch("nowhere at all", null)
        // Shown for a few seconds, then gone.
        idle(1_500)
        assertEquals("Nothing called \"nowhere at all\"", car.state!!.errorMessage.toString())
        assertEquals(PlaybackStateCompat.STATE_PLAYING, car.state!!.state)
        assertFalse(car.graph.core.connectionState.value is ConnectionState.Disconnected)
        idle(6_000)
        assertNull(car.state!!.errorMessage)
    }
}
