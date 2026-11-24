package com.privateime.core.engine

import com.privateime.core.detect.SensitivityDetector
import com.privateime.core.lexicon.FieldMemory
import com.privateime.core.lexicon.LearningStore
import com.privateime.core.model.Category
import com.privateime.core.model.Confidence
import com.privateime.core.model.FieldSignals
import com.privateime.core.model.Verdict
import com.privateime.core.privacy.AppRegistry
import com.privateime.core.privacy.PrivacyDecision
import com.privateime.core.privacy.PrivacyPolicy
import com.privateime.core.privacy.PrivacySettings
import com.privateime.core.privacy.Tier

/**
 * Ties the detector, the field memory and the policy together.
 *
 * This is the object the IME holds one instance of and asks two questions of:
 *
 *  * "[onFieldFocused]" — what should happen in this field?
 *  * "[onTextChanged]" — has the content changed the answer?
 *
 * Everything else (storage, UI, accessibility) is kept outside so the ordering
 * rules live in one readable place.
 */
class FieldPrivacyEngine(
    private val store: LearningStore,
    private val detector: SensitivityDetector = SensitivityDetector(),
    private val policy: PrivacyPolicy = PrivacyPolicy(PrivacySettings(), AppRegistry.default()),
    private val clock: () -> Long,
    private val audit: AuditLog? = null,
) {

    data class State(
        val signals: FieldSignals,
        val verdict: Verdict,
        val decision: PrivacyDecision,
    ) {
        val tier: Tier get() = decision.tier
        val locked: Boolean get() = decision.tier == Tier.LOCKED
        val guarded: Boolean get() = decision.tier == Tier.GUARDED
    }

    /**
     * Called from `onStartInput`. Synchronous and allocation-light: it runs on the
     * IME's main thread while the previous app is still waiting for the keyboard.
     */
    fun onFieldFocused(signals: FieldSignals): State {
        val memory = store.fieldMemory(signals.signatureKey)?.let { toVerdict(it, signals.signatureKey) }
        var verdict = detector.assess(signals, memory)

        // If a sticky verdict exists and the fresh assessment disagrees in the
        // weaker direction, keep the memory. Apps that flip their inputType after
        // focus rely on the IME forgetting; we don't.
        if (memory != null && memory.sticky && memory.confidence.atLeast(Confidence.MODERATE) &&
            !verdict.sticky && verdict.category.specificity < memory.category.specificity
        ) {
            verdict = memory.copy(
                reasons = verdict.reasons,
                score = maxOf(verdict.score, memory.score),
            )
        }

        val decision = policy.decide(verdict, signals.packageName)
        persistMemory(signals, verdict)
        audit?.record(
            AuditLog.Entry(
                atMillis = clock(),
                packageName = signals.packageName,
                fieldShape = signals.shapeDescription(),
                tier = decision.tier,
                category = verdict.category,
                codes = verdict.reasons.map { it.code } + decision.reasonCodes,
                transition = "focus",
            ),
        )
        return State(signals, verdict, decision)
    }

    /**
     * Called as the user types. Only ever *escalates*: a field that started OPEN
     * can become GUARDED or LOCKED when its content turns out to be a card
     * number, but a field already locked cannot be un-locked mid-session by an
     * unusual character. Downgrades only happen when focus moves to another field.
     *
     * @param textSample a bounded sample of the current field content; it is used
     *   immediately and never stored.
     */
    fun onTextChanged(previous: State, textSample: String): State {
        val signals = previous.signals.copy(
            textSample = textSample,
            textLength = textSample.length,
        )
        val escalated = detector.escalateFromContent(previous.verdict.copy(signatureKey = signals.signatureKey), signals)
        if (escalated.category == previous.verdict.category &&
            escalated.confidence == previous.verdict.confidence
        ) {
            return previous
        }
        if (escalated.confidence.rank <= previous.verdict.confidence.rank &&
            escalated.category.specificity <= previous.verdict.category.specificity
        ) {
            return previous
        }
        val decision = policy.decide(escalated, signals.packageName)
        persistMemory(signals, escalated)
        audit?.record(
            AuditLog.Entry(
                atMillis = clock(),
                packageName = signals.packageName,
                fieldShape = signals.shapeDescription(),
                tier = decision.tier,
                category = escalated.category,
                codes = escalated.reasons.map { it.code } + decision.reasonCodes,
                transition = "escalate",
            ),
        )
        return State(signals, escalated, decision)
    }

    /** Called from `onFinishInput`; clears anything that must not outlive the field. */
    fun onFieldLeft(state: State?) {
        // Nothing is retained in this object between fields on purpose. The
        // method exists so the IME has an explicit place to hook future
        // session-scoped buffers, and so the audit log records the transition.
        state ?: return
        audit?.record(
            AuditLog.Entry(
                atMillis = clock(),
                packageName = state.signals.packageName,
                fieldShape = state.signals.shapeDescription(),
                tier = state.decision.tier,
                category = state.verdict.category,
                codes = state.decision.reasonCodes,
                transition = "blur",
            ),
        )
    }

    /** Records a user override ("always private here" / "this is fine"). */
    fun onUserOverride(state: State, force: Tier?) {
        val key = state.signals.signatureKey
        if (key.isEmpty()) return
        val now = clock()
        store.rememberField(
            FieldMemory(
                signatureKey = key,
                packageName = state.signals.packageName,
                category = state.verdict.category,
                confidence = state.verdict.confidence.name,
                sticky = force != Tier.OPEN,
                learnedAtMillis = now,
                lastSeenMillis = now,
                timesSeen = 1,
            ),
        )
        audit?.record(
            AuditLog.Entry(
                atMillis = now,
                packageName = state.signals.packageName,
                fieldShape = state.signals.shapeDescription(),
                tier = force ?: state.decision.tier,
                category = state.verdict.category,
                codes = listOf("user.override"),
                transition = "override",
            ),
        )
    }

    private fun persistMemory(signals: FieldSignals, verdict: Verdict) {
        if (signals.signatureKey.isEmpty()) return
        if (!verdict.sensitive && !verdict.sticky) return
        if (verdict.confidence == Confidence.NONE) return
        val now = clock()
        val existing = store.fieldMemory(signals.signatureKey)
        store.rememberField(
            FieldMemory(
                signatureKey = signals.signatureKey,
                packageName = signals.packageName,
                category = verdict.category,
                confidence = verdict.confidence.name,
                sticky = verdict.sticky || (existing?.sticky == true),
                learnedAtMillis = existing?.learnedAtMillis ?: now,
                lastSeenMillis = now,
                timesSeen = (existing?.timesSeen ?: 0) + 1,
                safeLabel = existing?.safeLabel,
            ),
        )
    }

    private fun toVerdict(memory: FieldMemory, signatureKey: String): Verdict {
        val confidence = runCatching { Confidence.valueOf(memory.confidence) }
            .getOrDefault(Confidence.NONE)
        return Verdict(
            category = memory.category,
            confidence = confidence,
            score = if (confidence == Confidence.HIGH) 1.0 else 0.6,
            reasons = emptyList(),
            signatureKey = signatureKey,
            sticky = memory.sticky,
        )
    }
}
