package com.dnd.blocker

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony

data class ThreadInfo(
    val id: Long,
    val addresses: List<String>,
    val snippet: String,
    val date: Long,
    val unread: Boolean,
    val hasAttachment: Boolean,
)

data class ChatMessage(
    val key: String,
    val incoming: Boolean,
    val address: String,
    val text: String,
    val images: List<Uri>,
    val date: Long,
    val status: String?,
)

/** Reads conversations from the system SMS/MMS provider. */
object Threads {
    private val CONVERSATIONS = Uri.parse("content://mms-sms/conversations?simple=true")
    private val CANONICAL = Uri.parse("content://mms-sms/canonical-addresses")
    private val MMS_PARTS = Uri.parse("content://mms/part")

    fun list(ctx: Context): List<ThreadInfo> = try {
        val cr = ctx.contentResolver
        val addresses = HashMap<Long, String>()
        cr.query(CANONICAL, arrayOf("_id", "address"), null, null, null)?.use { c ->
            while (c.moveToNext()) addresses[c.getLong(0)] = c.getString(1) ?: ""
        }
        buildList {
            cr.query(
                CONVERSATIONS,
                arrayOf("_id", "date", "message_count", "recipient_ids", "snippet", "read", "has_attachment"),
                null, null, "date DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(2) == 0) continue
                    val ids = (c.getString(3) ?: "").split(' ').mapNotNull { it.toLongOrNull() }
                    add(
                        ThreadInfo(
                            id = c.getLong(0),
                            addresses = ids.mapNotNull { addresses[it] },
                            snippet = c.getString(4) ?: "",
                            date = c.getLong(1),
                            unread = c.getInt(5) == 0,
                            hasAttachment = c.getInt(6) != 0,
                        )
                    )
                }
            }
        }
    } catch (e: Exception) {
        emptyList() // No READ_SMS permission yet
    }

    fun messages(ctx: Context, threadId: Long): List<ChatMessage> {
        if (threadId <= 0) return emptyList()
        val cr = ctx.contentResolver
        val out = mutableListOf<ChatMessage>()
        val args = arrayOf(threadId.toString())
        try {
            cr.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                "${Telephony.Sms.THREAD_ID}=?", args, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val type = c.getInt(4)
                    out += ChatMessage(
                        key = "s${c.getLong(0)}",
                        incoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX,
                        address = c.getString(1) ?: "",
                        text = c.getString(2) ?: "",
                        images = emptyList(),
                        date = c.getLong(3),
                        status = when (type) {
                            Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> "Sending…"
                            Telephony.Sms.MESSAGE_TYPE_FAILED -> "Failed"
                            else -> null
                        },
                    )
                }
            }
            cr.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX),
                "${Telephony.Mms.THREAD_ID}=?", args, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val box = c.getInt(2)
                    val texts = mutableListOf<String>()
                    val images = mutableListOf<Uri>()
                    cr.query(MMS_PARTS, arrayOf("_id", "ct", "text"), "mid=?", arrayOf(id.toString()), null)?.use { p ->
                        while (p.moveToNext()) {
                            val ct = p.getString(1) ?: ""
                            when {
                                ct == "text/plain" -> p.getString(2)?.let { texts += it }
                                ct.startsWith("image/") -> images += Uri.parse("content://mms/part/${p.getLong(0)}")
                            }
                        }
                    }
                    val incoming = box == Telephony.Mms.MESSAGE_BOX_INBOX
                    out += ChatMessage(
                        key = "m$id",
                        incoming = incoming,
                        address = if (incoming) mmsSender(ctx, id) else "",
                        text = texts.joinToString("\n"),
                        images = images,
                        date = c.getLong(1) * 1000,
                        status = when (box) {
                            Telephony.Mms.MESSAGE_BOX_OUTBOX -> "Sending…"
                            Telephony.Mms.MESSAGE_BOX_FAILED -> "Failed"
                            else -> null
                        },
                    )
                }
            }
        } catch (e: SecurityException) {
            // No READ_SMS permission yet
        }
        return out.sortedBy { it.date }
    }

    private fun mmsSender(ctx: Context, mmsId: Long): String =
        ctx.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"), arrayOf("address"), "type=137", null, null
        )?.use { if (it.moveToFirst()) it.getString(0) ?: "" else "" } ?: ""

    fun markRead(ctx: Context, threadId: Long) {
        if (threadId <= 0) return
        val read = ContentValues().apply { put("read", 1); put("seen", 1) }
        val where = "thread_id=? AND read=0"
        val args = arrayOf(threadId.toString())
        runCatching { ctx.contentResolver.update(Telephony.Sms.CONTENT_URI, read, where, args) }
        runCatching { ctx.contentResolver.update(Telephony.Mms.CONTENT_URI, read, where, args) }
    }

    fun threadIdFor(ctx: Context, addresses: List<String>): Long =
        runCatching { Telephony.Threads.getOrCreateThreadId(ctx, addresses.toSet()) }.getOrDefault(-1L)
}
