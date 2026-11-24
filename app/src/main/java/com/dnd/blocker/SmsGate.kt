package com.dnd.blocker

import android.app.Notification
import android.app.role.RoleManager
import android.content.Context
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Gatekeeper mode: the user keeps their own SMS app. We read only the notification it shows for a new
 * message and silently remove it when the message is clearly an ad. The message itself stays in their
 * SMS app; Android does not let other apps delete it.
 *
 * Only a SPAM verdict is acted on. Anything uncertain (an unknown sender without ad signals) is left alone,
 * so a real message is never silenced.
 */
object SmsGate {
    /** What we saw in an SMS app's notification. */
    data class Incoming(val sender: String, val number: String?, val body: String)

    private val recent = ArrayDeque<String>()

    /** Gatekeeper only runs while we are NOT the default SMS app (in full mode our own receiver filters). */
    fun applies(ctx: Context, pkg: String): Boolean =
        pkg != ctx.packageName &&
            !Roles.held(ctx, RoleManager.ROLE_SMS) &&
            pkg == runCatching { Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull()

    fun extract(n: Notification): Incoming? {
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return null
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null) {
            if (style.isGroupConversation) return null // group chats are never touched
            val last = style.messages.lastOrNull() ?: return null
            val person = last.person
            val sender = person?.name?.toString() ?: style.conversationTitle?.toString() ?: return null
            val number = person?.uri?.takeIf { it.startsWith("tel:") }?.removePrefix("tel:")
            return Incoming(sender, number, last.text?.toString() ?: return null)
        }
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: return null
        return Incoming(title, null, text)
    }

    /** Returns the filter result when the notification should be removed, or null to leave it. */
    fun judge(ctx: Context, m: Incoming): FilterResult? {
        if (m.body.isBlank()) return null
        val db = Db.get(ctx)
        val address = m.number ?: m.sender
        val number = SmsFilter.normalizeNumber(address)
        val isContact = Contacts.isContact(ctx, address) ||
            (m.sender.any { it.isLetter() } && Contacts.hasName(ctx, m.sender))
        val result = SmsFilter.classify(
            address, m.body,
            isContact = isContact,
            isWhitelisted = db.isWhitelisted(number),
            isBlacklisted = db.isBlacklisted(number),
            model = Learner.model(ctx),
        )
        return result.takeIf { it.verdict == Verdict.SPAM }
    }

    fun onPosted(ctx: Context, sbn: StatusBarNotification, cancel: (String) -> Unit) {
        val m = extract(sbn.notification ?: return) ?: return
        val result = judge(ctx, m) ?: return
        cancel(sbn.key)
        // SMS apps repost the same notification when it updates; log each ad once.
        val id = "${m.sender}|${m.body.hashCode()}"
        synchronized(recent) {
            if (id in recent) return
            recent.addLast(id)
            if (recent.size > 100) recent.removeFirst()
        }
        Db.get(ctx).addFiltered(m.number ?: m.sender, m.body, sbn.postTime, result, source = Db.SOURCE_NOTIFICATION)
    }
}
