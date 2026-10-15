package com.calegix.mumblevideo.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

/**
 * Keeps a share going while the phone would otherwise sleep.
 *
 * While any video is shared the CPU and Wi-Fi stay up, so a camera keeps streaming with the screen
 * locked instead of stalling when the radio drops into power save. A screen share also keeps the
 * display on: Android stops producing frames once the screen sleeps and locks, which viewers saw
 * as the share ending. The screen lock is the deprecated full-brightness kind because a service
 * has no window to put FLAG_KEEP_SCREEN_ON on while the user is in the app they are sharing.
 */
internal class ShareWakeLock(context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    private val cpu = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MumbleVideo:share").apply {
        setReferenceCounted(false)
    }

    @Suppress("DEPRECATION")
    private val screen = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "MumbleVideo:screen-share").apply {
        setReferenceCounted(false)
    }

    private val radio = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MumbleVideo:share")?.apply {
        setReferenceCounted(false)
    }

    /** [sharing]: any video is shared. [screenOn]: keep the display on too. */
    fun update(sharing: Boolean, screenOn: Boolean) {
        hold(cpu, sharing)
        hold(screen, sharing && screenOn)
        radio?.let {
            if (sharing && !it.isHeld) {
                it.acquire()
            } else if (!sharing && it.isHeld) {
                it.release()
            }
        }
        Log.i(TAG, "sharing=$sharing screenOn=$screenOn")
    }

    fun release() = update(sharing = false, screenOn = false)

    private fun hold(lock: PowerManager.WakeLock, on: Boolean) {
        if (on && !lock.isHeld) {
            lock.acquire()
        } else if (!on && lock.isHeld) {
            lock.release()
        }
    }

    private companion object {
        const val TAG = "MvWake"
    }
}
