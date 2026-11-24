package com.privateime.core.detect

import com.privateime.core.model.Category
import com.privateime.core.text.TextNormalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A keyword table for the labels, hints and ids that surround an input field.
 *
 * Two reasons this is data rather than logic:
 *
 *  1. Real-world labels are written by thousands of app developers in many
 *     languages, and the list grows forever. It should be cheap to extend.
 *  2. False positives are expensive (a user who suddenly loses word prediction
 *     in their chat app will uninstall the keyboard), so every term carries an
 *     explicit weight rather than an implicit "contains → sensitive".
 *
 * The table is matched against *normalized* text: NFKC-folded, lowercased, with
 * common filler phrases stripped by [LabelParser] first.
 */
object SensitiveLexicon {

    /**
     * How strongly a term points at a category.
     *
     * [STRONG] terms are specific enough to stand alone ("password", "验证码").
     * [MODERATE] terms are specific but can appear in benign contexts
     * ("card number" on a customer-service chat). [WEAK] terms only tip a
     * decision that is already leaning somewhere.
     */
    enum class Strength(val weight: Double) {
        STRONG(1.0),
        MODERATE(0.62),
        WEAK(0.28),
    }

    data class Term(
        val text: String,
        val category: Category,
        val strength: Strength,
        /** Latin terms are matched on word boundaries; CJK terms as substrings. */
        val latin: Boolean = text.all { it.code < 128 },
    )

    /** Terms that argue *against* a field being sensitive. */
    data class NegativeTerm(val text: String, val weight: Double, val latin: Boolean = text.all { it.code < 128 })

    /**
     * Chinese password phrasing. Written out separately because Chinese has no
     * word boundaries, so these are matched as substrings and every one of them
     * has to be specific enough to avoid firing on unrelated chat.
     */
    private fun chinesePassword(): List<String> = listOf(
        "密码", "密码框", "口令", "登录密码", "登陆密码", "账户密码",
        "用户密码", "原密码", "旧密码", "新密码", "确认密码", "重复密码",
        "再次输入密码", "修改密码", "重置密码", "设置密码", "请输入密码",
        "动态密码", "图形密码", "手势密码",
    )

    private val positive: List<Term> = buildList {
        // ---------------------------------------------------------------- password
        chinesePassword().forEach { add(Term(it, Category.PASSWORD, Strength.STRONG)) }
        listOf(
            "password", "passwd", "passcode", "pwd", "passwort", "wachtwoord",
            "contraseña", "contrasena", "mot de passe", "senha", "пароль",
            "パスワード", "パスコード", "비밀번호", "비밀", "пароль",
        ).forEach { add(Term(it, Category.PASSWORD, Strength.STRONG)) }
        listOf(
            "old password", "new password", "confirm password", "current password",
            "repeat password", "enter password", "change password",
        ).forEach { add(Term(it, Category.PASSWORD, Strength.STRONG)) }
        listOf("pass", "credential", "credentials").forEach {
            add(Term(it, Category.PASSWORD, Strength.WEAK))
        }
        // "login" alone is a button, but "login field" / "signin" hints at auth.
        listOf("signin", "sign in", "log in").forEach {
            add(Term(it, Category.PASSWORD, Strength.WEAK))
        }

        // --------------------------------------------------------------------- pin
        listOf(
            "pin", "pin code", "pincode", "pin number", "passcode",
            "支付密码", "交易密码", "取款密码", "查询密码", "提现密码", "消费密码",
            "提款密码", "卡的密码", "卡密码", "六位密码", "6位密码", "六位数字密码",
            "数字密码", "屏幕锁", "锁屏密码", "解锁密码", "设备密码", "sim卡密码",
            "sim pin", "device pin", "screen lock", "lock screen",
        ).forEach { add(Term(it, Category.PIN, Strength.STRONG)) }
        listOf("pin码", "pin 码").forEach { add(Term(it, Category.PIN, Strength.STRONG)) }

        // --------------------------------------------------------------------- otp
        listOf(
            "验证码", "校验码", "确认码", "认证码", "动态码", "动态密码", "短信码",
            "短信验证码", "邮件验证码", "邮箱验证码", "语音验证码", "图片验证码",
            "图形验证码", "安全验证码", "手机验证码", "验证数字", "验证编号",
            "一次性密码", "一次性口令", "一次一密", "动态口令", "令牌码",
        ).forEach { add(Term(it, Category.OTP, Strength.STRONG)) }
        listOf(
            "otp", "totp", "hotp", "one time password", "one-time password",
            "one time code", "one-time code", "verification code", "verify code",
            "auth code", "authentication code", "security code", "sms code",
            "2fa", "two factor", "two-factor", "mfa", "six digit code", "6-digit code",
            "captcha", "confirmation code", "activation code", "token code",
        ).forEach { add(Term(it, Category.OTP, Strength.STRONG)) }
        listOf("code", "token", "challenge").forEach {
            add(Term(it, Category.OTP, Strength.WEAK))
        }

        // ------------------------------------------------------------ payment card
        listOf(
            "卡号", "银行卡号", "银行卡", "信用卡", "信用卡号", "借记卡", "储蓄卡",
            "银联卡", "信用卡号码", "银行卡号码", "卡片号码", "卡号码",
            "银行账号", "收款账号", "付款账号", "付款卡", "支付卡", "快捷支付",
            "无卡支付", "卡片有效期", "信用卡有效期", "安全码", "后三位",
            "背面三位", "背面的三位", "cvn", "cvv2", "持卡人", "持卡人姓名",
        ).forEach { add(Term(it, Category.PAYMENT_CARD, Strength.MODERATE)) }
        // "账号" alone means "account" and is most often a login username, so it
        // stays weak and is outweighed by the username negatives below.
        add(Term("有效期", Category.PAYMENT_CARD, Strength.WEAK))
        add(Term("账号", Category.PAYMENT_CARD, Strength.WEAK))
        add(Term("account", Category.PAYMENT_CARD, Strength.WEAK))
        listOf(
            "card number", "cardnumber", "card no", "credit card", "debit card",
            "credit card number", "card verification", "card security code",
            "cvc", "cvv", "expiry", "expiration date", "exp month", "exp year",
            "mm/yy", "valid thru", "pan",
        ).forEach { add(Term(it, Category.PAYMENT_CARD, Strength.STRONG)) }
        listOf("卡", "card", "卡片", "bank", "银行").forEach {
            add(Term(it, Category.PAYMENT_CARD, Strength.WEAK))
        }
        // Combos that only make sense on a payment form.
        listOf("cvv/cvc", "cvv cvc", "cvc cvv").forEach {
            add(Term(it, Category.PAYMENT_CARD, Strength.STRONG))
        }

        // --------------------------------------------------------------------- pii
        listOf(
            "邮箱", "电子邮箱", "电子邮件", "邮箱地址", "邮件地址", "手机号", "手机号码",
            "手机", "电话", "电话号码", "联系电话", "联系方式", "姓名", "真实姓名",
            "身份证", "身份证号", "身份证号码", "证件号", "护照号", "地址", "收货地址",
            "详细地址", "家庭地址", "邮编", "邮政编码", "生日", "出生日期", "社保号",
            "社保卡号", "公积金", "税号", "纳税人识别号",
        ).forEach { add(Term(it, Category.PII, Strength.MODERATE)) }
        listOf("手机号", "手机号码", "身份证号", "身份证号码", "银行卡号").forEach {
            add(Term(it, Category.PII, Strength.STRONG))
        }
        listOf(
            "email", "e-mail", "email address", "phone", "phone number", "mobile",
            "mobile number", "telephone", "name", "full name", "first name",
            "last name", "surname", "address", "street address", "postal code",
            "postcode", "zip code", "zip", "birthday", "date of birth", "dob",
            "national id", "social security", "ssn", "passport number", "id number",
        ).forEach { add(Term(it, Category.PII, Strength.MODERATE)) }
        listOf("email", "email address", "phone number", "id number", "ssn").forEach {
            add(Term(it, Category.PII, Strength.STRONG))
        }
    }.distinctBy { it.text to it.category }

    private val negative: List<NegativeTerm> = listOf(
        // Search / query surfaces. Prediction here is a feature, not a leak.
        NegativeTerm("搜索", 0.75), NegativeTerm("查找", 0.6), NegativeTerm("查询", 0.45),
        NegativeTerm("筛选", 0.7), NegativeTerm("过滤", 0.7), NegativeTerm("检索", 0.7),
        NegativeTerm("search", 0.8), NegativeTerm("query", 0.7), NegativeTerm("filter", 0.7),
        NegativeTerm("find", 0.6), NegativeTerm("lookup", 0.5),
        // Identity, but not a secret. Still keeps cloud away via the PII tier.
        NegativeTerm("用户名", 0.55), NegativeTerm("登录名", 0.5), NegativeTerm("账号名", 0.4),
        NegativeTerm("昵称", 0.6), NegativeTerm("用户账号", 0.4), NegativeTerm("用户id", 0.5),
        NegativeTerm("username", 0.75), NegativeTerm("user name", 0.75),
        NegativeTerm("nickname", 0.7), NegativeTerm("login name", 0.6),
        NegativeTerm("screen name", 0.6), NegativeTerm("handle", 0.35),
        // Free text. Learning here is the product.
        NegativeTerm("评论", 0.65), NegativeTerm("回复", 0.5), NegativeTerm("留言", 0.6),
        NegativeTerm("弹幕", 0.7), NegativeTerm("话题", 0.4), NegativeTerm("正文", 0.5),
        NegativeTerm("comment", 0.7), NegativeTerm("reply", 0.55), NegativeTerm("message", 0.4),
        NegativeTerm("say something", 1.0), NegativeTerm("write a message", 0.9),
        NegativeTerm("post", 0.35), NegativeTerm("caption", 0.5),
        // Titles and free-form metadata.
        NegativeTerm("标题", 0.5), NegativeTerm("备注", 0.5), NegativeTerm("描述", 0.3),
        NegativeTerm("title", 0.5), NegativeTerm("note", 0.45), NegativeTerm("description", 0.3),
        NegativeTerm("标签", 0.4), NegativeTerm("分类", 0.35), NegativeTerm("tag", 0.4),
        // Address bar / URL fields are not secrets.
        NegativeTerm("网址", 0.6), NegativeTerm("链接", 0.55), NegativeTerm("url", 0.7),
        NegativeTerm("address bar", 1.0), NegativeTerm("web address", 0.8),
    )

    /** Filler that prefixes about half of the world's placeholders. Stripped before matching. */
    private val fillerPrefixes: List<String> = listOf(
        "请输入", "请再次输入", "请重新输入", "请填写", "请再次填写", "请确认", "请选择",
        "请输入您的", "请输入你的", "请填写您的", "输入您的", "输入你的", "请输入您",
        "确认您的", "再次输入", "重新输入", "输入", "填写", "您的", "你的", "请",
        "please enter", "please enter your", "please enter a", "enter your",
        "enter the", "enter a", "enter", "input", "type your", "type the", "type",
        "confirm your", "confirm the", "confirm", "re-enter", "reenter", "repeat your",
        "repeat the", "repeat", "your", "you're",
    ).sortedByDescending { it.length }

    private val fillerSuffixes: List<String> = listOf(
        "输入框", "必填", "选填", "（选填）", "(选填)", "(可选)", "(optional)",
        "optional", "required", "field",
    ).sortedByDescending { it.length }

    private data class Compiled(
        val term: Term,
        val latinRegex: Regex?,
    )

    private val compiledPositives: List<Compiled> = positive.map { term ->
        val folded = TextNormalizer.fold(term.text)
        Compiled(
            term = term,
            latinRegex = if (term.latin && folded.all { it.isLetterOrDigit() || it == ' ' }) {
                // Word boundaries keep "pin" out of "spinning" and "card" out of "discard".
                Regex("(?<![a-z0-9])" + Regex.escape(folded.trim()) + "(?![a-z0-9])")
            } else {
                null
            },
        )
    }

    private val compiledNegatives: List<Pair<NegativeTerm, Regex?>> = negative.map { term ->
        val folded = TextNormalizer.fold(term.text)
        term to if (term.latin && folded.all { it.isLetterOrDigit() || it == ' ' }) {
            Regex("(?<![a-z0-9])" + Regex.escape(folded.trim()) + "(?![a-z0-9])")
        } else {
            null
        }
    }

    /** Cache of `normalized label -> hits`. Labels repeat constantly; parsing is hot. */
    private val hitCache = ConcurrentHashMap<String, List<Term>>(256)

    /**
     * @param text already normalized by [LabelParser] (filler stripped).
     * @return lexicon terms present in the text, strongest first.
     */
    fun match(text: String): List<Term> {
        if (text.isEmpty()) return emptyList()
        hitCache[text]?.let { return it }
        val compact = TextNormalizer.compact(text)
        val spaced = " $text "
        val result = ArrayList<Term>(4)
        for (compiled in compiledPositives) {
            val term = compiled.term
            val folded = TextNormalizer.fold(term.text)
            val hit = if (compiled.latinRegex != null) {
                compiled.latinRegex.containsMatchIn(text)
            } else if (!term.latin) {
                // CJK: substring on the whitespace/punctuation-free form so
                // "银行 卡号" and "银行卡号" behave alike.
                compact.contains(TextNormalizer.compact(folded)) &&
                    TextNormalizer.compact(folded).isNotEmpty()
            } else {
                spaced.contains(folded)
            }
            if (hit) result.add(term)
        }
        result.sortWith(compareByDescending<Term> { it.strength.weight }.thenBy { it.category.ordinal })
        val immutable = result.toList()
        if (hitCache.size > 512) hitCache.clear()
        hitCache[text] = immutable
        return immutable
    }

    /** Negative weight present in the text, 0 when none. */
    fun negativeWeight(text: String): Double {
        if (text.isEmpty()) return 0.0
        var total = 0.0
        val compact = TextNormalizer.compact(text)
        for ((term, regex) in compiledNegatives) {
            val hit = if (regex != null) regex.containsMatchIn(text)
            else if (!term.latin) compact.contains(TextNormalizer.compact(term.text))
            else " $text ".contains(TextNormalizer.fold(term.text))
            if (hit) total += term.weight
        }
        // Saturate: three weak negatives should not add up to a veto-proof wall.
        return if (total > 0.9) 0.9 else total
    }

    /** Strips filler prefixes/suffixes from an already-normalized label. */
    fun stripFiller(normalized: String): String {
        var s = normalized.trim()
        var changed = true
        var guard = 0
        while (changed && guard++ < 6) {
            changed = false
            for (prefix in fillerPrefixes) {
                if (s.startsWith(prefix) && s.length > prefix.length) {
                    s = s.substring(prefix.length).trimStart(' ', ':', '：', '-', '—')
                    changed = true
                }
            }
            for (suffix in fillerSuffixes) {
                if (s.endsWith(suffix) && s.length > suffix.length) {
                    s = s.substring(0, s.length - suffix.length).trimEnd()
                    changed = true
                }
            }
        }
        return s
    }

    /** Exposed for tests and for the settings screen's "why was this flagged" copy. */
    fun allTerms(): List<Term> = positive

    fun allNegativeTerms(): List<NegativeTerm> = negative

    /** Locates a term by its normalized text, for diagnostics. */
    fun describe(term: Term): String =
        String.format(
            Locale.ROOT,
            "%s(%s, %s)",
            term.text,
            term.category.name,
            term.strength.name,
        )
}
