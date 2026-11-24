package com.privateime.core.lexicon

import com.privateime.core.model.Category
import com.privateime.core.model.FieldSignals
import com.privateime.core.privacy.CandidateMode
import com.privateime.core.privacy.PrivacyDecision
import com.privateime.core.privacy.Sink
import com.privateime.core.privacy.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningGateTest {

    private var now = 1_700_000_000_000L
    private val store = InMemoryLearningStore()
    private val gate = LearningGate(store) { now++ }

    private val open = PrivacyDecision.OPEN
    private val locked = PrivacyDecision(
        tier = Tier.LOCKED,
        blocked = Sink.values().toSet(),
        candidateMode = CandidateMode.NONE,
        reasonCodes = listOf("test"),
        requiresShield = true,
    )
    private val guardedNoPii = PrivacyDecision(
        tier = Tier.GUARDED,
        blocked = setOf(Sink.LEARNING_LOCAL, Sink.LEARNING_ADAPTIVE, Sink.SUGGEST_CLOUD, Sink.CLIPBOARD_CAPTURE),
        candidateMode = CandidateMode.LOCAL_GENERIC,
        reasonCodes = listOf("test"),
        requiresShield = true,
    )
    private val guardedPii = guardedNoPii.copy(blocked = guardedNoPii.blocked - Sink.LEARNING_LOCAL)

    private fun signals(pkg: String = "com.example") = FieldSignals(packageName = pkg)

    @Test
    fun `ordinary word is learned in an open field`() {
        assertTrue(
            gate.remember("明天", Category.NONE, signals(), open, LearnedWord.Origin.LOCAL_DICTIONARY),
        )
        assertNotNull(store.findWord("明天"))
        assertEquals("com.example", store.findWord("明天")?.packageName)
    }

    @Test
    fun `nothing is learned in a locked field`() {
        assertFalse(
            gate.remember("hunter2", Category.NONE, signals(), locked, LearnedWord.Origin.LOCAL_DICTIONARY),
        )
        assertTrue(store.allWords().isEmpty())
    }

    @Test
    fun `nothing is learned in a guarded field when learning is blocked`() {
        assertFalse(
            gate.remember("someone@example.com", Category.NONE, signals(), guardedNoPii, LearnedWord.Origin.LOCAL_DICTIONARY),
        )
        assertTrue(
            "opt-in pii learning should work",
            gate.remember("someone@example.com", Category.NONE, signals(), guardedPii, LearnedWord.Origin.LOCAL_DICTIONARY),
        )
    }

    @Test
    fun `credential-shaped word is refused even if the caller claims it is ordinary`() {
        // Defence in depth: a feature bug that passes Category.NONE must not be
        // able to put a card number into the dictionary.
        assertFalse(
            gate.remember("4111111111111111", Category.NONE, signals(), open, LearnedWord.Origin.LOCAL_DICTIONARY),
        )
        assertTrue(store.allWords().isEmpty())
    }

    @Test
    fun `claimed secret category is honoured`() {
        assertFalse(
            gate.remember("mypassword", Category.PASSWORD, signals(), open, LearnedWord.Origin.LOCAL_DICTIONARY),
        )
    }

    @Test
    fun `overlong strings are not learned`() {
        val paragraph = "这是一段很长的文字".repeat(20)
        assertFalse(
            gate.remember(paragraph, Category.NONE, signals(), open, LearnedWord.Origin.LOCAL_DICTIONARY),
        )
    }

    @Test
    fun `blank is not learned`() {
        assertFalse(gate.remember("   ", Category.NONE, signals(), open, LearnedWord.Origin.LOCAL_DICTIONARY))
    }

    @Test
    fun `clipboard origin respects the clipboard sink`() {
        val noClip = PrivacyDecision.OPEN.copy(blocked = setOf(Sink.CLIPBOARD_CAPTURE))
        assertFalse(
            gate.remember("复制的内容", Category.NONE, signals(), noClip, LearnedWord.Origin.CLIPBOARD_HISTORY),
        )
        assertTrue(
            gate.remember("复制的内容", Category.NONE, signals(), open, LearnedWord.Origin.CLIPBOARD_HISTORY),
        )
    }

    @Test
    fun `cloud origin respects the cloud sink`() {
        val noCloud = com.privateime.core.privacy.PrivacyDecision.OPEN.copy(blocked = setOf(Sink.SUGGEST_CLOUD))
        assertFalse(
            gate.remember("word", Category.NONE, signals(), noCloud, LearnedWord.Origin.CLOUD_VOCABULARY),
        )
    }

    @Test
    fun `revoke removes a word that turned out to be a credential`() {
        // A dictionary entry left over from an older build that had no policy.
        store.seed(
            LearnedWord(
                id = "legacy",
                text = "4111111111111111",
                packageName = "com.old.bank",
                firstSeenMillis = 1L,
                lastSeenMillis = 1L,
            ),
        )
        assertNotNull(store.findWord("4111111111111111"))
        assertTrue(gate.revokeIfCredential("4111111111111111"))
        assertNull(store.findWord("4111111111111111"))
        // A word that is not a credential is untouched.
        assertFalse(gate.revokeIfCredential("你好"))
    }

    @Test
    fun `repeated learning bumps occurrences and keeps provenance`() {
        gate.remember("thanks", Category.NONE, signals("com.a"), open, LearnedWord.Origin.LOCAL_DICTIONARY)
        gate.remember("thanks", Category.NONE, signals("com.b"), open, LearnedWord.Origin.LOCAL_DICTIONARY)
        val word = store.findWord("thanks")!!
        assertEquals(2, word.occurrences)
        assertEquals("com.a", word.packageName)
    }

    @Test
    fun `pii word keeps its category for later cleanup`() {
        gate.remember("someone@example.com", Category.PII, signals(), guardedPii, LearnedWord.Origin.LOCAL_DICTIONARY)
        assertEquals(Category.PII, store.findWord("someone@example.com")?.category)
    }

    @Test
    fun `gate cannot write when the store is asked to forget`() {
        gate.remember("hello", Category.NONE, signals(), open, LearnedWord.Origin.LOCAL_DICTIONARY)
        assertTrue(store.forget("hello"))
        assertNull(store.findWord("hello"))
    }
}
