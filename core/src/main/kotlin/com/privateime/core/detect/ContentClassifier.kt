package com.privateime.core.detect

import com.privateime.core.model.Category
import com.privateime.core.model.Confidence
import com.privateime.core.model.Evidence
import com.privateime.core.model.FieldSource
import com.privateime.core.text.TextNormalizer
import java.util.regex.Pattern

/**
 * Classifies a short sample of what is actually being typed.
 *
 * This is the last line of defence for the cases the metadata cannot cover:
 * a password box declared as `TYPE_CLASS_TEXT|TYPE_TEXT_VARIATION_NORMAL`, a
 * web form whose `<input>` has only `name="q"`, a PIN pad built out of four
 * separate `EditText`s. Labels can lie or be absent; the characters the user
 * types cannot.
 *
 * The classifier is deliberately conservative: it only ever reports evidence
 * strong enough to act on, and never echoes the sample back through [Evidence]
 * (details carry shapes and lengths only).
 */
object ContentClassifier {

    private val EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,255}\\.[a-zA-Z]{2,24}$")
    private val CN_MOBILE = Pattern.compile("^(?:\\+?86)?1[3-9]\\d{9}$")
    private val CN_ID = Pattern.compile("^\\d{17}[\\dXx]$")
    private val PHONE_LOOSE = Pattern.compile("^\\+?\\d{7,15}$")

    private const val MAX_SAMPLE = 128

    /**
     * @param text raw text currently present in the field (a bounded sample)
     * @param numericField whether the editor declares `TYPE_CLASS_NUMBER`
     * @param maxTextLength the editor's `maxLength`, or -1
     * @param maxCandidates how many characters we are willing to receive
     */
    fun classify(
        text: String?,
        numericField: Boolean,
        maxTextLength: Int,
        maxCandidates: Int = MAX_SAMPLE,
    ): List<Evidence> {
        if (text.isNullOrEmpty()) return emptyList()
        val sample = TextNormalizer.truncate(text, maxCandidates).trim()
        if (sample.isEmpty()) return emptyList()

        val out = ArrayList<Evidence>(2)
        val digits = TextNormalizer.digitsOnly(sample)
        val compact = sample.replace(Regex("[\\s\\-]"), "")
        val shape = shapeOf(sample)

        // ---- payment card ---------------------------------------------------
        // Luhn is strong evidence *given* a 13–19 digit run. It has a 1-in-10
        // false positive rate on random digit strings, so it is combined with
        // the field's capacity to get a "card-shaped" verdict.
        if (compact.length in 13..19 && compact.all(Char::isDigit) && passesLuhn(compact)) {
            val looksLikeCapacity = maxTextLength == -1 || maxTextLength in 12..24
            if (looksLikeCapacity) {
                out.add(
                    Evidence(
                        code = "content.card.luhn",
                        source = FieldSource.CONTENT,
                        weight = 0.9,
                        category = Category.PAYMENT_CARD,
                        detail = "digits=${compact.length}, luhn=ok, shape=$shape",
                        // Luhn is a checksum, not a guess. A 13–19 digit string
                        // that passes it is a card number for practical purposes,
                        // so this single signal is allowed to lock on its own.
                        hard = true,
                    ),
                )
            }
        }

        // ---- one-time code --------------------------------------------------
        // A 4–8 digit run that is *all* the field holds, in a field that cannot
        // hold much more, is an OTP/PIN candidate. Text that merely contains
        // digits ("房间号 203") is not.
        val onlyDigitsAndSeparators = sample.all { it.isDigit() || it == ' ' || it == '-' || it == ' ' }
        if (onlyDigitsAndSeparators && digits.length in 4..8 &&
            (maxTextLength in 4..8 || maxTextLength == -1) &&
            (numericField || maxTextLength in 4..8)
        ) {
            val category = if (digits.length <= 6) Category.PIN else Category.OTP
            out.add(
                Evidence(
                    code = "content.onetime.digits",
                    source = FieldSource.CONTENT,
                    weight = if (digits.length == 6) 0.58 else 0.42,
                    category = category,
                    detail = "digits=${digits.length}, maxLen=$maxTextLength",
                ),
            )
        }

        // ---- identifiers ----------------------------------------------------
        if (EMAIL.matcher(compact).matches()) {
            out.add(
                Evidence(
                    code = "content.pii.email",
                    source = FieldSource.CONTENT,
                    weight = 0.72,
                    category = Category.PII,
                    detail = "email-shaped",
                ),
            )
        } else if (CN_MOBILE.matcher(compact.removePrefix("+86")).matches()) {
            out.add(
                Evidence(
                    code = "content.pii.phone.cn",
                    source = FieldSource.CONTENT,
                    weight = 0.7,
                    category = Category.PII,
                    detail = "cn-mobile-shaped",
                ),
            )
        } else if (PHONE_LOOSE.matcher(compact).matches() && compact.length >= 9 && !numericField) {
            out.add(
                Evidence(
                    code = "content.pii.phone",
                    source = FieldSource.CONTENT,
                    weight = 0.35,
                    category = Category.PII,
                    detail = "phone-shaped",
                ),
            )
        }

        if (CN_ID.matcher(compact).matches()) {
            out.add(
                Evidence(
                    code = "content.pii.national_id",
                    source = FieldSource.CONTENT,
                    weight = 0.85,
                    category = Category.PII,
                    detail = "id-shaped",
                ),
            )
        }

        // ---- password-shaped token -----------------------------------------
        // A single long token mixing case, digits and symbols is very unlikely to
        // be a word the user is composing. This is only ever WEAK evidence (the
        // detector caps content-only password evidence at WEAK → guarded), because
        // a strong passphrase or a product code looks the same, but it is the only
        // signal available when a password box declares plain text and has no label.
        if (looksLikeCredential(sample) && !numericField) {
            out.add(
                Evidence(
                    code = "content.credential.shape",
                    source = FieldSource.CONTENT,
                    weight = 0.55,
                    category = Category.PASSWORD,
                    detail = "credential-shaped token len=${sample.length}",
                ),
            )
        }
        return out
    }

    /**
     * Heuristic used by the residue cleaner for a whole *learned phrase*, not a
     * live field. Broader than [classify] because a stored phrase has no
     * surrounding metadata at all.
     */
    fun residueEvidence(text: String): List<Evidence> {
        val out = ArrayList<Evidence>(2)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return out
        val digits = TextNormalizer.digitsOnly(trimmed)
        val nonDigit = trimmed.count { !it.isDigit() && !it.isWhitespace() && it != '-' }

        if (digits.length in 13..19 && nonDigit == 0 && passesLuhn(digits)) {
            out.add(
                Evidence(
                    "residue.card.luhn", FieldSource.CONTENT, 0.95, Category.PAYMENT_CARD,
                    "luhn-valid ${digits.length}-digit phrase",
                ),
            )
        }
        // 6–8 digits: plausibly an OTP. 4–5 digits alone are NOT flagged here:
        // years ("2024"), prices, house numbers and quantities all look identical,
        // and in a *learned dictionary* (unlike a live field with a maxLength)
        // there is no capacity signal to disambiguate. A 4-digit PIN is only
        // reported when the entry was already categorised as one.
        if (digits.length in 6..8 && nonDigit == 0) {
            out.add(
                Evidence(
                    "residue.onetime.digits", FieldSource.CONTENT, 0.55, Category.OTP,
                    "standalone ${digits.length}-digit phrase",
                ),
            )
        }
        if (EMAIL.matcher(trimmed).matches()) {
            out.add(
                Evidence("residue.pii.email", FieldSource.CONTENT, 0.85, Category.PII, "email-shaped"),
            )
        }
        if (CN_ID.matcher(trimmed).matches()) {
            out.add(
                Evidence(
                    "residue.pii.national_id", FieldSource.CONTENT, 0.9, Category.PII, "id-shaped",
                ),
            )
        }
        // Residue scanning is a *review* step, not an automatic deletion, so it is
        // deliberately more inclusive than the live guard: a digitless passphrase
        // ("CorrectHorseBattery") has no digit and would be missed by the live rule.
        // The cost is that an occasional punctuated phrase shows up for review.
        if (looksLikeCredential(trimmed, requireDigit = false)) {
            out.add(
                Evidence(
                    "residue.credential.shape", FieldSource.CONTENT, 0.5, Category.PASSWORD,
                    "mixed-class token, len=${trimmed.length}",
                ),
            )
        }
        return out
    }

    /**
     * A token that is very unlikely to be a natural-language word: mixes
     * upper/lower/digit/symbol, has no spaces, and is long enough that chance
     * does not explain it.
     *
     * @param requireDigit when true (the default) the token must contain a digit.
     *   Ordinary prose with punctuation ("Hello,World!") has three classes but no
     *   digit, and flagging it would cut cloud suggestions mid-sentence — the exact
     *   experience regression this feature is trying to avoid. Real credentials
     *   almost always contain a digit, so requiring one removes most false
     *   positives at negligible cost to recall.
     */
    fun looksLikeCredential(text: String, requireDigit: Boolean = true): Boolean {
        val t = text.trim()
        if (t.length < 8 || t.length > 128) return false
        if (t.any { it.isWhitespace() }) return false
        var classes = 0
        if (t.any(Char::isUpperCase)) classes++
        if (t.any(Char::isLowerCase)) classes++
        val hasDigit = t.any(Char::isDigit)
        if (hasDigit) classes++
        if (t.any { !it.isLetterOrDigit() }) classes++
        // A pure digit run is handled by the card/OTP rules; don't double count.
        if (t.all(Char::isDigit)) return false
        if (requireDigit && !hasDigit) return false
        return classes >= 3 && t.length >= 9
    }

    fun passesLuhn(digits: String): Boolean {
        if (digits.length < 12 || digits.length > 19) return false
        if (digits.any { !it.isDigit() }) return false
        var sum = 0
        var double = false
        for (i in digits.length - 1 downTo 0) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }

    /** Content-free shape string, e.g. `Aaa9!!` → `aadss`, for diagnostics only. */
    fun shapeOf(sample: String): String {
        val sb = StringBuilder(minOf(sample.length, 24))
        for (ch in sample.take(24)) {
            sb.append(
                when {
                    ch.isDigit() -> 'd'
                    ch in 'a'..'z' -> 'a'
                    ch in 'A'..'Z' -> 'A'
                    ch.isLetter() -> 'L'
                    ch.isWhitespace() -> '_'
                    else -> 's'
                },
            )
        }
        return sb.toString()
    }

    /** Maps content evidence to the guard tier it justifies. Used by tests and UI. */
    fun confidenceOf(evidence: Evidence): Confidence = when {
        evidence.weight >= 0.85 -> Confidence.HIGH
        evidence.weight >= 0.5 -> Confidence.MODERATE
        evidence.weight >= 0.25 -> Confidence.WEAK
        else -> Confidence.NONE
    }
}
