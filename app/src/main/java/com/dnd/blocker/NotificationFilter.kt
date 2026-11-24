package com.dnd.blocker

/** What to dismiss for an app the user selected. */
enum class AppMode { PROMO, ALL }

/** Decides whether to dismiss a notification. Pure Kotlin so it can be unit tested. */
object NotificationFilter {

    /** Never touched, even in "All" mode: calls, alarms, navigation, progress, media... */
    private val protectedCategories = setOf(
        "call", "missed_call", "alarm", "navigation", "transport", "progress",
        "sys", "stopwatch", "location_sharing",
    )

    /** Returns why the notification should be dismissed, or null to keep it. */
    fun decide(
        mode: AppMode,
        title: String,
        text: String,
        ongoing: Boolean,
        category: String?,
        model: SpamModel? = null,
    ): String? {
        if (ongoing) return null
        if (category in protectedCategories) return null
        val all = "$title\n$text"
        if (SmsFilter.isOtp(all) || SmsFilter.isBank(all)) return null

        if (mode == AppMode.ALL) return "all notifications from this app"

        if (category == "promo") return "marked as a promotion by the app"
        if (SmsFilter.hasOptOut(all)) return "ad opt-out marker"
        val hits = SmsFilter.adKeywordHits(all)
        if (hits >= 2) return "ad keywords"
        val p = model?.spamProbability(all)
        if (p != null) {
            if (p >= SpamModel.SPAM_THRESHOLD) return "learned spam score ${(p * 100).toInt()}%"
            if (hits == 1 && p >= 0.6) return "ad keyword + spam score ${(p * 100).toInt()}%"
        }
        return null
    }
}
