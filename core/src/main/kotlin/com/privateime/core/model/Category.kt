package com.privateime.core.model

import com.privateime.core.text.TextNormalizer
import java.security.MessageDigest

/**
 * What kind of sensitive content a field is expected to hold.
 *
 * The distinction between [isSecret] and [isPii] matters because it drives two
 * different policies:
 *
 *  * **Secrets** (passwords, PINs, one-time codes, card data) must never be
 *    persisted or transmitted from any sink, in any tier.
 *  * **PII** (e-mail, phone, address, ...) is not a credential, but shipping it
 *    to a cloud language model is still a privacy decision the user should opt
 *    into. Locally, it may be learned if the user allows it.
 */
enum class Category(
    val isSecret: Boolean,
    val isPii: Boolean,
) {
    NONE(false, false),
    PASSWORD(true, false),
    PIN(true, false),
    OTP(true, false),
    PAYMENT_CARD(true, false),
    PII(false, true),
    UNKNOWN_SECRET(true, false),
    ;

    /**
     * Used only to break ties when two categories have the same weight. A more
     * specific category wins over a generic one.
     */
    val specificity: Int
        get() = when (this) {
            NONE -> 0
            PII -> 1
            UNKNOWN_SECRET -> 2
            PASSWORD -> 3
            OTP -> 4
            PIN -> 5
            PAYMENT_CARD -> 6
        }

    companion object {
        /** Category a signal of this kind should be reported under. */
        fun secretOrNone(secret: Boolean): Category = if (secret) UNKNOWN_SECRET else NONE
    }
}

/** Coarse confidence buckets. Scores are documented in [SensitivityDetector]. */
enum class Confidence(val rank: Int) {
    NONE(0),
    WEAK(1),
    MODERATE(2),
    HIGH(3),
    ;

    fun atLeast(other: Confidence): Boolean = rank >= other.rank

    fun max(other: Confidence): Confidence = if (other.rank > rank) other else this
}

/** Where a piece of evidence came from. Kept for diagnostics and the audit log. */
enum class FieldSource {
    INPUT_TYPE,
    IME_OPTIONS,
    FIELD_NAME,
    VIEW_ID,
    HINT,
    LABEL,
    CONTENT_DESC,
    CLASS_NAME,
    ACCESSIBILITY,
    AUTOFILL_HINT,
    PAGE_CONTEXT,
    SEQUENCE,
    MEMORY,
    CONTENT,
    USER_OVERRIDE,
}

/**
 * One piece of evidence contributing to a [Verdict].
 *
 * @param code stable machine-readable identifier (used in the audit trail and
 *   mapped to localized copy by the UI layer)
 * @param weight contribution to the sensitivity score
 * @param category the category this evidence points at (or [Category.NONE])
 * @param detail human-readable context, never containing the input itself
 */
data class Evidence(
    val code: String,
    val source: FieldSource,
    val weight: Double,
    val category: Category = Category.NONE,
    val detail: String = "",
    /**
     * Hard evidence cannot be cancelled by negative lexical hints. A field that
     * reports `TYPE_TEXT_VARIATION_PASSWORD` is a password field even if its
     * label says "search".
     */
    val hard: Boolean = false,
)

/**
 * The result of assessing one editor.
 *
 * @param signatureKey content-free fingerprint of the field, used for the
 *   on-device "this app's password box is at view id X" memory. It never
 *   contains anything the user typed.
 */
data class Verdict(
    val category: Category,
    val confidence: Confidence,
    val score: Double,
    val reasons: List<Evidence>,
    val signatureKey: String,
    val sticky: Boolean = false,
) {
    val secret: Boolean get() = category.isSecret
    val pii: Boolean get() = category.isPii
    val sensitive: Boolean get() = secret || pii

    /** True when the verdict is strong enough to stop all learning outright. */
    val blocksLearning: Boolean
        get() = secret && confidence.atLeast(Confidence.MODERATE)

    fun withSticky(newSticky: Boolean): Verdict = copy(sticky = newSticky)

    companion object {
        fun none(signatureKey: String = ""): Verdict =
            Verdict(Category.NONE, Confidence.NONE, 0.0, emptyList(), signatureKey)
    }
}

/**
 * Deterministic, content-free key for a field.
 *
 * The key deliberately ignores the input type and the text: a badly written
 * app that changes its `inputType` between versions should still hit the same
 * memory entry, while the same view id in a different app must not.
 */
object SignatureKeys {
    fun of(signals: FieldSignals): String {
        val discriminator = when {
            !signals.viewIdResourceName.isNullOrBlank() -> "id:" + signals.viewIdResourceName
            !signals.fieldName.isNullOrBlank() -> "name:" + signals.fieldName
            else -> "shape:" + (signals.className ?: "") + "/" + hintShape(signals.hintText)
        }
        return digest(signals.packageName + "\u0000" + discriminator)
    }

    /** Page-level key for a web field with no stable view id. */
    fun ofPage(packageName: String, title: String?): String =
        digest(packageName + "\u0000page:" + (title?.let(TextNormalizer::compact) ?: ""))

    private fun hintShape(hint: String?): String {
        if (hint.isNullOrBlank()) return ""
        // Collapse digits so "请输入6位验证码" and "请输入4位验证码" share a shape.
        return TextNormalizer.compact(hint).replace(Regex("[0-9]"), "#")
    }

    private fun digest(value: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 8) {
            sb.append(HEX[(bytes[i].toInt() shr 4) and 0xF])
            sb.append(HEX[bytes[i].toInt() and 0xF])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
