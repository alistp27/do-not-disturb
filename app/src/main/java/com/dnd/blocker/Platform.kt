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
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

data class Sms(val id: Long, val address: String, val body: String, val date: Long, val type: Int)

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
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun blockHidden(ctx: Context) = p(ctx).getBoolean("block_hidden", false)
    fun setBlockHidden(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("block_hidden", v).apply()
    fun blockAllUnknown(ctx: Context) = p(ctx).getBoolean("block_unknown", false)
    fun setBlockAllUnknown(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("block_unknown", v).apply()
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
    fun recent(ctx: Context, limit: Int = 300): List<Sms> {
        val out = mutableListOf<Sms>()
        try {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out += Sms(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3), c.getInt(4))
                }
            }
        } catch (e: SecurityException) {
            // No READ_SMS permission yet.
        }
        return out
    }

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
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Messages", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_FILTER, "Filtered messages", NotificationManager.IMPORTANCE_MIN))
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
            .setSmallIcon(android.R.drawable.sym_action_chat)
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
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentTitle("$count messages filtered")
            .setContentText("Tap to review — OTP or bank messages may be here")
            .setContentIntent(openApp(ctx, 1)).setSilent(true).setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(FILTER_ID, n)
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
        )
        if (result.verdict == Verdict.INBOX) {
            SmsStore.insertInbox(ctx, address, body, date)
            Notifier.message(ctx, address, body)
        } else {
            db.addFiltered(address, body, date, result)
            Notifier.filtered(ctx, db.filteredCount())
        }
    }
}
