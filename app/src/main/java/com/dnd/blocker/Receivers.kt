package com.dnd.blocker

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import android.telecom.Call
import android.telecom.CallScreeningService

/** Receives every incoming SMS when this app is the default SMS app. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) return
        val address = parts[0].displayOriginatingAddress ?: ""
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        val date = System.currentTimeMillis()
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                MessageRouter.route(app, address, body, date)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** Required for the default SMS role: "reply with message" when rejecting a call. */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val to = intent?.data?.schemeSpecificPart?.substringBefore('?')
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!to.isNullOrBlank() && !text.isNullOrBlank()) {
            runCatching { SmsStore.send(this, to, text) }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/** Rejects ad calls (+98940/941/942) before the phone rings. */
class SpamCallScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(details, CallResponse.Builder().build())
            return
        }
        val raw = details.handle?.schemeSpecificPart
        val reason = CallFilter.shouldBlock(
            raw,
            isContact = Contacts.isContact(this, raw),
            blockHidden = Prefs.blockHidden(this),
            blockAllUnknown = Prefs.blockAllUnknown(this),
        )
        val response = if (reason != null) {
            Db.get(this).addBlockedCall(raw ?: "hidden", reason)
            CallResponse.Builder()
                .setDisallowCall(true).setRejectCall(true)
                .setSkipCallLog(false).setSkipNotification(true)
                .build()
        } else {
            CallResponse.Builder().build()
        }
        respondToCall(details, response)
    }
}
