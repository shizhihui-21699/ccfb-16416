package com.privateime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.privateime.core.model.FieldSignals
import com.privateime.input.AccessibilityFieldProbe
import com.privateime.input.EditorInfoExtractor

/**
 * Collects the [FieldSignals] snapshot for the focused editor.
 *
 * Split from the IME service so the ordering is explicit and testable:
 *
 *  1. Start from `EditorInfo` — always available, sometimes wrong.
 *  2. Ask the accessibility probe for labels / ids / `isPassword` — only if the
 *     user enabled the companion service and only for the app the IME is in.
 *  3. Read a bounded content sample **through the `InputConnection`**, never by
 *     keeping a copy of the text: the sample is used during this call and dropped.
 *
 * Each step can fail without failing the others. A keyboard that crashes or stalls
 * in `onStartInput` is unusable, so every external call here is defensively
 * wrapped and bounded in cost.
 */
class SignalCollector {

    data class Result(
        val signals: FieldSignals,
        /** True when accessibility enriched the snapshot. */
        val accessibilityUsed: Boolean,
        /** True when a content sample was available. */
        val hasTextSample: Boolean,
    )

    /**
     * @param editorInfo the framework metadata for the field that just gained focus
     * @param textSample bounded content sample already read by the caller, or null
     * @param screenTitle window/page title when known
     * @param textLength current length of the field content
     */
    fun collect(
        editorInfo: EditorInfo,
        textSample: String?,
        screenTitle: String?,
        textLength: Int,
    ): Result {
        val base = EditorInfoExtractor.extract(
            editorInfo = editorInfo,
            textSample = textSample,
            screenTitle = screenTitle,
            textLength = textLength,
        )

        val packageName = base.packageName
        AccessibilityFieldProbe.expectPackage(packageName)
        val probed = if (AccessibilityFieldProbe.serviceConnected && packageName.isNotEmpty()) {
            try {
                AccessibilityFieldProbe.probe(packageName)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        val signals = probed?.applyTo(base) ?: base

        // A WebView that reports web edit text but whose class is missing is still
        // a web field; make that explicit so page-level rules apply.
        val declaredType = signals.inputType
        val enriched = if (!signals.isWebView && declaredType != null) {
            val isWeb = (declaredType and InputType.TYPE_MASK_VARIATION) ==
                InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
            if (isWeb) signals.copy(isWebView = true) else signals
        } else {
            signals
        }

        return Result(
            signals = enriched,
            accessibilityUsed = probed != null,
            hasTextSample = !enriched.textSample.isNullOrEmpty(),
        )
    }

    /**
     * Reads a bounded content sample from the editor.
     *
     * Only called after focus is established. `getSurroundingText` exists from API
     * 31; on older platforms `getExtractedText` is the only option, so both paths
     * are present and the newer one is guarded by an SDK check rather than a
     * try/catch (a `NoSuchMethodError` on a missing default method is not always
     * catchable at the call site on some ART versions).
     *
     * @return the sample, or null when unavailable or empty
     */
    fun readSample(connection: android.view.inputmethod.InputConnection?, maxChars: Int = 128): String? {
        if (connection == null) return null
        return try {
            val surrounding = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                connection.getSurroundingText(0, maxChars, 0)?.text
            } else {
                null
            }
            if (!surrounding.isNullOrEmpty()) {
                surrounding.take(maxChars).toString()
            } else {
                val extracted = connection.getExtractedText(
                    android.view.inputmethod.ExtractedTextRequest().apply {
                        this.hintMaxChars = 0
                        this.hintMaxLines = 0
                    },
                    0,
                )
                extracted?.text?.take(maxChars)?.toString()?.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }
    }
}
