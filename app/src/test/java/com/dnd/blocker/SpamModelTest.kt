package com.dnd.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpamModelTest {

    @Test fun tokenizerNormalizes() {
        val t = SpamModel.tokenize("تخفیف۲۰٪ در https://x.ir/a نیم‌فاصله كيك")
        assertTrue("تخفیف" in t)
        assertTrue("<num>" in t)
        assertTrue("%" in t)
        assertTrue("<url>" in t)
        assertTrue("نیم" in t && "فاصله" in t)
        assertTrue("کیک" in t) // Arabic kaf/yeh unified
    }

    @Test fun seededModelRecognisesObviousAd() {
        val p = SpamModel.seeded().spamProbability("تخفیف ویژه جشنواره ثبت نام دوره رایگان")!!
        assertTrue("p=$p", p > 0.9)
    }

    @Test fun seededModelRecognisesPersonalMessage() {
        val p = SpamModel.seeded().spamProbability("سلام فردا ساعت ده بیا خونه")!!
        assertTrue("p=$p", p < 0.1)
    }

    @Test fun learnsNewSpamFromUser() {
        val m = SpamModel.seeded()
        val ad = "بیمه عمر با سود تضمینی مشاوره بیمه"
        val before = m.spamProbability(ad)
        repeat(3) { m.train(ad, isSpam = true) }
        val after = m.spamProbability(ad)!!
        assertTrue("before=$before after=$after", after > 0.9)
        assertEquals(3, m.userSpam)
    }

    @Test fun autoInboxNeedsUserExamples() {
        val m = SpamModel.seeded()
        assertFalse(m.canAutoInbox)
        repeat(5) { m.train("spam example $it تخفیف", true); m.train("سلام دوست $it", false) }
        assertTrue(m.canAutoInbox)
    }

    @Test fun unknownTextHasNoOpinion() {
        assertNull(SpamModel.seeded().spamProbability("qqq zzz"))
    }

    @Test fun classifierFeedsFilter() {
        val m = SpamModel.seeded()
        val r = SmsFilter.classify("09351112233", "دوره آنلاین با هدیه ویژه", false, false, false, m)
        assertEquals(Verdict.SPAM, r.verdict)
        // OTP still wins even if the model dislikes it
        val otp = SmsFilter.classify("98200012", "کد ورود 123456 هدیه ویژه", false, false, false, m)
        assertEquals(Verdict.INBOX, otp.verdict)
    }
}
