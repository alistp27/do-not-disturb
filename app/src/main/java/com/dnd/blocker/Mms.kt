package com.dnd.blocker

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

private const val TAG = "DndMms"
private const val ADDR_FROM = 137
private const val ADDR_TO = 151

object MmsStore {
    const val ACTION_DOWNLOADED = "com.dnd.blocker.MMS_DOWNLOADED"
    const val ACTION_SENT = "com.dnd.blocker.MMS_SENT"
    private const val EXTRA_FILE = "file"
    private const val EXTRA_MSG = "msg"
    private const val EXTRA_TID = "tid"
    private const val MAX_IMAGE_BYTES = 280_000

    private fun smsManager(ctx: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()

    private fun pduDir(ctx: Context) = File(ctx.cacheDir, "mms").apply { mkdirs() }

    private fun shareable(ctx: Context, file: File): Uri =
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.mmsfiles", file)

    private fun resultIntent(ctx: Context, action: String, file: File, extra: Intent.() -> Unit = {}): PendingIntent {
        val i = Intent(ctx, MmsResultReceiver::class.java).setAction(action)
            .putExtra(EXTRA_FILE, file.path).apply(extra)
        // Mutable: the MMS service adds result extras when it fires this intent.
        return PendingIntent.getBroadcast(
            ctx, file.name.hashCode(), i,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---------- Receiving ----------

    /** Called from the WAP push receiver with an M-Notification.ind. */
    fun onNotification(ctx: Context, pdu: ByteArray) {
        val n = runCatching { MmsPdu.parse(pdu) }.getOrElse { Log.w(TAG, "Bad notification", it); return }
        if (n.type != MmsType.NOTIFICATION_IND) return
        val location = n.contentLocation ?: return
        val from = n.from ?: ""
        val db = Db.get(ctx)
        if (db.isBlacklisted(SmsFilter.normalizeNumber(from))) {
            db.addFiltered(from, "[MMS not downloaded]", System.currentTimeMillis(), FilterResult(Verdict.SPAM, "blacklist"))
            Notifier.filtered(ctx, db.filteredCount())
            return
        }
        val file = File(pduDir(ctx), "dl-${System.nanoTime()}.pdu").apply { createNewFile() }
        val pi = resultIntent(ctx, ACTION_DOWNLOADED, file) { putExtra(EXTRA_TID, n.transactionId) }
        smsManager(ctx).downloadMultimediaMessage(ctx, location, shareable(ctx, file), null, pi)
    }

    fun onDownloaded(ctx: Context, intent: Intent, ok: Boolean) {
        val file = File(intent.getStringExtra(EXTRA_FILE) ?: return)
        try {
            if (!ok || file.length() == 0L) { Log.w(TAG, "MMS download failed"); return }
            val m = MmsPdu.parse(file.readBytes())
            if (m.type != MmsType.RETRIEVE_CONF) return
            MessageRouter.routeMms(ctx, m)
            intent.getStringExtra(EXTRA_TID)?.let { sendNotifyResp(ctx, it) }
        } finally {
            file.delete()
        }
    }

    /** Tells the carrier the message was retrieved so it stops re-sending the notification. */
    private fun sendNotifyResp(ctx: Context, tid: String) {
        runCatching {
            val file = File(pduDir(ctx), "ack-${System.nanoTime()}.pdu").apply { writeBytes(MmsPdu.buildNotifyResp(tid)) }
            smsManager(ctx).sendMultimediaMessage(ctx, shareable(ctx, file), null, null, resultIntent(ctx, ACTION_SENT, file))
        }.onFailure { Log.w(TAG, "NotifyResp failed", it) }
    }

    fun persistIncoming(ctx: Context, m: MmsMessage) {
        val from = m.from ?: return
        val now = System.currentTimeMillis() / 1000
        val values = ContentValues().apply {
            put(Telephony.Mms.THREAD_ID, Telephony.Threads.getOrCreateThreadId(ctx, from))
            put(Telephony.Mms.DATE, now)
            put(Telephony.Mms.DATE_SENT, if (m.date > 0) m.date else now)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.READ, 0)
            put(Telephony.Mms.SEEN, 0)
            put(Telephony.Mms.MESSAGE_TYPE, MmsType.RETRIEVE_CONF)
            put(Telephony.Mms.MMS_VERSION, 0x12)
            put(Telephony.Mms.CONTENT_TYPE, m.contentType ?: "application/vnd.wap.multipart.related")
            put(Telephony.Mms.TEXT_ONLY, if (m.parts.none { it.isImage }) 1 else 0)
            m.transactionId?.let { put(Telephony.Mms.TRANSACTION_ID, it) }
            m.subject?.let { put(Telephony.Mms.SUBJECT, it); put(Telephony.Mms.SUBJECT_CHARSET, 106) }
        }
        val id = ctx.contentResolver.insert(Telephony.Mms.CONTENT_URI, values)?.lastPathSegment ?: return
        writeParts(ctx, id, m.parts)
        writeAddr(ctx, id, from, ADDR_FROM)
    }

    // ---------- Sending ----------

    fun send(ctx: Context, recipients: List<String>, text: String?, image: Uri?) {
        val parts = mutableListOf<MmsPart>()
        if (!text.isNullOrBlank()) parts += MmsPart("text/plain", 106, "text.txt", text.toByteArray())
        if (image != null) parts += MmsPart("image/jpeg", 106, "image.jpg", jpegForMms(ctx, image))
        require(parts.isNotEmpty()) { "Nothing to send" }

        val tid = "T" + System.currentTimeMillis().toString(16)
        val msgUri = runCatching { persistOutgoing(ctx, recipients, parts, tid) }.getOrNull()
        val file = File(pduDir(ctx), "send-$tid.pdu").apply { writeBytes(MmsPdu.buildSendReq(recipients, parts, tid)) }
        val pi = resultIntent(ctx, ACTION_SENT, file) { msgUri?.let { putExtra(EXTRA_MSG, it.toString()) } }
        smsManager(ctx).sendMultimediaMessage(ctx, shareable(ctx, file), null, null, pi)
    }

    fun onSent(ctx: Context, intent: Intent, ok: Boolean) {
        intent.getStringExtra(EXTRA_FILE)?.let { File(it).delete() }
        val msg = intent.getStringExtra(EXTRA_MSG) ?: return
        val box = if (ok) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_FAILED
        runCatching {
            ctx.contentResolver.update(Uri.parse(msg), ContentValues().apply { put(Telephony.Mms.MESSAGE_BOX, box) }, null, null)
        }
    }

    private fun persistOutgoing(ctx: Context, recipients: List<String>, parts: List<MmsPart>, tid: String): Uri? {
        val now = System.currentTimeMillis() / 1000
        val values = ContentValues().apply {
            put(Telephony.Mms.THREAD_ID, Telephony.Threads.getOrCreateThreadId(ctx, recipients.toSet()))
            put(Telephony.Mms.DATE, now)
            put(Telephony.Mms.DATE_SENT, now)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX)
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SEEN, 1)
            put(Telephony.Mms.MESSAGE_TYPE, MmsType.SEND_REQ)
            put(Telephony.Mms.MMS_VERSION, 0x12)
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.mixed")
            put(Telephony.Mms.TEXT_ONLY, if (parts.none { it.isImage }) 1 else 0)
            put(Telephony.Mms.TRANSACTION_ID, tid)
        }
        val uri = ctx.contentResolver.insert(Telephony.Mms.CONTENT_URI, values) ?: return null
        val id = uri.lastPathSegment ?: return uri
        writeParts(ctx, id, parts)
        writeAddr(ctx, id, "insert-address-token", ADDR_FROM)
        recipients.forEach { writeAddr(ctx, id, it, ADDR_TO) }
        return uri
    }

    // ---------- Provider helpers ----------

    private fun writeParts(ctx: Context, mmsId: String, parts: List<MmsPart>) {
        val partsUri = Uri.parse("content://mms/$mmsId/part")
        parts.forEachIndexed { i, p ->
            val name = p.name ?: "part$i"
            val v = ContentValues().apply {
                put(Telephony.Mms.Part.MSG_ID, mmsId)
                put(Telephony.Mms.Part.CONTENT_TYPE, p.mime)
                put(Telephony.Mms.Part.CONTENT_LOCATION, name)
                put(Telephony.Mms.Part.CONTENT_ID, "<$name>")
                if (p.mime.startsWith("text/") || p.mime == "application/smil") {
                    put(Telephony.Mms.Part.CHARSET, 106)
                    put(Telephony.Mms.Part.TEXT, p.text())
                }
            }
            val partUri = ctx.contentResolver.insert(partsUri, v) ?: return@forEachIndexed
            if (!p.mime.startsWith("text/") && p.mime != "application/smil") {
                ctx.contentResolver.openOutputStream(partUri)?.use { it.write(p.data) }
            }
        }
    }

    private fun writeAddr(ctx: Context, mmsId: String, address: String, type: Int) {
        ctx.contentResolver.insert(Uri.parse("content://mms/$mmsId/addr"), ContentValues().apply {
            put(Telephony.Mms.Addr.MSG_ID, mmsId)
            put(Telephony.Mms.Addr.ADDRESS, address)
            put(Telephony.Mms.Addr.TYPE, type)
            put(Telephony.Mms.Addr.CHARSET, 106)
        })
    }

    // ---------- Images ----------

    fun decodeScaled(ctx: Context, uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        return ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    /** Carriers usually reject MMS over ~300 KB, so shrink until it fits. */
    private fun jpegForMms(ctx: Context, uri: Uri): ByteArray {
        val src = decodeScaled(ctx, uri, 1280) ?: error("Cannot read image")
        for (dim in intArrayOf(1280, 960, 720, 480)) {
            val scale = dim.toFloat() / max(src.width, src.height)
            val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
            for (q in intArrayOf(85, 70, 55, 40)) {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= MAX_IMAGE_BYTES) return out.toByteArray()
            }
        }
        error("Image is too large to send as MMS")
    }
}

/** Receives the M-Notification.ind for incoming MMS. Required for the default SMS role. */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = intent.getByteArrayExtra("data") ?: return
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try { MmsStore.onNotification(app, data) }
            catch (e: Exception) { Log.w(TAG, "MMS notification failed", e) }
            finally { pending.finish() }
        }.start()
    }
}

/** Gets the result of MMS downloads and sends from the system MMS service. */
class MmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ok = resultCode == Activity.RESULT_OK
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    MmsStore.ACTION_DOWNLOADED -> MmsStore.onDownloaded(app, intent, ok)
                    MmsStore.ACTION_SENT -> MmsStore.onSent(app, intent, ok)
                }
            } catch (e: Exception) {
                Log.w(TAG, "MMS result handling failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
