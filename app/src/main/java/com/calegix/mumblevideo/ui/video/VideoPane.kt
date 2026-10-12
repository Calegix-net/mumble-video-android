package com.calegix.mumblevideo.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material.icons.automirrored.outlined.StopScreenShare
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.calegix.mumblevideo.core.StreamKey
import com.calegix.mumblevideo.core.Subscription
import com.calegix.mumblevideo.ui.AppViewModelProvider

@Composable
fun VideoPane(
    onOpenStream: (StreamKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VideoViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Tiles only need the playback side of the ViewModel.
    val playback: StreamPlayback = viewModel
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Box(modifier.fillMaxSize()) {
        if (!state.supported) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.VideocamOff, contentDescription = null, modifier = Modifier.size(48.dp))
                Text(
                    "This server doesn't support video",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "Video needs a mumble-video server that advertises it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OwnVideoControls(
                        state,
                        onToggleCamera = viewModel::toggleCamera,
                        onToggleScreen = viewModel::toggleScreenShare,
                    )
                }
                if (state.tiles.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Nobody is sharing video in your channel right now.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(state.tiles, key = { it.stream.key.toString() }) { tile ->
                    VideoTile(
                        tile = tile,
                        autoSubscribe = state.autoSubscribe,
                        playback = playback,
                        onOpen = { onOpenStream(tile.stream.key) },
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun OwnVideoControls(state: VideoUiState, onToggleCamera: () -> Unit, onToggleScreen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onToggleCamera) {
                Icon(
                    if (state.local.camera !=
                        null
                    ) {
                        Icons.Outlined.VideocamOff
                    } else {
                        Icons.Outlined.Videocam
                    },
                    contentDescription = null,
                )
                Text(
                    if (state.local.camera !=
                        null
                    ) {
                        "Stop camera"
                    } else {
                        "Start camera"
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            FilledTonalButton(onClick = onToggleScreen) {
                Icon(
                    if (state.local.screen !=
                        null
                    ) {
                        Icons.AutoMirrored.Outlined.StopScreenShare
                    } else {
                        Icons.AutoMirrored.Outlined.ScreenShare
                    },
                    contentDescription = null,
                )
                Text(
                    if (state.local.screen !=
                        null
                    ) {
                        "Stop sharing"
                    } else {
                        "Share screen"
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (state.local.camera != null || state.local.screen != null) {
            WatchingList(state.watchers)
        }
    }
}

@Composable
private fun WatchingList(watchers: List<Watcher>) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                if (watchers.isEmpty()) "Nobody is watching you yet" else "Watching you (${watchers.size})",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            // Plain labels, not chips: there is nothing to do with a watcher yet.
            watchers.forEach { watcher ->
                Text(
                    "${watcher.name} · ${watcher.streams.joinToString()}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun VideoTile(tile: StreamTile, autoSubscribe: Boolean, playback: StreamPlayback, onOpen: () -> Unit) {
    val stream = tile.stream
    // Remembered per stream, so a data-saver user who tapped "watch" keeps watching while scrolling.
    var wanted by rememberSaveable(stream.key.toString()) { mutableStateOf(autoSubscribe || stream.isLocal) }
    if (autoSubscribe) wanted = true

    if (wanted && !stream.isLocal) {
        DisposableEffect(stream.key) {
            playback.acquire(stream.key, thumbnail = true)
            onDispose { playback.release(stream.key) }
        }
    }
    val title = if (stream.isLocal) "You · ${stream.sourceName}" else "${tile.userName} · ${stream.sourceName}"
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClickLabel = "Open full screen") {
                if (wanted) onOpen() else wanted = true
            }
            .semantics(mergeDescendants = true) { contentDescription = title },
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (wanted) {
                VideoSurface(
                    stream.key,
                    playback::attachSurface,
                    playback::detachSurface,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.PlayCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                    Text("Tap to watch", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                if (stream.sourceKind.isScreen) Icons.AutoMirrored.Outlined.ScreenShare else Icons.Outlined.Videocam,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${stream.codec} · ${stream.width}×${stream.height} · ${statusLabel(stream.subscription)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            stream.audio?.let { audio ->
                Icon(
                    if (audio.muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = if (audio.muted) "Shared audio muted" else "Has shared audio",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

internal fun statusLabel(subscription: Subscription): String = when (subscription) {
    Subscription.None -> "not watching"
    Subscription.Pending -> "connecting…"
    is Subscription.Active -> "${subscription.framesPerSecond.toInt()} fps"
    is Subscription.Failed -> subscription.reason
}
