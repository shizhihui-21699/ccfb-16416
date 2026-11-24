package com.privateime.core.detect

import com.privateime.core.model.FieldSignals
import com.privateime.core.text.TextNormalizer

/**
 * Extracts human-meaningful label candidates from a [FieldSignals] snapshot.
 *
 * Android apps describe an input field in a surprising number of places:
 *
 *  * `EditorInfo.hintText` (placeholder)
 *  * `TextView` label next to the field (`labelFor`), exposed by accessibility
 *  * the hint of a wrapping `TextInputLayout` (which is *not* the hint of the
 *    inner `EditText`; it lives on the parent's content description in many
 *    versions of Material Components)
 *  * a `contentDescription` on the field
 *  * the resource id, which developers write in the same vocabulary as the label
 *    (`et_password`, `login_pwd_input`, ...)
 *  * `fieldName` / autofill hints, on Android 8+
 *  * for web pages, Chrome exposes the HTML `placeholder`, the `name`/`id`
 *    attributes via `viewIdResourceName`, and the `<label>` text via
 *    `AccessibilityNodeInfo.hintText`
 *
 * All of these are joined into one normalized haystack, one candidate at a
 * time, so a hit can be traced back to its source.
 */
object LabelParser {

    /**
     * A label candidate and where it came from.
     *
     * @param text normalized (folded, filler-stripped); safe to match on
     * @param raw the original, for diagnostics
     */
    data class Candidate(
        val text: String,
        val raw: String,
        val source: com.privateime.core.model.FieldSource,
    )

    /**
     * @return candidates, strongest provenance first. Callers should prefer the
     *   first hit for a category so that "old password" beats a generic
     *   resource id.
     */
    fun candidates(signals: FieldSignals): List<Candidate> {
        val out = ArrayList<Candidate>(8)
        fun add(source: com.privateime.core.model.FieldSource, raw: String?) {
            if (raw.isNullOrBlank()) return
            val normalized = TextNormalizer.collapseWhitespace(
                SensitiveLexicon.stripFiller(TextNormalizer.fold(raw)),
            )
            if (normalized.isEmpty()) return
            // Skip strings that are pure decoration ("*", "-", "…").
            if (normalized.none { it.isLetterOrDigit() }) return
            out.add(Candidate(normalized, TextNormalizer.collapseWhitespace(raw), source))
        }

        add(com.privateime.core.model.FieldSource.HINT, signals.hintText)
        add(com.privateime.core.model.FieldSource.HINT, signals.accessibilityHint)
        add(com.privateime.core.model.FieldSource.LABEL, signals.labelledText)
        add(com.privateime.core.model.FieldSource.LABEL, signals.label)
        add(com.privateime.core.model.FieldSource.CONTENT_DESC, signals.contentDescription)

        if (signals.autofillHints.isNotEmpty()) {
            add(com.privateime.core.model.FieldSource.AUTOFILL_HINT, signals.autofillHints.joinToString(" "))
        }

        // Resource ids and field names are written in snake_case/camelCase. Split
        // them so "et_confirm_password" becomes "et confirm password" and the
        // lexicon's word-boundary regexes can match "password" on its own.
        add(com.privateime.core.model.FieldSource.VIEW_ID, humanize(signals.viewIdResourceName))
        add(com.privateime.core.model.FieldSource.FIELD_NAME, humanize(signals.fieldName))

        // privateImeOptions carries app-specific flags; a few apps smuggle hints there.
        add(com.privateime.core.model.FieldSource.IME_OPTIONS, signals.privateImeOptions)

        // On web pages there is no label node we can reach from the IME, but the
        // page title often says what the form is for ("登录 - 某某银行").
        if (signals.web) {
            add(com.privateime.core.model.FieldSource.PAGE_CONTEXT, signals.screenTitle)
        }
        return out
    }

    /** Parses `et_confirm_password` / `etConfirmPassword` into `et confirm password`. */
    fun humanize(identifier: String?): String? {
        if (identifier.isNullOrBlank()) return null
        val sb = StringBuilder(identifier.length + 8)
        var prev = ' '
        for (ch in identifier) {
            when {
                ch == '_' || ch == '-' || ch == '.' || ch == ':' || ch == '/' -> {
                    if (prev != ' ') sb.append(' ')
                    prev = ' '
                }
                ch.isUpperCase() && prev.isLowerCase() -> {
                    sb.append(' ').append(ch.lowercaseChar())
                    prev = ch
                }
                ch.isDigit() && prev.isLetter() -> {
                    sb.append(' ').append(ch)
                    prev = ch
                }
                ch.isLetter() && prev.isDigit() -> {
                    sb.append(' ').append(ch)
                    prev = ch
                }
                else -> {
                    sb.append(ch)
                    prev = ch
                }
            }
        }
        return sb.toString().trim()
    }

    /**
     * Field name fragments that are pure framework noise. A view id of
     * `android:id/edit` carries no semantics at all.
     */
    val NOISE_TOKENS: Set<String> = setOf(
        "et", "edt", "edit", "edittext", "input", "inputview", "text", "textview",
        "tv", "iv", "imageview", "btn", "button", "view", "layout", "container",
        "wrapper", "root", "fragment", "activity", "widget", "id", "main", "content",
        "edit1", "edit2", "edittext1", "edittext2", "input1", "input2",
    )

    /** True when every token of the humanized identifier is noise. */
    fun isNoise(humanized: String): Boolean {
        val tokens = humanized.lowercase().split(' ', '_').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        return tokens.all { it in NOISE_TOKENS || it.all(Char::isDigit) }
    }
}
