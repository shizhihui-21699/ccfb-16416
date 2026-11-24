package com.privateime.core.privacy

import com.privateime.core.detect.ContentClassifier

/**
 * Decides whether copied text may enter clipboard history.
 *
 * Two independent questions, and conflating them is a common mistake:
 *
 *  1. **Is the field sensitive?** Copying out of a password manager's field must
 *     not create a history entry even if the text is a URL.
 *  2. **Does the text itself look like a credential?** A card number copied out
 *     of a chat message is just as dangerous in history as one copied out of a
 *     payment form.
 *
 * Either one is sufficient to refuse. The check is cheap and runs on copy, not on
 * every keystroke.
 */
object ClipboardGuard {

    sealed class Result {
        data object Allowed : Result()
        data class Blocked(val reasonCodes: List<String>) : Result()
    }

    /**
     * @param decision the current field's decision, or null when no field is focused
     * @param text the copied text
     */
    fun evaluate(decision: PrivacyDecision?, text: String?): Result {
        val reasons = ArrayList<String>(3)
        if (decision != null && !decision.allows(Sink.CLIPBOARD_CAPTURE)) {
            reasons += "field.blocked." + decision.tier.name.lowercase()
        }
        if (text != null && ContentClassifier.residueEvidence(text).any { it.category.isSecret }) {
            reasons += "content.credentialShaped"
        }
        return if (reasons.isEmpty()) Result.Allowed else Result.Blocked(reasons)
    }

    /**
     * True when the text must not persist in clipboard *history* — either because
     * it is a credential, or because it is a national ID, which is as damaging to
     * leave lying around and has no legitimate reason to be in a copy history.
     *
     * Ordinary PII (an e-mail address, a phone number) is deliberately **not**
     * residue here: people copy their own address constantly, and scrubbing history
     * on every such copy would make the history feature useless. That kind of
     * content is still flagged by the dictionary scanner, which is a review step
     * rather than an automatic deletion.
     */
    fun isResidue(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return ContentClassifier.residueEvidence(text).any { evidence ->
            evidence.category.isSecret || evidence.code == "residue.pii.national_id"
        }
    }
}
