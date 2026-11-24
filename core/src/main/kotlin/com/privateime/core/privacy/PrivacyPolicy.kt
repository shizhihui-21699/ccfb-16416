package com.privateime.core.privacy

import com.privateime.core.model.Category
import com.privateime.core.model.Confidence
import com.privateime.core.model.Verdict

/**
 * User-facing privacy settings, as the policy layer sees them.
 *
 * Stored as plain data so it can be round-tripped through SharedPreferences or
 * a JSON file without the core module knowing about Android.
 */
data class PrivacySettings(
    /** Master switch for cloud suggestions. Off means off everywhere. */
    val cloudSuggestionsEnabled: Boolean = true,
    /** Master switch for clipboard-history capture. */
    val clipboardSyncEnabled: Boolean = true,
    /** Learning of ordinary words. */
    val localLearningEnabled: Boolean = true,
    /** Adaptive typing statistics. */
    val adaptiveLearningEnabled: Boolean = true,
    /**
     * Whether personal data (e-mail, phone, address) may be learned locally.
     * Default off: the reported incident was caused by exactly this, and a
     * keyboard that memorises an address book by default is surprising.
     */
    val learnPiiLocally: Boolean = false,
    /**
     * How much of a *PII* field's usual comfort to keep. Secrets are always
     * locked; this only affects [Tier.GUARDED].
     */
    val guardedCandidateMode: CandidateMode = CandidateMode.LOCAL_GENERIC,
    /**
     * Extra caution for web browsers, where the IME gets the least metadata.
     * When on, a WEAK web verdict is promoted to GUARDED (never to LOCKED).
     */
    val strictWebMode: Boolean = true,
    /** Extra caution for apps in the bundled finance/bank list. */
    val strictFinanceApps: Boolean = true,
    /**
     * Per-app overrides. Value is the tier to force. A user who insists that
     * their password manager's search box is safe must be able to say so.
     */
    val appOverrides: Map<String, Tier> = emptyMap(),
    /** Fields the user explicitly marked "not sensitive" (signature keys). */
    val trustedSignatures: Set<String> = emptySet(),
    /** Fields the user explicitly marked "always private" (signature keys). */
    val alwaysPrivateSignatures: Set<String> = emptySet(),
)

/**
 * Applies [PrivacySettings] to a [Verdict] and produces the effective
 * [PrivacyDecision].
 *
 * The matrix, in one place:
 *
 * | tier     | local learning | adaptive | cloud | personal dict | clipboard | candidates |
 * |----------|---------------|----------|-------|---------------|-----------|------------|
 * | OPEN     | user switch   | user     | user  | yes           | user      | FULL       |
 * | GUARDED  | PII switch    | no       | no    | yes           | no        | LOCAL_GENERIC |
 * | LOCKED   | no            | no       | no    | no            | no        | NONE       |
 *
 * Rationale for the two surprising cells: personal-dictionary *reads* stay on in
 * GUARDED because an address field is exactly where the user wants their own
 * words; they are off in LOCKED because a credential field must not display
 * anything the user typed before, even locally, since screen capture and
 * shoulder-surfing are real. Adaptive learning is off in GUARDED because
 * keyboard statistics are a content store that is hard to purge selectively.
 */
class PrivacyPolicy(
    private val settings: PrivacySettings = PrivacySettings(),
    private val apps: AppRegistry = AppRegistry.EMPTY,
) {

    fun decide(verdict: Verdict, packageName: String): PrivacyDecision {
        val reasons = ArrayList<String>(6)

        // ---- explicit per-field user override wins over everything ----------
        if (verdict.signatureKey.isNotEmpty() && verdict.signatureKey in settings.trustedSignatures) {
            return PrivacyDecision.OPEN.copy(
                reasonCodes = listOf("user.trustedField"),
            )
        }
        if (verdict.signatureKey.isNotEmpty() && verdict.signatureKey in settings.alwaysPrivateSignatures) {
            return locked(listOf("user.alwaysPrivate", "verdict." + verdict.category.name.lowercase()))
        }

        // ---- explicit per-app override --------------------------------------
        settings.appOverrides[packageName]?.let { forced ->
            return when (forced) {
                Tier.OPEN -> PrivacyDecision.OPEN.copy(
                    reasonCodes = listOf("user.appOverrideOpen"),
                    // Cloud and clipboard master switches still apply.
                    blocked = masterBlocked(),
                )
                Tier.GUARDED -> guarded(verdict, reasons + "user.appOverrideGuarded")
                Tier.LOCKED -> locked(reasons + "user.appOverrideLocked")
            }
        }

        // ---- verdict-driven tier --------------------------------------------
        var tier = when {
            verdict.secret && verdict.confidence.atLeast(Confidence.WEAK) -> Tier.LOCKED
            verdict.pii && verdict.confidence.atLeast(Confidence.MODERATE) -> Tier.GUARDED
            else -> Tier.OPEN
        }

        // Low-confidence secrets: treat as guarded rather than open. A WEAK
        // password signal should switch off cloud, but not destroy typing.
        if (verdict.secret && verdict.confidence == Confidence.WEAK && tier == Tier.LOCKED) {
            tier = Tier.GUARDED
            reasons += "confidence.weakSecret"
        }

        // Web pages: metadata is thin, so a weak PII hit is enough to guard.
        if (settings.strictWebMode && tier == Tier.OPEN && verdict.pii &&
            verdict.confidence == Confidence.WEAK
        ) {
            tier = Tier.GUARDED
            reasons += "web.strict"
        }

        // Curated app knowledge is a tie-breaker only: it can never *lower* a
        // tier, and it can raise an OPEN field to GUARDED in a finance app.
        if (settings.strictFinanceApps && tier == Tier.OPEN && apps.isFinance(packageName)) {
            tier = Tier.GUARDED
            reasons += "app.finance"
        }

        reasons += "verdict." + verdict.category.name.lowercase()
        reasons += "confidence." + verdict.confidence.name.lowercase()

        return when (tier) {
            Tier.OPEN -> PrivacyDecision.OPEN.copy(
                blocked = masterBlocked(),
                reasonCodes = reasons,
                candidateMode = if (Sink.SUGGEST_CLOUD in masterBlocked()) {
                    CandidateMode.FULL
                } else {
                    CandidateMode.FULL
                },
            )
            Tier.GUARDED -> guarded(verdict, reasons)
            Tier.LOCKED -> locked(reasons)
        }
    }

    /** Sinks cut by the user's master switches, in every field. */
    private fun masterBlocked(): Set<Sink> = buildSet {
        if (!settings.cloudSuggestionsEnabled) add(Sink.SUGGEST_CLOUD)
        if (!settings.clipboardSyncEnabled) add(Sink.CLIPBOARD_CAPTURE)
        if (!settings.localLearningEnabled) add(Sink.LEARNING_LOCAL)
        if (!settings.adaptiveLearningEnabled) add(Sink.LEARNING_ADAPTIVE)
    }

    private fun guarded(verdict: Verdict, reasons: List<String>): PrivacyDecision {
        val blocked = buildSet {
            addAll(masterBlocked())
            add(Sink.SUGGEST_CLOUD)
            add(Sink.CLIPBOARD_CAPTURE)
            add(Sink.LEARNING_ADAPTIVE)
            add(Sink.DIAGNOSTICS_CONTEXT)
            // PII learning is opt-in.
            if (!settings.learnPiiLocally || !verdict.pii) add(Sink.LEARNING_LOCAL)
            // A degraded secret stays out of the personal dictionary's read path.
            if (verdict.secret) add(Sink.SUGGEST_PERSONAL_DICT)
        }
        return PrivacyDecision(
            tier = Tier.GUARDED,
            blocked = blocked,
            candidateMode = settings.guardedCandidateMode,
            reasonCodes = reasons,
            requiresShield = true,
        )
    }

    private fun locked(reasons: List<String>): PrivacyDecision = PrivacyDecision(
        tier = Tier.LOCKED,
        blocked = Sink.values().toSet(),
        candidateMode = CandidateMode.NONE,
        reasonCodes = reasons,
        requiresShield = true,
    )

    /**
     * Convenience for non-field sinks: may the keyboard record something the
     * user just copied? Only when no sensitive field is focused *and* the user
     * enabled clipboard sync.
     */
    fun mayCaptureClipboard(currentDecision: PrivacyDecision?): Boolean {
        if (!settings.clipboardSyncEnabled) return false
        val decision = currentDecision ?: PrivacyDecision.OPEN
        return decision.allows(Sink.CLIPBOARD_CAPTURE) && decision.tier == Tier.OPEN
    }

    /** Whether a learned phrase may be submitted to the cloud vocabulary sync. */
    fun maySyncLearnedWord(category: Category): Boolean {
        if (!settings.cloudSuggestionsEnabled) return false
        return when (category) {
            Category.NONE -> true
            Category.PII -> settings.learnPiiLocally
            else -> false
        }
    }
}
