package com.calegix.mumblevideo.client.audio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
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
 * A car is the exception. Placing a call over its hands-free profile makes the car show a phone
 * call with a running timer, drops voice to call quality and stalls while the link switches over,
 * so with [AudioConfig.carAsMedia] the session leaves call mode while a car is connected: voice
 * plays as media over A2DP and the phone's own microphone records. [onStream] tells playback
 * which stream that means, since media and call audio go to different places.
 *
 * The choice is made again whenever an audio device comes or goes, so headphones connected
 * mid-call take over the microphone and the sound, and disconnecting them falls back. Undone by
 * [end]. Bluetooth needs the BLUETOOTH_CONNECT permission on Android 12+; without it headsets are
 * not offered as communication devices, cars cannot be told apart, and the speaker is used.
 */
internal class AudioRouting(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var callMode = false
    private var config = AudioConfig()

    /** The stream playback should use right now; follows [onStream]. */
    @Volatile var stream: AudioOutputStream = AudioOutputStream.VoiceCall
        private set

    /** Called on the main thread whenever [stream] changes during a session. */
    var onStream: (AudioOutputStream) -> Unit = {}

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = choose()

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = choose()
    }

    fun begin(config: AudioConfig) {
        this.config = config
        stream = config.outputStream
        if (config.outputStream != AudioOutputStream.VoiceCall) return
        active = true
        choose()
        audio.registerAudioDeviceCallback(devices, handler)
    }

    private fun choose() {
        if (!active) return
        val car = if (config.bluetoothHeadset && config.carAsMedia) connectedCar() else null
        if (car != null) {
            if (callMode || stream != AudioOutputStream.Media) {
                leaveCallMode()
                Log.i(TAG, "voice played as media through ${car.productName}, phone microphone")
            }
            setStream(AudioOutputStream.Media)
            return
        }
        if (!callMode) {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            callMode = true
        }
        setStream(AudioOutputStream.VoiceCall)
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

    private fun setStream(next: AudioOutputStream) {
        if (stream == next) return
        stream = next
        onStream(next)
    }

    /** A connected Bluetooth media output whose device class says it is a car. */
    private fun connectedCar(): AudioDeviceInfo? {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type in BLUETOOTH_MEDIA && isCar(adapter, it.address)
        }
    }

    // BLUETOOTH_CONNECT is checked just below; lint does not follow a check behind a version test.
    @SuppressLint("MissingPermission")
    private fun isCar(adapter: BluetoothAdapter, address: String): Boolean {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            adapter.getRemoteDevice(address).bluetoothClass?.deviceClass in CAR_CLASSES
        } catch (e: SecurityException) {
            false
        }
    }

    private fun leaveCallMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
        }
        audio.mode = AudioManager.MODE_NORMAL
        callMode = false
    }

    fun end() {
        if (!active) return
        active = false
        audio.unregisterAudioDeviceCallback(devices)
        leaveCallMode()
    }

    private companion object {
        const val TAG = "MvRouting"
        val BLUETOOTH = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
        val BLUETOOTH_MEDIA = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_SPEAKER)
        val WIRED = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
        val CAR_CLASSES = setOf(
            BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
        )
    }
}
