package com.calegix.mumblevideo.car

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.calegix.mumblevideo.MumbleVideoApp
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.MumbleCore
import com.calegix.mumblevideo.core.MumbleCoreException
import com.calegix.mumblevideo.data.servers.SavedServer
import com.calegix.mumblevideo.data.servers.ServerRepository
import com.calegix.mumblevideo.session.SessionController
import com.calegix.mumblevideo.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Android Auto's way in: the car browses saved servers and channels here, and its now-playing
 * screen drives the same session the phone app uses (mute, deafen, disconnect).
 *
 * Bound only while the car (or another media browser) is using it; it holds no connection itself.
 */
class CarMediaService : MediaBrowserServiceCompat() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var media: MediaSessionCompat
    private lateinit var car: CarSessionBridge

    override fun onCreate() {
        super.onCreate()
        val container = (application as MumbleVideoApp).container
        media = MediaSessionCompat(this, "MumbleVideoCar")
        val openPhone = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        media.setSessionActivity(openPhone)
        car = CarSessionBridge(container.session, container.servers, scope, openPhone, media::setPlaybackState) {
            media.setMetadata(it)
        }
        media.setCallback(car.callback)
        media.isActive = true
        sessionToken = media.sessionToken
        car.start { notifyChildrenChanged(it) }
    }

    override fun onDestroy() {
        scope.cancel()
        media.isActive = false
        media.release()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        // Media resumption on the phone's lock screen: nothing to resume, a voice session is live or not.
        if (rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) == true) return null
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        // Debug builds also answer test tools such as the Media Controller Tester.
        if (!debuggable && clientPackageName !in CAR_CLIENTS && clientPackageName != packageName) return null
        return BrowserRoot(CarMedia.ROOT, CarMedia.listStyle())
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        result.detach()
        scope.launch { result.sendResult(car.children(parentId).toMutableList()) }
    }

    private companion object {
        val CAR_CLIENTS = setOf(
            "com.google.android.projection.gearhead", // Android Auto
            "com.google.android.carassistant", // the Assistant in the car
            "com.google.android.googlequicksearchbox", // "Hey Google, play … on Mumble Video"
        )
    }
}

/**
 * The car's side of the session, without the service around it so it can be tested: follows the
 * session into playback state and metadata, answers the browse tree, and carries out what the car asks.
 */
class CarSessionBridge(
    private val session: SessionController,
    private val servers: ServerRepository,
    private val scope: CoroutineScope,
    private val openPhone: PendingIntent,
    private val publishState: (PlaybackStateCompat) -> Unit,
    private val publishMetadata: (MediaMetadataCompat?) -> Unit,
) {
    private val core: MumbleCore get() = session.core

    /** The last failed car action, shown once on the now-playing screen. */
    private val problem = MutableStateFlow<String?>(null)
    private var clearProblem: Job? = null

    fun start(childrenChanged: (String) -> Unit) {
        scope.launch {
            combine(homeFlow(), problem, ::Pair).conflate().collect { (home, problem) ->
                publishState(CarMedia.playback(home, problem, openPhone))
                publishMetadata(CarMedia.metadata(home))
                // Hosts limit how often the screen may change, and talking flickers far faster.
                delay(REDRAW_INTERVAL_MS)
            }
        }
        scope.launch {
            combine(servers.servers, connectedServerId(), ::Pair).distinctUntilChanged().drop(1)
                .collect { childrenChanged(CarMedia.SERVERS) }
        }
        scope.launch {
            channelsFlow().distinctUntilChanged().drop(1).conflate().collect {
                childrenChanged(CarMedia.CHANNELS)
                delay(REDRAW_INTERVAL_MS)
            }
        }
    }

    suspend fun children(parentId: String): List<MediaItem> = when (parentId) {
        CarMedia.ROOT -> CarMedia.tabs()
        CarMedia.SERVERS -> CarMedia.servers(servers.servers.first(), connectedServerId().first())
        CarMedia.CHANNELS -> CarMedia.channels(channelsFlow().first())
        else -> emptyList()
    }

    val callback = object : MediaSessionCompat.Callback() {
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            act { play(CarMedia.parse(mediaId)) }
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            act { search(query.orEmpty()) }
        }

        // Deliberately not ACTION_PLAY: the car sends play on its own when it resumes the last media
        // app, and joining voice with the car's microphone open must be something you chose.

        override fun onStop() {
            act { session.disconnect() }
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            val voice = core.voiceState.value
            when (action) {
                CarMedia.MUTE -> act { core.setSelfMuted(!voice.selfMuted) }
                CarMedia.DEAFEN -> act { core.setSelfDeafened(!voice.selfDeafened) }
            }
        }
    }

    private suspend fun play(target: CarMedia.Target?) {
        when (target) {
            is CarMedia.Target.Server -> servers.get(target.id)?.let { connect(it) }
            is CarMedia.Target.Channel -> core.joinChannel(target.id)
            null -> Unit
        }
    }

    /** Voice search: a channel when connected, else a saved server; by name, ignoring case. */
    private suspend fun search(query: String) {
        val wanted = query.trim()
        if (wanted.isEmpty()) return
        if (core.connectionState.value is ConnectionState.Connected) {
            val channel = CarModel.channels(core.serverState.value).firstOrNull { it.name.contains(wanted, true) }
            if (channel != null) return core.joinChannel(channel.id)
        }
        val server = servers.servers.first().firstOrNull {
            it.displayName.contains(wanted, true) || it.host.contains(wanted, true)
        } ?: return report("Nothing called \"$wanted\"")
        connect(server)
    }

    private suspend fun connect(server: SavedServer) {
        val already = core.connectionState.value is ConnectionState.Connected &&
            session.currentServer.value?.id == server.id
        if (!already) session.connect(server)
    }

    private fun act(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: MumbleCoreException) {
                report(e.message ?: "That didn't work")
            }
        }
    }

    private fun report(message: String) {
        problem.value = message
        clearProblem?.cancel()
        clearProblem = scope.launch {
            delay(PROBLEM_SHOWN_MS)
            problem.value = null
        }
    }

    private fun homeFlow(): Flow<CarHome> = combine(
        combine(core.connectionState, core.serverState, core.talkStates, ::Triple),
        core.voiceState,
        servers.servers,
        session.currentServer,
    ) { (connection, server, talk), voice, saved, now -> CarModel.home(connection, server, talk, voice, saved, now) }
        .distinctUntilChanged()

    private fun channelsFlow(): Flow<List<CarChannel>> = combine(core.connectionState, core.serverState) { c, s ->
        if (c is ConnectionState.Connected) CarModel.channels(s) else emptyList()
    }

    private fun connectedServerId(): Flow<Long?> = combine(core.connectionState, session.currentServer) { c, s ->
        s?.id?.takeIf { c is ConnectionState.Connected }
    }

    private companion object {
        const val REDRAW_INTERVAL_MS = 1_000L
        const val PROBLEM_SHOWN_MS = 5_000L
    }
}
