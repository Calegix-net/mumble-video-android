package com.calegix.mumblevideo.client.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.calegix.mumblevideo.core.AudioConfig
import com.calegix.mumblevideo.core.AudioOutputStream

/**
 * Puts the audio system into voice-call mode for the session and keeps choosing where it plays
 * and records: a Bluetooth headset if one is connected (and allowed), else a wired one, else the
 * loudspeaker rather than the earpiece, which is what a voice chat app is expected to do.
 *
 * The choice is made again whenever an audio device comes or goes, so headphones connected
 * mid-call take over the microphone and the sound, and disconnecting them falls back. Undone by
 * [end]. Bluetooth needs the BLUETOOTH_CONNECT permission on Android 12+; without it headsets are
 * not offered as communication devices and the speaker is used.
 */
internal class AudioRouting(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var config = AudioConfig()

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = choose()

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = choose()
    }

    fun begin(config: AudioConfig) {
        if (config.outputStream != AudioOutputStream.VoiceCall) return
        this.config = config
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        active = true
        choose()
        audio.registerAudioDeviceCallback(devices, handler)
    }

    private fun choose() {
        if (!active) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val available = audio.availableCommunicationDevices
            val preferred = available.firstOrNull { config.bluetoothHeadset && it.type in BLUETOOTH }
                ?: available.firstOrNull { it.type in WIRED }
                ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: return
            if (audio.communicationDevice?.id != preferred.id) {
                val ok = audio.setCommunicationDevice(preferred)
                Log.i(
                    TAG,
                    "voice routed to ${preferred.productName} (type ${preferred.type})${if (ok) "" else " FAILED"}",
                )
            }
        } else {
            val headset = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type in WIRED ||
                    it.type in BLUETOOTH
            }
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = !headset
        }
    }

    fun end() {
        if (!active) return
        active = false
        audio.unregisterAudioDeviceCallback(devices)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
        }
        audio.mode = AudioManager.MODE_NORMAL
    }

    private companion object {
        const val TAG = "MvRouting"
        val BLUETOOTH = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
        val WIRED = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
    }
}
