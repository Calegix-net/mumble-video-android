package com.calegix.mumblevideo.data.settings

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepScreenAwakeTest {
    private suspend fun read(json: String) =
        AppSettingsSerializer.readFrom(ByteArrayInputStream(json.encodeToByteArray()))

    @Test
    fun `the old switch migrates and the default is while video`() = runTest {
        assertEquals(KeepScreenAwake.WhileVideo, read("{}").screenAwake)
        assertEquals(KeepScreenAwake.WhileVideo, read("""{"keepScreenOn":false}""").screenAwake)
        assertEquals(KeepScreenAwake.WhileOpen, read("""{"keepScreenOn":true}""").screenAwake)
        assertEquals(KeepScreenAwake.Off, read("""{"keepScreenOn":true,"keepScreenAwake":"Off"}""").screenAwake)
        assertEquals(KeepScreenAwake.WhileVideo, read("""{"keepScreenAwake":"Sometimes"}""").screenAwake)
    }

    @Test
    fun `a choice survives a round trip`() = runTest {
        val out = ByteArrayOutputStream()
        AppSettingsSerializer.writeTo(AppSettings(keepScreenAwake = KeepScreenAwake.Off), out)
        assertEquals(KeepScreenAwake.Off, read(out.toByteArray().decodeToString()).screenAwake)
    }

    @Test
    fun `each choice keeps the screen on when it should`() {
        val off = AppSettings(keepScreenAwake = KeepScreenAwake.Off)
        val video = AppSettings(keepScreenAwake = KeepScreenAwake.WhileVideo)
        val open = AppSettings(keepScreenAwake = KeepScreenAwake.WhileOpen)

        assertFalse(off.keepsScreenOn(connected = true, sendingVideo = true, watchingVideo = true))

        assertFalse(video.keepsScreenOn(connected = true, sendingVideo = false, watchingVideo = false))
        assertTrue(video.keepsScreenOn(connected = true, sendingVideo = true, watchingVideo = false))
        assertTrue(video.keepsScreenOn(connected = true, sendingVideo = false, watchingVideo = true))
        assertFalse(video.keepsScreenOn(connected = false, sendingVideo = true, watchingVideo = true))

        assertTrue(open.keepsScreenOn(connected = false, sendingVideo = false, watchingVideo = false))
    }
}
