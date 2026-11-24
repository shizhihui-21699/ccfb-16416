package com.privateime.core.engine

import com.privateime.core.cleanup.ResidueScanner
import com.privateime.core.lexicon.InMemoryLearningStore
import com.privateime.core.lexicon.LearningGate
import com.privateime.core.lexicon.LearnedWord
import com.privateime.core.model.Category
import com.privateime.core.model.FieldSignals
import com.privateime.core.model.InputTypeNames
import com.privateime.core.privacy.AppRegistry
import com.privateime.core.privacy.PrivacyPolicy
import com.privateime.core.privacy.PrivacySettings
import com.privateime.core.privacy.Tier
import com.privateime.core.text.WordTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end reproduction of the reported incident, and of its fix.
 *
 * The report: an app's password box was not declared as a password, so the
 * keyboard learned the password; it then appeared as a suggestion in a *different*
 * app. Separately, credentials already in the dictionary needed to be found and
 * removed.
 *
 * This test drives the same objects the IME drives, in the same order, and asserts
 * the user-visible outcome.
 */
class IncidentReproductionTest {

    private var now = 1_000L
    private val store = InMemoryLearningStore()
    private val policy = PrivacyPolicy(PrivacySettings(), AppRegistry.default())
    private val engine = FieldPrivacyEngine(store, policy = policy, clock = { now++ })
    private val gate = LearningGate(store) { now++ }

    /** What the IME does for each committed word. */
    private fun learn(word: String, state: FieldPrivacyEngine.State): Boolean =
        gate.remember(
            text = word,
            claimedCategory = Category.NONE,
            signals = state.signals,
            decision = state.decision,
            origin = LearnedWord.Origin.LOCAL_DICTIONARY,
        )

    @Test
    fun `the reported leak does not happen - undeclared password is not learned`() {
        // The app declares plain text and gives no hint. Only the resource id lies
        // in the usual place (et_pwd), which the IME sees via accessibility.
        val signals = FieldSignals(
            packageName = "com.victim.app",
            inputType = InputTypeNames.CLASS_TEXT,
            viewIdResourceName = "com.victim.app:id/et_pwd",
        )
        val state = engine.onFieldFocused(signals)
        assertEquals(Tier.LOCKED, state.tier)

        // The user types their password and presses space.
        val password = "Tr0ub4dor&3"
        for (token in WordTokenizer.tokenize(password)) {
            assertFalse("password must not be learned", learn(token, state))
        }
        assertTrue("dictionary must stay empty", store.allWords().isEmpty())
    }

    @Test
    fun `a genuinely unlabelled password field is caught by content and not learned`() {
        // No id, no hint, declared as plain text. Only the content betrays it.
        val signals = FieldSignals(
            packageName = "com.victim.app",
            inputType = InputTypeNames.CLASS_TEXT,
            viewIdResourceName = "com.victim.app:id/field1",
        )
        val focused = engine.onFieldFocused(signals)
        // At focus time this looks innocent.
        assertEquals(Tier.OPEN, focused.tier)

        // The user types; the IME re-checks content and escalates. Content alone is
        // deliberately capped at GUARDED (a password-shaped token is also how a
        // product code looks), but guarding is enough: learning is already off.
        val password = "Xk9#mQ2vL7p"
        val escalated = engine.onTextChanged(focused, password)
        assertEquals(Tier.GUARDED, escalated.tier)
        assertTrue(escalated.decision.learningDisabled)

        // A word committed *after* escalation is refused.
        assertFalse(learn(password, escalated))
        assertNull(store.findWord(password))
    }

    @Test
    fun `a card number pasted into a chat field is not learned`() {
        val signals = FieldSignals(
            packageName = "com.chat.app",
            inputType = InputTypeNames.CLASS_TEXT,
            viewIdResourceName = "com.chat.app:id/message_input",
            hintText = "说点什么…",
        )
        val focused = engine.onFieldFocused(signals)
        assertEquals(Tier.OPEN, focused.tier)

        // Pasting the full card number escalates before anything is learned.
        val escalated = engine.onTextChanged(focused, "4111 1111 1111 1111")
        assertEquals(Tier.LOCKED, escalated.tier)
        for (token in WordTokenizer.tokenize("4111 1111 1111 1111")) {
            assertFalse(learn(token, escalated))
        }
    }

    @Test
    fun `credentials already learned before the fix are found and removed`() {
        // A dictionary from a build that had no policy: the leak already happened.
        store.seed(
            LearnedWord("old1", "Tr0ub4dor&3", Category.PASSWORD, "com.victim.app", 3, 1, 1),
            LearnedWord("old2", "4111111111111111", Category.NONE, "com.chat.app", 1, 1, 1),
            LearnedWord("old3", "452913", Category.NONE, "com.bank.app", 2, 1, 1),
            LearnedWord("ok1", "明天见", Category.NONE, "com.chat.app", 9, 1, 1),
        )

        val report = ResidueScanner(store).scan()
        val subjects = report.findings.map { it.subject }.toSet()

        assertTrue("the old password is found", subjects.contains("Tr0ub4dor&3"))
        assertTrue("the card number is found", subjects.contains("4111111111111111"))
        assertTrue("the six-digit code is surfaced for review", subjects.contains("452913"))
        assertFalse("ordinary words are untouched", subjects.contains("明天见"))

        val removed = ResidueScanner(store).purgeAutoDeletable(report)
        assertTrue(removed >= 2)
        assertNull("the password is gone", store.findWord("Tr0ub4dor&3"))
        assertNull("the card number is gone", store.findWord("4111111111111111"))
        assertNotNull("ordinary words survive cleanup", store.findWord("明天见"))
    }

    @Test
    fun `ordinary typing in a chat app is still learned`() {
        // The fix must not turn the keyboard catatonic. This is the experience
        // requirement stated alongside the defect.
        val signals = FieldSignals(
            packageName = "com.chat.app",
            inputType = InputTypeNames.CLASS_TEXT or InputTypeNames.FLAG_AUTO_CORRECT,
            viewIdResourceName = "com.chat.app:id/chat_input",
            hintText = "说点什么…",
        )
        val state = engine.onFieldFocused(signals)
        assertEquals(Tier.OPEN, state.tier)

        assertTrue(learn("明天", state))
        assertTrue(learn("周末一起吃饭", state))
        assertEquals(2, store.allWords().size)
    }
}
