package com.dnd.blocker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MmsPduTest {

    @Test fun parsesNotificationInd() {
        val loc = "http://mms.example/abc"
        val pdu = byteArrayOf(0x8C.toByte(), 0x82.toByte()) +
            byteArrayOf(0x98.toByte()) + "TID1".toByteArray() + Z +
            byteArrayOf(0x8D.toByte(), 0x92.toByte()) +
            // From: value-length, address-present-token, text
            byteArrayOf(0x89.toByte(), 16, 0x80.toByte()) + "+989121234567".toByteArray() + Z + byteArrayOf(0) +
            byteArrayOf(0x88.toByte(), 0x05, 0x81.toByte(), 0x03, 0x01, 0x51, 0x80.toByte()) + // expiry (skipped)
            byteArrayOf(0x83.toByte()) + loc.toByteArray() + Z

        val m = MmsPdu.parse(pdu)
        assertEquals(MmsType.NOTIFICATION_IND, m.type)
        assertEquals("TID1", m.transactionId)
        assertEquals("+989121234567", m.from)
        assertEquals(loc, m.contentLocation)
    }

    @Test fun sendReqRoundTrip() {
        val img = byteArrayOf(1, 2, 3, 4, 5)
        val pdu = MmsPdu.buildSendReq(
            listOf("+98 912 123 4567", "09351112233"),
            listOf(
                MmsPart("text/plain", 106, "text.txt", "سلام دنیا".toByteArray()),
                MmsPart("image/jpeg", 106, "image.jpg", img),
            ),
            "T123",
        )
        val m = MmsPdu.parse(pdu)
        assertEquals(MmsType.SEND_REQ, m.type)
        assertEquals("T123", m.transactionId)
        assertEquals(listOf("+989121234567", "09351112233"), m.to)
        assertEquals(2, m.parts.size)
        assertEquals("سلام دنیا", m.text())
        assertEquals("image/jpeg", m.parts[1].mime)
        assertEquals("image.jpg", m.parts[1].name)
        assertArrayEquals(img, m.parts[1].data)
    }

    @Test fun largePartUsesMultiByteLength() {
        val big = ByteArray(300_000) { (it % 251).toByte() }
        val pdu = MmsPdu.buildSendReq(listOf("0912"), listOf(MmsPart("image/png", 106, "a.png", big)), "T")
        assertArrayEquals(big, MmsPdu.parse(pdu).parts[0].data)
    }

    @Test fun notifyRespEncoding() {
        val expected = byteArrayOf(0x8C.toByte(), 0x83.toByte(), 0x98.toByte()) + "X".toByteArray() + Z +
            byteArrayOf(0x8D.toByte(), 0x92.toByte(), 0x95.toByte(), 0x81.toByte())
        assertArrayEquals(expected, MmsPdu.buildNotifyResp("X"))
    }

    @Test fun stripsAddressType() {
        assertEquals("+989121234567", MmsPdu.cleanAddress("+989121234567/TYPE=PLMN"))
    }
}

private val Z = byteArrayOf(0)
