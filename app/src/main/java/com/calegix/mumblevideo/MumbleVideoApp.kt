package com.calegix.mumblevideo

import android.app.Application
import com.calegix.mumblevideo.service.Notifications

class MumbleVideoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        container = AppContainer(this)
    }
}
