package com.calegix.mumblevideo.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.calegix.mumblevideo.MumbleVideoApp
import com.calegix.mumblevideo.core.UserSession
import kotlinx.coroutines.launch

/** Reply and Mark as read on a chat notification, from the phone's shade or from Android Auto. */
class ChatReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as MumbleVideoApp).container
        val notifier = container.messageNotifier
        val conversation = if (intent.hasExtra(EXTRA_SESSION)) {
            MessageNotifier.Conversation.Private(
                UserSession(intent.getIntExtra(EXTRA_SESSION, 0)),
                intent.getStringExtra(EXTRA_NAME).orEmpty(),
            )
        } else {
            MessageNotifier.Conversation.Channel
        }
        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
                if (text.isNullOrEmpty()) return
                val done = goAsync()
                container.appScope.launch {
                    try {
                        notifier.reply(conversation, text)
                    } finally {
                        done.finish()
                    }
                }
            }

            ACTION_MARK_READ -> notifier.markRead(conversation)
        }
    }

    companion object {
        const val ACTION_REPLY = "com.calegix.mumblevideo.action.CHAT_REPLY"
        const val ACTION_MARK_READ = "com.calegix.mumblevideo.action.CHAT_MARK_READ"
        const val KEY_TEXT = "text"
        const val EXTRA_SESSION = "session"
        const val EXTRA_NAME = "name"
    }
}
