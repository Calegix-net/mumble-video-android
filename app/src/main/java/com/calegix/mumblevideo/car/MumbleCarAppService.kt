package com.calegix.mumblevideo.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.calegix.mumblevideo.MumbleVideoApp

/**
 * Android Auto's way in: the car screen runs these templates on top of the same session the phone
 * app uses, so connecting in the car is connecting on the phone and the other way round.
 */
class MumbleCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug builds also talk to the Desktop Head Unit and other test hosts.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            val app = (carContext.applicationContext as MumbleVideoApp).container
            return CarHomeScreen(carContext, app.session, app.servers)
        }
    }
}
