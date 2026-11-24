package com.privateime.core.cleanup

import com.privateime.core.lexicon.InMemoryLearningStore
import com.privateime.core.lexicon.LearnedWord
import com.privateime.core.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The residue scanner is the answer to "已经学进去的敏感词要能找出来清理".
 * These tests use the kinds of strings that actually end up in a dictionary after
 * a leak, and assert both the finding *and* the disposition, because a cleanup
 * that deletes a user's legitimate words is its own incident.
 */
class ResidueScannerTest {

    private val clock = { 1_700_000_000_000L }

    private fun word(
        text: String,
        category: Category = Category.NONE,
        pkg: String? = "com.example",
        occurrences: Int = 1,
    ) = LearnedWord(
        id = text.hashCode().toString(),
        text = text,
        category = category,
        packageName = pkg,
        occurrences = occurrences,
        firstSeenMillis = clock(),
        lastSeenMillis = clock(),
    )

    @Test
    fun `luhn valid card is auto deleted`() {
        val store = InMemoryLearningStore(listOf(word("4111111111111111", pkg = "com.chrome")))
        val report = ResidueScanner(store).scan()
        assertEquals(1, report.autoDeleteCount)
        val finding = report.findings.single()
        assertEquals(Category.PAYMENT_CARD, finding.word.category)
        assertTrue("4111111111111111" !in finding.preview)
    }

    @Test
    fun `card with spaces and dashes is detected`() {
        val store = InMemoryLearningStore(
            listOf(
                word("4111 1111 1111 1111"),
                word("4111-1111-1111-1111"),
            ),
        )
        val report = ResidueScanner(store).scan()
        assertEquals(2, report.autoDeleteCount)
    }

    @Test
    fun `six digit code is surfaced for review not silently deleted`() {
        // 6 digits is genuinely ambiguous: OTP, zip, price, year range. It must be
        // shown to the user, not auto-deleted, or we start eating legitimate words.
        val store = InMemoryLearningStore(listOf(word("482913")))
        val report = ResidueScanner(store).scan()
        assertEquals(0, report.autoDeleteCount)
        assertEquals(1, report.reviewCount)
    }

    @Test
    fun `word that was already categorised as a secret is a finding`() {
        // Data from a build that leaked before the fix: the category is the proof.
        val store = InMemoryLearningStore(
            listOf(word("MyP@ssw0rd", category = Category.PASSWORD, pkg = "com.bank")),
        )
        val report = ResidueScanner(store).scan()
        val finding = report.findings.single()
        assertEquals(1.0, finding.riskScore, 0.001)
        assertEquals(ResidueScanner.Finding.Disposition.AUTO_DELETE, finding.disposition)
    }

    @Test
    fun `national id is auto deleted`() {
        // 11010519491231002X — a valid-shaped CN resident id.
        val store = InMemoryLearningStore(listOf(word("11010519491231002X")))
        val report = ResidueScanner(store).scan()
        val finding = report.findings.single()
        assertTrue(finding.codes.contains("residue.pii.national_id"))
        assertEquals(ResidueScanner.Finding.Disposition.AUTO_DELETE, finding.disposition)
    }

    @Test
    fun `email residue is offered for review`() {
        val store = InMemoryLearningStore(listOf(word("someone@example.com")))
        val report = ResidueScanner(store).scan()
        val finding = report.findings.single()
        assertEquals(ResidueScanner.Finding.Disposition.REVIEW, finding.disposition)
    }

    @Test
    fun `ordinary words are not findings`() {
        val store = InMemoryLearningStore(
            listOf(
                word("你好"),
                word("周末一起吃饭"),
                word("tomorrow"),
                word("2024"),
            ),
        )
        val report = ResidueScanner(store).scan()
        assertTrue(report.findings.isEmpty())
        assertEquals(0, report.totalAtRisk)
    }

    @Test
    fun `short numeric phrases below four digits are ignored`() {
        val store = InMemoryLearningStore(listOf(word("42"), word("123")))
        val report = ResidueScanner(store).scan()
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun `credential shaped token is reviewed and masked`() {
        val store = InMemoryLearningStore(listOf(word("Xk9#mQ2\$vL7p")))
        val report = ResidueScanner(store).scan(includePii = false)
        val finding = report.findings.single()
        assertEquals(Category.PASSWORD, finding.word.category)
        assertEquals(ResidueScanner.Finding.Disposition.REVIEW, finding.disposition)
        assertFalse(finding.preview.contains("mQ2"))
    }

    @Test
    fun `brand names are not mistaken for credentials`() {
        val store = InMemoryLearningStore(
            listOf(word("iPhone"), word("PayPal"), word("iOS18"), word("HelloWorld")),
        )
        val report = ResidueScanner(store).scan()
        // HelloWorld is two classes only (upper+lower) — not credential-shaped.
        assertTrue("brands must survive: ${report.findings.map { it.subject }}", report.findings.isEmpty())
    }

    @Test
    fun `pii scan can be excluded`() {
        val store = InMemoryLearningStore(
            listOf(word("someone@example.com"), word("4111111111111111")),
        )
        val report = ResidueScanner(store).scan(includePii = false)
        assertEquals(1, report.totalAtRisk)
    }

    @Test
    fun `purge removes only auto deletable`() {
        val store = InMemoryLearningStore(
            listOf(
                word("4111111111111111"),
                word("482913"),
                word("你好"),
            ),
        )
        val scanner = ResidueScanner(store)
        val report = scanner.scan()
        val removed = scanner.purgeAutoDeletable(report)
        assertEquals(1, removed)
        assertNull(store.findWord("4111111111111111"))
        assertNotNull("review items survive until approved", store.findWord("482913"))
        assertNotNull(store.findWord("你好"))
    }

    @Test
    fun `purge approved removes exactly those`() {
        val store = InMemoryLearningStore(
            listOf(word("4111111111111111"), word("482913"), word("schönen")),
        )
        val scanner = ResidueScanner(store)
        val report = scanner.scan()
        val approved = report.findings.filter { it.word.text == "482913" }
        assertEquals(1, scanner.purge(approved))
        assertNull(store.findWord("482913"))
        assertNotNull(store.findWord("4111111111111111"))
        assertNotNull(store.findWord("schönen"))
    }

    @Test
    fun `findings sort by risk then frequency`() {
        val store = InMemoryLearningStore(
            listOf(
                word("482913", occurrences = 10),
                word("4111111111111111", occurrences = 1),
                word("someone@example.com", occurrences = 5),
            ),
        )
        val report = ResidueScanner(store).scan()
        assertEquals("4111111111111111", report.findings.first().subject)
    }

    @Test
    fun `grouping makes one-tap cleanup possible`() {
        val store = InMemoryLearningStore(
            listOf(
                word("4111111111111111"),
                word("5500005555555559"),
                word("482913"),
                word("someone@example.com"),
            ),
        )
        val groups = ResidueScanner.groupForDisplay(ResidueScanner(store).scan())
        assertEquals(2, groups["cards"]?.size)
        assertTrue(groups.containsKey("emails"))
        assertTrue(groups.containsKey("codes"))
    }

    @Test
    fun `finance provenance raises risk`() {
        val bank = ResidueScanner(
            InMemoryLearningStore(listOf(word("482913", pkg = "com.chase.sig.android"))),
        ).scan().findings.single()
        val shop = ResidueScanner(
            InMemoryLearningStore(listOf(word("482913", pkg = "com.shopping"))),
        ).scan().findings.single()
        assertTrue(bank.riskScore > shop.riskScore)
        assertTrue(bank.codes.contains("provenance.finance"))
    }

    @Test
    fun `mask never reveals the whole credential`() {
        val store = InMemoryLearningStore(listOf(word("4111 1111 1111 1111")))
        val finding = ResidueScanner(store).scan().findings.single()
        assertFalse(finding.preview.contains("1111 1111"))
        assertTrue(finding.preview.contains("4111".take(2)))
    }
}
