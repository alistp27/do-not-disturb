package com.dnd.blocker

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Minimal MMS PDU (OMA MMS Encapsulation / WSP) encoder and decoder.
 * Android has no public MMS parser, so the default SMS app has to bring its own.
 * Pure Kotlin, no Android dependencies, so it is unit tested.
 */
object MmsType {
    const val SEND_REQ = 0x80
    const val NOTIFICATION_IND = 0x82
    const val NOTIFYRESP_IND = 0x83
    const val RETRIEVE_CONF = 0x84
}

class MmsPart(val mime: String, val charset: Long, val name: String?, val data: ByteArray) {
    val isText get() = mime.startsWith("text/plain")
    val isImage get() = mime.startsWith("image/")
    fun text(): String = String(data, MmsPdu.charsetFor(charset))
}

class MmsMessage {
    var type = 0
    var transactionId: String? = null
    var contentLocation: String? = null
    var from: String? = null
    val to = mutableListOf<String>()
    var subject: String? = null
    var date = 0L
    var contentType: String? = null
    var parts: List<MmsPart> = emptyList()

    fun text(): String = parts.filter { it.isText }.joinToString("\n") { it.text() }.trim()
}

internal class PduReader(private val d: ByteArray) {
    var pos = 0
    fun hasMore() = pos < d.size
    fun peek() = d[pos].toInt() and 0xFF
    fun u8() = d[pos++].toInt() and 0xFF
    fun bytes(n: Int): ByteArray = d.copyOfRange(pos, pos + n).also { pos += n }

    fun uintvar(): Int {
        var v = 0
        while (true) {
            val b = u8()
            v = (v shl 7) or (b and 0x7F)
            if (b and 0x80 == 0) return v
        }
    }

    fun textString(): String {
        if (hasMore() && (peek() == 0x7F || peek() == 0x22)) pos++
        val start = pos
        while (pos < d.size && d[pos].toInt() != 0) pos++
        val s = String(d, start, pos - start, Charsets.UTF_8)
        if (pos < d.size) pos++ // null terminator
        return s
    }

    fun valueLength(): Int {
        val b = u8()
        return when {
            b < 31 -> b
            b == 31 -> uintvar()
            else -> throw IllegalArgumentException("Bad value-length $b")
        }
    }

    fun longInt(): Long {
        val n = u8()
        var v = 0L
        repeat(n) { v = (v shl 8) or u8().toLong() }
        return v
    }

    fun integer(): Long = if (peek() >= 0x80) (u8() and 0x7F).toLong() else longInt()

    /** Skips any WSP-encoded value using the generic first-octet rules. */
    fun skipValue() {
        val b = peek()
        when {
            b < 31 -> pos += 1 + b
            b == 31 -> { pos++; val n = uintvar(); pos += n }
            b < 128 -> textString()
            else -> pos++
        }
    }

    fun encodedString(): String {
        if (peek() > 31) return textString()
        val len = valueLength()
        val stop = pos + len
        val cs = integer()
        var start = pos
        if (start < stop && (d[start].toInt() and 0xFF) == 0x7F) start++
        var end = start
        while (end < stop && d[end].toInt() != 0) end++
        pos = stop
        return String(d, start, end - start, MmsPdu.charsetFor(cs))
    }
}

internal class PduWriter {
    private val o = ByteArrayOutputStream()
    fun u8(b: Int) = o.write(b)
    fun bytes(b: ByteArray) = o.write(b)
    fun text(s: String) { o.write(s.toByteArray(Charsets.UTF_8)); o.write(0) }
    fun uintvar(v: Int) {
        val out = ArrayList<Int>()
        var x = v
        out.add(x and 0x7F)
        x = x ushr 7
        while (x > 0) { out.add((x and 0x7F) or 0x80); x = x ushr 7 }
        out.asReversed().forEach { o.write(it) }
    }
    fun toByteArray(): ByteArray = o.toByteArray()
}

object MmsPdu {
    private val wellKnownMime = mapOf(
        0x00 to "*/*", 0x01 to "text/*", 0x02 to "text/html", 0x03 to "text/plain",
        0x07 to "text/x-vCalendar", 0x08 to "text/x-vCard",
        0x1C to "image/*", 0x1D to "image/gif", 0x1E to "image/jpeg", 0x1F to "image/tiff",
        0x20 to "image/png", 0x21 to "image/vnd.wap.wbmp",
        0x22 to "application/vnd.wap.multipart.*", 0x23 to "application/vnd.wap.multipart.mixed",
        0x26 to "application/vnd.wap.multipart.alternative", 0x33 to "application/vnd.wap.multipart.related",
    )

    fun charsetFor(mib: Long): Charset = when (mib) {
        3L -> Charsets.US_ASCII
        4L -> Charsets.ISO_8859_1
        1000L -> Charsets.UTF_16BE // ISO-10646-UCS-2
        1013L -> Charsets.UTF_16BE
        1014L -> Charsets.UTF_16LE
        1015L -> Charsets.UTF_16
        else -> Charsets.UTF_8
    }

    /** "+989121234567/TYPE=PLMN" -> "+989121234567" */
    fun cleanAddress(a: String): String = a.substringBefore("/TYPE=").trim()

    fun parse(data: ByteArray): MmsMessage {
        val r = PduReader(data)
        val m = MmsMessage()
        while (r.hasMore()) {
            val f = r.u8()
            if (f < 0x80) { // application header: token-text then value-text
                r.pos--; r.textString(); r.textString(); continue
            }
            when (f and 0x7F) {
                0x0C -> m.type = r.u8()
                0x18 -> m.transactionId = r.textString()
                0x03 -> m.contentLocation = r.textString()
                0x05 -> m.date = r.longInt()
                0x16 -> m.subject = r.encodedString()
                0x17 -> m.to += cleanAddress(r.encodedString())
                0x09 -> {
                    val len = r.valueLength()
                    val stop = r.pos + len
                    if (r.u8() == 0x80) m.from = cleanAddress(r.encodedString())
                    r.pos = stop
                }
                0x04 -> { // Content-Type is always the last header; the body follows.
                    m.contentType = readContentType(r).first
                    m.parts = parseMultipart(r)
                    return m
                }
                else -> r.skipValue()
            }
        }
        return m
    }

    private fun readContentType(r: PduReader): Pair<String, Long> {
        val b = r.peek()
        if (b >= 0x80) { r.pos++; return (wellKnownMime[b and 0x7F] ?: "application/octet-stream") to 106L }
        if (b > 31) return r.textString() to 106L
        val len = r.valueLength()
        val stop = r.pos + len
        val mime = if (r.peek() >= 0x80) wellKnownMime[r.u8() and 0x7F] ?: "application/octet-stream" else r.textString()
        var charset = 106L
        while (r.pos < stop) {
            val p = r.peek()
            when {
                p == 0x81 -> { r.pos++; charset = if (r.peek() in 32..127) 106L.also { r.textString() } else r.integer() }
                p >= 0x80 -> { r.pos++; r.skipValue() }
                else -> { r.textString(); r.skipValue() }
            }
        }
        r.pos = stop
        if (charset == 0L) charset = 106L
        return mime to charset
    }

    private fun parseMultipart(r: PduReader): List<MmsPart> {
        if (!r.hasMore()) return emptyList()
        val parts = mutableListOf<MmsPart>()
        val n = r.uintvar()
        repeat(n) {
            val headersLen = r.uintvar()
            val dataLen = r.uintvar()
            val headersEnd = r.pos + headersLen
            val (mime, cs) = readContentType(r)
            var name: String? = null
            while (r.pos < headersEnd) {
                val h = r.peek()
                when {
                    h == 0x8E -> { r.pos++; name = r.textString() } // Content-Location
                    h >= 0x80 -> { r.pos++; r.skipValue() }
                    else -> { r.textString(); r.textString() }
                }
            }
            r.pos = headersEnd
            parts += MmsPart(mime, cs, name, r.bytes(dataLen))
        }
        return parts
    }

    fun buildSendReq(to: List<String>, parts: List<MmsPart>, transactionId: String): ByteArray {
        val w = PduWriter()
        w.u8(0x8C); w.u8(MmsType.SEND_REQ)
        w.u8(0x98); w.text(transactionId)
        w.u8(0x8D); w.u8(0x92) // MMS version 1.2
        w.u8(0x89); w.u8(0x01); w.u8(0x81) // From: insert-address-token
        for (t in to) { w.u8(0x97); w.text("${t.filter { it.isDigit() || it == '+' }}/TYPE=PLMN") }
        w.u8(0x84); w.u8(0xA3) // application/vnd.wap.multipart.mixed
        w.uintvar(parts.size)
        parts.forEachIndexed { i, p ->
            val h = PduWriter()
            if (p.isText) {
                h.bytes(byteArrayOf(0x03, 0x83.toByte(), 0x81.toByte(), 0xEA.toByte())) // text/plain; charset=utf-8
            } else {
                h.text(p.mime)
            }
            val name = p.name ?: "part$i"
            h.u8(0x8E); h.text(name)              // Content-Location
            h.u8(0xC0); h.u8(0x22); h.text("<$name>") // Content-ID
            val hb = h.toByteArray()
            w.uintvar(hb.size); w.uintvar(p.data.size); w.bytes(hb); w.bytes(p.data)
        }
        return w.toByteArray()
    }

    fun buildNotifyResp(transactionId: String): ByteArray {
        val w = PduWriter()
        w.u8(0x8C); w.u8(MmsType.NOTIFYRESP_IND)
        w.u8(0x98); w.text(transactionId)
        w.u8(0x8D); w.u8(0x92)
        w.u8(0x95); w.u8(0x81) // X-Mms-Status: retrieved
        return w.toByteArray()
    }
}
