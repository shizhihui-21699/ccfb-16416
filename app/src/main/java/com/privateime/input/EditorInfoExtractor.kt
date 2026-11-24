package com.privateime.input

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.privateime.core.model.FieldSignals
import com.privateime.core.model.InputTypeNames

/**
 * Turns the IME's [EditorInfo] into a [FieldSignals] snapshot.
 *
 * `EditorInfo` is the *only* metadata every IME gets for free, and it is often
 * wrong or empty. What it reliably gives us:
 *
 *  * `inputType` — authoritative when the developer set it
 *  * `hintText` — usually the placeholder the user sees
 *  * `fieldName` — the autofill hint / XML `android:autofillHints` on API 26+
 *  * `privateImeOptions` — app-specific flags, occasionally carrying hints
 *  * `imeOptions` — including `IME_FLAG_NO_PERSONALIZED_LEARNING`, which an app
 *    sets to tell the IME "do not learn here". We treat that as authoritative.
 *  * `packageName` — which app
 *
 * Notably absent: the label, the class name, the resource id, the current text,
 * and the max length. Those come from [AccessibilityFieldProbe] when a service is
 * enabled, and their absence is exactly why detection cannot be a single check.
 */
object EditorInfoExtractor {

    /**
     * @param editorInfo the framework object; not retained after this call
     * @param textSample bounded content snapshot, or null
     * @param screenTitle window title, when the platform gave us one
     */
    fun extract(
        editorInfo: EditorInfo,
        textSample: String? = null,
        screenTitle: String? = null,
        textLength: Int = 0,
    ): FieldSignals {
        val inputType = normalizeInputType(editorInfo.inputType)
        return FieldSignals(
            packageName = editorInfo.packageName ?: "",
            inputType = inputType,
            imeOptions = editorInfo.imeOptions,
            privateImeOptions = editorInfo.privateImeOptions,
            fieldName = editorInfo.fieldName ?: parseFieldNameFromPrivateOptions(editorInfo.privateImeOptions),
            fieldId = editorInfo.fieldId,
            hintText = editorInfo.hintText?.toString(),
            label = editorInfo.label?.toString(),
            screenTitle = screenTitle,
            textSample = textSample,
            textLength = textLength,
        )
    }

    /**
     * Normalizations that matter:
     *
     *  1. `TYPE_NULL` (0) means "this field does not accept text", usually a
     *     custom canvas editor. It is reported as `null` so that callers can tell
     *     "no text entry" apart from "plain text".
     *  2. A bare variation with no class bits (class 0, variation != 0) is malformed
     *     but is passed through unchanged: it is not plain text, and silently
     *     stripping it would turn a would-be password box into an ordinary one.
     *     The detector treats an unclassifiable type as no evidence at all.
     *  3. `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` is a password whose *display* is
     *     visible. It is still a password. Detectors that only look for
     *     `TYPE_TEXT_VARIATION_PASSWORD` miss every "show password" toggle.
     */
    private fun normalizeInputType(raw: Int): Int? {
        if (raw == 0) return null
        return raw
    }

    /**
     * Some apps smuggle hints through `privateImeOptions`, e.g.
     * `com.myapp.secure=true`. We only look for a small set of known markers
     * rather than guessing at arbitrary payloads.
     */
    private fun parseFieldNameFromPrivateOptions(privateImeOptions: String?): String? {
        if (privateImeOptions.isNullOrBlank()) return null
        // Common forms: "com.google.android.inputmethod.latin.noMicrophoneKey",
        // "nm=...,fn=..." (Chrome's field name convention).
        for (part in privateImeOptions.split(',')) {
            val kv = part.trim()
            if (kv.startsWith("fn=") || kv.startsWith("fieldName=")) {
                return kv.substringAfter('=')
            }
        }
        return null
    }

    /**
     * Reconstructs the framework constant name for logging. Kept here so the core
     * module's mirrored constants stay in sync with a single source of truth at
     * use time.
     */
    fun describe(inputType: Int?): String =
        inputType?.let { InputTypeNames.describe(it) } ?: "null"
}
