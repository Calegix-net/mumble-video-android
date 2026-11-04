package com.calegix.mumblevideo.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.calegix.mumblevideo.R
import com.calegix.mumblevideo.core.ChatMessage
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.MessageTarget
import com.calegix.mumblevideo.core.MumbleCoreException
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.data.settings.SettingsRepository
import com.calegix.mumblevideo.session.SessionController
import com.calegix.mumblevideo.ui.MainActivity
import com.calegix.mumblevideo.ui.session.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Posts chat notifications while the app is in the background, per the notification settings.
 *
 * Each conversation (the channel, or one person's private messages) is a messaging-style
 * notification with Reply and Mark as read, which is what Android Auto needs to read messages out
 * and take a spoken reply.
 */
class MessageNotifier(
    private val context: Context,
    private val session: SessionController,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    /** Where a notification's messages came from, and so where a reply goes. */
    sealed interface Conversation {
        val notificationId: Int

        data object Channel : Conversation {
            override val notificationId = Notifications.ID_MESSAGES
        }

        data class Private(val session: UserSession, val name: String) : Conversation {
            override val notificationId get() = Notifications.ID_PRIVATE_MESSAGES + session.value
        }
    }

    /** Unread messages per conversation, oldest first: what each notification shows. */
    private val unread = mutableMapOf<Conversation, List<ChatMessage>>()

    fun start() {
        scope.launch {
            session.core.messages.collect { message ->
                val prefs = settings.settings.value
                val private = message.target is MessageTarget.ToUser
                val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (message.isOwn || foreground || !prefs.notifyMessages || (prefs.notifyPrivateOnly && !private)) {
                    return@collect
                }
                val conversation = conversationOf(message)
                unread[conversation] = (unread[conversation].orEmpty() + message).takeLast(MAX_SHOWN)
                post(conversation, silent = private && !prefs.vibrateOnPrivateMessage)
            }
        }
        // A new connection is a new chat: a reply from an old notification would go to the wrong place.
        scope.launch {
            session.core.connectionState.collect { if (it is ConnectionState.Connecting) clearAll() }
        }
    }

    /** Sends [text] where [conversation]'s messages came from, then clears its notification. */
    suspend fun reply(conversation: Conversation, text: String) {
        val core = session.core
        val target = when (conversation) {
            is Conversation.Private -> MessageTarget.ToUser(conversation.session)
            Conversation.Channel -> core.serverState.value.selfUser?.channel?.let { MessageTarget.ToChannel(it) }
        }
        try {
            if (target == null) throw MumbleCoreException(MumbleCoreException.Kind.NotConnected, "Not connected")
            core.sendMessage(target, ChatViewModel.escapeHtml(text))
            markRead(conversation)
        } catch (e: MumbleCoreException) {
            // Said in the same notification, so the car reads it out where the reply was given.
            val failed = ChatMessage(
                id = -1,
                timestampEpochMillis = System.currentTimeMillis(),
                sender = null,
                senderName = context.getString(R.string.app_name),
                target = MessageTarget.ToUser(UserSession(-1)),
                html = "Couldn't send \"$text\": ${e.message}",
            )
            unread[conversation] = unread[conversation].orEmpty() + failed
            post(conversation, silent = false)
        }
    }

    fun markRead(conversation: Conversation) {
        unread.remove(conversation)
        NotificationManagerCompat.from(context).cancel(conversation.notificationId)
    }

    private fun clearAll() {
        unread.keys.toList().forEach(::markRead)
    }

    private fun post(conversation: Conversation, silent: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val messages = unread[conversation].orEmpty()
        val last = messages.lastOrNull() ?: return
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
        when (conversation) {
            Conversation.Channel -> style.setConversationTitle(channelName()).setGroupConversation(true)
            is Conversation.Private -> style.setGroupConversation(false)
        }
        messages.forEach { message ->
            style.addMessage(
                plain(message.html),
                message.timestampEpochMillis,
                Person.Builder().setName(message.senderName).setKey(message.sender?.value?.toString()).build(),
            )
        }
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val reply = NotificationCompat.Action.Builder(0, "Reply", action(ChatReplyReceiver.ACTION_REPLY, conversation))
            .addRemoteInput(RemoteInput.Builder(ChatReplyReceiver.KEY_TEXT).setLabel("Reply").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val read = NotificationCompat.Action.Builder(
            0,
            "Mark as read",
            action(ChatReplyReceiver.ACTION_MARK_READ, conversation),
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (conversation is Conversation.Private) "${last.senderName} (private)" else last.senderName,
            )
            .setContentText(plain(last.html))
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(reply)
            .addInvisibleAction(read)
            .setSilent(silent)
            .build()
        NotificationManagerCompat.from(context).notify(conversation.notificationId, notification)
    }

    private fun action(name: String, conversation: Conversation): PendingIntent {
        val intent = Intent(context, ChatReplyReceiver::class.java).setAction(name)
        if (conversation is Conversation.Private) {
            intent.putExtra(ChatReplyReceiver.EXTRA_SESSION, conversation.session.value)
            intent.putExtra(ChatReplyReceiver.EXTRA_NAME, conversation.name)
        }
        // Mutable: the reply text is added to the intent by whoever answers.
        return PendingIntent.getBroadcast(
            context,
            name.hashCode() * 31 + conversation.notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun channelName(): String {
        val state = session.core.serverState.value
        val channel = state.selfUser?.channel?.let { state.channels[it]?.name }?.ifBlank { null }
        return channel ?: session.currentServer.value?.displayName ?: context.getString(R.string.app_name)
    }

    private fun plain(html: String) = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()

    companion object {
        /** The car reads these out; a long backlog is not worth hearing. */
        private const val MAX_SHOWN = 10

        fun conversationOf(message: ChatMessage): Conversation {
            val target = message.target
            val sender = message.sender
            return if (target is MessageTarget.ToUser && sender != null) {
                Conversation.Private(sender, message.senderName)
            } else {
                Conversation.Channel
            }
        }
    }
}
