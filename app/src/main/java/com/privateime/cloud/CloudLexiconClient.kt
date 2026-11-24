package com.privateime.cloud

import com.privateime.core.detect.ContentClassifier
import com.privateime.core.model.Category
import com.privateime.core.model.FieldSignals
import com.privateime.core.privacy.PrivacyDecision
import com.privateime.core.privacy.Sink
import com.privateime.core.text.TextNormalizer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single door to the network for everything that involves typed text.
 *
 * Every call site goes through [requestSuggestions] or [queueVocabularySync],
 * both of which check the privacy decision *before* building a request. There is
 * deliberately no "send raw payload" API, so a future feature cannot accidentally
 * bypass the policy: it has to come through this gate and therefore through
 * [PrivacyDecision].
 *
 * ## What a request may contain
 *
 * Even in an OPEN field the client sends only:
 *  * the current composing prefix (what the user is typing right now),
 *  * a locale and a device-independent session id,
 *  * never the field content, the package name, the surrounding text, or history.
 *
 * In GUARDED and LOCKED fields it sends nothing at all — not even a health check
 * keyed to the field, because a request timed at the moment a card number is
 * being typed is itself a signal.
 */
object CloudLexiconClient {

    /** The transport. Swapped for a fake in tests and for a no-op in offline builds. */
    interface Transport {
        /** @return completion candidates for [prefix], or empty on failure. */
        fun fetchCompletions(prefix: String, locale: String): List<String>

        /** Fire-and-forget vocabulary upload. */
        fun uploadVocabulary(words: List<String>, locale: String): Boolean
    }

    object NoopTransport : Transport {
        override fun fetchCompletions(prefix: String, locale: String): List<String> = emptyList()
        override fun uploadVocabulary(words: List<String>, locale: String): Boolean = false
    }

    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cloud-lexicon").apply { isDaemon = true }
    }

    @Volatile
    var transport: Transport = NoopTransport

    /** Set when the user or the OS (data saver, no network) disables uploads. */
    private val inFlight = AtomicBoolean(false)

    /**
     * Requests cloud completions for the current composing prefix.
     *
     * @param decision the *current field's* decision; if cloud is blocked this
     *   returns immediately without touching the network or the executor
     * @param prefix what the user has typed into the composing region. Bounded.
     * @param onResult invoked on the calling thread's looper via the supplied
     *   executor; an empty list means "no cloud suggestions available"
     */
    fun requestSuggestions(
        decision: PrivacyDecision,
        signals: FieldSignals,
        prefix: String,
        locale: String,
        callbackExecutor: Executor,
        onResult: (List<String>) -> Unit,
    ) {
        if (!decision.allows(Sink.SUGGEST_CLOUD)) {
            onResult(emptyList())
            return
        }
        if (prefix.isEmpty() || prefix.length > MAX_PREFIX) {
            onResult(emptyList())
            return
        }
        // Guard against a PII-typed prefix slipping through in an OPEN field: the
        // content classifier is the last check before bytes leave the device.
        val credentialShaped = ContentClassifier.classify(
            text = prefix,
            numericField = signals.numericClass,
            maxTextLength = signals.maxTextLength,
        ).any { it.category.isSecret }
        if (credentialShaped) {
            onResult(emptyList())
            return
        }
        if (!inFlight.compareAndSet(false, true)) {
            onResult(emptyList())
            return
        }
        executor.execute {
            val result = try {
                transport.fetchCompletions(prefix, locale)
            } catch (_: Exception) {
                emptyList()
            } finally {
                inFlight.set(false)
            }
            callbackExecutor.execute { onResult(result.filter { it.isNotBlank() }.take(MAX_RESULTS)) }
        }
    }

    /**
     * Queues ordinary learned words for cloud vocabulary sync.
     *
     * @return true when a sync was actually scheduled. Refuses credentials
     *   outright; PII only if the caller already established the user's opt-in
     *   (the policy's `maySyncLearnedWord`).
     */
    fun queueVocabularySync(
        decision: PrivacyDecision,
        words: List<String>,
        categories: Map<String, Category>,
        locale: String,
    ): Boolean {
        if (!decision.allows(Sink.SUGGEST_CLOUD)) return false
        val safe = words.filter { word ->
            val category = categories[word] ?: Category.NONE
            !category.isSecret && isClean(word)
        }
        if (safe.isEmpty()) return false
        executor.execute {
            try {
                transport.uploadVocabulary(safe.take(MAX_SYNC), locale)
            } catch (_: Exception) {
                // Sync is best effort; nothing is retried with the leaked word.
            }
        }
        return true
    }

    private fun isClean(word: String): Boolean =
        ContentClassifier.residueEvidence(word).none { it.category.isSecret }

    /**
     * Words that must never be uploaded regardless of policy. Used by the
     * vocabulary migration when it walks an old dictionary.
     */
    fun isSafeToUpload(word: String): Boolean =
        word.isNotBlank() &&
            word.length <= 64 &&
            !TextNormalizer.digitsOnly(word).let { it.length in 13..19 } &&
            isClean(word)

    private const val MAX_PREFIX = 32
    private const val MAX_RESULTS = 8
    private const val MAX_SYNC = 200
}
