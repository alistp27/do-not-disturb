package com.dnd.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationFilterTest {
    private val model = SpamModel.seeded()

    private fun promo(title: String, text: String, category: String? = null, ongoing: Boolean = false) =
        NotificationFilter.decide(AppMode.PROMO, title, text, ongoing, category, model)

    @Test fun dismissesObviousPromotion() {
        assertNotNull(promo("جشنواره بهاره", "۳۰٪ تخفیف روی همه محصولات"))
    }

    @Test fun dismissesAppMarkedPromo() {
        assertEquals("marked as a promotion by the app", promo("New", "Check this out", category = "promo"))
    }

    @Test fun keepsNormalNotification() {
        assertNull(promo("علی", "سلام فردا ساعت ده میای؟"))
    }

    @Test fun keepsOtpEvenInAllMode() {
        assertNull(NotificationFilter.decide(AppMode.ALL, "بانک", "رمز پویا: 482917", false, null, model))
    }

    @Test fun keepsOngoingAndCalls() {
        assertNull(NotificationFilter.decide(AppMode.ALL, "Uploading", "50%", true, "progress", model))
        assertNull(NotificationFilter.decide(AppMode.ALL, "Incoming call", "Ali", false, "call", model))
    }

    @Test fun allModeDismissesOrdinaryNotifications() {
        assertEquals(
            "all notifications from this app",
            NotificationFilter.decide(AppMode.ALL, "Story", "Someone posted a new story", false, "social", model)
        )
    }
}
