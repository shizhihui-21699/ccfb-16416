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

class PrivacyPolicyTest {

    private fun verdict(
        category: Category,
        confidence: Confidence,
        score: Double = 1.0,
        key: String = "sig",
    ) = Verdict(
        category = category,
        confidence = confidence,
        score = score,
        reasons = listOf(Evidence("test", FieldSource.HINT, score, category)),
        signatureKey = key,
    )

    @Test
    fun `open field with defaults allows every sink`() {
        val d = PrivacyPolicy().decide(Verdict.none("s"), "com.example")
        assertEquals(Tier.OPEN, d.tier)
        assertEquals(CandidateMode.FULL, d.candidateMode)
        for (sink in Sink.values()) assertTrue("$sink should be allowed", d.allows(sink))
        assertFalse(d.requiresShield)
    }

    @Test
    fun `locked field blocks every sink`() {
        val d = PrivacyPolicy().decide(
            verdict(Category.PASSWORD, Confidence.HIGH),
            "com.example",
        )
        assertEquals(Tier.LOCKED, d.tier)
        assertEquals(CandidateMode.NONE, d.candidateMode)
        for (sink in Sink.values()) {
            assertFalse("$sink must be blocked in a credential field", d.allows(sink))
        }
        assertTrue(d.requiresShield)
        assertTrue(d.learningDisabled)
    }

    @Test
    fun `guarded field keeps typing usable but cuts cloud and clipboard`() {
        val d = PrivacyPolicy().decide(
            verdict(Category.PII, Confidence.HIGH),
            "com.example",
        )
        assertEquals(Tier.GUARDED, d.tier)
        assertEquals(CandidateMode.LOCAL_GENERIC, d.candidateMode)
        assertFalse(d.allows(Sink.SUGGEST_CLOUD))
        assertFalse(d.allows(Sink.CLIPBOARD_CAPTURE))
        assertFalse(d.allows(Sink.LEARNING_ADAPTIVE))
        // Local generic candidates require the personal dictionary to be
        // readable; that is the whole point of not destroying the experience.
        assertTrue(d.allows(Sink.SUGGEST_PERSONAL_DICT))
        assertFalse("pii learning is opt-in", d.allows(Sink.LEARNING_LOCAL))
    }

    @Test
    fun `weak secret is guarded not locked`() {
        val d = PrivacyPolicy().decide(
            verdict(Category.PIN, Confidence.WEAK),
            "com.example",
        )
        assertEquals(Tier.GUARDED, d.tier)
    }

    @Test
    fun `pii learning can be opted in`() {
        val policy = PrivacyPolicy(PrivacySettings(learnPiiLocally = true))
        val d = policy.decide(verdict(Category.PII, Confidence.HIGH), "com.example")
        assertEquals(Tier.GUARDED, d.tier)
        assertTrue(d.allows(Sink.LEARNING_LOCAL))
    }

    @Test
    fun `master switch disables cloud everywhere`() {
        val policy = PrivacyPolicy(PrivacySettings(cloudSuggestionsEnabled = false))
        val d = policy.decide(Verdict.none("s"), "com.example")
        assertFalse(d.allows(Sink.SUGGEST_CLOUD))
        assertEquals(Tier.OPEN, d.tier)
    }

    @Test
    fun `trusted field override opens a field the detector locked`() {
        val policy = PrivacyPolicy(
            PrivacySettings(trustedSignatures = setOf("sig")),
        )
        val d = policy.decide(verdict(Category.PASSWORD, Confidence.HIGH, key = "sig"), "com.example")
        assertEquals(Tier.OPEN, d.tier)
        assertTrue(d.allows(Sink.SUGGEST_CLOUD))
    }

    @Test
    fun `always private override locks an innocent field`() {
        val policy = PrivacyPolicy(
            PrivacySettings(alwaysPrivateSignatures = setOf("sig")),
        )
        val d = policy.decide(Verdict.none("sig"), "com.example")
        assertEquals(Tier.LOCKED, d.tier)
    }

    @Test
    fun `app override to locked wins`() {
        val policy = PrivacyPolicy(
            PrivacySettings(appOverrides = mapOf("com.secure.app" to Tier.LOCKED)),
        )
        val d = policy.decide(Verdict.none("s"), "com.secure.app")
        assertEquals(Tier.LOCKED, d.tier)
    }

    @Test
    fun `finance app raises an open field to guarded`() {
        val policy = PrivacyPolicy(PrivacySettings(), AppRegistry.default())
        val d = policy.decide(Verdict.none("s"), "com.chase.sig.android")
        assertEquals(Tier.GUARDED, d.tier)
    }

    @Test
    fun `finance app cannot lower an existing lock`() {
        val policy = PrivacyPolicy(PrivacySettings(), AppRegistry.of(setOf("com.chase.sig.android")))
        val d = policy.decide(verdict(Category.PASSWORD, Confidence.HIGH), "com.chase.sig.android")
        assertEquals(Tier.LOCKED, d.tier)
    }

    @Test
    fun `strict web mode promotes weak pii`() {
        val policy = PrivacyPolicy(PrivacySettings(strictWebMode = true))
        val d = policy.decide(verdict(Category.PII, Confidence.WEAK), "com.android.chrome")
        assertEquals(Tier.GUARDED, d.tier)
    }

    @Test
    fun `strict web mode off leaves weak pii open`() {
        val policy = PrivacyPolicy(PrivacySettings(strictWebMode = false))
        val d = policy.decide(verdict(Category.PII, Confidence.WEAK), "com.android.chrome")
        assertEquals(Tier.OPEN, d.tier)
    }

    @Test
    fun `clipboard capture requires an open field and the master switch`() {
        val policy = PrivacyPolicy()
        assertTrue(policy.mayCaptureClipboard(null))
        assertTrue(policy.mayCaptureClipboard(PrivacyDecision.OPEN))
        assertFalse(
            policy.mayCaptureClipboard(
                policy.decide(verdict(Category.PII, Confidence.HIGH), "com.example"),
            ),
        )
        val off = PrivacyPolicy(PrivacySettings(clipboardSyncEnabled = false))
        assertFalse(off.mayCaptureClipboard(null))
    }

    @Test
    fun `cloud sync refuses credentials and pii by default`() {
        val policy = PrivacyPolicy()
        assertTrue(policy.maySyncLearnedWord(Category.NONE))
        assertFalse(policy.maySyncLearnedWord(Category.PASSWORD))
        assertFalse(policy.maySyncLearnedWord(Category.PII))
    }

    @Test
    fun `reason codes are stable identifiers not prose`() {
        val d = PrivacyPolicy().decide(verdict(Category.PASSWORD, Confidence.HIGH), "com.example")
        assertTrue(d.reasonCodes.contains("verdict.password"))
        assertTrue(d.reasonCodes.contains("confidence.high"))
        assertTrue(d.reasonCodes.none { it.contains(" ") })
    }

    // ------------------------------------------------------------- the registry

    @Test
    fun `registry matches whole segments only`() {
        val registry = AppRegistry.default()
        assertTrue(registry.isFinance("com.acme.bank"))
        assertTrue(registry.isFinance("com.acme.wallet.pro"))
        assertFalse("payroll is not a payment app", registry.isFinance("com.acme.payroll"))
    }

    @Test
    fun `super apps are deliberately not in the finance list`() {
        val registry = AppRegistry.default()
        // WeChat/Alipay are banks *and* the user's main chat app. Raising every
        // field there would kill prediction in the busiest app on the phone.
        assertFalse(registry.isFinance("com.tencent.mm"))
        assertFalse(registry.isFinance("com.eg.android.AlipayGphone"))
    }
}
