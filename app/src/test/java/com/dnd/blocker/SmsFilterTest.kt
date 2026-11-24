package com.dnd.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsFilterTest {

    private fun verdict(sender: String, body: String, contact: Boolean = false) =
        SmsFilter.classify(sender, body, contact, isWhitelisted = false, isBlacklisted = false).verdict

    @Test fun normalizesNumbers() {
        assertEquals("09121234567", SmsFilter.normalizeNumber("+98 912 123 4567"))
        assertEquals("09121234567", SmsFilter.normalizeNumber("00989121234567"))
        assertEquals("09121234567", SmsFilter.normalizeNumber("۰۹۱۲۱۲۳۴۵۶۷"))
    }

    @Test fun contactsAlwaysPass() {
        assertEquals(Verdict.INBOX, verdict("09121234567", "تخفیف ویژه لغو11", contact = true))
    }

    @Test fun persianOtpPasses() {
        assertEquals(Verdict.INBOX, verdict("98200012", "کد تایید شما: ۴۸۲۹۱۷"))
    }

    @Test fun englishOtpPasses() {
        assertEquals(Verdict.INBOX, verdict("+15551234", "Your verification code is 123456"))
    }

    @Test fun bankMessagePasses() {
        assertEquals(
            Verdict.INBOX,
            verdict("9850001", "برداشت از حساب 1234\nمبلغ 500,000 ریال\nمانده: 2,000,000")
        )
    }

    @Test fun optOutAdIsSpam() {
        assertEquals(Verdict.SPAM, verdict("50001234", "۱۰٪ تخفیف دوره آنلاین\nلغو۱۱"))
    }

    @Test fun discountCodeIsNotOtp() {
        assertEquals(Verdict.SPAM, verdict("30001234", "کد تخفیف 12345 برای جشنواره"))
    }

    @Test fun unknownGoesToQuarantine() {
        assertEquals(Verdict.QUARANTINE, verdict("09351112233", "سلام، کی میای؟"))
    }

    @Test fun blocksAdCallPrefixes() {
        assertEquals("ad prefix 0941", CallFilter.shouldBlock("+989411234567", false, false, false))
        assertNull(CallFilter.shouldBlock("+989121234567", false, false, false))
        assertNull(CallFilter.shouldBlock("+989401234567", true, false, false))
        assertEquals("hidden number", CallFilter.shouldBlock(null, false, true, false))
    }
}
