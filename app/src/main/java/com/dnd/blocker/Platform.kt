package com.dnd.blocker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import java.util.Locale
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

fun Context.hasPermission(p: String) =
    ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

object Contacts {
    fun isContact(ctx: Context, number: String?): Boolean {
        if (number.isNullOrBlank() || !ctx.hasPermission(Manifest.permission.READ_CONTACTS)) return false
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
                ?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun name(ctx: Context, number: String?): String? {
        if (number.isNullOrBlank() || !ctx.hasPermission(Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun blockHidden(ctx: Context) = p(ctx).getBoolean("block_hidden", false)
    fun setBlockHidden(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("block_hidden", v).apply()
    fun blockAllUnknown(ctx: Context) = p(ctx).getBoolean("block_unknown", false)
    fun setBlockAllUnknown(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("block_unknown", v).apply()

    /** "system", "fa" or "en". */
    fun language(ctx: Context): String = p(ctx).getString("language", "system") ?: "system"
    fun setLanguage(ctx: Context, v: String) = p(ctx).edit().putString("language", v).commit()

    /** "system", "light" or "dark". */
    fun theme(ctx: Context): String = p(ctx).getString("theme", "system") ?: "system"
    fun setTheme(ctx: Context, v: String) = p(ctx).edit().putString("theme", v).apply()

    fun onboarded(ctx: Context) = p(ctx).getBoolean("onboarded", false)
    fun setOnboarded(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("onboarded", v).apply()
}

/**
 * Archived conversations, stored as thread id -> date of the newest message when archived.
 * A newer message brings the conversation back to the main list.
 */
object Archive {
    private fun p(ctx: Context) = ctx.getSharedPreferences("archive", Context.MODE_PRIVATE)
    fun all(ctx: Context): Map<Long, Long> =
        p(ctx).all.mapNotNull { (k, v) -> k.toLongOrNull()?.let { id -> (v as? Long)?.let { id to it } } }.toMap()
    fun isArchived(map: Map<Long, Long>, t: ThreadInfo) = map[t.id]?.let { t.date <= it } == true
    fun add(ctx: Context, t: ThreadInfo) = p(ctx).edit().putLong(t.id.toString(), t.date).apply()
    fun remove(ctx: Context, id: Long) = p(ctx).edit().remove(id.toString()).apply()
}

/** Applies the in-app language choice (and its text direction) to a context. */
object Lang {
    fun wrap(base: Context): Context {
        val code = Prefs.language(base)
        if (code == "system") return base
        val locale = Locale.forLanguageTag(code)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}

object Roles {
    fun held(ctx: Context, role: String): Boolean =
        ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(role) == true

    fun requestIntent(ctx: Context, role: String): Intent? {
        val rm = ctx.getSystemService(RoleManager::class.java) ?: return null
        return if (rm.isRoleAvailable(role) && !rm.isRoleHeld(role)) rm.createRequestRoleIntent(role) else null
    }
}

object SmsStore {
    fun insertInbox(ctx: Context, address: String, body: String, date: Long) {
        ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date); put(Telephony.Sms.READ, 0); put(Telephony.Sms.SEEN, 0)
        })
    }

    fun send(ctx: Context, address: String, body: String) {
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sms.divideMessage(body)
        sms.sendMultipartTextMessage(address, null, parts, null, null)
        // The default SMS app is responsible for recording sent messages.
        if (Roles.held(ctx, RoleManager.ROLE_SMS)) {
            ctx.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.READ, 1)
            })
        }
    }
}

object Notifier {
    private const val CH_MSG = "messages"
    private const val CH_FILTER = "filtered"
    private const val FILTER_ID = 1

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val l = Lang.wrap(ctx)
        nm.createNotificationChannel(NotificationChannel(CH_MSG, l.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_FILTER, l.getString(R.string.channel_filtered), NotificationManager.IMPORTANCE_MIN))
    }

    private fun openApp(ctx: Context, tab: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx, tab,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun canNotify(ctx: Context) =
        (Build.VERSION.SDK_INT < 33 || ctx.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    @Suppress("MissingPermission")
    fun message(ctx: Context, from: String, body: String) {
        if (!canNotify(ctx)) return
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_MSG)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(from).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(ctx, 0)).setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }

    @Suppress("MissingPermission")
    fun filtered(ctx: Context, count: Int) {
        if (!canNotify(ctx)) return
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_FILTER)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(Lang.wrap(ctx).getString(R.string.notif_filtered_title, count))
            .setContentText(Lang.wrap(ctx).getString(R.string.notif_filtered_text))
            .setContentIntent(openApp(ctx, 1)).setSilent(true).setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(FILTER_ID, n)
    }
}

/** Holds the on-device spam model and saves what the user teaches it. */
object Learner {
    @Volatile private var model: SpamModel? = null

    fun model(ctx: Context): SpamModel = model ?: synchronized(this) {
        model ?: SpamModel.seeded().also { Db.get(ctx).loadModelInto(it); model = it }
    }

    fun learn(ctx: Context, text: String, isSpam: Boolean) {
        val body = text.removePrefix("[MMS] ")
        val tokens = model(ctx).train(body, isSpam)
        Db.get(ctx).saveTraining(tokens, isSpam)
    }

    fun reset(ctx: Context) {
        synchronized(this) {
            Db.get(ctx).resetModel()
            model = null
        }
    }
}

object MessageRouter {
    fun route(ctx: Context, address: String, body: String, date: Long) {
        val db = Db.get(ctx)
        val number = SmsFilter.normalizeNumber(address)
        val result = SmsFilter.classify(
            address, body,
            isContact = Contacts.isContact(ctx, address),
            isWhitelisted = db.isWhitelisted(number),
            isBlacklisted = db.isBlacklisted(number),
            model = Learner.model(ctx),
        )
        if (result.verdict == Verdict.INBOX) {
            SmsStore.insertInbox(ctx, address, body, date)
            Notifier.message(ctx, address, body)
        } else {
            db.addFiltered(address, body, date, result)
            Notifier.filtered(ctx, db.filteredCount())
        }
    }

    fun routeMms(ctx: Context, m: MmsMessage) {
        val db = Db.get(ctx)
        val from = m.from ?: ""
        val text = listOfNotNull(m.subject, m.text()).filter { it.isNotBlank() }.joinToString("\n")
        val result = SmsFilter.classify(
            from, text,
            isContact = Contacts.isContact(ctx, from),
            isWhitelisted = db.isWhitelisted(SmsFilter.normalizeNumber(from)),
            isBlacklisted = db.isBlacklisted(SmsFilter.normalizeNumber(from)),
            model = Learner.model(ctx),
        )
        if (result.verdict == Verdict.INBOX) {
            MmsStore.persistIncoming(ctx, m)
            Notifier.message(ctx, from, text.ifBlank { "[MMS]" })
        } else {
            val attachments = m.parts.count { it.isImage }
            val note = if (attachments > 0) "\n[+$attachments image(s) not kept]" else ""
            db.addFiltered(from, "[MMS] $text$note", System.currentTimeMillis(), result)
            Notifier.filtered(ctx, db.filteredCount())
        }
    }
}
