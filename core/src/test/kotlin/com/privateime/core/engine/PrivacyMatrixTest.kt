package com.privateime.core.engine

import com.privateime.core.lexicon.InMemoryLearningStore
import com.privateime.core.model.Category
import com.privateime.core.model.FieldSignals
import com.privateime.core.model.InputTypeNames
import com.privateime.core.privacy.AppRegistry
import com.privateime.core.privacy.CandidateMode
import com.privateime.core.privacy.PrivacyDecision
import com.privateime.core.privacy.PrivacyPolicy
import com.privateime.core.privacy.PrivacySettings
import com.privateime.core.privacy.Sink
import com.privateime.core.privacy.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-style tests over the full policy matrix.
 *
 * These exist because the dangerous failures in this feature are *combinations*:
 * a tier that blocks learning but forgets the clipboard, a master switch that is
 * honoured in one tier but not another, an override that opens a field but leaves
 * the cloud sink on. Enumerating the matrix catches those; a handful of examples
 * does not.
 */
class PrivacyMatrixTest {

    private val allSettings = listOf(
        PrivacySettings(),
        PrivacySettings(cloudSuggestionsEnabled = false),
        PrivacySettings(clipboardSyncEnabled = false),
        PrivacySettings(localLearningEnabled = false),
        PrivacySettings(learnPiiLocally = true),
        PrivacySettings(strictWebMode = false),
    )

    private val verdicts = listOf(
        com.privateime.core.model.Verdict.none("k"),
        verdict(Category.PII, com.privateime.core.model.Confidence.HIGH),
        verdict(Category.PASSWORD, com.privateime.core.model.Confidence.HIGH),
        verdict(Category.PIN, com.privateime.core.model.Confidence.MODERATE),
    )

    private fun verdict(
        category: Category,
        confidence: com.privateime.core.model.Confidence,
    ) = com.privateime.core.model.Verdict(
        category = category,
        confidence = confidence,
        score = 1.0,
        reasons = emptyList(),
        signatureKey = "k",
    )

    @Test
    fun `locked always blocks every sink regardless of settings`() {
        for (settings in allSettings) {
            val decision = PrivacyPolicy(settings, AppRegistry.EMPTY)
                .decide(verdict(Category.PASSWORD, com.privateime.core.model.Confidence.HIGH), "com.example")
            assertEquals("settings=$settings", Tier.LOCKED, decision.tier)
            for (sink in Sink.values()) {
                assertFalse("settings=$settings sink=$sink", decision.allows(sink))
            }
            assertEquals(CandidateMode.NONE, decision.candidateMode)
            assertTrue(decision.requiresShield)
        }
    }

    @Test
    fun `locked always blocks cloud and capture even when the user enabled everything`() {
        val permissive = PrivacySettings(
            cloudSuggestionsEnabled = true,
            clipboardSyncEnabled = true,
            localLearningEnabled = true,
            adaptiveLearningEnabled = true,
            learnPiiLocally = true,
        )
        val decision = PrivacyPolicy(permissive, AppRegistry.EMPTY)
            .decide(verdict(Category.PAYMENT_CARD, com.privateime.core.model.Confidence.HIGH), "com.example")
        assertEquals(Tier.LOCKED, decision.tier)
        assertFalse(decision.allows(Sink.SUGGEST_CLOUD))
        assertFalse(decision.allows(Sink.CLIPBOARD_CAPTURE))
        assertFalse(decision.allows(Sink.LEARNING_LOCAL))
        assertTrue(decision.learningDisabled)
    }

    @Test
    fun `guarded never allows cloud or clipboard capture under any settings`() {
        for (settings in allSettings) {
            val decision = PrivacyPolicy(settings, AppRegistry.EMPTY)
                .decide(verdict(Category.PII, com.privateime.core.model.Confidence.HIGH), "com.example")
            // strictWebMode=false cannot unguard a HIGH-confidence PII verdict.
            assertEquals("settings=$settings", Tier.GUARDED, decision.tier)
            assertFalse("settings=$settings", decision.allows(Sink.SUGGEST_CLOUD))
            assertFalse("settings=$settings", decision.allows(Sink.CLIPBOARD_CAPTURE))
            assertFalse("settings=$settings", decision.allows(Sink.LEARNING_ADAPTIVE))
        }
    }

    @Test
    fun `guarded keeps personal dictionary readable so typing stays usable`() {
        for (settings in allSettings) {
            val decision = PrivacyPolicy(settings, AppRegistry.EMPTY)
                .decide(verdict(Category.PII, com.privateime.core.model.Confidence.HIGH), "com.example")
            assertTrue(
                "settings=$settings: losing local candidates in an e-mail field is the experience regression we refuse",
                decision.allows(Sink.SUGGEST_PERSONAL_DICT),
            )
            assertEquals(CandidateMode.LOCAL_GENERIC, decision.candidateMode)
        }
    }

    @Test
    fun `open tier honours master switches`() {
        val noCloud = PrivacyPolicy(PrivacySettings(cloudSuggestionsEnabled = false), AppRegistry.EMPTY)
            .decide(com.privateime.core.model.Verdict.none("k"), "com.example")
        assertFalse(noCloud.allows(Sink.SUGGEST_CLOUD))
        assertEquals(Tier.OPEN, noCloud.tier)

        val noClip = PrivacyPolicy(PrivacySettings(clipboardSyncEnabled = false), AppRegistry.EMPTY)
            .decide(com.privateime.core.model.Verdict.none("k"), "com.example")
        assertFalse(noClip.allows(Sink.CLIPBOARD_CAPTURE))
    }

    @Test
    fun `every sensitive verdict produces a shield`() {
        for (verdict in verdicts.filter { it.sensitive }) {
            val decision = PrivacyPolicy(PrivacySettings(), AppRegistry.EMPTY).decide(verdict, "com.example")
            assertTrue("category=${verdict.category}", decision.requiresShield)
        }
    }

    @Test
    fun `open verdict needs no shield`() {
        val decision = PrivacyPolicy(PrivacySettings(), AppRegistry.EMPTY)
            .decide(com.privateime.core.model.Verdict.none("k"), "com.example")
        assertFalse(decision.requiresShield)
        assertEquals(CandidateMode.FULL, decision.candidateMode)
    }

    // --------------------------------------------------- monotonicity property

    /**
     * The engine must never *downgrade* a field within a focus session. A user who
     * types a card number and then deletes a digit is still in the card field; the
     * shield staying on is correct, and turning it off at the first non-matching
     * character would leak the next attempt.
     */
    @Test
    fun `tier never decreases during a session`() {
        val store = InMemoryLearningStore()
        var now = 1L
        val engine = FieldPrivacyEngine(store, clock = { now++ })
        val signals = FieldSignals(
            packageName = "com.tencent.mm",
            inputType = InputTypeNames.CLASS_TEXT,
            viewIdResourceName = "com.tencent.mm:id/chat_input",
        )
        var state = engine.onFieldFocused(signals)
        assertEquals(Tier.OPEN, state.tier)

        var lastTier = state
        val inputs = listOf(
            "4111 1111 1111 1111",
            "4111 1111 1111 111",
            "",
            "hello",
            "4111111111111111",
        )
        for (input in inputs) {
            state = engine.onTextChanged(state, input)
            assertTrue(
                "tier went backwards at input=\"$input\": ${lastTier.tier} -> ${state.tier}",
                state.tier.ordinal >= lastTier.tier.ordinal,
            )
            lastTier = state
        }
    }

    @Test
    fun `guarded to locked escalation is allowed`() {
        val store = InMemoryLearningStore()
        var now = 1L
        val engine = FieldPrivacyEngine(store, clock = { now++ })
        val signals = FieldSignals(
            packageName = "com.android.chrome",
            inputType = InputTypeNames.CLASS_TEXT or InputTypeNames.TEXT_VARIATION_WEB_EDIT_TEXT,
            isWebView = true,
            maxTextLength = 19,
            viewIdResourceName = "f",
        )
        val open = engine.onFieldFocused(signals)
        val locked = engine.onTextChanged(open, "4111 1111 1111 1111")
        assertTrue(locked.tier.ordinal >= open.tier.ordinal)
        assertEquals(Tier.LOCKED, locked.tier)
    }
}
