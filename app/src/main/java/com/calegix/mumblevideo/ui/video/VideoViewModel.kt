package com.calegix.mumblevideo.ui.video

import android.content.Intent
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calegix.mumblevideo.core.CameraRequest
import com.calegix.mumblevideo.core.LocalVideoState
import com.calegix.mumblevideo.core.MumbleCoreException
import com.calegix.mumblevideo.core.ScreenShareRequest
import com.calegix.mumblevideo.core.StreamKey
import com.calegix.mumblevideo.core.VideoStream
import com.calegix.mumblevideo.data.settings.AppSettings
import com.calegix.mumblevideo.data.settings.SettingsRepository
import com.calegix.mumblevideo.session.SessionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StreamTile(val stream: VideoStream, val userName: String)

data class Watcher(val name: String, val streams: List<String>)

data class VideoUiState(
    val supported: Boolean = false,
    val tiles: List<StreamTile> = emptyList(),
    val local: LocalVideoState = LocalVideoState(),
    val watchers: List<Watcher> = emptyList(),
    val autoSubscribe: Boolean = true,
    val dataSaver: Boolean = false,
    val maxVideoBandwidth: Int = 0,
    /** Last answer to "share the phone's sound too?", offered as the default next time. */
    val shareDeviceAudio: Boolean = true,
)

/** What a view showing a stream needs: hold a subscription and hand over its surface. */
interface StreamPlayback {
    fun acquire(stream: StreamKey, thumbnail: Boolean)

    fun release(stream: StreamKey)

    fun attachSurface(stream: StreamKey, surface: Surface)

    fun detachSurface(stream: StreamKey, surface: Surface)
}

class VideoViewModel(
    private val session: SessionController,
    private val settings: SettingsRepository,
) : ViewModel(),
    StreamPlayback {
    private val core = session.core

    val uiState: StateFlow<VideoUiState> = combine(
        core.videoStreams,
        core.serverState,
        core.localVideo,
        core.watchers,
        combine(core.capabilities, settings.settings, ::Pair),
    ) { streams, state, local, watchers, (caps, prefs) ->
        fun name(stream: VideoStream) = state.users[stream.key.session]?.name ?: "Unknown"
        val ownLabels = streams.filter { it.isLocal }.associate {
            it.key to
                if (it.sourceKind.isScreen) "screen" else "camera"
        }
        val bySession = watchers.flatMap { (key, sessions) ->
            sessions.map { it to key }
        }.groupBy({ it.first }, { it.second })
        VideoUiState(
            supported = caps.video,
            // Own streams first, then everyone else's in a stable order.
            tiles = streams.sortedWith(compareBy({ !it.isLocal }, { name(it).lowercase() }, { it.key.streamId }))
                .map { StreamTile(it, if (it.isLocal) "You" else name(it)) },
            local = local,
            watchers = bySession.map { (session, keys) ->
                Watcher(state.users[session]?.name ?: "Unknown", keys.mapNotNull { ownLabels[it] })
            }.sortedBy { it.name.lowercase() },
            autoSubscribe = prefs.autoSubscribe && !prefs.dataSaver,
            dataSaver = prefs.dataSaver,
            shareDeviceAudio = prefs.shareDeviceAudio,
            maxVideoBandwidth = caps.maxVideoBandwidth,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideoUiState())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Called by a tile or the fullscreen view while it is on screen. */
    override fun acquire(stream: StreamKey, thumbnail: Boolean) {
        val bandwidth = if (thumbnail && settings.settings.value.dataSaver) AppSettings.DATA_SAVER_BANDWIDTH else 0
        session.subscriptions.acquire(stream, bandwidth)
    }

    override fun release(stream: StreamKey) = session.subscriptions.release(stream)

    override fun attachSurface(stream: StreamKey, surface: Surface) = session.subscriptions.attachSurface(
        stream,
        surface,
    )

    override fun detachSurface(stream: StreamKey, surface: Surface) = session.subscriptions.detachSurface(
        stream,
        surface,
    )

    fun setStreamAudio(stream: StreamKey, volume: Float, muted: Boolean) {
        viewModelScope.launch { core.setStreamAudio(stream, volume, muted) }
    }

    fun toggleCamera() = command {
        if (core.localVideo.value.camera != null) core.stopCamera() else startCamera(settings.settings.value.cameraId)
    }

    /** Moves to the next camera the device has (front, back, external...), and remembers it. */
    fun switchCamera() = command {
        val cameras = core.availableCameras()
        if (cameras.size < 2) return@command
        val current =
            settings.settings.value.cameraId
                ?: cameras.firstOrNull { it.facing == settings.settings.value.cameraFacing }?.id
        val next = cameras[(cameras.indexOfFirst { it.id == current } + 1).mod(cameras.size)]
        settings.update { it.copy(cameraId = next.id, cameraFacing = next.facing) }
        if (core.localVideo.value.camera != null) {
            core.stopCamera()
            startCamera(next.id)
        }
    }

    private suspend fun startCamera(cameraId: String?) {
        val prefs = settings.settings.value
        core.startCamera(
            CameraRequest(
                facing = prefs.cameraFacing,
                cameraId = cameraId,
                width = prefs.cameraResolution.width,
                height = prefs.cameraResolution.height,
                framerate = prefs.cameraFramerate,
                codec = prefs.codecPreference,
            ),
        )
    }

    fun stopScreenShare() = command { core.stopScreenShare() }

    fun setShareDeviceAudio(share: Boolean) {
        viewModelScope.launch { settings.update { it.copy(shareDeviceAudio = share) } }
    }

    /** Starts sharing with the result of the system's screen-capture consent. */
    fun startScreenShare(resultCode: Int, data: Intent) = command {
        val prefs = settings.settings.value
        session.startScreenShare(
            resultCode,
            data,
            ScreenShareRequest(
                width = prefs.screenResolution.width,
                height = prefs.screenResolution.height,
                framerate = prefs.screenFramerate,
                codec = prefs.codecPreference,
                includeAudio = prefs.shareDeviceAudio,
            ),
        )
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun command(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: MumbleCoreException) {
                _message.value = e.message
            }
        }
    }
}
