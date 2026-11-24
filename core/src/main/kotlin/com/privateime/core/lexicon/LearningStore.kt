package com.privateime.core.lexicon

import com.privateime.core.model.Category
import com.privateime.core.model.FieldSignals
import com.privateime.core.model.Verdict
import com.privateime.core.privacy.PrivacyDecision
import com.privateime.core.privacy.Sink

/**
 * One thing the keyboard has learned about the user.
 *
 * @param text the learned phrase. For a credential this is the leak, so any
 *   caller that renders it must go through [displayText].
 * @param category what kind of content it was; [Category.NONE] for ordinary words
 * @param packageName the app it was learned in, if known. `null` for words
 *   migrated from an older build that did not record provenance.
 * @param occurrences how often it was seen. Used to rank cleanup findings.
 * @param firstSeenMillis / lastSeenMillis wall clock, for the audit view
 * @param origin which learning sink produced it
 * @param riskScore filled in by the residue scanner; 0 means "not yet scanned"
 */
data class LearnedWord(
    val id: String,
    val text: String,
    val category: Category = Category.NONE,
    val packageName: String? = null,
    val occurrences: Int = 1,
    val firstSeenMillis: Long = 0L,
    val lastSeenMillis: Long = 0L,
    val origin: Origin = Origin.LOCAL_DICTIONARY,
    val riskScore: Double = 0.0,
    val riskCodes: List<String> = emptyList(),
) {
    enum class Origin {
        /** The user dictionary that drives suggestions in other apps. */
        LOCAL_DICTIONARY,

        /** Adaptive statistics: n-grams, recency lists, per-app language model. */
        ADAPTIVE_MODEL,

        /** Cloud vocabulary that was synced down or queued up. */
        CLOUD_VOCABULARY,

        /** Clipboard history. */
        CLIPBOARD_HISTORY,

        /** An entry in typing history / recent inputs. */
        TYPING_HISTORY,
    }

    val hasProvenance: Boolean get() = packageName != null

    /**
     * Renders the word for the cleanup UI.
     *
     * Credentials are shown only as a shape: the whole point is that this string
     * must not be displayed where another app could screen-capture it. Ordinary
     * words are shown in full because the user needs to recognize them to decide.
     */
    fun displayText(reveal: Boolean = false): String = when {
        reveal -> text
        category.isSecret -> mask(text)
        else -> text
    }

    /**
     * Masks a credential for display so it can be recognised without being
     * readable.
     *
     * The rule is deliberately conservative at the short end: for anything shorter
     * than [MASK_REVEAL_MIN_LENGTH] the *entire* value is masked. A 5-digit PIN
     * shown as `12•45` would expose four of five characters, which is worse than
     * useless on a screen another app can screenshot. Only long values (card
     * numbers, tokens) reveal a two-character head and tail, which is enough to
     * tell two similar entries apart without giving up the value.
     */
    private fun mask(value: String): String {
        if (value.length < MASK_REVEAL_MIN_LENGTH) {
            return "•".repeat(value.length) + " #" + distinguishingTag(value)
        }
        val head = value.take(2)
        val tail = value.takeLast(2)
        return head + "•".repeat(minOf(12, value.length - 4)) + tail + " #" + distinguishingTag(value)
    }

    /**
     * A 4-character tag derived from the whole value, so that two masked entries
     * that look alike (`41••••11`) can still be told apart in the cleanup list.
     * It is a truncated SHA-256, not a prefix of the value: it reveals nothing about
     * the characters and cannot be used to reconstruct the credential.
     */
    private fun distinguishingTag(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(4)
        for (i in 0 until 4) {
            val b = digest[i].toInt() and 0xFF
            sb.append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    private companion object {
        /** Below this length, nothing of the value itself is revealed. */
        const val MASK_REVEAL_MIN_LENGTH = 9
        val HEX = "0123456789abcdef".toCharArray()
    }
}

/**
 * A persisted record of a field's privacy verdict.
 *
 * This is what lets the engine recognise a password box on the second visit even
 * if the app presents it differently (a common anti-autofill trick is to declare
 * a plain text type on the first layout pass and flip it once the user starts
 * typing). Only [signatureKey] and the verdict are stored — never the label or
 * the text.
 */
data class FieldMemory(
    val signatureKey: String,
    val packageName: String,
    val category: Category,
    val confidence: String,
    val sticky: Boolean,
    val learnedAtMillis: Long,
    val lastSeenMillis: Long,
    val timesSeen: Int,
    /** Non-secret short label the user can recognize ("登录密码"), for the UI only. */
    val safeLabel: String? = null,
)

/**
 * Storage boundary for everything the keyboard has learned.
 *
 * Implementations live on the Android side (SQLite + SharedPreferences); the
 * core module only sees this interface, which keeps the cleaner and policy
 * testable and makes it hard for a feature to bypass the policy: there is no
 * "just add a word" path that is not [remember].
 */
interface LearningStore {
    /** All learned words, newest first. Callers must handle large results. */
    fun allWords(): List<LearnedWord>

    fun findWord(text: String): LearnedWord?

    /** Insert or bump. Must be a no-op if the decision forbids the sink. */
    fun remember(
        text: String,
        category: Category,
        packageName: String?,
        origin: LearnedWord.Origin,
        nowMillis: Long,
    ): Boolean

    /** Removes a single learned word and any derived statistics that contain it. */
    fun forget(text: String): Boolean

    /** Removes many at once; must be atomic enough that a crash does not half-purge. */
    fun forgetAll(texts: Collection<String>): Int

    /** Wipes an entire sink. Used by "clear everything" and by rotating credentials. */
    fun purgeSink(origin: LearnedWord.Origin): Int

    // ---- field memory -------------------------------------------------------

    fun fieldMemory(signatureKey: String): FieldMemory?

    fun rememberField(memory: FieldMemory)

    fun forgetField(signatureKey: String)

    fun allFieldMemories(): List<FieldMemory>
}

/**
 * Gatekeeper between the turning of keys and the store.
 *
 * Every learning path in the IME goes through [remember]. That is the single
 * choke point where the privacy decision is enforced; a feature that wants to
 * learn something cannot skip it without taking a [PrivacyDecision] apart.
 */
class LearningGate(
    private val store: LearningStore,
    private val clock: () -> Long,
) {

    /**
     * @return true when the word was stored.
     *
     * Refuses when:
     *  * the sink is blocked for this field,
     *  * the word is blank or too long to be a legitimate dictionary entry,
     *  * the word classifies as a credential (defence in depth: even if a caller
     *    passes `Category.NONE`, the content scanner gets the final say).
     */
    fun remember(
        text: String,
        claimedCategory: Category,
        signals: FieldSignals,
        decision: PrivacyDecision,
        origin: LearnedWord.Origin,
    ): Boolean {
        if (text.isBlank()) return false
        if (text.length > MAX_WORD_LENGTH) return false
        val sink = origin.sink()
        if (!decision.allows(sink)) return false

        val category = stricterOf(claimedCategory, classifyWord(text))
        if (category.isSecret) return false
        if (category.isPii && !decision.allows(Sink.LEARNING_LOCAL)) return false

        return store.remember(
            text = text,
            category = category,
            packageName = signals.packageName.ifBlank { null },
            origin = origin,
            nowMillis = clock(),
        )
    }

    /**
     * Second-chance escalation: a word that was allowed at commit time but looks
     * like a credential now (e.g. the first four digits of a card number are
     * innocuous, the sixteenth is not). Called when a field's verdict changes.
     */
    fun revokeIfCredential(text: String): Boolean {
        if (!classifyWord(text).isSecret) return false
        return store.forget(text)
    }

    private fun classifyWord(text: String): Category {
        val evidence = com.privateime.core.detect.ContentClassifier.residueEvidence(text)
        val secret = evidence.filter { it.category.isSecret }.maxByOrNull { it.weight }
        return secret?.category ?: Category.NONE
    }

    private fun stricterOf(a: Category, b: Category): Category = when {
        a.isSecret || b.isSecret -> if (a.isSecret) a else b
        a.isPii || b.isPii -> Category.PII
        else -> Category.NONE
    }

    private fun LearnedWord.Origin.sink(): Sink = when (this) {
        LearnedWord.Origin.LOCAL_DICTIONARY -> Sink.LEARNING_LOCAL
        LearnedWord.Origin.ADAPTIVE_MODEL -> Sink.LEARNING_ADAPTIVE
        LearnedWord.Origin.CLOUD_VOCABULARY -> Sink.SUGGEST_CLOUD
        LearnedWord.Origin.CLIPBOARD_HISTORY -> Sink.CLIPBOARD_CAPTURE
        LearnedWord.Origin.TYPING_HISTORY -> Sink.LEARNING_ADAPTIVE
    }

    companion object {
        /**
         * Longer than any word a keyboard should memorise. A 200-character
         * "word" is a pasted paragraph, and pasting is not learning.
         */
        const val MAX_WORD_LENGTH = 64
    }
}
