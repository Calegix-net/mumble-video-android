package com.calegix.mumblevideo.client.audio

import com.calegix.mumblevideo.core.AudioConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRoutingTest {
    private val phoneMic = AudioConfig(carAsMedia = true)

    private fun media(config: AudioConfig, bluetoothCar: Boolean = false) =
        AudioRouting.playAsMedia(config, bluetoothCar)

    @Test
    fun `by default the car's microphone is used, so a car keeps the call`() {
        assertFalse(media(AudioConfig(carProjection = true)))
        assertFalse(media(AudioConfig(), bluetoothCar = true))
    }

    @Test
    fun `with the phone's microphone chosen, Android Auto plays as media, not as a call`() {
        assertTrue(media(phoneMic.copy(carProjection = true)))
    }

    @Test
    fun `Android Auto counts even with Bluetooth headsets turned off`() {
        assertTrue(media(phoneMic.copy(carProjection = true, bluetoothHeadset = false)))
    }

    @Test
    fun `a car's Bluetooth plays as media with the phone's microphone, as before`() {
        assertTrue(media(phoneMic, bluetoothCar = true))
        assertFalse(media(phoneMic.copy(bluetoothHeadset = false), bluetoothCar = true))
    }

    @Test
    fun `no car means a call as usual`() {
        assertFalse(media(phoneMic))
    }
}
