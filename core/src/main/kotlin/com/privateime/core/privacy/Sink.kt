package com.privateime.core.privacy

/**
 * Every place typed text can leave the editor.
 *
 * The list is deliberately *sink-shaped* rather than feature-shaped: a product
 * feature ("smart replies") may write to several sinks, and the policy must be
 * able to cut them individually when a field is sensitive without disabling the
 * feature everywhere.
 */
enum class Sink {
    /**
     * The on-device user dictionary: words the keyboard offers in *other* apps.
     * This is the sink that produced the reported bug — a password typed into a
     * mislabelled field became a suggestion in a chat app.
     */
    LEARNING_LOCAL,

    /**
     * The adaptive language model (typing-history statistics used to reorder
     * candidates). It is local, but it can reconstruct typed strings over time,
     * so it is treated as a store of user content, not just counts.
     */
    LEARNING_ADAPTIVE,

    /** Cloud next-word / completion service. Leaves the device. */
    SUGGEST_CLOUD,

    /**
     * Reading the personal dictionary while composing candidates in this field.
     * Blocking it means the user's own learned words are not offered *here*
     * (important: a leaked password must not be suggested back into the very
     * field where the attacker is watching).
     */
    SUGGEST_PERSONAL_DICT,

    /**
     * Clipboard capture: copying text into the cross-device clipboard history.
     * Reading the clipboard for an explicit user paste is *not* this sink and is
     * never blocked.
     */
    CLIPBOARD_CAPTURE,

    /** Crash/diagnostics uploads that may include the last typed context. */
    DIAGNOSTICS_CONTEXT,
}

/**
 * How much the field must be locked down.
 *
 * The tiers exist because "when in doubt, disable prediction" is what makes a
 * keyboard unusable. [GUARDED] keeps the field productive (no cloud, no
 * clipboard capture, local-only candidates) while [LOCKED] degrades to a plain
 * key-pad because the content is a credential.
 */
enum class Tier {
    /** Ordinary text. All sinks allowed (subject to the user's own switches). */
    OPEN,

    /**
     * Personal data that is not a credential. Cloud and clipboard stay off;
     * locally learned words may still be offered if the user opted in.
     */
    GUARDED,

    /**
     * Credentials: passwords, PINs, one-time codes, card numbers. Nothing is
     * recorded, nothing is uploaded, and no personal vocabulary is offered.
     */
    LOCKED,
    ;

    companion object {
        fun of(verdictTier: Boolean): Tier = if (verdictTier) LOCKED else OPEN
    }
}

/** What the candidate strip should show. */
enum class CandidateMode {
    /** Everything the product normally offers. */
    FULL,

    /**
     * Candidates computed on-device from the current word only (spelling,
     * capitalization, script conversion) — no user history, no network. This is
     * what keeps an e-mail or address field usable in [Tier.GUARDED].
     */
    LOCAL_GENERIC,

    /**
     * No candidates. Used for credentials, where even a completion for the
     * current word is either useless (a password is not a word) or a leak (a
     * one-time code is not completed, it is typed).
     */
    NONE,
}

/**
 * The verdict of the privacy layer for one field: which sinks are cut, why, and
 * what the UI should show.
 *
 * @param tier the effective tier after user settings and per-app overrides
 * @param blocked the sinks that must not be written to or read from in this field
 * @param candidateMode what the keyboard should render above the keys
 * @param reasonCodes stable identifiers for the UI's localized explanation
 * @param requiresShield true when the UI must show the visible privacy indicator
 */
data class PrivacyDecision(
    val tier: Tier,
    val blocked: Set<Sink>,
    val candidateMode: CandidateMode,
    val reasonCodes: List<String>,
    val requiresShield: Boolean,
) {
    fun allows(sink: Sink): Boolean = sink !in blocked

    /** True when this field must not feed any learning sink at all. */
    val learningDisabled: Boolean
        get() = Sink.LEARNING_LOCAL in blocked && Sink.LEARNING_ADAPTIVE in blocked

    companion object {
        val OPEN = PrivacyDecision(
            tier = Tier.OPEN,
            blocked = emptySet(),
            candidateMode = CandidateMode.FULL,
            reasonCodes = listOf("policy.open"),
            requiresShield = false,
        )
    }
}
