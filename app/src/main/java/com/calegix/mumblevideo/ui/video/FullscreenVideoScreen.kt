package com.calegix.mumblevideo.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.calegix.mumblevideo.core.StreamKey
import com.calegix.mumblevideo.core.Subscription
import com.calegix.mumblevideo.ui.AppViewModelProvider
import kotlinx.coroutines.delay

/** One stream, edge to edge on black, with share-audio controls. Tap toggles the overlay. */
@Composable
fun FullscreenVideoScreen(
    stream: StreamKey,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VideoViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tile = state.tiles.firstOrNull { it.stream.key == stream }
    // The stream ended while we were watching it.
    val currentOnBack by rememberUpdatedState(onBack)
    val ended = tile == null && state.tiles.isNotEmpty()
    LaunchedEffect(ended) { if (ended) currentOnBack() }

    DisposableEffect(stream) {
        viewModel.acquire(stream, thumbnail = false)
        onDispose { viewModel.release(stream) }
    }
    var overlay by rememberSaveable { mutableStateOf(true) }
    // Pinch out to fill the screen (cropping), pinch in for the whole picture, as in YouTube.
    var fill by rememberSaveable { mutableStateOf(false) }
    var zoomHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(zoomHint) {
        if (zoomHint != null) {
            delay(ZOOM_HINT_MS)
            zoomHint = null
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var zoom = 1f
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            zoom *= event.calculateZoom()
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (zoom > PINCH_THRESHOLD && !fill) {
                        fill = true
                        zoomHint = "Zoomed to fill"
                    } else if (zoom < 1 / PINCH_THRESHOLD && fill) {
                        fill = false
                        zoomHint = "Original"
                    }
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                overlay =
                    !overlay
            },
        contentAlignment = Alignment.Center,
    ) {
        // Some senders announce 0x0 (the size is in the bitstream); a zero aspect ratio would crash layout.
        val aspect =
            tile?.stream?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
        VideoSurface(
            stream,
            viewModel::attachSurface,
            viewModel::detachSurface,
            (tile?.stream?.let(::streamAspect) ?: aspect).let { shape ->
                if (fill) Modifier.fillAspect(shape) else Modifier.fitAspect(shape)
            },
        )

        zoomHint?.let { hint ->
            Text(
                hint,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (overlay && tile != null) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(4.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(tile.userName, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        val sub = tile.stream.subscription
                        val detail = if (sub is Subscription.Active) {
                            "${sub.decodedWidth}×${sub.decodedHeight} · ${sub.framesPerSecond.toInt()} fps · loss ${sub.lossPermille / 10.0}%"
                        } else {
                            statusLabel(sub)
                        }
                        Text(
                            "${tile.stream.sourceName} · ${tile.stream.codec} · $detail",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Box(Modifier.weight(1f))
                tile.stream.audio?.let { audio ->
                    ShareAudioControls(
                        volume = audio.volume,
                        muted = audio.muted,
                        onChange = { volume, muted -> viewModel.setStreamAudio(stream, volume, muted) },
                    )
                }
            }
        }
    }
}

private const val PINCH_THRESHOLD = 1.15f
private const val ZOOM_HINT_MS = 1200L

@Composable
private fun ShareAudioControls(volume: Float, muted: Boolean, onChange: (Float, Boolean) -> Unit) {
    var local by remember(volume) { mutableFloatStateOf(volume) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(
            Color.Black.copy(alpha = 0.5f),
        ).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        IconButton(onClick = { onChange(local, !muted) }) {
            Icon(
                if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                contentDescription = if (muted) "Unmute shared audio" else "Mute shared audio",
                tint = Color.White,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local, muted) },
            valueRange = 0f..2f,
            enabled = !muted,
            modifier = Modifier
                .weight(1f)
                .semantics {
                    contentDescription = "Shared audio volume"
                    stateDescription = "${(local * 100).toInt()} percent"
                },
        )
        Text("${(local * 100).toInt()}%", color = Color.White, modifier = Modifier.padding(start = 8.dp))
    }
}
