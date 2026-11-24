package com.privateime.core.cleanup

import com.privateime.core.detect.ContentClassifier
import com.privateime.core.lexicon.LearnedWord
import com.privateime.core.lexicon.LearningStore
import com.privateime.core.model.Category
import com.privateime.core.privacy.AppRegistry
import com.privateime.core.text.TextNormalizer

/**
 * Finds credentials that already made it into the dictionary, and removes them.
 *
 * This is the part the reported incident actually needs, because the leak has
 * already happened by the time the fix ships: the user's old password is sitting
 * in the user dictionary and being offered in other apps.
 *
 * ## Why a score rather than a pattern match
 *
 * The scanner sees only strings, with no field context. That forces trade-offs:
 *
 *  * A 16-digit Luhn-valid string is almost certainly a card number, and the
 *    cost of a false positive (deleting a legitimate entry) is negligible.
 *  * A 6-digit string is *plausibly* an OTP, but also a zip code, a price, a year
 *    range. Deleting it silently is wrong; surfacing it for review is right.
 *  * A random-looking 12-character token is *probably* a password, but it is
 *    also how a user's Wi-Fi key or product code looks, and they may have typed
 *    those deliberately.
 *
 * So each finding carries a [Finding.Disposition]:
 *
 *  * [AutoDelete] — safe to remove without asking (Luhn-valid card numbers,
 *    national IDs, e-mail addresses).
 *  * [Review] — show the user a masked preview and remove on confirmation.
 *  * [Keep] — recorded for the audit trail only.
 *
 * Nothing here ever displays a credential in full; [Finding.preview] masks it.
 */
class ResidueScanner(
    private val store: LearningStore,
    private val apps: AppRegistry = AppRegistry.default(),
) {

    data class Finding(
        val word: LearnedWord,
        val riskScore: Double,
        val codes: List<String>,
        val disposition: Disposition,
    ) {
        enum class Disposition { AUTO_DELETE, REVIEW, KEEP }

        /** Masked rendering; safe to put on screen. */
        val preview: String get() = word.displayText(reveal = false)

        /** Full text, for the deletion call only — never render this. */
        val subject: String get() = word.text
    }

    data class Report(
        val findings: List<Finding>,
        val scanned: Int,
        val autoDeleteCount: Int,
        val reviewCount: Int,
    ) {
        val totalAtRisk: Int get() = autoDeleteCount + reviewCount
    }

    /**
     * Scans every learned word.
     *
     * @param includePii when false, only credential-shaped strings are reported.
     *   The default is true because an e-mail address in the personal dictionary
     *   is exactly the kind of residue users complain about.
     * @param maxScan caps the work for very large dictionaries; the newest words
     *   are scanned first because recent leaks are the urgent ones.
     */
    fun scan(includePii: Boolean = true, maxScan: Int = 20_000): Report {
        val words = store.allWords().take(maxScan).sortedByDescending { it.lastSeenMillis }
        val findings = ArrayList<Finding>(16)
        for (word in words) {
            val finding = assess(word, includePii) ?: continue
            findings.add(finding)
        }
        findings.sortWith(
            compareByDescending<Finding> { it.riskScore }
                .thenByDescending { it.word.occurrences },
        )
        return Report(
            findings = findings,
            scanned = words.size,
            autoDeleteCount = findings.count { it.disposition == Finding.Disposition.AUTO_DELETE },
            reviewCount = findings.count { it.disposition == Finding.Disposition.REVIEW },
        )
    }

    /**
     * Assesses one word.
     * @return null when the word is unremarkable.
     */
    fun assess(word: LearnedWord, includePii: Boolean = true): Finding? {
        // A word that was already filed under a secret category is a leak no
        // matter what it looks like.
        val codes = ArrayList<String>(4)
        var score = 0.0

        if (word.category.isSecret) {
            codes += "category." + word.category.name.lowercase()
            score = maxOf(score, 1.0)
        }

        for (evidence in ContentClassifier.residueEvidence(word.text)) {
            if (!includePii && !evidence.category.isSecret) continue
            codes += evidence.code
            score = maxOf(score, evidence.weight)
        }

        // Provenance matters: a 6-digit string learned in a banking app is much
        // more likely an OTP than the same string learned in a shopping app. This
        // uses the same curated registry as the field policy so there is one list
        // to maintain, and it is still only a small nudge.
        if (word.packageName != null && score > 0.0 && apps.isFinance(word.packageName)) {
            score = minOf(1.0, score + 0.12)
            codes += "provenance.finance"
        }

        if (score <= 0.0) return null

        val category = when {
            "residue.card.luhn" in codes -> Category.PAYMENT_CARD
            "residue.pii.national_id" in codes -> Category.PII
            "residue.pii.email" in codes -> Category.PII
            "residue.credential.shape" in codes -> Category.PASSWORD
            word.category != Category.NONE -> word.category
            else -> Category.UNKNOWN_SECRET
        }
        val enriched = word.copy(category = category, riskScore = score, riskCodes = codes)

        val disposition = when {
            "residue.card.luhn" in codes -> Finding.Disposition.AUTO_DELETE
            "residue.pii.national_id" in codes -> Finding.Disposition.AUTO_DELETE
            "residue.pii.email" in codes -> Finding.Disposition.REVIEW
            score >= 0.9 -> Finding.Disposition.AUTO_DELETE
            score >= 0.4 -> Finding.Disposition.REVIEW
            else -> Finding.Disposition.KEEP
        }
        return Finding(enriched, score, codes, disposition)
    }

    /** Deletes every AUTO_DELETE finding. Returns the number removed. */
    fun purgeAutoDeletable(report: Report): Int {
        val subjects = report.findings
            .filter { it.disposition == Finding.Disposition.AUTO_DELETE }
            .map { it.subject }
        if (subjects.isEmpty()) return 0
        return store.forgetAll(subjects)
    }

    /** Deletes exactly the findings the user approved. */
    fun purge(approved: Collection<Finding>): Int {
        if (approved.isEmpty()) return 0
        return store.forgetAll(approved.map { it.subject })
    }

    /** Deletes every finding at or above [minScore], regardless of disposition. */
    fun purgeAbove(report: Report, minScore: Double): Int =
        purge(report.findings.filter { it.riskScore >= minScore })

    companion object {
        /**
         * Groups findings into buckets for the cleanup UI: "card numbers" is a
         * one-tap action, "possible one-time codes" needs a look.
         */
        fun groupForDisplay(report: Report): Map<String, List<Finding>> {
            val groups = LinkedHashMap<String, MutableList<Finding>>()
            for (finding in report.findings) {
                if (finding.disposition == Finding.Disposition.KEEP) continue
                val key = when {
                    "residue.card.luhn" in finding.codes -> "cards"
                    "residue.pii.national_id" in finding.codes -> "national_ids"
                    "residue.pii.email" in finding.codes -> "emails"
                    "residue.credential.shape" in finding.codes -> "tokens"
                    finding.word.category == Category.OTP -> "codes"
                    finding.word.category == Category.PIN -> "pins"
                    TextNormalizer.digitsOnly(finding.word.text).length in 4..8 -> "codes"
                    else -> "other"
                }
                groups.getOrPut(key) { ArrayList() }.add(finding)
            }
            return groups
        }
    }
}
