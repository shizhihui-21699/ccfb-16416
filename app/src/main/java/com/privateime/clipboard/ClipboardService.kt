package com.privateime.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.privateime.core.privacy.ClipboardGuard
import com.privateime.core.privacy.PrivacyDecision
import com.privateime.core.privacy.Sink

/**
 * Wraps the platform clipboard so that *reading for an explicit paste* and
 * *capturing into history* are impossible to confuse.
 *
 * ## The rule
 *
 * Reading the clipboard because the user pressed "paste" is always allowed — that
 * is the user's own action, and blocking it would break the keyboard.
 *
 * Copying clipboard content into the keyboard's history (the cross-device sync
 * the user complained about) is capture, and capture goes through
 * [ClipboardGuard]: blocked when the focused field is sensitive, and blocked when
 * the payload itself is credential-shaped regardless of the field.
 *
 * Android 10+ already marks password-manager payloads with
 * `ClipDescription.EXTRA_IS_SENSITIVE`, and the platform honours it by hiding the
 * clipboard preview. We honour it too, and treat it as an absolute veto — an app
 * told us the payload is a secret and the keyboard must not overrule that.
 */
class ClipboardService(
    context: Context,
    private val history: ClipboardHistory,
) {

    private val manager: ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /**
     * Must be called when the IME sees a new clipboard primary clip. Android gives
     * IMEs a `onPrimaryClipChanged` callback, which is how a keyboard builds its
     * history; it is also the exact place the leak happens.
     *
     * @return true when the clip was captured into history
     */
    fun onPrimaryClipChanged(decision: PrivacyDecision?): Boolean {
        val clip = try {
            manager?.primaryClip
        } catch (_: Exception) {
            null
        } ?: return false

        if (isMarkedSensitive(clip)) {
            // The source app explicitly marked this a secret. Never capture.
            history.recordSuppressed("clip.sensitive-mark")
            return false
        }
        val text = extractText(clip) ?: return false
        return when (val result = ClipboardGuard.evaluate(decision, text)) {
            is ClipboardGuard.Result.Allowed -> {
                history.append(text)
                true
            }
            is ClipboardGuard.Result.Blocked -> {
                history.recordSuppressed(result.reasonCodes.joinToString(","))
                false
            }
        }
    }

    /**
     * Explicit paste: returns the clipboard text regardless of policy, because the
     * user asked for it. The caller must not persist the result.
     */
    fun readForPaste(allowed: Boolean): String? {
        if (!allowed) return null
        val clip = try {
            manager?.primaryClip
        } catch (_: Exception) {
            null
        } ?: return null
        return extractText(clip)
    }

    /** True when the current clip may be offered as a *history suggestion*. */
    fun canOfferFromHistory(decision: PrivacyDecision): Boolean =
        decision.allows(Sink.CLIPBOARD_CAPTURE)

    /**
     * Removes credential-shaped entries already in history. Called on startup and
     * after any cleanup action, because history predates the fix.
     *
     * @return how many entries were removed
     */
    fun scrubHistory(): Int = history.scrub { ClipboardGuard.isResidue(it) }

    private fun isMarkedSensitive(clip: ClipData): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            clip.description.extras?.getBoolean(ClipDescriptionIsSensitiveKey, false) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun extractText(clip: ClipData): String? {
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        val text = item.coerceToText(null)?.toString() ?: return null
        return text.takeIf { it.isNotBlank() }
    }

    private companion object {
        /**
         * `ClipDescription.EXTRA_IS_SENSITIVE`. Referenced as a literal because the
         * constant only exists from API 33 and we avoid a redundant SDK gate at
         * every use site; the string itself is stable.
         */
        const val ClipDescriptionIsSensitiveKey = "android.content.extra.IS_SENSITIVE"
    }
}
