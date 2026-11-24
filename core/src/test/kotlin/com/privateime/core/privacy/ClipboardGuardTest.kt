package com.privateime.core.privacy

import com.privateime.core.model.Category
import com.privateime.core.model.Confidence
import com.privateime.core.model.Evidence
import com.privateime.core.model.FieldSource
import com.privateime.core.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clipboard guard must separate two questions that look alike:
 *
 *  1. "may we *capture* this into history?"
 *  2. "does this payload look like a secret regardless of where it was copied?"
 *
 * Either is enough to refuse. Getting this wrong in the permissive direction
 * recreates the original bug; getting it wrong in the restrictive direction makes
 * copy/paste fail, which users notice immediately.
 */
class ClipboardGuardTest {

    private fun lockedField() = PrivacyDecision(
        tier = Tier.LOCKED,
        blocked = Sink.values().toSet(),
        candidateMode = CandidateMode.NONE,
        reasonCodes = listOf("test"),
        requiresShield = true,
    )

    private fun guardedField() = PrivacyDecision(
        tier = Tier.GUARDED,
        blocked = setOf(Sink.CLIPBOARD_CAPTURE, Sink.SUGGEST_CLOUD),
        candidateMode = CandidateMode.LOCAL_GENERIC,
        reasonCodes = listOf("test"),
        requiresShield = true,
    )

    @Test
    fun `ordinary text in an open field is allowed`() {
        val result = ClipboardGuard.evaluate(PrivacyDecision.OPEN, "明天见面")
        assertTrue(result is ClipboardGuard.Result.Allowed)
    }

    @Test
    fun `capture from a locked field is blocked`() {
        val result = ClipboardGuard.evaluate(lockedField(), "明天见面")
        assertTrue(result is ClipboardGuard.Result.Blocked)
        assertTrue((result as ClipboardGuard.Result.Blocked).reasonCodes.contains("field.blocked.locked"))
    }

    @Test
    fun `capture from a guarded field is blocked`() {
        val result = ClipboardGuard.evaluate(guardedField(), "some text")
        assertTrue(result is ClipboardGuard.Result.Blocked)
    }

    @Test
    fun `card number is blocked even in an open field`() {
        val result = ClipboardGuard.evaluate(PrivacyDecision.OPEN, "4111111111111111")
        assertTrue(result is ClipboardGuard.Result.Blocked)
        assertTrue((result as ClipboardGuard.Result.Blocked).reasonCodes.contains("content.credentialShaped"))
    }

    @Test
    fun `no focused field still applies the content check`() {
        val allowed = ClipboardGuard.evaluate(null, "hello")
        assertTrue(allowed is ClipboardGuard.Result.Allowed)
        val blocked = ClipboardGuard.evaluate(null, "4111111111111111")
        assertTrue(blocked is ClipboardGuard.Result.Blocked)
    }

    @Test
    fun `null text with an open field is allowed`() {
        assertTrue(ClipboardGuard.evaluate(PrivacyDecision.OPEN, null) is ClipboardGuard.Result.Allowed)
    }

    // ------------------------------------------------------------------ residue

    @Test
    fun `card, national id and credential-shaped token are residue`() {
        assertTrue(ClipboardGuard.isResidue("4111111111111111"))
        assertTrue(ClipboardGuard.isResidue("11010519491231002X"))
        assertTrue(ClipboardGuard.isResidue("Xk9#mQ2vL7p"))
    }

    @Test
    fun `ordinary copied text is not residue`() {
        assertFalse(ClipboardGuard.isResidue("明天下午三点开会"))
        assertFalse(ClipboardGuard.isResidue("https://example.com/article"))
        assertFalse(ClipboardGuard.isResidue(""))
        assertFalse(ClipboardGuard.isResidue(null))
    }

    @Test
    fun `a short number is not residue`() {
        // "2024" and "123" are far more likely to be years and quantities than
        // credentials; flagging them would scrub ordinary history.
        assertFalse(ClipboardGuard.isResidue("2024"))
        assertFalse(ClipboardGuard.isResidue("123"))
    }

    @Test
    fun `plain pii is not clipboard residue`() {
        // People copy their own address and phone constantly. Scrubbing history on
        // every such copy would make the history feature useless, so ordinary PII is
        // left to the dictionary review step instead.
        assertFalse(ClipboardGuard.isResidue("someone@example.com"))
        val result = ClipboardGuard.evaluate(PrivacyDecision.OPEN, "someone@example.com")
        assertTrue("email alone should not block an explicit copy", result is ClipboardGuard.Result.Allowed)
    }
}
