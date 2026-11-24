package com.privateime.core.model

/**
 * A normalized, Android-free snapshot of everything we know about the field
 * that currently has input focus.
 *
 * The IME fills this from `EditorInfo`; the accessibility probe fills the
 * gaps that badly written apps leave. Keeping it a plain data class makes the
 * whole decision engine exercisable on the JVM.
 *
 * **Never put user input in here.** [textSample] is the single exception and is
 * used only for content classification inside the process; callers must not
 * persist it.
 */
data class FieldSignals(
    val packageName: String = "",
    val activityName: String? = null,
    /** Window/page title, when the platform exposes one (web pages, some apps). */
    val screenTitle: String? = null,
    /** `EditorInfo.inputType`, or null when the source did not report one. */
    val inputType: Int? = null,
    val imeOptions: Int = 0,
    val privateImeOptions: String? = null,
    val fieldName: String? = null,
    val fieldId: Int = 0,
    val hintText: String? = null,
    val label: String? = null,
    /** Best-effort autofill hint vocabulary (`password`, `creditCardNumber`, ...). */
    val autofillHints: List<String> = emptyList(),
    val className: String? = null,
    val viewIdResourceName: String? = null,
    val contentDescription: String? = null,
    /** Accessibility `hintText`; Chrome exposes the HTML placeholder here. */
    val accessibilityHint: String? = null,
    /** Text of a `labelFor`/`labeledBy` node, or the closest preceding label. */
    val labelledText: String? = null,
    /** Accessibility `isPassword()`; independent of `inputType`. */
    val isPassword: Boolean = false,
    val maxTextLength: Int = -1,
    val textLength: Int = 0,
    /**
     * Up to a few dozen characters of what is currently in the field, used for
     * content classification only. Must never be written to disk or sent
     * anywhere.
     */
    val textSample: String? = null,
    val isWebView: Boolean = false,
    val accessibilityPresent: Boolean = false,
) {
    val signatureKey: String by lazy(LazyThreadSafetyMode.NONE) { SignatureKeys.of(this) }

    val numericClass: Boolean
        get() = inputType != null && InputTypeNames.classOf(inputType) == InputTypeNames.CLASS_NUMBER

    val phoneClass: Boolean
        get() = inputType != null && InputTypeNames.classOf(inputType) == InputTypeNames.CLASS_PHONE

    val noSuggestionsFlag: Boolean
        get() = inputType != null && InputTypeNames.hasFlag(inputType, InputTypeNames.FLAG_NO_SUGGESTIONS)

    val noPersonalizedLearning: Boolean
        get() = imeOptions and ImeFlags.NO_PERSONALIZED_LEARNING != 0

    /** True when the source is a web page rather than a native view. */
    val web: Boolean
        get() = isWebView ||
            className?.contains("WebView", ignoreCase = true) == true ||
            inputType?.let { InputTypeNames.variationOf(it) == InputTypeNames.TEXT_VARIATION_WEB_EDIT_TEXT } == true

    /** A compact, human-readable description of the shape; safe to log. */
    fun shapeDescription(): String = buildString {
        append(packageName.ifBlank { "?" })
        if (!viewIdResourceName.isNullOrBlank()) append(" /id=").append(viewIdResourceName)
        if (!fieldName.isNullOrBlank()) append(" /name=").append(fieldName)
        append(" /class=").append(className ?: "-")
        append(" /inputType=").append(inputType?.let(InputTypeNames::describe) ?: "-")
        append(" /maxLen=").append(maxTextLength)
    }

    companion object {
        /** @return a copy with `textSample` removed. */
        fun FieldSignals.withoutText(): FieldSignals = copy(textSample = null)
    }
}

/** Constants mirrored from `android.text.InputType` so `core` has no Android dependency. */
object InputTypeNames {
    const val MASK_CLASS = 0x0000000f
    const val MASK_VARIATION = 0x00000ff0
    const val MASK_FLAGS = 0x00fff000

    const val CLASS_TEXT = 0x00000001
    const val CLASS_NUMBER = 0x00000002
    const val CLASS_PHONE = 0x00000003
    const val CLASS_DATETIME = 0x00000004
    const val CLASS_NULL = 0x00000000

    const val TEXT_VARIATION_NORMAL = 0x00000000
    const val TEXT_VARIATION_URI = 0x00000010
    const val TEXT_VARIATION_EMAIL_ADDRESS = 0x00000020
    const val TEXT_VARIATION_EMAIL_SUBJECT = 0x00000030
    const val TEXT_VARIATION_SHORT_MESSAGE = 0x00000040
    const val TEXT_VARIATION_LONG_MESSAGE = 0x00000050
    const val TEXT_VARIATION_PERSON_NAME = 0x00000060
    const val TEXT_VARIATION_POSTAL_ADDRESS = 0x00000070
    const val TEXT_VARIATION_PASSWORD = 0x00000080
    const val TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    const val TEXT_VARIATION_WEB_EDIT_TEXT = 0x000000a0
    const val TEXT_VARIATION_FILTER = 0x000000b0
    const val TEXT_VARIATION_PHONETIC = 0x000000c0
    const val TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0x000000d0
    const val TEXT_VARIATION_WEB_PASSWORD = 0x000000e0

    const val NUMBER_VARIATION_NORMAL = 0x00000000
    const val NUMBER_VARIATION_PASSWORD = 0x00000010

    const val FLAG_NO_SUGGESTIONS = 0x00080000
    const val FLAG_AUTO_CORRECT = 0x00008000
    const val FLAG_AUTO_COMPLETE = 0x00010000

    fun classOf(inputType: Int): Int = inputType and MASK_CLASS

    fun variationOf(inputType: Int): Int = inputType and MASK_VARIATION

    fun hasFlag(inputType: Int, flag: Int): Boolean = inputType and flag != 0

    /** Descriptive name for diagnostics; no user data involved. */
    fun describe(inputType: Int): String {
        val cls = classOf(inputType)
        val variation = variationOf(inputType)
        val clsName = when (cls) {
            CLASS_TEXT -> "TEXT"
            CLASS_NUMBER -> "NUMBER"
            CLASS_PHONE -> "PHONE"
            CLASS_DATETIME -> "DATETIME"
            CLASS_NULL -> "NULL"
            else -> "0x" + Integer.toHexString(cls)
        }
        val varName = when (cls) {
            CLASS_TEXT -> when (variation) {
                TEXT_VARIATION_NORMAL -> "normal"
                TEXT_VARIATION_URI -> "uri"
                TEXT_VARIATION_EMAIL_ADDRESS -> "email"
                TEXT_VARIATION_EMAIL_SUBJECT -> "emailSubject"
                TEXT_VARIATION_SHORT_MESSAGE -> "shortMessage"
                TEXT_VARIATION_LONG_MESSAGE -> "longMessage"
                TEXT_VARIATION_PERSON_NAME -> "personName"
                TEXT_VARIATION_POSTAL_ADDRESS -> "postalAddress"
                TEXT_VARIATION_PASSWORD -> "password"
                TEXT_VARIATION_VISIBLE_PASSWORD -> "visiblePassword"
                TEXT_VARIATION_WEB_EDIT_TEXT -> "webEditText"
                TEXT_VARIATION_FILTER -> "filter"
                TEXT_VARIATION_PHONETIC -> "phonetic"
                TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "webEmail"
                TEXT_VARIATION_WEB_PASSWORD -> "webPassword"
                else -> "0x" + Integer.toHexString(variation)
            }
            CLASS_NUMBER -> if (variation == NUMBER_VARIATION_PASSWORD) "password" else "normal"
            else -> "0x" + Integer.toHexString(variation)
        }
        return "$clsName/$varName"
    }
}

/** Constants mirrored from `android.view.inputmethod.EditorInfo`. */
object ImeFlags {
    const val NO_PERSONALIZED_LEARNING = 0x01000000
    const val NO_SUGGESTIONS = 0x00080000
    const val NO_FULLSCREEN = 0x02000000
    const val NO_EXTRACT_UI = 0x10000000
}
