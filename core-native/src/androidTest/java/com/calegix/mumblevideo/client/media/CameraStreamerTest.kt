package com.calegix.mumblevideo.client.media

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraStreamerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun camerasAreListedWithSizes() {
        val cameras = CameraStreamer.cameras(context)
        assumeTrue("no camera on this device", cameras.isNotEmpty())
        cameras.forEach { assertTrue(it.name, it.sizes.isNotEmpty() && it.maxFramerate > 0) }
    }

    @Test
    fun streamsEncodedFramesStartingWithAKeyframe() {
        val camera = CameraStreamer.cameras(context).firstOrNull()
        assumeTrue("no camera on this device", camera != null)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)

        val frames = Collections.synchronizedList(mutableListOf<CameraStreamer.EncodedFrame>())
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val enough = CountDownLatch(10)
        val streamer = CameraStreamer(
            context,
            CameraStreamer.Config(cameraId = camera!!.id, width = 640, height = 480, framerate = 15, bitrate = 500_000),
            onFrame = {
                frames += it
                enough.countDown()
            },
            onError = { errors += it },
        )
        try {
            streamer.start()
            enough.await(5, TimeUnit.SECONDS)
        } finally {
            streamer.close()
        }
        assertTrue("errors: $errors", errors.isEmpty())
        assertTrue("only ${frames.size} frames", frames.size >= 10)
        assertTrue("first frame is not a keyframe", frames.first().isKeyframe)
        assertEquals(frames.indices.map { it.toLong() }, frames.map { it.frameNumber })
        assertTrue(
            frames.all {
                it.width == streamer.outputWidth && it.height == streamer.outputHeight &&
                    it.data.isNotEmpty()
            },
        )
        assertEquals(0, streamer.outputWidth % 16)
        streamer.close() // idempotent
    }
}
