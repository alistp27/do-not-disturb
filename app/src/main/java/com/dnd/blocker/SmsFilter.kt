package com.dnd.blocker

/** Pure filtering logic (no Android dependencies) so it can be unit tested. */
enum class Verdict { INBOX, QUARANTINE, SPAM }

data class FilterResult(val verdict: Verdict, val reason: String)

object SmsFilter {

    private val otpKeywords = listOf(
        "کد", "رمز", "پویا", "تایید", "تأیید", "ورود", "یکبار",
        "code", "otp", "verification", "password", "passcode", "pin"
    )

    private val bankKeywords = listOf(
        "برداشت", "واریز", "مانده", "موجودی", "حساب", "کارت", "ریال",
        "بانک", "انتقال", "خرید", "شبا"
    )

    private val adKeywords = listOf(
        "تخفیف", "جشنواره", "فروش ویژه", "هدیه", "رایگان", "قرعه", "کلیک",
        "ثبت نام", "ثبت‌نام", "دوره", "پیشنهاد ویژه", "حراج", "%", "٪", "http", "www."
    )

    /** Ad SMS in Iran must contain an opt-out like "لغو11". */
    private val optOutRegex = Regex("لغو\\s*\\d+|انصراف\\s*\\d+")
    private val otpNumberRegex = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    /** Bulk advertising line prefixes. */
    private val serviceLinePrefixes = listOf("1000", "2000", "3000", "5000", "9000")

    /** Converts Persian (۰-۹) and Arabic (٠-٩) digits to ASCII digits. */
    fun normalizeDigits(s: String): String = buildString(s.length) {
        for (c in s) append(
            when (c) {
                in '۰'..'۹' -> '0' + (c - '۰')
                in '٠'..'٩' -> '0' + (c - '٠')
                else -> c
            }
        )
    }

    /** Normalizes a phone number to Iranian local form: +989121234567 -> 09121234567. */
    fun normalizeNumber(raw: String?): String {
        if (raw == null) return ""
        var n = normalizeDigits(raw).filter { it.isDigit() || it == '+' }
        n = when {
            n.startsWith("+98") -> "0" + n.substring(3)
            n.startsWith("0098") -> "0" + n.substring(4)
            n.startsWith("98") && n.length == 12 -> "0" + n.substring(2)
            else -> n
        }
        return n
    }

    fun isOtp(body: String): Boolean {
        val b = normalizeDigits(body).lowercase()
        if (b.contains("تخفیف")) return false // "کد تخفیف" = discount code, not an OTP
        return otpKeywords.any { b.contains(it) } && otpNumberRegex.containsMatchIn(b)
    }

    fun isBank(body: String): Boolean {
        val b = normalizeDigits(body)
        val hits = bankKeywords.count { b.contains(it) }
        return hits >= 2 && b.any { it.isDigit() }
    }

    fun hasOptOut(body: String): Boolean = optOutRegex.containsMatchIn(normalizeDigits(body))

    fun isServiceLine(number: String): Boolean =
        serviceLinePrefixes.any { number.startsWith(it) }

    fun classify(
        sender: String?,
        body: String,
        isContact: Boolean,
        isWhitelisted: Boolean,
        isBlacklisted: Boolean,
        model: SpamModel? = null,
    ): FilterResult {
        val number = normalizeNumber(sender)
        if (isContact) return FilterResult(Verdict.INBOX, "contact")
        if (isWhitelisted) return FilterResult(Verdict.INBOX, "whitelist")
        if (isBlacklisted) return FilterResult(Verdict.SPAM, "blacklist")
        if (hasOptOut(body)) return FilterResult(Verdict.SPAM, "ad opt-out marker")
        if (isOtp(body)) return FilterResult(Verdict.INBOX, "otp")
        if (isBank(body)) return FilterResult(Verdict.INBOX, "bank")

        val b = normalizeDigits(body).lowercase()
        val adHits = adKeywords.count { b.contains(it) }
        if (adHits >= 2 || (adHits >= 1 && isServiceLine(number))) {
            return FilterResult(Verdict.SPAM, "ad keywords")
        }

        val p = model?.spamProbability(body)
        if (p != null) {
            val pct = "${(p * 100).toInt()}%"
            if (p >= SpamModel.SPAM_THRESHOLD) return FilterResult(Verdict.SPAM, "learned spam score $pct")
            if (p <= SpamModel.HAM_THRESHOLD && model.canAutoInbox) {
                return FilterResult(Verdict.INBOX, "learned spam score $pct")
            }
            return FilterResult(Verdict.QUARANTINE, "unknown sender · spam score $pct")
        }
        return FilterResult(Verdict.QUARANTINE, "unknown sender")
    }
}

object CallFilter {
    private val spamPrefixes = listOf("0940", "0941", "0942")

    fun shouldBlock(
        rawNumber: String?,
        isContact: Boolean,
        blockHidden: Boolean,
        blockAllUnknown: Boolean,
    ): String? {
        val number = SmsFilter.normalizeNumber(rawNumber)
        if (number.isBlank()) return if (blockHidden) "hidden number" else null
        if (isContact) return null
        if (spamPrefixes.any { number.startsWith(it) }) return "ad prefix ${number.take(4)}"
        if (blockAllUnknown) return "unknown number"
        return null
    }
}
