package com.calegix.mumblevideo.ui.video

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import com.calegix.mumblevideo.core.StreamKey
import kotlin.math.roundToInt

/**
 * A SurfaceView whose surface is handed to the core for [stream]. The core decodes and renders
 * straight into it; nothing frame-related passes through Compose.
 *
 * SurfaceHolder callbacks run on the main thread, and SurfaceView destroys the surface as soon as
 * surfaceDestroyed returns, which is why [com.calegix.mumblevideo.core.MumbleCore.setVideoSurface]
 * with null must block until the core has let go. Leaving composition detaches the view, which
 * destroys the surface through the same path.
 */
@Composable
fun VideoSurface(
    stream: StreamKey,
    onAttach: (StreamKey, Surface) -> Unit,
    onDetach: (StreamKey, Surface) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnAttach by rememberUpdatedState(onAttach)
    val currentOnDetach by rememberUpdatedState(onDetach)
    key(stream) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = currentOnAttach(stream, holder.surface)

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = Unit

                            override fun surfaceDestroyed(holder: SurfaceHolder) = currentOnDetach(
                                stream,
                                holder.surface,
                            )
                        },
                    )
                }
            },
            modifier = modifier,
        )
    }
}

/**
 * Sizes content to [aspect] (width / height) inside the space it is given, as large as fits:
 * letterboxed or pillarboxed, never stretched. The decoder and the camera preview both scale to
 * whatever surface they get, so the surface itself must have the picture's shape.
 */
fun Modifier.fitAspect(aspect: Float): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val (w, h) = when {
            !maxH.isFinite() || maxH >= Constraints.Infinity -> maxW to maxW / aspect
            maxW / maxH > aspect -> maxH * aspect to maxH
            else -> maxW to maxW / aspect
        }
        val placeable = measurable.measure(Constraints.fixed(w.roundToInt(), h.roundToInt()))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    },
)

/**
 * Sizes content to [aspect] so it covers the whole space it is given, centred, with whatever
 * spills over cut off by the screen edge: YouTube's "zoomed to fill". Only for full-screen use,
 * since a SurfaceView is not clipped by Compose.
 */
fun Modifier.fillAspect(aspect: Float): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val (w, h) = if (maxW / maxH > aspect) maxW to maxW / aspect else maxH * aspect to maxH
        val placeable = measurable.measure(Constraints.fixed(w.roundToInt(), h.roundToInt()))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(((maxW - w) / 2).roundToInt(), ((maxH - h) / 2).roundToInt())
        }
    },
)

/** A stream's shape: what the decoder last reported, else what was announced, else 16:9. */
fun streamAspect(stream: com.calegix.mumblevideo.core.VideoStream): Float {
    val active = stream.subscription as? com.calegix.mumblevideo.core.Subscription.Active
    val (w, h) = if (active != null && active.decodedWidth > 0 && active.decodedHeight > 0) {
        active.decodedWidth to active.decodedHeight
    } else {
        stream.width to stream.height
    }
    return if (w > 0 && h > 0) w.toFloat() / h else 16f / 9f
}
