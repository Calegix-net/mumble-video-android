package com.calegix.mumblevideo.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.calegix.mumblevideo.R
import com.calegix.mumblevideo.core.MessageTarget
import com.calegix.mumblevideo.data.settings.SettingsRepository
import com.calegix.mumblevideo.session.SessionController
import com.calegix.mumblevideo.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Posts chat notifications while the app is in the background, per the notification settings. */
class MessageNotifier(
    private val context: Context,
    private val session: SessionController,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            session.core.messages.collect { message ->
                val prefs = settings.settings.value
                val private = message.target is MessageTarget.ToUser
                val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (message.isOwn || foreground || !prefs.notifyMessages || (prefs.notifyPrivateOnly && !private)) {
                    return@collect
                }
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    return@collect
                }
                val text = HtmlCompat.fromHtml(message.html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
                val open = PendingIntent.getActivity(
                    context,
                    1,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_MESSAGES)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(if (private) "${message.senderName} (private)" else message.senderName)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setSilent(private && !prefs.vibrateOnPrivateMessage)
                    .build()
                NotificationManagerCompat.from(context).notify(Notifications.ID_MESSAGES, notification)
            }
        }
    }
}
