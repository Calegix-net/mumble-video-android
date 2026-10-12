package com.calegix.mumblevideo.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.calegix.mumblevideo.MumbleVideoApp
import com.calegix.mumblevideo.R
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.VoiceState
import com.calegix.mumblevideo.session.SessionController
import com.calegix.mumblevideo.ui.MainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while connected, with an ongoing notification carrying mute, deafen
 * and disconnect actions (the same three Mumla offers, with disconnect in place of its overlay).
 *
 * It owns no connection logic: it watches [SessionController] and stops itself once the
 * connection is over.
 */
class ConnectionService : LifecycleService() {

    private val session: SessionController by lazy { (application as MumbleVideoApp).container.session }
    private var started = false
    private var seenActive = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_MUTE -> lifecycleScope.launch {
                session.core.setSelfMuted(!session.core.voiceState.value.selfMuted)
            }

            ACTION_DEAFEN -> lifecycleScope.launch {
                session.core.setSelfDeafened(!session.core.voiceState.value.selfDeafened)
            }

            ACTION_DISCONNECT -> lifecycleScope.launch { session.disconnect() }
        }
        if (!started) {
            started = true
            headset.start()
            goForeground(build(session.core.connectionState.value, session.core.voiceState.value))
            observe()
        }
        return START_NOT_STICKY
    }

    private val headset by lazy {
        HeadsetButton(this, session, (application as MumbleVideoApp).container.settings, lifecycleScope)
    }

    override fun onDestroy() {
        headset.stop()
        super.onDestroy()
    }

    private fun observe() {
        // Foreground types follow what is being shared; re-declared only when that changes.
        lifecycleScope.launch {
            combine(
                session.core.localVideo.map {
                    it.camera != null
                },
                session.screenCaptureForeground,
            ) { camera, screen ->
                camera to screen
            }.distinctUntilChanged().drop(1).collect { (camera, screen) ->
                runCatching {
                    goForeground(
                        build(session.core.connectionState.value, session.core.voiceState.value),
                        camera,
                        screen,
                    )
                }.onFailure { Log.w("MvService", "could not change foreground type", it) }
                session.onForegroundTypesUpdated()
            }
        }
        lifecycleScope.launch {
            combine(session.core.connectionState, session.core.voiceState, session.core.serverState) {
                    state,
                    voice,
                    _,
                ->
                state to voice
            }.collect { (state, voice) ->
                val over = state is ConnectionState.Disconnected || state is ConnectionState.Rejected
                if (!over) seenActive = true
                if (over && seenActive) {
                    ServiceCompat.stopForeground(this@ConnectionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    notify(build(state, voice))
                }
            }
        }
    }

    private fun goForeground(notification: Notification, camera: Boolean = false, screen: Boolean = false) {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (screen) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        // While the camera is shared, so it keeps running with the screen off or the app in the
        // background (Android 11+ stops camera access in background services without this type).
        if (camera &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        // The microphone type exists from 11 and is only allowed once the permission is granted
        // (enforced from 14). On 10, a media-playback service may record anyway.
        val micGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && micGranted) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, Notifications.ID_CONNECTION, notification, types)
    }

    private fun notify(notification: Notification) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(Notifications.ID_CONNECTION, notification)
        }
    }

    private fun build(state: ConnectionState, voice: VoiceState): Notification {
        val server = session.currentServer.value?.displayName ?: getString(R.string.app_name)
        val channel = session.core.serverState.value.let { s -> s.selfUser?.channel?.let { s.channels[it]?.name } }
        val text = when (state) {
            is ConnectionState.Connected -> buildString {
                append(channel ?: "Connected")
                if (voice.selfDeafened) {
                    append(" · deafened")
                } else if (voice.selfMuted) {
                    append(" · muted")
                }
            }

            is ConnectionState.Connecting -> "Connecting…"

            is ConnectionState.CertificateTrustRequired -> "Waiting for you to trust the server certificate"

            is ConnectionState.Reconnecting -> "Connection lost, reconnecting (attempt ${state.attempt})…"

            is ConnectionState.Rejected -> "Rejected: ${state.message}"

            is ConnectionState.Disconnected -> "Disconnected"
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, Notifications.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(server)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (state is ConnectionState.Connected) {
            builder.addAction(0, if (voice.selfMuted) "Unmute" else "Mute", action(ACTION_MUTE))
            builder.addAction(0, if (voice.selfDeafened) "Undeafen" else "Deafen", action(ACTION_DEAFEN))
        }
        builder.addAction(0, "Disconnect", action(ACTION_DISCONNECT))
        return builder.build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this,
        name.hashCode(),
        Intent(this, ConnectionService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val ACTION_MUTE = "com.calegix.mumblevideo.action.MUTE"
        private const val ACTION_DEAFEN = "com.calegix.mumblevideo.action.DEAFEN"
        private const val ACTION_DISCONNECT = "com.calegix.mumblevideo.action.DISCONNECT"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
        }
    }
}
