package com.dmytro.leo.messages

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Reads messenger notifications when Leo is asked "чи мені ніхто не писав?". Unread messages are
 * exactly the ones whose notifications are still there: the messenger removes them once the chat is read.
 * Keeps nothing: the notifications are read only on request, and only those of the messengers in [Messengers].
 */
class MessageNotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun unreadChats(): List<UnreadChat> =
        runCatching { activeNotifications }.getOrNull().orEmpty().mapNotNull { toChat(it) }

    private fun toChat(sbn: StatusBarNotification): UnreadChat? {
        val n = sbn.notification ?: return null
        if (sbn.packageName !in Messengers.packages) return null
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return null
        if (!Messengers.isFromPeople(sbn.packageName, n.channelId)) return null

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null) {
            val group = style.isGroupConversation
            if (Messengers.isChannelPost(sbn.packageName, n.shortcutId, group)) return null
            // A message without a person is the user's own reply; if none has a person, they are all from the chat
            val anyPerson = style.messages.any { it.person != null }
            val messages = style.messages.mapNotNull { msg ->
                val text = msg.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val sender = msg.person?.name?.toString() ?: if (anyPerson) return@mapNotNull null else title ?: return@mapNotNull null
                IncomingMessage(sender, text, msg.timestamp)
            }
            val chatTitle = style.conversationTitle?.toString()?.takeIf { group } ?: messages.lastOrNull()?.sender ?: title ?: return null
            return UnreadChat(sbn.packageName, chatTitle, group, messages)
        }

        // No MessagingStyle: title = who, text = what
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.takeIf { it.isNotBlank() } ?: return null
        title ?: return null
        return UnreadChat(sbn.packageName, title, group = false, messages = listOf(IncomingMessage(title, text, sbn.postTime)))
    }

    companion object {
        @Volatile
        var instance: MessageNotificationService? = null
            private set

        fun isAllowed(context: Context) = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        /** Allowed, but the system hasn't bound the service (yet or anymore): ask it to. */
        fun rebind(context: Context) =
            requestRebind(ComponentName(context, MessageNotificationService::class.java))
    }
}
