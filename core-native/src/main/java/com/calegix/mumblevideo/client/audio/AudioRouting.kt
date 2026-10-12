package com.calegix.mumblevideo.client.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.calegix.mumblevideo.core.AudioConfig
import com.calegix.mumblevideo.core.AudioOutputStream

/**
 * Puts the audio system into voice-call mode for the session and picks where it plays: a
 * Bluetooth or wired headset if one is connected (and allowed), otherwise the loudspeaker rather
 * than the earpiece, which is what a voice chat app is expected to do. Undone by [end].
 */
internal class AudioRouting(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var active = false

    fun begin(config: AudioConfig) {
        if (config.outputStream != AudioOutputStream.VoiceCall) return
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        active = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audio.availableCommunicationDevices
            val preferred = devices.firstOrNull { config.bluetoothHeadset && it.type in BLUETOOTH }
                ?: devices.firstOrNull { it.type in WIRED }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            preferred?.let { audio.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = true
        }
    }

    fun end() {
        if (!active) return
        active = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
        }
        audio.mode = AudioManager.MODE_NORMAL
    }

    private companion object {
        val BLUETOOTH = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
        val WIRED = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
    }
}
