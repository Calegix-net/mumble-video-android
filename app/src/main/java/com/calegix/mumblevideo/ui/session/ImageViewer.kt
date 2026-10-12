package com.calegix.mumblevideo.ui.session

import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * A chat picture full screen, as the Pixel's photo viewer does it: pinch to zoom and drag to pan,
 * and at normal size a swipe up or down throws it away (back and the close button work too).
 */
@Composable
fun ImageViewer(bitmap: ImageBitmap, description: String, onDismiss: () -> Unit) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var drag by remember { mutableFloatStateOf(0f) }
        val scope = rememberCoroutineScope()
        val dismissDistance = with(androidx.compose.ui.platform.LocalDensity.current) { DISMISS_DISTANCE.toPx() }
        // The backdrop fades as the picture is pulled away, showing the chat behind.
        val backdrop = (1f - abs(drag) / (dismissDistance * 3)).coerceIn(0.2f, 1f)

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = backdrop))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var swiping = false
                        do {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (fingers >= 2 || scale > 1f) {
                                scale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            } else if (fingers == 1) {
                                swiping = true
                                drag += pan.y
                            }
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        if (swiping) {
                            if (abs(drag) > dismissDistance) {
                                currentOnDismiss()
                            } else {
                                scope.launch { animate(drag, 0f) { value, _ -> drag = value } }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y + drag
                    },
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(4.dp),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Close picture", tint = Color.White)
            }
        }
    }
}

private val DISMISS_DISTANCE = 120.dp
private const val MAX_SCALE = 5f
