package com.calegix.mumblevideo.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.calegix.mumblevideo.R

object Notifications {
    const val CHANNEL_CONNECTION = "connection"
    const val CHANNEL_MESSAGES = "messages"
    const val ID_CONNECTION = 1
    const val ID_MESSAGES = 2

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_CONNECTION,
                    context.getString(R.string.channel_connection),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.channel_connection_description)
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_MESSAGES,
                    context.getString(R.string.channel_messages),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.channel_messages_description) },
            ),
        )
    }
}
