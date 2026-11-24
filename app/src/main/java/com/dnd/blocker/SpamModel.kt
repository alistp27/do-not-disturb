package com.dnd.blocker

import kotlin.math.exp
import kotlin.math.ln

/**
 * Small on-device Naive Bayes spam classifier. Learns from the user's
 * "Spam" / "Not spam" / "Block sender" choices; nothing leaves the phone.
 * Starts with a few built-in examples so it has an opinion from day one.
 * Pure Kotlin (no Android dependencies) so it can be unit tested.
 */
class SpamModel {
    private val spam = HashMap<String, Int>()
    private val ham = HashMap<String, Int>()
    private val vocab = HashSet<String>()
    private var spamTokens = 0
    private var hamTokens = 0
    private var spamDocs = 0
    private var hamDocs = 0

    /** Examples the user taught (built-in seed examples not counted). */
    var userSpam = 0; private set
    var userHam = 0; private set

    /** Only let the model move messages to the inbox once the user has taught it enough. */
    val canAutoInbox get() = userSpam >= MIN_USER_EXAMPLES && userHam >= MIN_USER_EXAMPLES

    /** Trains on one message and returns the tokens learned (to persist them). */
    @Synchronized
    fun train(text: String, isSpam: Boolean, fromUser: Boolean = true): Set<String> {
        val tokens = tokenize(text).toSet()
        tokens.forEach { addCount(it, if (isSpam) 1 else 0, if (isSpam) 0 else 1) }
        addDocs(if (isSpam) 1 else 0, if (isSpam) 0 else 1, fromUser)
        return tokens
    }

    @Synchronized
    fun addCount(token: String, s: Int, h: Int) {
        if (s > 0) { spam[token] = (spam[token] ?: 0) + s; spamTokens += s }
        if (h > 0) { ham[token] = (ham[token] ?: 0) + h; hamTokens += h }
        vocab += token
    }

    @Synchronized
    fun addDocs(s: Int, h: Int, fromUser: Boolean) {
        spamDocs += s; hamDocs += h
        if (fromUser) { userSpam += s; userHam += h }
    }

    /** Probability (0..1) that the text is spam, or null if the model can't judge it. */
    @Synchronized
    fun spamProbability(text: String): Double? {
        if (spamDocs == 0 || hamDocs == 0) return null
        val tokens = tokenize(text).toSet().filter { it in vocab }
        if (tokens.size < 2) return null
        val v = vocab.size.toDouble()
        var diff = 0.0 // log P(tokens|spam) - log P(tokens|ham), equal priors
        for (t in tokens) {
            diff += ln(((spam[t] ?: 0) + 1.0) / (spamTokens + v))
            diff -= ln(((ham[t] ?: 0) + 1.0) / (hamTokens + v))
        }
        return 1.0 / (1.0 + exp(-diff.coerceIn(-30.0, 30.0)))
    }

    companion object {
        const val MIN_USER_EXAMPLES = 5
        const val SPAM_THRESHOLD = 0.9
        const val HAM_THRESHOLD = 0.1

        private val urlRegex = Regex("(https?://|www\\.)\\S+", RegexOption.IGNORE_CASE)
        private val numberRegex = Regex("\\d+")
        private val splitRegex = Regex("[^\\p{L}<>%]+")

        fun tokenize(text: String): List<String> {
            var t = SmsFilter.normalizeDigits(text).lowercase()
                .replace('ي', 'ی').replace('ك', 'ک').replace('ة', 'ه')
                .replace('‌', ' ') // ZWNJ (نیم‌فاصله)
                .replace('٪', '%')
            t = urlRegex.replace(t, " <url> ")
            t = numberRegex.replace(t, " <num> ")
            return t.split(splitRegex).filter { it.length >= 2 || it == "%" }
        }

        /** Built-in examples so the model works before the user teaches it. */
        private val seedSpam = listOf(
            "۲۰٪ تخفیف ویژه برای شما فقط تا پایان هفته ثبت نام کنید",
            "جشنواره فروش ویژه با هدیه رایگان همین حالا خرید کنید",
            "دوره آنلاین آموزش با تخفیف استثنایی ظرفیت محدود",
            "شما برنده قرعه کشی شدید برای دریافت جایزه کلیک کنید",
            "پیشنهاد ویژه امروز ارسال رایگان سفارش دهید",
            "وام فوری بدون ضامن با اقساط بلند مدت تماس بگیرید",
            "کد تخفیف اختصاصی شما فعال شد از فروشگاه ما خرید کنید",
            "حراج بزرگ پایان فصل تا ۷۰ درصد تخفیف",
            "مشاوره رایگان مهاجرت و اخذ ویزا همین امروز",
            "Special offer just for you, 50% off, click the link",
        )
        private val seedHam = listOf(
            "سلام کجایی؟ زنگ بزن",
            "فردا ساعت ده جلسه داریم یادت نره",
            "رسیدم خونه، خیالت راحت",
            "عکس ها رو برات فرستادم ببین",
            "مامان گفت شام بیا اینجا",
            "باشه ممنون، بعدا صحبت می کنیم",
            "نوبت دکتر شما فردا ساعت چهار است",
            "Hi, are we still meeting tomorrow?",
        )

        fun seeded(): SpamModel = SpamModel().apply {
            seedSpam.forEach { train(it, isSpam = true, fromUser = false) }
            seedHam.forEach { train(it, isSpam = false, fromUser = false) }
        }
    }
}
