package com.calegix.mumblevideo.client.audio

import com.calegix.mumblevideo.core.AudioConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRoutingTest {
    private fun media(config: AudioConfig, bluetoothCar: Boolean = false) =
        AudioRouting.playAsMedia(config, bluetoothCar)

    @Test
    fun `Android Auto plays as media, not as a call`() {
        assertTrue(media(AudioConfig(carProjection = true)))
    }

    @Test
    fun `Android Auto counts even with Bluetooth headsets turned off`() {
        assertTrue(media(AudioConfig(carProjection = true, bluetoothHeadset = false)))
    }

    @Test
    fun `turning car-as-media off keeps the call, Android Auto or not`() {
        assertFalse(media(AudioConfig(carProjection = true, carAsMedia = false)))
        assertFalse(media(AudioConfig(carAsMedia = false), bluetoothCar = true))
    }

    @Test
    fun `a car's Bluetooth still plays as media, as before`() {
        assertTrue(media(AudioConfig(), bluetoothCar = true))
        assertFalse(media(AudioConfig(bluetoothHeadset = false), bluetoothCar = true))
    }

    @Test
    fun `no car means a call as usual`() {
        assertFalse(media(AudioConfig()))
    }
}
