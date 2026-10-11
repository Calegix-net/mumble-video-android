package com.calegix.mumblevideo.ui.video

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.calegix.mumblevideo.core.StreamKey

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
